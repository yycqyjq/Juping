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
 *   <li><b>看门狗</b>：卡住不动超过阈值（position 长时间不变且未暂停）就主动重连。
 *       点播与直播用两档阈值 —— 直播的位置可能长时间不增长甚至恒为 0，
 *       用点播阈值会把正常播放误杀成卡死。</li>
 *   <li><b>卡死熔断</b>：连续卡死到上限就停手。重连不一定能救回本身有问题的流
 *       （CDN 限速、分段缺失、码率超出老芯片能力），而每一轮重连都会打断一次
 *       刚恢复的播放 —— 体验比直接报错更差。</li>
 *   <li><b>Surface 重建</b>：SurfaceView 被销毁重建时必须重新 setSurface，
 *       否则画面会黑但声音正常 —— 这个现象在老设备上非常常见。</li>
 * </ol>
 *
 * <p><b>明确没有做的</b>：分辨率降级。这里曾经写过这么一条，但代码里从来没有实现 ——
 * 而 DLNA 推过来的是<b>手机指定的那个 URL</b>，我们无法换成低码率地址，
 * 所以它在协议层面就做不到。注释里的假承诺比不写更糟，故删掉。
 *
 * <p>所有阈值与退避参数见 {@link PlaybackPolicy} —— 那边是纯逻辑，可在桌面上跑断言。
 */
public class MediaPlayerController {

    private static final String TAG = "MediaPlayerController";

    // 阈值 / 退避 / 上限全部收在 PlaybackPolicy 里 —— 那边是纯逻辑、可在桌面上跑断言。
    // 这里只引用，避免同一套数字散落在两处（改了一处忘了另一处是最难查的那种 bug）。
    private static final long WATCHDOG_INTERVAL_MS = PlaybackPolicy.WATCHDOG_INTERVAL_MS;

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
    private int stallCount;
    private long lastPosition = -1L;
    private long lastProgressAt;

    /**
     * 已排期但还没执行的那次重连。
     *
     * <p>必须持引用才能取消。原来这里是匿名 Runnable 直接 postDelayed，
     * 谁也拿不到它 —— 于是「换了个视频，却被上一个视频的旧重连打断」
     * 这类问题就无从处理，表现是投屏偶发闪断。
     */
    private Runnable pendingRetry;

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
        // 先掐掉上一次排期的重连：否则它会在一两秒后拿**旧 URL** 再起一次播放，
        // 把用户刚投上来的新视频顶掉。表现就是"投了新视频，画面跳回上一个"。
        cancelPendingRetry();
        currentUrl = url;
        retryCount = 0;
        stallCount = 0;
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
                    // 这里只清「错误重连」计数。
                    // 卡死计数**绝对不能**在这儿清 —— 见下方 checkStall 里的说明：
                    // 「能 prepare 但立刻卡死」的流每一轮都会走到这个回调，
                    // 一清就等于熔断永不触发，变成无限重连。
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
        long delay = PlaybackPolicy.retryDelayMs(retryCount);
        if (delay < 0) {
            Log.w(TAG, "重连次数已达上限（" + PlaybackPolicy.MAX_RETRY + " 次），放弃");
            notifyState("ERROR");
            return;
        }
        retryCount++;
        // 同一时刻只允许有一次待执行的重连。否则 onError 与看门狗同时触发时
        // 会叠出两个，各自 startInternal() 时把对方刚建的 MediaPlayer 释放掉，
        // 播放来回闪断 —— 看起来像"网络不好"，其实是自己在打架。
        cancelPendingRetry();
        Log.i(TAG, "第 " + retryCount + " 次重连，延迟 " + delay + "ms");
        notifyState("RECONNECTING");
        pendingRetry = new Runnable() {
            @Override
            public void run() {
                pendingRetry = null;
                if (!userPaused && currentUrl != null) {
                    startInternal();
                }
            }
        };
        handler.postDelayed(pendingRetry, delay);
    }

    /** 取消已排期但还没执行的重连 */
    private void cancelPendingRetry() {
        if (pendingRetry != null) {
            handler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
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
                // 位置真的动了，才算"这一轮卡死过去了"，把连续计数归零。
                // 这里是卡死计数**唯一**该归零的地方 ——
                // 放在 onPrepared 里会让「能 prepare 但立刻卡死」的流永远清空计数，
                // 熔断失效、无限重连（PlaybackPolicy 里有详细说明）。
                stallCount = 0;
                return;
            }

            // 时长已知 → 点播流，用严格阈值；时长未知或为 0 → 直播/分段流，放宽
            int duration = player.getDuration();
            if (!PlaybackPolicy.isStalled(now - lastProgressAt, duration)) {
                return;
            }

            stallCount++;
            if (PlaybackPolicy.shouldStopRetryingStalls(stallCount)) {
                Log.w(TAG, "连续卡死 " + stallCount + " 次，停止重连");
                if (listener != null) {
                    listener.onError("播放持续卡死，已停止重试");
                }
                notifyState("ERROR");
                return;
            }

            Log.w(TAG, "检测到卡死（位置停在 " + pos + "ms，时长 " + duration
                    + "ms），第 " + stallCount + " 次重连");
            if (listener != null) {
                listener.onError("播放卡死，正在重连（第 " + stallCount + " 次）");
            }
            retryCount = 0;             // 卡死按网络问题处理，错误重连计数清零
            lastProgressAt = now;       // 重置计时，避免下一轮立刻重复触发
            scheduleRetry();
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
        stallCount = 0;
        cancelPendingRetry();
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
        pendingRetry = null;
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
