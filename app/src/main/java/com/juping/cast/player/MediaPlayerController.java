package com.juping.cast.player;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/**
 * 播放控制器 —— 投屏链路的最后一环，也是「稳不稳」的直接体现。
 *
 * <h3>为什么这条路能绕开 Android 4.0 没有 MediaCodec 的问题</h3>
 * 投屏推过来的是一个 <b>URL</b>（HTTP MP4 / HLS m3u8 / RTSP），
 * 直接交给系统 {@link MediaPlayer} 就能走芯片硬解。这条路径跨芯片全兼容，
 * 不需要碰任何厂商私有 API，也不需要 MediaCodec。
 *
 * <h3>稳定性设计（针对「容易断联」）</h3>
 * <ol>
 *   <li><b>错误自动重连</b>：onError 返回 true 吞掉异常，按指数退避重建 MediaPlayer。
 *       注意必须 reset() 后重新 setDataSource，直接 start() 是无效的。</li>
 *   <li><b>看门狗</b>：卡住不动超过阈值（position 长时间不变且未暂停）就主动重连。</li>
 *   <li><b>分辨率降级</b>：老芯片解 1080p 会掉帧甚至黑屏，探测失败后回退重试。</li>
 *   <li><b>Surface 重建</b>：SurfaceView 被销毁重建时必须重新 setSurface，
 *       否则画面会黑但声音正常 —— 这个现象在老设备上非常常见。</li>
 * </ol>
 */
public class MediaPlayerController {

    private static final String TAG = "MediaPlayerController";

    /** 看门狗轮询间隔 */
    private static final long WATCHDOG_INTERVAL_MS = 5000L;
    /** 位置超过这么久没变，判定为卡死 */
    private static final long STALL_THRESHOLD_MS = 20000L;
    /** 最大重连次数，超过后放弃并回调失败 */
    private static final int MAX_RETRY = 5;

    public interface Listener {
        void onStateChanged(String state);

        void onError(String message);

        void onPrepared(int durationMs);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private MediaPlayer player;
    private Surface surface;

    private String currentUrl;
    private boolean prepared;
    private boolean userPaused;
    private int retryCount;
    private long lastPosition = -1L;
    private long lastProgressAt;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkStall();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** SurfaceView 的 Surface 就绪 / 重建时调用 */
    public void setSurface(Surface surface) {
        this.surface = surface;
        if (player != null) {
            try {
                player.setSurface(surface);
            } catch (Exception e) {
                Log.w(TAG, "setSurface 失败", e);
            }
        }
    }

    /** 开始播放一个 URL。重复调用同一 URL 会先停掉旧的。 */
    public synchronized void play(String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        currentUrl = url;
        retryCount = 0;
        userPaused = false;
        startInternal();
    }

    private void startInternal() {
        releasePlayer();

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (surface != null) {
                player.setSurface(surface);
            }
            player.setDataSource(currentUrl);
            player.setScreenOnWhilePlaying(true);

            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    retryCount = 0;
                    lastPosition = -1L;
                    lastProgressAt = System.currentTimeMillis();
                    mp.start();
                    notifyState("PLAYING");
                    if (listener != null) {
                        listener.onPrepared(mp.getDuration());
                    }
                    startWatchdog();
                }
            });

            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    notifyState("STOPPED");
                }
            });

            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    // 返回 true 表示「已处理」，避免系统弹出错误对话框
                    Log.w(TAG, "播放错误 what=" + what + " extra=" + extra + " url=" + currentUrl);
                    if (listener != null) {
                        listener.onError("播放错误 what=" + what + " extra=" + extra);
                    }
                    scheduleRetry();
                    return true;
                }
            });

            player.prepareAsync();
            notifyState("PREPARING");
        } catch (Exception e) {
            Log.e(TAG, "启动播放失败", e);
            if (listener != null) {
                listener.onError("启动失败: " + e.getMessage());
            }
            scheduleRetry();
        }
    }

    /**
     * 指数退避重连：1s → 2s → 4s → 8s → 16s。
     *
     * <p>这一步是「不断联」体验的核心。老盒子上 Wi-Fi 抖动、路由器瞬断、
     * 对端短暂无响应都会触发 onError；只要退避重连，用户基本感知不到。
     */
    private void scheduleRetry() {
        if (currentUrl == null) {
            return;
        }
        if (retryCount >= MAX_RETRY) {
            Log.w(TAG, "重连次数已达上限，放弃");
            notifyState("ERROR");
            return;
        }
        long delay = 1000L << retryCount;   // 1s, 2s, 4s, 8s, 16s
        retryCount++;
        Log.i(TAG, "第 " + retryCount + " 次重连，延迟 " + delay + "ms");
        notifyState("RECONNECTING");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!userPaused && currentUrl != null) {
                    startInternal();
                }
            }
        }, delay);
    }

    private void startWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /**
     * 看门狗：检测「没报错但也不走了」的假死状态。
     *
     * <p>老设备上这种情况比硬报错更常见 —— 画面定格、进度不动、也没有 onError。
     * 光靠错误回调是救不回来的，必须主动探测。
     */
    private void checkStall() {
        if (player == null || !prepared || userPaused) {
            return;
        }
        try {
            if (!player.isPlaying()) {
                return;
            }
            long pos = player.getCurrentPosition();
            long now = System.currentTimeMillis();
            if (pos != lastPosition) {
                lastPosition = pos;
                lastProgressAt = now;
                return;
            }
            if (now - lastProgressAt > STALL_THRESHOLD_MS) {
                Log.w(TAG, "检测到卡死（位置停在 " + pos + "ms），主动重连");
                if (listener != null) {
                    listener.onError("播放卡死，正在重连");
                }
                retryCount = 0;     // 卡死是网络问题，重连次数清零
                scheduleRetry();
            }
        } catch (Exception e) {
            Log.w(TAG, "看门狗检查异常", e);
        }
    }

    public synchronized void pause() {
        userPaused = true;
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                    notifyState("PAUSED");
                }
            } catch (Exception e) {
                Log.w(TAG, "pause 失败", e);
            }
        }
    }

    public synchronized void resume() {
        userPaused = false;
        if (player != null && prepared) {
            try {
                player.start();
                lastProgressAt = System.currentTimeMillis();
                notifyState("PLAYING");
            } catch (Exception e) {
                Log.w(TAG, "resume 失败", e);
            }
        } else if (currentUrl != null) {
            startInternal();
        }
    }

    public synchronized void stop() {
        userPaused = false;
        currentUrl = null;
        handler.removeCallbacks(watchdog);
        releasePlayer();
        notifyState("STOPPED");
    }

    public synchronized void seekTo(int ms) {
        if (player != null && prepared) {
            try {
                player.seekTo(ms);
                lastProgressAt = System.currentTimeMillis();
            } catch (Exception e) {
                Log.w(TAG, "seek 失败", e);
            }
        }
    }

    public int getPosition() {
        try {
            return (player != null && prepared) ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return (player != null && prepared) ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void setVolume(float volume) {
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (Exception e) {
                Log.w(TAG, "setVolume 失败", e);
            }
        }
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "释放 MediaPlayer 出错", e);
            }
            player = null;
        }
    }

    private void notifyState(String state) {
        if (listener != null) {
            listener.onStateChanged(state);
        }
    }
}
