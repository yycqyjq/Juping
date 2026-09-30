package com.juping.cast.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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

        /**
         * 播放源已变更（播放列表自动续播）。
         *
         * <p>SetNextAVTransportURI 预告的下一曲在当前曲目自然播完后自动接棒时，
         * 控制命令不走 SOAP（没有 SetAVTransportURI），服务层的 URI/元数据状态
         * 靠这个回调对齐 —— GetMediaInfo 回读与事件推送都依赖它。
         * 实现方应更新 URI/元数据/标题并推送 AVTransport 事件。
         */
        void onSourceChanged(String uri, String metadata);

        /**
         * 播放出错。
         *
         * <p>{@code kind} 是 {@link PlaybackPolicy} 里的 {@code ERR_*} 分类 ——
         * 界面据此显示用户能看懂的话（"连不上媒体服务器" / "这台盒子解不了这个格式"）。
         *
         * <p>{@code detail} 是给日志和排障用的技术细节（{@code what}/{@code extra}、
         * 异常消息）。**它不该直接显示给用户**：{@code "what=1 extra=-1010"}
         * 这种字符串用户看不懂，也没法据此做任何决定。
         */
        void onError(int kind, String detail);

        /**
         * 已就绪。
         *
         * <p>{@code hasVideo == false} 表示这是**纯音频流**（音乐投屏）。
         * 界面必须据此切到音乐形态 —— 否则 SurfaceView 上什么都没有，
         * 电视就是一片黑，用户会以为投屏坏了（声音其实正常在放）。
         *
         * <p><b>可能对同一次播放回调多次</b>：老芯片在 onPrepared 时
         * 视频尺寸未就绪（getVideoWidth()==0），先按纯音频回调；
         * 延迟复查确认有视频后，会以 {@code hasVideo == true} 再回调一次。
         * 实现方必须幂等 —— 服务层与界面（轮询刷新）都已按此设计。
         */
        void onPrepared(int durationMs, boolean hasVideo);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    /**
     * 只用于 {@code MediaPlayer.setWakeMode()}。
     *
     * <p>持有的是 Service 自身，生命周期与这个控制器完全一致，不会泄漏。
     * 之所以必须拿到它：{@code setWakeMode} 的签名要一个 Context，
     * 而这个能力没法用别的方式表达（见 {@link #startInternal()} 里的说明）。
     */
    private final Context context;

    public MediaPlayerController(Context context) {
        this.context = context;
    }

    private MediaPlayer player;
    private Surface surface;

    /**
     * native 播放器已不可碰（已 release 或停在错误态）。
     *
     * <p>为什么必须有：在错误态/已释放的 MediaPlayer 上调用任何方法
     * （getDuration/getPosition/……）都会触发一次 -38 错误回调 →
     * onError → 事件推送又要读播放器 → 再触发…… 25 次/秒的
     * ERROR 刷屏死循环（真机网易云切歌实测）。置位后所有访问器
     * 走缓存值，直到 startInternal 造出新实例。
     */
    private volatile boolean playerReleased;

    /**
     * native 播放器已停在错误态且重连已放弃 —— 同样不可碰。
     * 与 playerReleased 的区别：这个由「重连次数已达上限」置位，
     * startInternal 造出新实例时解除。
     */
    private volatile boolean nativePlayerDead;

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

    /**
     * 看门狗的基准：上一次看到的位置，以及那一刻的时刻。
     *
     * <p>{@code volatile} 是必须的：{@code seekTo()} 在控制点的指令线程上跑，
     * 会刷新 {@link #lastProgressAt}；而 {@link #checkStall()} 在主线程上读它。
     * 不加的话，指令线程刚写的值主线程可能看不见 —— 表现成「拖完进度条
     * 立刻被判卡死并重连」，而且**时有时无**，是最难复现的那类 bug。
     */
    private volatile long lastPosition = -1L;
    private volatile long lastProgressAt;

    /**
     * 最后一次从播放器读到的有效位置，给「未就绪期间」兜底用。
     *
     * <p>重连会释放播放器，那段时间 {@code getCurrentPosition()} 读不到东西。
     * 原来这里返回 0 —— 控制点碰上一次几百毫秒的重连，就会看到进度条
     * **跳回开头**，恢复后又跳回来。改报最后已知位置，观感上只是"卡了一下"，
     * 而不是"进度没了"。
     */
    private volatile int lastKnownPosition;

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
     * 这次 seek 是否已观察到「位置到达目标附近」。
     * 落地判据是一次性的：播放越过目标点之后 |raw-target| 会再次变大，
     * 拿它当持续性判据会让「报目标位置」变成永真 —— 进度条冻死在
     * Seek 点（真机踩过）。锁存之后对外恢复真实位置上报，
     * pendingSeekMs 的清位仍统一在 checkStall（主线程）。
     */
    private volatile boolean pendingSeekLanded;

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
     * 是否静音。
     *
     * <p>和 {@link #volume} **必须分开记**：静音不是"音量 0"。
     * 控制点按静音再取消静音时，音量要回到原来的值 —— 如果实现成
     * 「静音 = setVolume(0)」，取消静音就只能回到满格，用户设过的音量被吃掉。
     * 同理 GetVolume 报的也应该是用户设的那个值，而不是静音后的 0。
     *
     * <p>和音量一样，重连重建播放器实例后必须重放（见 {@link #applyVolume()}）。
     */
    private boolean muted;

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

    /**
     * 已排期但还没执行的「视频尺寸延迟复查」。
     *
     * <p>持引用才能取消（换片 / 停止 / 重连时），否则一次过期的复查会把
     * 上一个片源的视频判定套到新片源上。与 {@link #pendingRetry} 同一条纪律。
     */
    private Runnable videoRecheck;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkStall();
            checkProxyFallback();
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

    /**
     * 开始播放一个 URL。**同一个 URL 重复调用是幂等的**。
     *
     * @return true 表示这次真的重新起了播放；false 表示被幂等忽略
     */
    public synchronized boolean play(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        // 幂等：控制点把同一个地址又发了一遍。
        //
        // 这不是理论上的边角情况。腾讯视频这类控制点**拖拽进度条时**会重发
        // SetAVTransportURI（同一个 URL）再跟一条 Seek。而 startInternal()
        // 第一句就是 releasePlayer()，照单全收的话每次拖拽都会：
        //   ① 释放 MediaPlayer → 视频层关闭 → 电视上闪一下蓝屏
        //   ② 重新 prepareAsync → 重新缓冲、位置归零 → 用户看到的"断开重连"
        //
        // 判定本身抽到了 PlaybackPolicy.shouldRebuild —— 那边是纯逻辑，
        // 「同地址 × 各种播放器状态」的组合能在桌面上逐个跑断言，
        // 而不是只能靠在真机上一遍遍试。
        if (!PlaybackPolicy.shouldRebuild(url, currentUrl, prepared, preparing)) {
            Log.i(TAG, "收到与当前相同的地址，幂等忽略（不重建播放器）");
            return false;
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
        // 新片源到达，「下一曲」队列作废（新歌单会重新 SetNext）
        nextUrl = null;
        nextMetadata = null;
        // 兜底位置同理：不清的话，新片子起播前会先报上一部的进度
        lastKnownPosition = 0;
        startInternal();
        return true;
    }

    private void startInternal() {
        releasePlayer();
        // 新实例创建时解除「native 不可碰」—— 上一轮错误态的豁免到此为止
        nativePlayerDead = false;
        try {
            player = new MediaPlayer();
            playerReleased = false;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // 把记住的音量与静音状态应用到新的 MediaPlayer 实例上。
            // 重连会重建实例，不重放的话一次断流就会把音量拉回满格、静音也丢了。
            applyVolume();
            // 播放期间必须钉住 CPU，否则息屏后解码会被系统挂起。
            //
            // 只靠 setScreenOnWhilePlaying(true) 是不够的：那一句**只对设了 Surface
            // 的视频有效**（官方文档明写"仅在 setSurface() 被调用后有效"）。
            // 而音乐投屏恰恰没有 Surface —— 屏幕不会亮，CPU 也不被钉住，
            // 用户关了电视屏（或盒子进了待机）音乐就会卡住甚至断流。
            //
            // setWakeMode 从 API 1 就有，且它申请的 PARTIAL_WAKE_LOCK 由播放器自己
            // 在 release() 时释放，不需要我们手动配平。WAKE_LOCK 权限在 manifest 里。
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            if (surface != null) {
                player.setSurface(surface);
            }
            // 本地预取代理：http(s) 渐进流经 127.0.0.1 缓冲后喂给播放器，
            // 把富余带宽兑换成「数据已在本地」。m3u8（HLS）自带分片逻辑，
            // 按字节寻址的代理没意义，bypass 直连。曾经代理超时的地址
            // （bypassUrl）也直连 —— 厂商自研播放器栈的行为不会自己变好。
            // 开关见 PlaybackPolicy.PROXY_ENABLED（当前默认关，原因见彼处注释）。
            String playUrl = currentUrl;
            boolean proxyable = PlaybackPolicy.PROXY_ENABLED
                    && currentUrl != null
                    && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))
                    && !currentUrl.contains(".m3u8")
                    && !currentUrl.equals(bypassUrl);
            proxiedCurrent = false;
            // MediaProxy **无条件创建**：开关关闭时它只是个空壳对象（缓冲在
            // localize 之后才分配）。放在条件内的话，R8 会把 PROXY_ENABLED=false
            // 折叠成死代码把整个类从 dex 里删掉 —— dex 入口点守卫数对不上就红了。
            if (proxy == null) {
                proxy = new MediaProxy(PlaybackPolicy.PROXY_BUFFER_BYTES);
            }
            if (proxyable) {
                playUrl = proxy.localize(currentUrl);
                proxiedCurrent = !playUrl.equals(currentUrl);
            }
            prepareStartedAt = System.currentTimeMillis();
            player.setDataSource(playUrl);
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

                    // 视频尺寸延迟复查：部分老芯片（MTK 5880 实测）在 onPrepared
                    // 时 getVideoWidth() 仍返回 0 —— 视频要等首帧解码才有尺寸。
                    // 只在这里判一次的话，视频流会被误判成纯音频，
                    // 电视上对着一个视频弹「音乐投屏」卡片（真机踩到）。
                    // 复查翻案后用 onPrepared 再通知一次 —— 服务层幂等，
                    // 界面是轮询刷新，重复通知天然安全。
                    if (!hasVideo) {
                        scheduleVideoRecheck(mp, 1);
                        // getVideoWidth 在部分平台（海信 MTK 4.0.4 实测）**永远**
                        // 返回 0 —— 视频在渲染、API 却不报。这时改问媒体服务器：
                        // Content-Type 是服务端自述的事实（video/ 开头的翻案成视频；
                        // audio/ 开头的维持音乐卡片，绝不把音频误判成黑屏视频）。
                        scheduleContentTypeProbe(mp, currentUrl);
                    }

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
                    // 播放列表续播：SetNextAVTransportURI 预告过的下一曲在这里接棒。
                    // 必须抛到下一轮消息循环再重建播放器 —— 在播放器自己的
                    // onCompletion 回调里 release 它自己，老平台上有崩溃先例。
                    if (nextUrl != null && nextUrl.length() > 0 && !userPaused) {
                        final String u = nextUrl;
                        final String m = nextMetadata;
                        nextUrl = null;
                        nextMetadata = null;
                        Log.i(TAG, "当前曲目播完，自动续播下一曲: " + u);
                        notifyState("TRANSITIONING");
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                // 切源 + 重建播放器（此刻已不在 onCompletion
                                // 回调栈里，release 旧实例是安全的）
                                currentUrl = u;
                                startInternal();
                                if (listener != null) {
                                    listener.onSourceChanged(u, m);
                                }
                            }
                        });
                        return;
                    }
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
                        listener.onError(PlaybackPolicy.classifyMediaError(what, extra),
                                "播放错误 what=" + what + " extra=" + extra);
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
            // 看门狗必须在 **prepare 阶段就跑起来**：原来只在 onPrepared 里
            // 启动，等于「prepare 卡死」的整个窗口内没有任何探测 ——
            // 代理路径的超时降级正是要在这个窗口里生效的。
            startWatchdog();
            notifyState("PREPARING");
        } catch (Exception e) {
            Log.e(TAG, "启动播放失败", e);
            // 起播就失败：复位准备状态，否则后面所有 Play 都会被当成
            // "准备中"吞掉 —— 表现是投不上去，而且点播放键也没任何反应。
            preparing = false;
            if (listener != null) {
                listener.onError(PlaybackPolicy.classifyStartFailure(e),
                        "启动失败: " + e);
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
            // 放弃后**必须置 nativePlayerDead**：放弃意味着播放器停在错误态，
            // 而此时任何 getDuration/getPosition 调用都会再触发一次 -38 错误
            // → onError → notifyState("ERROR") → 事件推送又要 getDuration……
            // 25 次/秒的 ERROR 刷屏死循环（真机网易云切歌实测）。置位后
            // 所有访问器走缓存/中性值，不再碰 native 播放器。
            nativePlayerDead = true;
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

    /**
     * 视频尺寸延迟复查：第 {@code attempt} 次（1 起步）。
     *
     * <p>节奏先快后慢（见 {@link PlaybackPolicy} 的 VIDEO_RECHECK_* 常量）：
     * 首帧解码通常几十到几百毫秒就绪，所以第一次只等 500ms；
     * 仍没有说明这台芯片更慢，等 2 秒再试最后一次。
     * 到上限还没有，就认命 —— 那多半真是纯音频流，音乐卡片本来就是对的。
     *
     * <p>回调里必须校验「播放器实例还是发起时的那个」：复查排队期间可能
     * 已经重连（实例重建）、换片或停止，与发起时不符就放弃 ——
     * 该来的新 onPrepared 会安排新的复查。不校验的话，过期复查会把
     * 上一个片源的判定套到新片源上。
     */
    private void scheduleVideoRecheck(final MediaPlayer mp, final int attempt) {
        cancelVideoRecheck();
        long delay = (attempt <= 1) ? PlaybackPolicy.VIDEO_RECHECK_FIRST_MS
                                    : PlaybackPolicy.VIDEO_RECHECK_SECOND_MS;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                videoRecheck = null;
                if (player != mp || !prepared || hasVideo) {
                    return;
                }
                if (detectVideo(mp)) {
                    hasVideo = true;
                    Log.i(TAG, "视频尺寸延迟就绪（第 " + attempt
                            + " 次复查），从音乐形态切回视频形态");
                    if (listener != null) {
                        listener.onPrepared(mp.getDuration(), true);
                    }
                    return;
                }
                if (attempt < PlaybackPolicy.VIDEO_RECHECK_MAX_ATTEMPTS) {
                    scheduleVideoRecheck(mp, attempt + 1);
                }
            }
        };
        videoRecheck = task;
        handler.postDelayed(task, delay);
    }

    /** 取消已排期但还没执行的视频尺寸复查 */
    private void cancelVideoRecheck() {
        if (videoRecheck != null) {
            handler.removeCallbacks(videoRecheck);
            videoRecheck = null;
        }
    }

    /**
     * Content-Type 探测：问媒体服务器「你给我的是什么」。
     *
     * <p>为什么需要第二个信号：视频尺寸复查依赖 {@code getVideoWidth()}，
     * 而它在部分平台（海信 MTK 4.0.4 实测）**永远返回 0** —— 视频明明在
     * SurfaceView 上渲染（SurfaceFlinger 图层可见），API 却不报。
     * Content-Type 是媒体服务器自述的事实，与芯片实现无关。
     *
     * <p>纪律：**只有 video/ 开头的类型才翻案**。audio/ 开头的维持音乐卡片 —— 把音频
     * 误判成视频，用户对着黑屏以为投屏坏了，比卡片盖住视频更糟；
     * 其它（octet-stream、探测失败）维持现状，宁漏勿错。
     *
     * <p>网络跑在独立守护线程上（3 秒超时，失败即放弃）；
     * 回调切回主线程前必须校验实例与状态，与复查同一条纪律。
     */
    private void scheduleContentTypeProbe(final MediaPlayer mp, final String url) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String type = probeContentType(url);
                if (type == null || !type.startsWith("video/")) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 排队期间世界可能已变：重连换实例、换片、停止。
                        if (player != mp || !prepared || hasVideo) {
                            return;
                        }
                        hasVideo = true;
                        Log.i(TAG, "Content-Type 为 " + type
                                + "（getVideoWidth 恒 0 的平台），从音乐形态切回视频形态");
                        if (listener != null) {
                            listener.onPrepared(mp.getDuration(), true);
                        }
                    }
                });
            }
        }, "ct-probe");
        t.setDaemon(true);
        t.start();
    }

    /** HEAD 拿 Content-Type，失败退回 Range GET。超时 3 秒，失败返回 null */
    private static String probeContentType(String url) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            String type = c.getContentType();
            c.disconnect();
            if (type != null) {
                return type;
            }
        } catch (Exception ignored) {
        }
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            String type = c.getContentType();
            c.disconnect();
            return type;
        } catch (Exception e) {
            Log.w(TAG, "Content-Type 探测失败（放弃翻案）: " + e.getMessage());
            return null;
        }
    }

    private void startWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /**
     * 代理路径 prepare 超时的降级检查（看门狗每拍调一次）。
     *
     * <p>厂商自研播放器栈对 127.0.0.1 代理的行为不可控（海信 CmpbPlayer
     * 实测：连接后立即断开，prepare 永远不完成）—— 不能让投屏永远卡在
     * TRANSITIONING。超时就把这个地址记入 bypass，直连重建。
     * prepare 成功的代理路径不受影响（preparing 会翻转为 prepared）。
     */
    private void checkProxyFallback() {
        if (!preparing || !proxiedCurrent) {
            return;
        }
        if (System.currentTimeMillis() - prepareStartedAt
                < PlaybackPolicy.PREPARE_PROXY_TIMEOUT_MS) {
            return;
        }
        Log.w(TAG, "代理路径 prepare 超时，降级直连重试");
        String url = currentUrl;
        bypassUrl = url;
        proxiedCurrent = false;
        // 打破幂等判定强制重建：currentUrl 置空后 play() 会当成新片源
        currentUrl = null;
        play(url);
    }

    /**
     * 看门狗：检测「没报错但也不走了」的假死状态。
     *
     * <p>老设备上这种情况比硬报错更常见 —— 画面定格、进度不动、也没有 onError。
     * 光靠错误回调是救不回来的，必须主动探测。
     */
    private void checkStall() {
        if (player == null || playerReleased || nativePlayerDead || !prepared || userPaused) {
            return;
        }
        // ---- seek 待决：既豁免看门狗，也负责在超时之后收尾 ----
        //
        // 位置在 seek 落地前本来就不会动 —— 那是"还没跳过去"，不是卡死。
        // 不豁免的话，一次慢 seek（老芯片上要好几秒）会被判成卡死并触发重连，
        // 而重连会把播放拉回开头：用户看到的就是"拖了一下，电视跳回去了"。
        //
        // 「清 pendingSeekMs」只在这里做（外加 seekTo / stop / release 那几处显式
        // 重置）。放到 getPosition() 里会让两个线程同时改这个状态，而它同时又
        // 兼着看门狗的豁免开关 —— 那种竞态只会表现为"偶发重连"，最难查。
        long pending = pendingSeekMs;
        if (pending >= 0) {
            if (pendingSeekLanded
                    || PlaybackPolicy.isSeekExpired(System.currentTimeMillis() - pendingSeekAtMs)) {
                Log.w(TAG, "seek 到 " + pending + "ms 超过 "
                        + PlaybackPolicy.SEEK_PENDING_TIMEOUT_MS + "ms 仍未落地，放弃等待");
                pendingSeekMs = -1L;
                // 放弃之后必须**立刻把基准对齐到当前位置**。
                // 不对齐的话，"seek 期间位置没动"这段静止会被下一轮检查直接
                // 算成卡死 —— 于是超时兜底反而制造出一次多余的重连。
                lastProgressAt = System.currentTimeMillis();
                try {
                    lastPosition = player.getCurrentPosition();
                } catch (Exception ignored) {
                    // 读不到就保持原值，下一轮会自行对齐
                }
            }
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
                    listener.onError(PlaybackPolicy.ERR_GIVEUP, "播放持续卡死，已停止重试");
                }
                notifyState("ERROR");
                return;
            }

            Log.w(TAG, "检测到卡死（位置停在 " + pos + "ms，时长 " + duration
                    + "ms），第 " + stallCount + " 次重连");
            if (listener != null) {
                listener.onError(PlaybackPolicy.ERR_STALLED,
                        "播放卡死，正在重连（第 " + stallCount + " 次）");
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
        if (player != null && !playerReleased && !nativePlayerDead) {
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

    /** 本地预取缓冲代理（懒创建；Stop 时清缓冲，断流重连特意保留） */
    private MediaProxy proxy;

    /** 当前这次播放是否走的代理路径 */
    private boolean proxiedCurrent;

    /** 本次 prepare 的起始时刻 —— 代理超时降级的计时基准 */
    private long prepareStartedAt;

    /** 已确认要直连的地址（代理 prepare 超时后降级，同地址不再走代理） */
    private String bypassUrl;

    /**
     * 下一曲（SetNextAVTransportURI 预告的续播源）—— 播放列表/连续播放。
     * 当前曲目**自然播完**（onCompletion）时自动接棒；被 Stop/换片打断则作废。
     */
    private String nextUrl;
    private String nextMetadata;

    /** 预告下一曲（SetNextAVTransportURI 的播放器侧入口） */
    public synchronized void setNext(String url, String metadata) {
        nextUrl = url;
        nextMetadata = metadata == null ? "" : metadata;
        Log.i(TAG, "已预告下一曲: " + nextUrl);
    }

    public synchronized void stop() {
        userPaused = false;
        currentUrl = null;
        stallCount = 0;
        // Stop = 控制点明确结束：下一曲队列不跨 Stop 存活（DLNA 语义）
        nextUrl = null;
        nextMetadata = null;
        // Stop = 控制点明确结束：代理缓冲立刻释放（8MB 不能在空闲时占着）。
        // 断流重连路径不走这里，所以缓冲能活过重连 —— 那正是它的价值。
        if (proxy != null) {
            proxy.reset();
        }
        cancelPendingRetry();
        handler.removeCallbacks(watchdog);
        // 停了就没有"待决的 seek"可言。不清的话，下一次播放的当前位置
        // 会先报成上一次拖到的那个位置。
        pendingSeekMs = -1L;
        // 兜底位置同理归零：用户已经停止投屏了，界面上不该再挂着进度。
        // （注意 releasePlayer() 里**不能**清它 —— 重连也会走那条路，
        //   清了就等于重连期间又跳回 0，正是这次要修的现象。）
        lastKnownPosition = 0;
        // 先显式解绑 Surface，再释放播放器。
        //
        // 顺序有意义：setSurface(null) 让解码器先交出输出面、视频层随即关闭；
        // 直接 release() 的话，某些平台上那一层会残留一段时间，
        // 屏幕上表现为"停了之后还蓝着一块"。
        //
        // 只在 stop() 里做，**不放进 releasePlayer()** —— 重连也走那条路，
        // 而重连期间解绑画面会多闪一次蓝，正是要避免的。
        if (player != null) {
            try {
                player.setSurface(null);
            } catch (Exception e) {
                Log.w(TAG, "解绑 Surface 失败", e);
            }
        }
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
        pendingSeekLanded = false;
        // 水位线对齐到目标：不改的话，往回拖之后旧的高水位线会让外推
        // 以为位置还停在旧处，甚至把进度直接推到片尾（钳到 dur 的副作用）。
        hiRaw = ms;
        hiWall = System.currentTimeMillis();

        if (player != null && prepared && !playerReleased && !nativePlayerDead) {
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

    /**
     * 位置水位线：会话内见过的最大原始位置 + 见到它的墙钟。
     * 位置只要在前进就抬水位线；停摆超过 POSITION_FREEZE_EXTRAPOLATE_MS
     * 才从水位线外推 —— 对 1 秒粒度的抖动免疫。
     */
    private int hiRaw = -1;
    private long hiWall;

    public int getPosition() {
        try {
            if (player == null || playerReleased || nativePlayerDead || !prepared) {
                // 未就绪（含重连中）→ 报**最后已知位置**，而不是 0。
                // 报 0 会让控制点的进度条直接跳回开头，而重连往往只要几百毫秒。
                // nativePlayerDead：错误态/已释放的播放器碰一下就触发 -38
                // → onError → ERROR 刷屏死循环（网易云切歌真机踩过）。
                return lastKnownPosition;
            }
            int raw = player.getCurrentPosition();
            lastKnownPosition = raw;
            long now = System.currentTimeMillis();
            // 水位线：会话内见过的最大位置 + 见到它的墙钟。
            // 正常播放时位置每拍都在前进、水位线每拍都在抬，冻结局不会触发；
            // 它只在「位置真的停摆」的平台上兜底（见下面的外推分支）。
            if (raw > hiRaw) {
                hiRaw = raw;
                hiWall = now;
            }
            long pending = pendingSeekMs;
            if (pending >= 0) {
                if (PlaybackPolicy.isSeekSettled(raw, pending)) {
                    // 落地**锁存**：位置一旦到达目标附近，这次 seek 就算完成。
                    // 不能用「|raw-target|≤容差」当持续性判据 —— 落地之后
                    // 播放会**越过**目标点继续走，差值只会越拉越大，
                    // 「没落地」就成了永真，目标位置被原样报 15 秒（超时兜底），
                    // 手机进度条冻死在 Seek 点 —— 真机踩过，就是本修的起因。
                    pendingSeekLanded = true;
                }
                if (!pendingSeekLanded
                        && !PlaybackPolicy.isSeekExpired(now - pendingSeekAtMs)) {
                    // 还没落地也没超时：报目标位置（控制点按"用户拖到哪"算，
                    // 两边一致）。落地/超时后走通用路径 —— 位置该多少报多少。
                    // pendingSeekMs 的清位统一在 checkStall（主线程），
                    // 这里只置锁存标志，避免两个线程同时改一个状态。
                    return (int) pending;
                }
            }
            // 位置冻结外推：个别平台 Seek 后媒体时钟可能真的停摆
            // （水位线不再前进）。水位线之后墙钟走了超过阈值、且确实在播，
            // 按「水位线 + 墙钟流逝」外推（钳到时长）。正常平台上水位线
            // 每拍都在抬，这个分支永远不会进。
            if (raw > 0
                    && now - hiWall > PlaybackPolicy.POSITION_FREEZE_EXTRAPOLATE_MS
                    && isActivelyPlaying()) {
                long est = hiRaw + (now - hiWall);
                int dur = 0;
                try {
                    dur = player.getDuration();
                } catch (Exception ignored) {
                }
                if (dur > 0 && est > dur) {
                    est = dur;
                }
                return (int) est;
            }
            return raw;
        } catch (Exception e) {
            return lastKnownPosition;
        }
    }

    /** 是否真的在播（暂停 / 未就绪 / 释放中都不算）—— 位置外推的前提 */
    private boolean isActivelyPlaying() {
        if (userPaused || player == null || playerReleased || nativePlayerDead || !prepared) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int getDuration() {
        try {
            if (player == null || playerReleased || nativePlayerDead || !prepared) {
                // 同 getPosition：错误态/已释放的播放器碰一下就是 -38 循环
                return 0;
            }
            return player.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    public void setVolume(float volume) {
        // 先记下来再下发：重连会重建 MediaPlayer 实例，不记的话音量会丢。
        this.volume = Math.max(0f, Math.min(1f, volume));
        applyVolume();
    }

    /** 当前音量，0 ~ 100。给 RenderingControl 的 GetVolume 回读用。 */
    public int getVolume0to100() {
        return Math.round(volume * 100f);
    }

    /**
     * 设置静音。
     *
     * <p>同样**先记再下发** —— 重连重建实例后要重放，
     * 否则一次断流就把用户的静音给取消了。
     */
    public void setMute(boolean mute) {
        this.muted = mute;
        applyVolume();
    }

    /** 当前是否静音。给 RenderingControl 的 GetMute 回读用。 */
    public boolean isMuted() {
        return muted;
    }

    /**
     * 把「音量 + 静音」这两个状态一起下发给播放器。
     *
     * <p>只留这一个出口：任何一处单独调 {@code player.setVolume()} 都会漏掉静音，
     * 于是出现「设了静音、改一下音量就又有声音了」这类只在特定顺序下复现的 bug。
     */
    private void applyVolume() {
        if (player == null || playerReleased || nativePlayerDead) {
            return;
        }
        try {
            float v = muted ? 0f : volume;
            player.setVolume(v, v);
        } catch (Exception e) {
            Log.w(TAG, "应用音量失败", e);
        }
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
        // 释放后 native 播放器不可再碰（任何方法调用都会触发 -38 错误回调）
        playerReleased = true;
        // 外推锚点随实例一起作废 —— 不清的话，新片源开头的正常位置
        // 会被当成「冻结」而误触发外推。
        hiRaw = -1;
        hiWall = 0;
        // 实例都没了，"准备中"和"待决的 seek"也就失去了载体。
        // 不清的话，下一次播放的「当前位置」会一直报上一次拖到的那个位置 ——
        // 控制点看到的进度条是上一部片子的。
        preparing = false;
        pendingSeekMs = -1L;
        // 视频尺寸复查同理：载体（MediaPlayer 实例）没了就必须摘掉，
        // 否则过期复查会对着已释放的实例跑 —— 判定套到新片源上。
        cancelVideoRecheck();
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
