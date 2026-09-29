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

        /**
         * 已就绪。
         *
         * <p>{@code hasVideo == false} 表示这是**纯音频流**（音乐投屏）。
         * 界面必须据此切到音乐形态 —— 否则 SurfaceView 上什么都没有，
         * 电视就是一片黑，用户会以为投屏坏了（声音其实正常在放）。
         */
        void onPrepared(int durationMs, boolean hasVideo);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private MediaPlayer player;
    private Surface surface;

    private String currentUrl;
    private boolean prepared;

    /**
     * 是否正在 prepare（{@code prepareAsync} 已发出、回调还没到）。
     *
     * <p><b>必须和 {@link #prepared} 分开</b>：这两者的正确处置完全相反。
     * {@code prepared} → 直接 {@code start()}；
     * {@code preparing} → 什么都不做，等 onPrepared 自己 {@code start()}。
     *
     * <p>合成一个布尔值就区分不出「准备中」和「还没开始」，于是 prepare 期间的
     * Play 会把正在准备的实例掐掉重建 —— 见 {@link #resume} 里的详细说明。
     */
    private boolean preparing;

    private boolean userPaused;
    private int retryCount;
    private int stallCount;
    private long lastPosition = -1L;
    private long lastProgressAt;

    /**
     * 已下发但还没落地的 seek 目标（毫秒）；-1 表示当前没有待决的 seek。
     *
     * <p>它一次解决三件事，三者都指向同一个用户现象「拖完进度条两边不同步」：
     * <ol>
     *   <li><b>未 prepare 时的 seek 不丢</b>：投屏刚起来就拖进度条时，
     *       prepareAsync 还没回来，此时 seekTo 是无效的。暂存下来，
     *       onPrepared 之后补发 —— 否则电视一动不动，手机却显示已经拖过去了。</li>
     *   <li><b>落地前报目标值</b>：{@code seekTo()} 是异步的，位置在真正落地前
     *       读到的还是旧值。这期间对外报目标位置，手机轮询到的就和它自己显示的一致。</li>
     *   <li><b>看门狗不误杀</b>：seek 未落地时位置本来就不动，不豁免的话
     *       会被判成「卡死」并触发重连 —— 而重连把播放拉回开头。</li>
     * </ol>
     *
     * <p>{@code volatile}：seekTo 在主线程（Play 指令线程），
     * getPosition 在 HTTP 连接线程，两个线程都要看得到。
     */
    private volatile long pendingSeekMs = -1L;

    /** 下发这次 seek 的时刻，用于超时兜底（见 {@link PlaybackPolicy#isSeekExpired}） */
    private volatile long pendingSeekAtMs;

    /**
     * 当前音量，0.0 ~ 1.0。
     *
     * <p>**必须自己记**：{@code MediaPlayer.getVolume()} 是 API 23 才有的方法，
     * 在 API 15 的盒子上调用就是 NoSuchMethodError。而这个字段要回给
     * RenderingControl 的 GetVolume —— 原来那里硬编码 return 100，
     * 控制点拖完音量条回读会看到跳回 100，等于谎报。
     */
    private float volume = 1.0f;

    /**
     * 当前流有没有视频轨。onPrepared 时用 {@code getVideoWidth()} 判定。
     *
     * <p>用 getVideoWidth 而不是 {@code getTrackInfo()}：后者是 API 16 才有的，
     * 而 minSdk 是 14。{@code getVideoWidth()} 从 API 1 就在，
     * 纯音频时返回 0 —— 这是这个版本上唯一可靠的判据。
     */
    private boolean hasVideo;

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
        // 换了片源，上一次的 seek 目标立刻作废 ——
        // 不清的话，新片子的「当前位置」会先报成上一部片子拖到的进度。
        pendingSeekMs = -1L;
        startInternal();
    }

    private void startInternal() {
        releasePlayer();

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // 把记住的音量应用到新的 MediaPlayer 实例上。
            // 重连会重建实例，不重放这一句的话，一次断流就会把音量悄悄拉回满格。
            player.setVolume(volume, volume);
            if (surface != null) {
                player.setSurface(surface);
            }
            player.setDataSource(currentUrl);
            player.setScreenOnWhilePlaying(true);

            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    // 准备完成 —— 复位。不复位的话播放器会永远停在"准备中"，
                    // 之后每一个 Play 都会被 playAction 判成 PLAY_WAIT 而吞掉。
                    preparing = false;
                    // 这里只清「错误重连」计数。
                    // 卡死计数**绝对不能**在这儿清 —— 见下方 checkStall 里的说明：
                    // 「能 prepare 但立刻卡死」的流每一轮都会走到这个回调，
                    // 一清就等于熔断永不触发，变成无限重连。
                    retryCount = 0;
                    lastPosition = -1L;
                    lastProgressAt = System.currentTimeMillis();
                    hasVideo = detectVideo(mp);

                    // prepare 期间攒下的 seek 到这里补发。
                    // 不补的话，投屏刚起来（画面还没出来）时拖的进度条会被
                    // 永久丢弃 —— 手机显示已经拖过去了，电视一动不动。
                    if (pendingSeekMs >= 0) {
                        try {
                            mp.seekTo((int) pendingSeekMs);
                            pendingSeekAtMs = System.currentTimeMillis();
                            Log.i(TAG, "prepare 完成，补发暂存的 seek " + pendingSeekMs + "ms");
                        } catch (Exception e) {
                            Log.w(TAG, "补发 seek 失败", e);
                        }
                    }

                    mp.start();
                    notifyState("PLAYING");
                    if (listener != null) {
                        listener.onPrepared(mp.getDuration(), hasVideo);
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
                    // prepare 失败也要复位，否则重连之后 Play 会被误判成
                    // "准备中"而永远不生效 —— 表现是投不上去，而且点播放键没反应。
                    preparing = false;
                    if (listener != null) {
                        listener.onError("播放错误 what=" + what + " extra=" + extra);
                    }
                    scheduleRetry();
                    return true;
                }
            });

            // 置位必须在 prepareAsync **之前**。onPrepared 是异步回调，
            // 放在后面虽然大概率也来得及，但那是在赌时序 —— 而这条路径
            // 本来就只在"prepare 快慢"这个边界上出问题，赌不起。
            preparing = true;
            player.prepareAsync();
            notifyState("PREPARING");
        } catch (Exception e) {
            Log.e(TAG, "启动播放失败", e);
            // 起播就失败：复位准备状态，否则后面所有 Play 都会被当成
            // "准备中"吞掉 —— 表现是投不上去，而且点播放键也没任何反应。
            preparing = false;
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
        // seek 待决期间位置本来就不会动 —— 那是 seek 还没落地，不是卡死。
        // 不豁免的话，一次慢 seek（老芯片上要好几秒）会被判成卡死并触发重连，
        // 而重连会把播放拉回开头。用户看到的就是"拖了一下，电视跳回去了"。
        if (pendingSeekMs >= 0) {
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
        int action = PlaybackPolicy.playAction(player != null && prepared, preparing,
                currentUrl != null);
        if (action == PlaybackPolicy.PLAY_START) {
            try {
                player.start();
                lastProgressAt = System.currentTimeMillis();
                notifyState("PLAYING");
            } catch (Exception e) {
                Log.w(TAG, "resume 失败", e);
            }
        } else if (action == PlaybackPolicy.PLAY_WAIT) {
            // 正在 prepare —— **什么都不做**。
            //
            // 这里原来是「有 URL 就 startInternal()」，而 startInternal() 第一句是
            // releasePlayer()。于是 Play 一到就把正在 prepare 的那个实例释放重建。
            //
            // 控制点（腾讯视频 / B站 这类）是把 SetAVTransportURI 和 Play 连着发的，
            // 间隔只有几十毫秒，而 prepare 要几百毫秒以上 —— 几乎必然撞上。
            // 两条指令互相拆台，谁先谁后全看 prepare 快慢，所以表现成
            // 「有时候投得上、有时候投不上」。
            //
            // 而 onPrepared 回调里本来就会 start()，Play 的意图已经被满足了。
            Log.i(TAG, "Play 到达时仍在 prepare，已并入本次准备（不重建播放器）");
        } else if (action == PlaybackPolicy.PLAY_PREPARE) {
            startInternal();
        }
    }

    public synchronized void stop() {
        userPaused = false;
        currentUrl = null;
        stallCount = 0;
        cancelPendingRetry();
        handler.removeCallbacks(watchdog);
        // 停了就没有"待决的 seek"可言。不清的话，下一次播放的当前位置
        // 会先报成上一次拖到的那个位置。
        pendingSeekMs = -1L;
        releasePlayer();
        notifyState("STOPPED");
    }

    public synchronized void seekTo(int ms) {
        if (ms < 0) {
            return;
        }
        // 无论能不能立刻下发，都先记下来 —— 这个目标值有三重作用，
        // 见字段 pendingSeekMs 的注释。核心是：控制点拖了进度条之后，
        // 它下次轮询必须看到"已经到那了"，否则就是不同步。
        pendingSeekMs = ms;
        pendingSeekAtMs = System.currentTimeMillis();

        if (player != null && prepared) {
            try {
                player.seekTo(ms);
                lastProgressAt = pendingSeekAtMs;
            } catch (Exception e) {
                Log.w(TAG, "seek 失败", e);
            }
        } else {
            // 还没 prepare：暂存，等 onPrepared 之后补发。
            //
            // 原来这里是**直接丢弃** —— 用户在投屏刚起来（画面还没出来）时
            // 拖一下进度条，电视端一动不动，而手机端显示已经拖过去了。
            // 这是"拖拽不同步"的形态之一，而且它跟 seek 本身能不能用无关。
            Log.i(TAG, "seek 请求早于 prepare，已暂存 " + ms + "ms");
        }
    }

    public int getPosition() {
        try {
            if (player == null || !prepared) {
                return 0;
            }
            int raw = player.getCurrentPosition();
            long pending = pendingSeekMs;
            if (pending < 0) {
                return raw;
            }
            // seek 还没落地：位置读到的还是旧值，直接报出去会让控制点的
            // 进度条被**拉回去**。这期间报目标位置 —— 控制点自己也是按
            // "用户拖到哪"来算的，两边一致，用户看到的就是"同步"。
            long elapsed = System.currentTimeMillis() - pendingSeekAtMs;
            if (PlaybackPolicy.isSeekSettled(raw, pending)
                    || PlaybackPolicy.isSeekExpired(elapsed)) {
                // 落地了（真实位置追上来了）或超时了 → 交回真实位置。
                // 超时这条是诚实兜底：万一这次 seek 永远落不了地，
                // 不能一直报乐观值 —— 那是谎报军情。
                pendingSeekMs = -1L;
                return raw;
            }
            return (int) pending;
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
        // 先记下来再下发：重连会重建 MediaPlayer 实例，不记的话音量会丢。
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (player != null) {
            try {
                player.setVolume(this.volume, this.volume);
            } catch (Exception e) {
                Log.w(TAG, "setVolume 失败", e);
            }
        }
    }

    /** 当前音量，0 ~ 100。给 RenderingControl 的 GetVolume 回读用。 */
    public int getVolume0to100() {
        return Math.round(volume * 100f);
    }

    /**
     * 当前流有没有视频轨。只在 onPrepared 之后有意义。
     * 界面据此决定显示视频画面还是音乐卡片。
     */
    public boolean hasVideo() {
        return hasVideo;
    }

    /**
     * 判定这个流有没有视频轨。
     *
     * <p>{@code getVideoWidth()} 从 API 1 就在，纯音频返回 0 ——
     * 这是这个版本上唯一可靠的判据（{@code getTrackInfo()} 要 API 16，不能用）。
     *
     * <p>异常时**按有视频处理**：宁可退回原来的黑屏，也不要对着一个视频
     * 弹出音乐卡片 —— 后者更离谱，也更难解释。
     */
    private static boolean detectVideo(MediaPlayer mp) {
        try {
            return mp.getVideoWidth() > 0;
        } catch (Exception e) {
            Log.w(TAG, "getVideoWidth 失败，按有视频处理", e);
            return true;
        }
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        pendingRetry = null;
        releasePlayer();
    }

    private void releasePlayer() {
        prepared = false;
        // 实例都没了，"准备中"和"待决的 seek"也就失去了载体。
        // 不清的话，下一次播放的「当前位置」会一直报上一次拖到的那个位置 ——
        // 控制点看到的进度条是上一部片子的。
        preparing = false;
        pendingSeekMs = -1L;
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
