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
         * 真实片尾自然播完（无续播、非假 EOS、非过期实例）。
         *
         * <p>这与 {@code onStateChanged("STOPPED")} 的区别：
         * <ul>
         *   <li>{@code STOPPED} 还会在暂停、控制点 Stop、错误等多条路径触发，不具备「自然播完」语义；</li>
         *   <li>本回调仅在 {@link #handleCompletion} 的真实片尾分支、且 {@code player} 未被替换时触发。</li>
         * </ul>
         * 实现方应清除当前片源字段（currentUri/元数据/标题/歌手/封面/歌词/下一曲/错误），
         * 使 {@link com.juping.cast.player.RenderState#hasContent()} 归假，界面回到空闲面板。
         * <b>不改 transportState</b>（已由前一步 {@code notifyState("STOPPED")} 推过）。
         */
        void onPlaybackEnded();

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

        /**
         * 厂商说：这段视频的**画面**编码本机解不了。
         *
         * <p>这是 {@link PlaybackPolicy#INFO_VIDEO_CODEC_NOT_SUPPORT} 那个 info 事件
         * 的回调形态。它**不是错误** —— 播放不中断、{@link #onError} 不会来，音频
         * 照常走，标准 API 层面（{@code getVideoWidth()}）也看不出任何异常，
         * 结果就是"有声音、一片纯黑"。真相与实测对照表见
         * {@link PlaybackPolicy#INFO_VIDEO_CODEC_NOT_SUPPORT}。
         *
         * @param detail 给日志用的技术细节（what/extra/地址），不该直接显示给用户
         */
        void onVideoCodecUnsupported(String detail);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    /**
     * 后台释放开始的时刻（0 = 当前没有在飞的释放）。
     *
     * <p>由调用线程在**启动后台线程之前**写上，由后台线程在 {@code release()}
     * 返回之后清掉。挂死时它就一直留着 —— 那正是判定器要的信号。
     *
     * <p>同一时刻最多只有一次在飞的释放：{@code player} 在交给后台线程之前
     * 就被置空了，后来的 {@code releasePlayer()} 走 {@code player == null}
     * 那条早退分支，不会再起第二个。
     */
    private volatile long releaseStartedAtMs = 0L;

    /** 这一轮后台释放是否已经判定为「厂商栈挂死」并上报过 —— 防每拍重复上报。 */
    private volatile boolean releaseStuckReported = false;

    /**
     * 有实例正在后台释放、新实例还没建出来 —— 也就是「这次建实例还在路上」。
     *
     * <p>native 的 {@code release()} 一律交给后台线程之后（见
     * {@link #releasePlayer}），建实例不再发生在调用方那一条语句里，而是等
     * 释放线程回主线程跑回调。这段空窗期里 {@code player == null} 且
     * {@code preparing == false}，与「根本没有实例、该建一个」长得一模一样 ——
     * 于是 {@code Play}（与 SetAVTransportURI 只隔几十毫秒）会判成
     * 「该建实例」，把刚建好、正在 prepare 的那个又拆了重建。
     *
     * <p>真机 2026-10-04 实测：一次换片源出现**两次 release + 两次 prepare**。
     * 这个标志就是为了把那段空窗期标出来，让重复的建实例请求并入在飞的这一次。
     *
     * <p>只有「释放完成后会回来续跑」（{@code onReleased != null}）才算数 ——
     * Stop / 退出路径没有后续动作，不该被它挡住。
     */
    private volatile boolean buildPending = false;

    /**
     * 「后台释放卡死」的判定器。
     *
     * <p>只做一件事：到点还没等到 {@code release()} 回来，就**如实上报一次**。
     *
     * <p><b>刻意不在这里做任何恢复动作</b>（重建实例 / 重启进程）：
     * 旧实例的 native 释放还占着厂商那套资源，赌「再建一个就能绕过」
     * 是拿竞态换概率 —— 这个项目已经在 {@code reset()} 的跨实例竞态上
     * 吃过一次亏（视频蓝屏 F1）。所以这里只负责「说清楚发生了什么」：
     * 用户看到错误提示、控制点不再转圈、日志里留下判据。
     *
     * <p>恢复手段只有**重新启动应用**：旧实例的 native 释放还占着厂商那套资源，
     * 再建新实例只是排在同一个坑后面（这也是这里不自动重启进程的原因 ——
     * 那是有用户可见代价的决定，得由人拍）。
     */
    private final Runnable releaseStuckWatchdog = new Runnable() {
        @Override
        public void run() {
            long started = releaseStartedAtMs;
            if (started == 0L || releaseStuckReported) {
                return;
            }
            long waited = System.currentTimeMillis() - started;
            if (!PlaybackPolicy.isReleaseStuck(waited)) {
                // 还没到点 —— 再排一次补足剩下的时间（不按固定周期轮询，
                // 免得阈值改了之后实际判定时间跟着漂）。
                handler.postDelayed(this, PlaybackPolicy.RELEASE_STUCK_MS - waited);
                return;
            }
            releaseStuckReported = true;
            Log.e(TAG, "厂商 MediaPlayer.release() 已挂起 " + waited + "ms 仍未返回"
                    + " —— 判定播放器栈已死：这一路播放无法继续，后续投屏也会被并入"
                    + "这次永不返回的释放（不重复建实例，免得再踩同一个 native 锁）。"
                    + "服务本身还活着（遥控器不再 ANR），恢复手段是重新启动应用");
            if (listener != null) {
                listener.onError(PlaybackPolicy.ERR_PLAYER_DEAD,
                        "release() 挂起 " + waited + "ms 未返回");
            }
            // 让控制点别再转圈：这一路播放到此为止。
            //
            // 走 "ERROR" 而不是 "STOPPED" —— 服务层把 ERROR 也映射成
            // TransportState=STOPPED，但语义上「出错停的」和「用户停的」
            // 不是一回事，日志里要能一眼分开。
            notifyState("ERROR");
        }
    };

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

    /**
     * 当前播放器实例。
     *
     * <p>{@code volatile}：探针线程每 250ms 读它一次（判断「我负责的那个实例还在不在」），
     * 而它由主线程与 HTTP 线程改写。原来只有主线程碰它，加探针之后必须补上可见性 ——
     * 否则探针可能拿着一个已经释放的旧实例继续问位置。
     */
    private volatile MediaPlayer player;
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

    // ─────────────────────────── native 采样缓存（修 A 的基石）───────────────────────────
    //
    // 下面这一组字段回答同一个问题：**native 播放器只能由谁读**。
    //
    // 答案是「只有探针线程」。原因是真机 ANR 取证（/data/anr/traces.txt，pid 1733）：
    // 厂商栈（海信 MTK）的 MediaPlayer.seekTo() 会挂住不返回，而它握着实例的 native
    // 串行锁 —— 同实例的 getCurrentPosition()/getDuration() 全部跟着堵。原来的主线程
    // ticker 每 0.5 秒直调一次 native 查询，于是主线程被冻死：UI 卡死、所有控制指令
    // （Seek / Stop / SetAVTransportURI）一起无响应，7 个以上 upnp-conn 线程排在同一个
    // 实例锁后面等。
    //
    // 现在：探针线程独占 native 读 → 写下面这几个缓存；界面、看门狗、控制点回读
    // （DlnaRendererService）一律读缓存。挂起的那次 seek 最多拖住探针，拖不住 UI。
    //
    // 见 PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS 的说明。

    /** 最近一次采样到的**原始**位置（毫秒）。native 的读只发生在探针线程。 */
    private volatile int cachedPositionMs;

    /** 上面那个位置被采到的墙钟；0 = 本次播放还没采到过。 */
    private volatile long cachedSampleAtMs;

    /**
     * 缓存的时长（毫秒）。
     *
     * <p>onPrepared 里先垫一次（那时 native 一定没挂），之后由探针线程刷新。
     * 界面判「有没有内容」（{@code MainActivity.isPlaying()}）与控制点回读都看它 ——
     * 原来它们每拍直调 {@code getDuration()}，正是 ANR 里主线程被冻死的那一句。
     */
    private volatile int cachedDurationMs;

    /** 缓存的「是否正在播放」。{@code isActivelyPlaying()} 的外推判据要用它。 */
    private volatile boolean cachedPlaying;

    /**
     * 探针这一次采样**正在读的那个实例**；{@code null} = 当前不在 native 里。
     *
     * <p>与 {@link #cachedSampleAtMs} 的区别：那个是「上一次**成功**采样的时刻」，
     * 这个是「这一次采样**正在**进行」。只有后者能区分两种沉默：
     * 「播放器停了所以没数据」与「探针被 native 卡住了」——
     * 前者可以照常重建，后者一碰 native 就跟着堵死。
     *
     * <p><b>为什么要连实例一起记</b>：卡住的那条探针线程**永远回不来**，
     * 它留下的时间戳会一直挂着。等新实例建好、又需要释放时，如果只看时间戳，
     * 就会把新实例也误判成「挂起」—— 于是每一次释放都走后台，白绕一圈。
     * 带上实例引用，判据自然只对「当前这个实例」成立。
     */
    private volatile MediaPlayer probeInFlightOwner;
    private volatile long probeInFlightSinceMs;

    /** 探针线程本体与开关；随实例重建而重起（旧的那条可能正卡在 native 里，让它自己烂掉）。 */
    private Thread sampler;
    private volatile boolean samplerRunning;
    private volatile MediaPlayer samplerOwner;

    /**
     * 当前片源的真实视频宽高（软件信箱用）；{@code null} = 还没探到 / 探不到。
     *
     * <p><b>按地址记账</b>：它只对 {@link #aspectUrl} 这个地址成立，换片源即作废
     * （见 {@link #play}）—— 否则新片源在探完之前会拿着上一部的宽高去摆信箱，
     * 黑边方向直接错。
     *
     * <p>{@code volatile}：探测在 daemon 线程上跑，结果由 handler 切回主线程写；
     * 界面刷新线程会读它。
     */
    private volatile int[] videoAspect;

    /**
     * 已经探测完成的地址（探到 {@code null} 也算完成 —— 避免每个 tick 对同一个
     * 地址反复发探测请求）。<b>按地址记账，换片源即作废。</b>
     */
    private volatile String aspectUrl;

    /**
     * 正在探测的地址（防重入：同一个地址不并发探两次）。<b>按地址记账，换片源即作废。</b>
     */
    private volatile String aspectProbing;

    /**
     * 当前片源的 DPB 预检是否超限（真机表现＝硬件放弃视频、黑屏有声）。
     *
     * <p><b>与 {@link #videoAspect} 同一份探测、同一个临界区落账、同样按地址作废</b> ——
     * 两个结论都从 {@link VideoAspectProbe#probeResult} 一次拿回，不存在"宽高是新的、
     * 超限标志是旧的"。A3 提示条据此亮文案；解不出（null）时为 false ——
     * 「没预检出」绝不许伪装成「超限」。
     */
    private volatile boolean aspectDpbExceeds;

    /**
     * 是否正在 prepare（{@code prepareAsync} 已发出、回调还没到）。
     *
     * <p><b>必须和 {@link #prepared} 分开</b>：这两者的正确处置完全相反。
     * {@code prepared} → 直接 {@code start()}；
     * {@code preparing} → 什么都不做，等 onPrepared 自己 {@code start()}。
     *
     * <p>合成一个布尔值就区分不出「准备中」和「还没开始」，于是 prepare 期间的
     * Play 会把正在准备的实例掐掉重建 —— 见 {@link #resume} 里的详细说明。
     *
     * <p>volatile：{@link #isPreparing()} 在界面刷新线程上读（「视频准备中」判据），
     * 置位却发生在控制点线程的 startInternal 里 —— 不加的话界面可能一直看不见
     * 翻假的值，占位层赖着不走。
     */
    private volatile boolean preparing;

    /** 上一次 releasePlayer() 完成 release 的时刻（0 = 从未释放过）。
     *  只为取证日志量「teardown → prepare」间隔用，见 startInternal。 */
    private volatile long lastReleaseAtMs;

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
     * 本次播放「起播」的时刻 —— 与 {@link #lastProgressAt} 差别在哪，为什么必须分开记。
     *
     * <p>{@code lastProgressAt} 是<b>会动的基准</b>：位置每前进一次就刷新，
     * 所以 {@code now - lastProgressAt} 度量的是「位置最近一次前进到现在多久」，
     * 这是卡死判据要的语义。
     *
     * <p>而「起播了但一直没出声」要问的是另一个问题：<b>从起播到现在，位置动过没有</b>。
     * 位置从头到尾是 0 时 {@code lastProgressAt} 会在第一次看门狗检查时被对齐一次、
     * 之后就不动了，拿它当基准会把这段静止重新算短 —— 用它判会拖到通用阈值。
     * 单独记一个只由 onPrepared / resume 写入的起点，才是这个问题该有的时钟。
     */
    private volatile long playStartedAtMs;

    /**
     * 「这一轮播放已经到片尾了」—— 看门狗补判出来的播完，防止它每拍重报一次。
     *
     * <p>为什么需要这个闩锁：厂商栈对一部分流不送 {@code onCompletion}
     * （见 {@link PlaybackPolicy#isAtEndOfStream}），所以看门狗得替它判。
     * 但片尾的位置是**冻住不动的** —— 判完之后下一拍（5 秒后）看到的是同一个
     * 位置，会再判一次、再报一次 STOPPED，日志和控制点都被刷。
     *
     * <p>清除时机只有一个：位置真的动了（换片源、用户拖走、重连成功）。
     * 也就是说它表达的是「当前位置处在本轮的片尾」，而不是「这一轮播过了」。
     */
    private volatile boolean reachedEndOfStream;

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
     * 懒取的 {@code AudioManager}，只用来推框架 {@code STREAM_MUSIC} 流（§7.6 第③层）。
     *
     * <p>⚠️ 本机（海信 MTK5880）实测该流【不参与实际响度】——厂商音频绕过框架混音器，
     * 保留推送仅为不绕过混音器的普通 Android 设备兜底（三通路取证见 todo §7.24）。
     *
     * <p>懒取而不是在构造里取：多数会话里控制点一次音量都不调，没必要为一个
     * 可能用不到的服务去碰系统。取不到就保持 null，调用方按"不推框架流"处理。
     */
    private AudioManager audioManager;

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
            checkPrepareStuck();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** SurfaceView 的 Surface 就绪 / 重建时调用 */
    public void setSurface(Surface surface) {
        this.surface = surface;
        // native 已挂起时不碰实例：setSurface() 会排在挂起的那次 native 调用后面，
        // 而这条路径是从界面（主线程）走进来的 —— 那就是一次现成的 ANR。
        // 挂起态下画面本来已经僵住，等看门狗重建出新实例，surface 会在那时重新挂上
        // （startInternal 里有 `if (surface != null) player.setSurface(surface)`）。
        if (player != null && !isProbeWedged()) {
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
        if (!PlaybackPolicy.shouldRebuild(url, currentUrl, prepared, preparing, buildPending)) {
            Log.i(TAG, "收到与当前相同的地址，幂等忽略（不重建播放器）");
            return false;
        }
        // 先掐掉上一次排期的重连：否则它会在一两秒后拿**旧 URL** 再起一次播放，
        // 把用户刚投上来的新视频顶掉。表现就是"投了新视频，画面跳回上一个"。
        cancelPendingRetry();
        currentUrl = url;
        // 换了片源，按地址记的宽高账立刻作废 —— 判据抽在 clearAspectUnless
        // 里（play() 与续播两条路共用，见该方法注释）。
        clearAspectUnless(url);
        retryCount = 0;
        stallCount = 0;
        // 「已在片尾」同理作废：新片子还没播，谈不上片尾。
        // 不清的话，投一部新片而它的首拍位置恰好也在片尾附近时，
        // 会被上一部的闩锁顶掉、少判一次。
        reachedEndOfStream = false;
        userPaused = false;
        // 换了片源，上一次的 seek 目标立刻作废 ——
        // 不清的话，新片子的「当前位置」会先报成上一部片子拖到的进度。
        pendingSeekMs = -1L;
        // 「假 EOS 重建补发」同理作废：它扛得过重建，但扛不过换片源 ——
        // 不清的话，新片子 prepare 完会拿着上一部片子的进度去 seek。
        seekAfterRebuildMs = -1L;
        // 「补发次数」也作废：那是"同一次 seek"的计数，换片源就是新的一次。
        seekReplayCount = 0;
        // 「起播时刻」同理作废：留着上一部片子的起点在，新片子还没 prepare 完
        // 就可能被算出「起播已超宽限」—— 虽然 prepared 为假会挡住这次误判，
        // 但把两件事绑在一起靠巧合成立，不如在换片源时一并清干净。
        playStartedAtMs = 0L;
        // 新片源到达，「下一曲」队列作废（新歌单会重新 SetNext）
        nextUrl = null;
        nextMetadata = null;
        // 新片源 = 新的 prepare 机会，卡死重建计数清零
        prepareStuckRebuilds = 0;
        // 兜底位置同理：不清的话，新片子起播前会先报上一部的进度
        lastKnownPosition = 0;
        startInternal();
        return true;
    }

    /**
     * 建实例并起播 —— <b>整个「释放旧的 → 建新的 → 配置新的」序列必须原子</b>。
     *
     * <p><b>为什么是 {@code synchronized}</b>：这个方法会被三条不同的路调进来 ——
     * {@link #play()}（持实例锁）、{@link #resume()}（持锁）、以及
     * <b>auto-advance 后台线程</b>（续播，见 {@code onCompletion} 里的
     * {@code new Thread(..., "auto-advance")}）与释放回调（主线程）。
     * 后两条**不持锁**。
     *
     * <p>不加锁时两条路会互相拆台：A 线程刚 {@code new MediaPlayer()} 出来，
     * B 线程的 {@code releasePlayer()} 就把字段置空了，A 走到
     * {@code player.setScreenOnWhilePlaying()} 直接 NPE。
     * 真机取证：日志里 {@code setDataSource 完成: 距释放 -14ms} —— 负 14 毫秒
     * 说明 setDataSource 期间另有一次释放把 {@code lastReleaseAtMs} 推到了"未来"。
     * （{@code scheduleRetry} 里那段「两个 startInternal 会各自把对方刚建的实例
     * 释放掉」的注释描述的就是同一个竞态，当时只堵了重连这条路。）
     *
     * <p><b>代价</b>：持锁期间会跑 {@code setDataSource()}（可能几百毫秒）。
     * 主线程调进来时本就在锁内（{@code play()} 是 synchronized），行为不变；
     * 变的是 auto-advance 那条后台路 —— 它现在也会持锁。续播发生在「当前曲目
     * 自然播完」的时刻，此时几乎不会有并发的控制指令，这个窗口可以接受。
     */
    private synchronized void startInternal() {
        // ---- 「上一次建实例还在路上」：并入那一次，不重复建 ----
        //
        // 异步释放之后，从「开始释放」到「回调里 new MediaPlayer()」之间有一段空窗期，
        // 期间 player == null、preparing == false —— 与「没有实例、该建一个」无法区分。
        // 控制点是 SetAVTransportURI + Play 连着发的（隔几十毫秒），Play 必然落进
        // 这段空窗期，于是会再建一次、把刚建好的那个又拆掉（真机实测两次 release
        // + 两次 prepare）。这里直接早退：currentUrl 已经在 play() 里更新过，
        // 在飞的那次回调会用**最新**的地址去建，语义上完全等价。
        if (buildPending) {
            // 两种情形都会走到这里，必须分开说 —— 否则日志会说谎：
            //   ① 释放正常进行中：回调会来，它会用**最新**的 currentUrl 建实例；
            //   ② 释放已判定永不返回（releaseStuckReported）：不会有任何回调，
            //      「并入它的回调」是句空话，真实含义是「这一路及后续投屏都不受理了」。
            if (releaseStuckReported) {
                Log.w(TAG, "播放器栈已死（release 永不返回），本次投屏不受理"
                        + " —— 恢复手段是重新启动应用");
            } else {
                Log.i(TAG, "上一次建实例还在路上，本次并入它的回调（不重复建播放器）");
            }
            return;
        }
        // 释放可能被挪到后台线程（native 已挂起时）—— 那种情况下**先返回**，
        // 等释放完成再由回调重新进来。不能在这里同步等：等的就是那次挂起的 native。
        // 重新进来时 player 已是 null，releasePlayer 立刻返回 true，正常往下走。
        //
        // ⚠️ 那个 `true` **必须是真正的空操作**（§7.31）：这条重入路上，
        // 控制点可能已经往 pendingSeekMs 暂存了一次 Seek（换片源 + 立刻拖进度条），
        // 释放回调里再清一次状态就会把它静默吃掉 —— 用户看到「拖了，电视从头发」。
        if (!releasePlayer(new Runnable() {
            @Override
            public void run() {
                // 释放回来了，「建实例还在路上」这段空窗期到此结束 ——
                // 必须在 startInternal() 之前清，否则会被上面那道早退挡回来。
                buildPending = false;
                // 后台释放完成 —— 世界可能已经变了（用户暂停了、停止投屏了），
                // 与 pendingRetry 的回调同一条纪律：条件不成立就不再起播。
                if (!userPaused && currentUrl != null) {
                    startInternal();
                }
            }
        })) {
            return;
        }
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
            // 取证（视频蓝屏 F1/F3 决策）：setDataSource 完成时刻 —— 与
            // 「释放播放器: release 结束」的时差就是 teardown→prepare 的实际间隔。
            //
            // lastReleaseAtMs == 0 表示**本次进程还从没释放过播放器**（冷投的第一部）。
            // 这时不能拿 0 当基准相减：那会打出「距释放 1791076172236ms」这种
            // 17 亿毫秒的垃圾，把真正有用的那条时间线搅浑。
            Log.i(TAG, "setDataSource 完成: t=" + prepareStartedAt
                    + "，距释放 " + (lastReleaseAtMs == 0
                            ? "（本次进程尚未释放过）"
                            : (prepareStartedAt - lastReleaseAtMs) + "ms"));
            // 起播的同时异步探一下直连 MP4 的真实宽高（软件信箱用）。
            // 探测只对非 m3u8 地址有意义 —— 这里不重复挡 m3u8，交给
            // VideoAspectProbe.probe 自己挡（红线只留一处，免得两处判据漂移）。
            // 探到与否都不影响播放：失败静默退回全屏。
            maybeProbeAspect(currentUrl);
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
                    // 新的一轮播放从 prepare 完成算起 —— 「已在片尾」这个闩锁
                    // 必须跟着复位，否则重连出来的新实例会在片尾判据上被
                    // 上一轮的状态顶掉（见字段说明）。
                    reachedEndOfStream = false;
                    lastProgressAt = System.currentTimeMillis();
                    // 起播时刻与 lastProgressAt 同源取一次。之后 lastProgressAt 会被
                    // 位置前进反复刷新，这个起点不动 —— 见字段说明与 checkStall 的判据。
                    playStartedAtMs = lastProgressAt;
                    hasVideo = detectVideo(mp);
                    // 诊断（视频蓝屏取证）：prepare 成功返回时的耗时 —— 用来确认
                    // 闸门/看门狗是否把「30 秒」降了下来（见 `.agent/video-bluescreen-plan.md` §5 组 C）。
                    Log.i(TAG, "prepare 结束: 耗时 "
                            + (System.currentTimeMillis() - prepareStartedAt)
                            + "ms（成功），hasVideo=" + hasVideo);

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
                    //
                    // 两个来源，取"更新"的那个：
                    //   ① prepare 还没完成时控制点就拖了 → pendingSeekMs 本来就在；
                    //   ② 一次「假 EOS 重建」替控制点保下来的（seekAfterRebuildMs）——
                    //      pendingSeekMs 已被 releasePlayer() 清掉，这里必须重新装上，
                    //      否则进度条上报与落地判定都看不见这次 seek。
                    long replayMs = seekAfterRebuildMs >= 0 ? seekAfterRebuildMs : pendingSeekMs;
                    seekAfterRebuildMs = -1L;
                    if (replayMs >= 0) {
                        pendingSeekMs = replayMs;
                        pendingSeekAtMs = System.currentTimeMillis();
                        pendingSeekLanded = false;
                        hiRaw = (int) replayMs;
                        hiWall = pendingSeekAtMs;
                        try {
                            mp.seekTo((int) replayMs);
                            Log.i(TAG, "prepare 完成，补发暂存的 seek " + replayMs + "ms");
                        } catch (Exception e) {
                            Log.w(TAG, "补发 seek 失败", e);
                        }
                    }

                    // ---- 时长在这里垫一次底，然后交给探针线程刷新 ----
                    //
                    // onPrepared 是 native 刚回调上来的时刻，那一刻它一定没挂 ——
                    // 这是整个生命周期里**唯一**可以放心直调 getDuration() 的窗口。
                    // 之后界面（每 0.5 秒判「有没有内容」）与控制点回读
                    // （GetMediaInfo / GetPositionInfo）都读这个缓存。
                    //
                    // 原实现是那两处各自直调 native，而主线程那一处正是真机 ANR
                    // （/data/anr/traces.txt，pid 1733）里被冻死的那一句。
                    cachedDurationMs = mp.getDuration();
                    cachedPositionMs = 0;
                    cachedPlaying = false;
                    cachedSampleAtMs = System.currentTimeMillis();
                    // 探针线程随实例启动 —— 全进程唯一的周期性 native 读者
                    startSampler(mp);

                    mp.start();
                    notifyState("PLAYING");
                    if (listener != null) {
                        listener.onPrepared(cachedDurationMs, hasVideo);
                    }
                    startWatchdog();
                }
            });

            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    handleCompletion(mp);
                }
            });

            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    // 返回 true 表示「已处理」，避免系统弹出错误对话框
                    Log.w(TAG, "播放错误 what=" + what + " extra=" + extra + " url=" + currentUrl);
                    // 诊断（视频蓝屏取证）：把「从 prepare 开始到出错」的耗时留下来 ——
                    // 与成功路径的「prepare 结束」配对，用于分流根因与计时
                    // （见 `.agent/video-bluescreen-plan.md` §5 组 C）。
                    Log.w(TAG, "prepare 结束（错误）: 耗时 "
                            + (System.currentTimeMillis() - prepareStartedAt)
                            + "ms，what=" + what + " extra=" + extra);
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

            // 厂商把「画面编码解不了」当 info 事件发（不中断播放、不走 onError），
            // 这是应用唯一能感知到它的地方 —— 详见
            // PlaybackPolicy.INFO_VIDEO_CODEC_NOT_SUPPORT 里的实测对照表。
            //
            // 只认这一个码：同一次播放里厂商还会发标准的 701/702（缓冲开始/结束）
            // 之类，那些与「解不了」无关，一律不理会。
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (PlaybackPolicy.isVideoCodecUnsupportedInfo(what)) {
                        String detail = "厂商 info what=0x" + Integer.toHexString(what)
                                + " extra=" + extra + " url=" + currentUrl;
                        Log.w(TAG, "画面编码解不了（厂商 info 事件）: " + detail);
                        if (listener != null) {
                            listener.onVideoCodecUnsupported(detail);
                        }
                    }
                    // 返回 false：不消费，让框架/其它监听方照常处理
                    return false;
                }
            });

            // 诊断（视频蓝屏取证）：prepare 发出这一刻，输出面在不在。
            // 注：A/B 对照后「Surface 顺序」假设已被证伪（根因是 release→prepare
            // 异步竞态，见 `.agent/video-bluescreen-plan.md` §2.2），这条日志保留
            // 作可观测性用，不再是根因判据。不打 URL（带签名，属敏感）。
            Log.i(TAG, "prepare 开始: surface=" + (surface != null));
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
                // 把复查当时读到的尺寸记下来：这条日志是「画面到底有没有出来」
                // 唯一的现场记录 —— 编码解不了时它恒为 0x0，而正常的 H.264
                // 会报出真实尺寸（真机实测 960x540），事后排障全靠它。
                int w = 0, h = 0;
                try {
                    w = mp.getVideoWidth();
                    h = mp.getVideoHeight();
                } catch (Exception e) {
                    Log.w(TAG, "读视频尺寸失败", e);
                }
                Log.i(TAG, "视频尺寸复查（第 " + attempt + " 次）: " + w + "x" + h);
                if (detectVideo(mp)) {
                    hasVideo = true;
                    Log.i(TAG, "视频尺寸延迟就绪（第 " + attempt
                            + " 次复查），从音乐形态切回视频形态");
                    if (listener != null) {
                        listener.onPrepared(cachedDurationMs, true);
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
                            listener.onPrepared(cachedDurationMs, true);
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

    // ───────────────────────────── native 探针（全进程唯一的 native 读者）─────────────────────────────

    /**
     * 为某个播放器实例启动探针线程。
     *
     * <p><b>每个实例一条，不复用。</b>卡住的那条线程永远回不来（它就堵在 native 里），
     * 复用它等于让新实例也没有位置可读。旧线程在 native 返回后会发现
     * {@code samplerOwner} 已经换人（或 {@code samplerRunning} 已为假）而自行退出。
     *
     * @param owner 这条探针负责的实例；换实例必须重起一条
     */
    private void startSampler(MediaPlayer owner) {
        stopSampler();
        samplerOwner = owner;
        samplerRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                samplerLoop();
            }
        }, "juping-native-probe");
        // daemon：进程要退就退，别为了它拖着不关
        t.setDaemon(true);
        sampler = t;
        t.start();
    }

    /**
     * 停掉探针。
     *
     * <p><b>只置标志、不 join</b>：这条线程此刻可能正卡在 native 里（那正是它需要
     * 被换掉的原因），join 就是把调用者一起拖进同一个坑。让它自己在 native 返回后退出。
     */
    private void stopSampler() {
        samplerRunning = false;
        samplerOwner = null;
        sampler = null;
    }

    private void samplerLoop() {
        final MediaPlayer mine = samplerOwner;
        while (samplerRunning && samplerOwner == mine) {
            if (prepared && !playerReleased && !nativePlayerDead && player == mine) {
                sampleOnce(mine);
            }
            try {
                Thread.sleep(PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * 采一拍：位置 / 时长 / 播放态，写进缓存。
     *
     * <p>这三句是**整个应用里仅有的**周期性 native 读。以前它们分散在主线程
     * ticker、看门狗、控制点轮询三处，各自都能被一次挂起的 seek 冻住；
     * 现在只有这一条线程可能被冻，而它冻住只意味着「进度条不再刷新」。
     */
    private void sampleOnce(MediaPlayer mp) {
        probeInFlightOwner = mp;
        probeInFlightSinceMs = System.currentTimeMillis();
        try {
            int p = mp.getCurrentPosition();
            int d = mp.getDuration();
            boolean playing = mp.isPlaying();
            if (mp != player) {
                // 采样期间换过实例：这一拍作废，别把旧实例的值写进新实例的账本
                return;
            }
            cachedPositionMs = p;
            cachedDurationMs = d;
            cachedPlaying = playing;
            cachedSampleAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            // 读失败就保持上一拍的值 —— 与 getPosition/getDuration 原来在错误态下的
            // 语义一致（报最后已知值，而不是 0 或抛出去）
        } finally {
            // 只清**自己这一拍**留下的标记：卡死的那条旧线程可能在很久以后才返回，
            // 那时新实例的探针可能正在 native 里 —— 无条件清会把新探针的
            // 「正在读」标记抹掉，挂起判据跟着失灵（该走后台释放的走了同步）。
            if (probeInFlightOwner == mp) {
                probeInFlightOwner = null;
                probeInFlightSinceMs = 0;
            }
        }
    }

    /**
     * 探针是不是已经被 native 卡住了。
     *
     * <p>判据必须是「**当前这个实例**上的那次采样」超时：卡死的旧线程留下的时间戳
     * 不能算到新实例头上（见 {@link #probeInFlightOwner}）。
     */
    private boolean isProbeWedged() {
        MediaPlayer owner = probeInFlightOwner;
        long since = probeInFlightSinceMs;
        return owner != null
                && owner == player
                && since > 0
                && System.currentTimeMillis() - since > PlaybackPolicy.NATIVE_PROBE_STUCK_MS;
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
     * prepare 卡死的重建检查（看门狗每拍调一次）。
     *
     * <p>海信 CmpbPlayer 实测：连续切歌后媒体服务可能卡死 —— prepareAsync
     * 发出后 onPrepared/onError 都不来，手机卡在「加载」、Stop 也没反应，
     * 只能重启电视。重建播放器会拿到全新的播放器实例，多数情况能自救；
     * 连续重建仍卡死则如实报 ERROR 并提示重启电视（媒体服务进程级卡死，
     * 应用层无解）。
     */
    private void checkPrepareStuck() {
        if (!preparing || playerReleased) {
            return;
        }
        long stuckMs = System.currentTimeMillis() - prepareStartedAt;
        if (stuckMs < PlaybackPolicy.PREPARE_STUCK_REBUILD_MS) {
            return;
        }
        if (prepareStuckRebuilds >= PlaybackPolicy.PREPARE_STUCK_MAX_REBUILDS) {
            Log.e(TAG, "prepare 连续 " + prepareStuckRebuilds + " 次重建仍卡死"
                    + " —— 疑似电视媒体服务异常，建议重启电视后重试");
            preparing = false;
            nativePlayerDead = true;
            notifyState("ERROR");
            return;
        }
        prepareStuckRebuilds++;
        Log.w(TAG, "prepare 卡死 " + stuckMs + "ms（无任何回调），第 "
                + prepareStuckRebuilds + " 次重建播放器自救");
        String url = currentUrl;
        currentUrl = null;
        play(url);
    }


    /**
     * 「播完了」的**唯一处置点**。
     *
     * <p><b>两个入口共用它</b>：① 厂商送来的 {@code onCompletion}；
     * ② 看门狗在片尾补判的 —— 厂商栈对一部分流**根本不送播完回调**
     * （真机 B站 1080P：位置走到 205s，一次播完回调都没有），
     * 判据见 {@link PlaybackPolicy#isAtEndOfStream}。
     *
     * <p>刻意合成一处：这里面有 nextUrl 接棒、假 EOS 甄别、STOPPED 上报三件事，
     * 拆成两份迟早会走岔 —— 而"播完了"这件事本来就只有一个含义。
     */
    private void handleCompletion(MediaPlayer mp) {
        // 取证：每一次「播完」回调都把判据的输入留下来。
        //
        // 没有这一行，「判据为什么不触发」事后完全无法回溯 —— 真机踩到过：
        // 一次 4 分钟 prepare 卡死之后等来的 EOS，日志里只剩一句
        // 「播放状态: STOPPED」，preparing / 已观察位置 / duration
        // 当时各是什么值，全无从查起。
        //
        // 刻意放在**过期实例校验之前**：被丢弃的过期回调同样是静默的，
        // 而"这次播完为什么被丢掉"恰恰是要查的问题之一。
        long sinceStart = System.currentTimeMillis() - prepareStartedAt;
        int dur = getDuration();
        Log.i(TAG, "播完回调：过期实例=" + (mp != player)
                + "，preparing=" + preparing + "，prepared=" + prepared
                + "，userPaused=" + userPaused + "，已观察最远位置=" + maxPlayedMs
                + "ms，时长=" + dur + "ms，起播至今=" + sinceStart + "ms");
        // 过期实例的「播完」一概忽略：重连 / 换片 / 续播都会重建实例，
        // 而老实例的回调可能在新实例已经开工之后才到 —— 照单全收
        // 会把刚起来的播放报成 STOPPED。与 scheduleVideoRecheck /
        // scheduleContentTypeProbe 是同一条纪律。
        if (mp != player) {
            return;
        }
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
            // 切源 + 重建放**后台线程**：与 onSetUri 的 HTTP 线程模式
            // 一致，且避免厂商播放器的 setDataSource 在主线程上卡住
            // 时把整个应用冻住（手机端表现为永久「加载中」）。
            // 此刻已不在 onCompletion 回调栈里，release 旧实例安全。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    currentUrl = u;
                    // 续播绕过了 play()（不经 SOAP、不重进那条幂等判定），
                    // 但「换片源清宽高账」这一步**不能跟着被绕过**：
                    // 视频→视频续播时不清账，新片探完之前会拿着上一部
                    // 的宽高摆信箱 —— 黑边方向直接错（QA 复审②）。
                    clearAspectUnless(u);
                    startInternal();
                    if (listener != null) {
                        listener.onSourceChanged(u, m);
                    }
                }
            }, "auto-advance").start();
            return;
        }
        // ---- 「假 EOS」判据 ----
        //
        // 厂商栈会把「释放旧播放器资源」和「切换输入源」规范化成一次
        // EOS（日志原文 "This is adt event,the Normal event type is EOS!!!"），
        // 框架据此回调到这里。真机 8 次实测，每次都在起播后 1 秒内，
        // 最典型的是「投完视频再投音频，首次 Set 直接变 STOPPED」——
        // 手机显示投上了，电视其实已经停了。
        //
        // 判据与阈值全在 PlaybackPolicy（纯逻辑，桌面上有断言）。
        // 放在续播分支**之后**：有下一曲时照常接棒，既有行为不变。
        if (!userPaused
                && PlaybackPolicy.isSpuriousCompletion(preparing, sinceStart,
                        maxPlayedMs, dur)) {
            Log.w(TAG, "收到疑似假 EOS（厂商层把「释放旧资源 / 切输入源」"
                    + "当成了播完）：preparing=" + preparing + "，已观察最远位置 "
                    + maxPlayedMs + "ms / 时长 " + dur + "ms，起播至今 "
                    + sinceStart + "ms —— 重建播放器，不报 STOPPED");
            // 重建要走 releasePlayer()，而那里会把 pendingSeekMs 清掉 ——
            // 用户刚拖的那一次 seek 就被这一次假 EOS 吃掉了（真机：Seek 下发
            // 113ms 后就来假 EOS，重建完位置回到 0，手机上看就是「拖了没反应」；
            // 后面几首偶尔不触发假 EOS，于是"切到后面几首又可以了"）。
            // 记到 seekAfterRebuildMs 上：它能扛过 releasePlayer，
            // 由 onPrepared 补发。
            //
            // **但补发必须有上限**：厂商栈对个别文件会直接拒绝 seek
            // （真机：秋殇 mp3 → Failed / MTK ret -6），补发过去照样失败、
            // 照样来假 EOS。没有上限就是「重建 → 补发 → 又失败 → 又重建」
            // 的死循环（真机连续 51 轮）。补发够次数仍失败，就认定这个
            // seek 厂商做不到：丢掉它照常播放，不再谎报那个到不了的位置。
            if (pendingSeekMs >= 0) {
                if (PlaybackPolicy.canReplaySeekAfterRebuild(seekReplayCount)) {
                    seekAfterRebuildMs = pendingSeekMs;
                    seekReplayCount++;
                    Log.w(TAG, "把这次 seek " + pendingSeekMs + "ms 存下来，"
                            + "等重建后补发（第 " + seekReplayCount + " 次）");
                } else {
                    Log.w(TAG, "seek " + pendingSeekMs + "ms 重建后补发仍失败，"
                            + "放弃该 seek（不再重建），按真实位置继续播放");
                    seekAfterRebuildMs = -1L;
                    pendingSeekMs = -1L;
                    pendingSeekLanded = false;
                    hiRaw = -1;
                    hiWall = 0;
                }
            }
            scheduleRetry();
            return;
        }
        // ---- 真实片尾：报停止之后，还要把播放器收尾干净 ----
        //
        // 为什么不能只 notifyState("STOPPED") 就 return：
        // 自然播完 / 看门狗片尾补判走到这里时，厂商 MediaPlayer **仍然活着**、
        // 仍然占着 `TV_WIN_ID_MAIN` 那块硬件视频窗。MTK 的视频渲染不在
        // SurfaceFlinger 图层表里（screencap 抓不到），只有 release 播放器
        // 才能关掉那一层。不释放的后果：定格帧（或黑帧）**永久滞留**在屏幕上，
        // 用户按返回退出、重投别的片子都盖不住它 —— 表现为"还卡在上一条"。
        // 真机取证：日志 08:26:50 报 STOPPED 却没有任何 release，直到 3.5 分钟
        // 后的下一次操作才释放（`release 开始`）—— 那 3.5 分钟画面一直冻着。
        //
        // 必须**抛到下一轮主循环**再释放：此刻可能正站在厂商 onCompletion 的
        // 回调栈里，在那个栈里 release 播放器自己，老平台有崩溃先例
        // （见上方续播分支同一条纪律）。post 出去就脱离了回调栈。
        //
        // 解绑 Surface 放在 release 之前，与 stop() 同一条顺序纪律：先让解码器
        // 交出输出面、视频层随即关闭，再释放实例，避免"停住了还蓝着一块"。
        notifyState("STOPPED");
        // 「自然播完」要单独告诉服务层，不能只靠上面那句 STOPPED：
        // STOPPED 还会从暂停收尾、控制点 Stop、错误等多条路径进来，
        // 蹭那个字符串收尾会把「暂停」也清成片源为空（QA 复盘过同款误伤）。
        // 而界面形态机（PlaybackPolicy.modeOf）只要 currentUri 还挂着
        // 就判成「仍在放」→ 视频态 SurfaceView 继续盖着面板，可视频层
        // 又已经随 release 关掉 —— 结果就是「播完黑屏不回面板」。
        // 清字段不碰 native，站在回调栈里执行是安全的。
        if (listener != null) {
            listener.onPlaybackEnded();
        }
        final MediaPlayer doomed = player;
        handler.post(new Runnable() {
            @Override
            public void run() {
                // 排队期间可能又起了新实例（重投 / 续播）：只有字段还是
                // 当初那个"已播完"的实例才动手，否则放过它（新实例自有其收尾）。
                if (doomed != null && doomed == player) {
                    // 解绑 Surface 是 native 调用 —— 与 stop() 同一条纪律：
                    // native 已挂起时跳过，否则这句会把主线程堵死（ANR）。
                    // 跳过后仍靠下面的 release（走后台线程）关掉硬件视频窗，
                    // 只是可能多一瞬间残留，但绝不以 ANR 为代价。
                    if (!isProbeWedged()) {
                        try {
                            doomed.setSurface(null);
                        } catch (Exception e) {
                            Log.w(TAG, "片尾解绑 Surface 失败", e);
                        }
                    }
                    releasePlayer(null);
                    Log.i(TAG, "片尾收尾：已释放播放器（清硬件视频窗）"
                            + (isProbeWedged() ? "，native 挂起，跳过解绑" : ""));
                }
            }
        });
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
            long seekElapsed = System.currentTimeMillis() - pendingSeekAtMs;
            if (pendingSeekLanded || PlaybackPolicy.isSeekExpired(seekElapsed)) {
                // ---- seek 落地看门狗（修 A ②）----
                //
                // 必须在清位**之前**判定：判据要的正是「还没落地」这个状态。
                //
                // 真机现象：拖进度条 / 点下一集之后，界面卡死、控制指令全部无响应。
                // ANR 取证（/data/anr/traces.txt，pid 1733）显示厂商栈的
                // MediaPlayer.seekTo() 挂住不返回，握着实例的 native 串行锁，
                // 主线程每 0.5 秒一次的 getDuration() 跟着堵死。
                //
                // 光把主线程从 native 上摘下来还不够：那一次 seek 永远落不了地，
                // 播放器就此僵在那里。所以超时后**重建播放器**，
                // 走与「假 EOS 重建」同一套补发机制（seekAfterRebuildMs 能扛过
                // releasePlayer，由 onPrepared 补发）。补发预算用尽就不再重建
                // —— 那是厂商直接拒绝这个 seek 的情形，重建会变成死循环。
                boolean rebuild = PlaybackPolicy.shouldRebuildOnSeekTimeout(
                        !pendingSeekLanded, seekElapsed, seekReplayCount);
                pendingSeekMs = -1L;
                // 立刻把基准对齐到当前位置：不对齐的话，"seek 期间位置没动"这段
                // 静止会被下一轮检查算成卡死 —— 超时兜底反而制造一次多余的重连。
                lastProgressAt = System.currentTimeMillis();
                lastPosition = cachedPositionMs;
                // 「落地」与「超时」是两回事，日志必须分开：原来共用一句
                // 「超过 15000ms 仍未落地，放弃等待」，正常落地也报超时告警，
                // 且打的是阈值常量而非真实耗时 —— 排障时完全误导。这里打真实 elapsed。
                if (pendingSeekLanded) {
                    Log.i(TAG, "seek 到 " + pending + "ms 已落地（耗时 " + seekElapsed + "ms）");
                } else if (rebuild) {
                    Log.w(TAG, "seek 到 " + pending + "ms 超过 "
                            + PlaybackPolicy.SEEK_PENDING_TIMEOUT_MS + "ms 仍未落地（实际 "
                            + seekElapsed + "ms）—— 重建播放器后补发（第 "
                            + (seekReplayCount + 1) + " 次）");
                    seekAfterRebuildMs = pending;
                    seekReplayCount++;
                    // 卡死按网络问题处理，错误重连计数清零（与下面 stallCount 分支同一条纪律）
                    retryCount = 0;
                    scheduleRetry();
                } else {
                    Log.w(TAG, "seek 到 " + pending + "ms 超过 "
                            + PlaybackPolicy.SEEK_PENDING_TIMEOUT_MS + "ms 仍未落地（实际 "
                            + seekElapsed + "ms），补发预算已用尽，放弃等待");
                }
            }
            return;
        }
        try {
            // 位置 / 时长 / 播放态一律读缓存 —— 看门狗跑在主线程上，
            // 这三句原来是直调 native 的（真机 ANR 里主线程就冻在这儿）。
            if (!cachedPlaying) {
                return;
            }
            long pos = cachedPositionMs;
            // 「假 EOS」判据的第二路采样：看门狗每 5 秒一拍。
            // 控制点不轮询 GetPositionInfo 时，就只剩这一路在采位置 ——
            // 没有它，判据会把「根本没采到位置」错当成「位置是 0」。
            if (pos > maxPlayedMs) {
                maxPlayedMs = (int) pos;
            }
            long now = System.currentTimeMillis();
            if (pos != lastPosition) {
                lastPosition = pos;
                lastProgressAt = now;
                // 位置真的动了，才算"这一轮卡死过去了"，把连续计数归零。
                // 这里是卡死计数**唯一**该归零的地方 ——
                // 放在 onPrepared 里会让「能 prepare 但立刻卡死」的流永远清空计数，
                // 熔断失效、无限重连（PlaybackPolicy 里有详细说明）。
                stallCount = 0;
                // 「已在片尾」同理作废：位置离开了片尾（用户拖回去了、或者
                // 重连之后从头开始），下一轮就该按正常判据走。
                reachedEndOfStream = false;
                return;
            }

            // 时长已知 → 点播流，用严格阈值；时长未知或为 0 → 直播/分段流，放宽
            int duration = cachedDurationMs;
            // ---- 片尾 ≠ 卡死 ----
            //
            // 这两件事在位置上长得一模一样（都是不再前进），唯一区别是**停在哪里**。
            // 厂商栈对一部分流不送 onCompletion，位置会一直停在片尾 ——
            // 没有这一条，看门狗等满 20 秒就把它判成"卡死"、重连、从 0 再放一遍，
            // 而这个循环永远不会停（每一轮都从片尾重新开始）。
            // 真机证据：B站 1080P，位置冻在 204900ms / 时长 205000ms，20 秒后重连。
            if (PlaybackPolicy.isAtEndOfStream(pos, duration)) {
                if (!reachedEndOfStream) {
                    reachedEndOfStream = true;
                    Log.i(TAG, "位置已到片尾（位置 " + pos + "ms / 时长 " + duration
                            + "ms），厂商没送播完回调 —— 按播完处理，不重连");
                    // 走与真 onCompletion 同一条处置路径：续播接棒 / 假 EOS 甄别 /
                    // STOPPED 上报。这里不自己拼一套 —— 见 handleCompletion 的注释。
                    handleCompletion(player);
                }
                return;
            }
            // 「起播了，但一直没出声」：位置从起播到现在恒为 0。
            // 与「卡死」是两件事（位置前进过 vs 从来没动过），阈值也该是两档 ——
            // 详见 PlaybackPolicy.isNotStarted。真机：秋殇 mp3 起播后位置冻在 0ms
            // 整整 25 秒才被通用阈值捞到，用户听到的就是「切到下一首了但没播放」。
            boolean notStarted = PlaybackPolicy.isNotStarted(prepared, userPaused, pos,
                    duration, now - playStartedAtMs);
            if (!notStarted && !PlaybackPolicy.isStalled(now - lastProgressAt, duration)) {
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

            if (notStarted) {
                // 位置恒 0 与「播着播着停住」的处置一样（重建），但日志必须分开 ——
                // 报「位置停在 0ms」会让排障的人以为位置读到过、又退回 0。
                Log.w(TAG, "起播后位置一直停在 0ms（时长 " + duration + "ms，起播至今 "
                        + (now - playStartedAtMs) + "ms，已超宽限 "
                        + PlaybackPolicy.NOT_STARTED_GRACE_MS + "ms），第 " + stallCount + " 次重连");
            } else {
                Log.w(TAG, "检测到卡死（位置停在 " + pos + "ms，时长 " + duration
                        + "ms），第 " + stallCount + " 次重连");
            }
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
        // native 已挂起时不碰实例：pause() 会排在挂起的那次 native 调用后面，
        // 而 Pause 往往是用户在卡死时的第一反应 —— 不能让它也堵住。
        // 挂起态由看门狗重建收尾（重建出来的实例本来就是停的）。
        if (player != null && !playerReleased && !nativePlayerDead && !isProbeWedged()) {
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
                currentUrl != null, buildPending);
        if (action == PlaybackPolicy.PLAY_START) {
            // native 已挂起时不碰实例（同 pause）：start() 会排在挂起的那次 native
            // 调用后面。挂起态下"取消暂停"没有意义 —— 那个播放器已经僵住了，
            // 等看门狗重建出来的新实例照常起播（重建路径不经过这里）。
            if (isProbeWedged()) {
                Log.w(TAG, "native 已挂起，resume 不下发（等看门狗重建）");
                return;
            }
            try {
                player.start();
                lastProgressAt = System.currentTimeMillis();
                // 取消暂停等于重新起播：起播时刻也要跟着走，否则
                // 「暂停很久后取消暂停」会被当成"起播后一直没出声"而误重建。
                playStartedAtMs = lastProgressAt;
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

    /** prepare 卡死的重建计数（新片源清零；到上限如实报 ERROR） */
    private int prepareStuckRebuilds;

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
        // 「假 EOS 重建补发」同理：Stop 之后不该再补发任何 seek
        // （它会扛过一次重建，所以必须在这里显式作废）。
        seekAfterRebuildMs = -1L;
        // 补发计数一并作废（语义同上）。
        seekReplayCount = 0;
        // 起播时刻同理作废：停止之后不该再有"本次起播"可言。
        playStartedAtMs = 0L;
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
        //
        // native 已挂起时跳过这一句：setSurface() 也是 native 调用，会排在挂起的
        // 那次 seek 后面一起堵死 —— Stop 是用户在卡死时最想按的键，不能让它跟着堵。
        // 那种情况下画面本来已经僵住，交给后面的释放（走后台线程）一并收尾。
        if (player != null && !isProbeWedged()) {
            try {
                player.setSurface(null);
            } catch (Exception e) {
                Log.w(TAG, "解绑 Surface 失败", e);
            }
        }
        releasePlayer(null);
        notifyState("STOPPED");
    }

    /**
     * 跳到指定位置（毫秒）。
     *
     * <p><b>刻意不是 {@code synchronized}</b>，状态更新收在一个短临界区里，
     * native 的 {@code seekTo()} 放在锁**外**调用。
     *
     * <p>为什么：真机 ANR 取证（{@code /data/anr/traces.txt}，pid 1733）里，
     * 厂商栈（海信 MTK）的 {@code MediaPlayer.seekTo()} 挂住不返回，而原实现是
     * {@code synchronized} 且**持锁**调 native —— 于是这个实例锁被一个不返回的调用
     * 长期占着：7 个以上的 upnp-conn 线程排在它后面等，界面上的「下一集」
     * 「停止」「换片源」全部无响应（用户报的正是这个）。
     *
     * <p>锁只保护**状态**（待决 seek、水位线、补发计数），不保护 native 调用 ——
     * 一次挂起的 seek 从此只拖住它自己那条线程。
     */
    public void seekTo(int ms) {
        if (ms < 0) {
            return;
        }
        MediaPlayer mp;
        synchronized (this) {
            // 无论能不能立刻下发，都先记下来 —— 这个目标值有三重作用，
            // 见字段 pendingSeekMs 的注释。核心是：控制点拖了进度条之后，
            // 它下次轮询必须看到"已经到那了"，否则就是不同步。
            pendingSeekMs = ms;
            pendingSeekAtMs = System.currentTimeMillis();
            pendingSeekLanded = false;
            // 控制点新拖的一次 seek = 用户的新意图：补发计数清零，重新给一次机会。
            // （上一次"厂商做不到"的结论只针对上一次那个目标，不该连累这一次。）
            seekReplayCount = 0;
            // 水位线对齐到目标：不改的话，往回拖之后旧的高水位线会让外推
            // 以为位置还停在旧处，甚至把进度直接推到片尾（钳到 dur 的副作用）。
            hiRaw = ms;
            hiWall = System.currentTimeMillis();
            mp = (player != null && prepared && !playerReleased && !nativePlayerDead)
                    ? player : null;
        }
        if (mp != null) {
            try {
                mp.seekTo(ms);
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

    /**
     * 本次播放**观察到过的最远位置**；从未采到过为 -1。
     *
     * <p>只服务「假 EOS」判据（见 {@link PlaybackPolicy#isSpuriousCompletion}）：
     * 厂商层会把「释放旧播放器资源 / 切换输入源」规范化成一次 EOS
     * （真机日志原文 {@code This is adt event,the Normal event type is EOS!!!}），
     * 框架据此回调 {@code onCompletion} —— 而那一刻位置离总时长还差得远。
     * 靠这条水位线把「真播完」和「假 EOS」分开。
     *
     * <p><b>为什么不复用 {@link #hiRaw}</b>：hiRaw 与 {@link #hiWall} 配套，
     * 服务的是「位置冻结外推」—— 让看门狗单向更新它会让外推量级失准。
     *
     * <p>采样点有两处：{@link #getPosition()}（控制点轮询时）与
     * {@link #checkStall()}（看门狗每 5 秒一拍）。两路都采，是因为只靠前者
     * 在「控制点不轮询」时会采不到位置，判据就失灵了。
     *
     * <p>{@code volatile}：写发生在 HTTP 连接线程与主线程，
     * 读发生在 onCompletion 回调（主线程）。
     */
    private volatile int maxPlayedMs = -1;

    /**
     * 「假 EOS 重建」要替控制点保住的 seek 目标（毫秒；-1 = 无）。
     *
     * <p><b>为什么必须有</b>：厂商栈把 seek 期间「释放旧资源 / 换输入源」也规范化成
     * 一次 EOS（真机实测：Seek 下发后 113ms 就到）。这次假 EOS 走重建，而
     * {@link #releasePlayer()} 会顺手清掉 {@link #pendingSeekMs} —— 用户刚拖的那一下
     * 就这么没了：真机上第一首拖进度条"完全没反应"，正是因为一次假 EOS 把它吃掉了。
     *
     * <p><b>为什么不直接不清 pendingSeekMs</b>：那是给「Stop / 换片源」用的语义，
     * 放开会让控制点看到上一部片子的进度。这个字段的语义窄得多 —— 只活一次重建，
     * 所以能扛过 releasePlayer，却必须在真正换片源（{@link #play}）与 Stop 时清掉。
     */
    private volatile long seekAfterRebuildMs = -1L;

    /**
     * 本次 seek 已经"重建后补发"过几次。
     *
     * <p>厂商栈对个别文件会<b>直接拒绝</b> seek（真机：秋殇 mp3 → {@code Failed}、
     * MTK {@code ret -6}），随后照例来一次假 EOS。若无上限，重建 → 补发 → 又失败
     * → 又假 EOS → 又重建 …… 就是死循环（真机连续 51 轮、每轮约 1.5 秒）。
     * 见 {@link PlaybackPolicy#MAX_SEEK_REPLAY}。
     *
     * <p>控制点每下发一次新的 {@link #seekTo(int)} 就清零 —— 那是用户的新意图，
     * 值得重新给一次机会。
     */
    private volatile int seekReplayCount;

    public int getPosition() {
        try {
            if (player == null || playerReleased || nativePlayerDead || !prepared) {
                // 未就绪（含重连中）→ 报**最后已知位置**，而不是 0。
                // 报 0 会让控制点的进度条直接跳回开头，而重连往往只要几百毫秒。
                // nativePlayerDead：错误态/已释放的播放器碰一下就触发 -38
                // → onError → ERROR 刷屏死循环（网易云切歌真机踩过）。
                return lastKnownPosition;
            }
            // native 读**只**发生在探针线程（见字段区的说明），这里读缓存。
            // 缓存最多比 native 落后一拍（250ms）—— 进度条是秒级的，无感；
            // 换回来的是「主线程再也不会被一次挂起的 seek 冻死」。
            int raw = cachedPositionMs;
            lastKnownPosition = raw;
            long now = System.currentTimeMillis();
            long pending = pendingSeekMs;
            // **seek 在飞（已下发、尚未落地）期间，这一拍读到的 raw 一律不进两条
            // 「只抬不落」的账本。** raw 不能丢（落地判定要用它），但它在 seek 刚
            // 下发的这几十毫秒里不可信：这台厂商播放器会报出 **≈(时长-1 秒)** 的位置
            // （真机：Seek 200000ms 后 167ms 来 EOS，已观察最远位置=263000ms/264000ms）。
            boolean seekInFlight = pending >= 0 && !pendingSeekLanded;
            // 「假 EOS」判据的水位线：只抬不落，记本次播放见过的最远位置。
            // 采进那个假读数，紧随其后的假 EOS 就"离片尾只差 1 秒"→ 判成真播完
            // → 报 STOPPED —— 真机第一首拖完直接停就是这么来的。
            if (!seekInFlight && raw > maxPlayedMs) {
                maxPlayedMs = raw;
            }
            // 位置水位线：会话内见过的最大位置 + 见到它的墙钟。
            // 正常播放时位置每拍都在前进、水位线每拍都在抬，冻结局不会触发；
            // 它只在「位置真的停摆」的平台上兜底（见下面的外推分支）。
            //
            // 同样不采 seek 在飞期间的 raw：往回拖时 player 还停在 seek 之前的
            // 旧位置，而旧位置**比 seekTo 设下的目标大** —— 采进去就把 seekTo 刚
            // 对齐好的水位线当场顶掉（网易云 15 次/秒的轮询几乎必然命中）。等 seek
            // 落地、位置低于水位线，外推分支便拿**拖之前的旧位置**当基准，2 秒
            // （{@link PlaybackPolicy#POSITION_FREEZE_EXTRAPOLATE_MS}）后把手机进度条
            // 拉回原处 —— 真机表现就是「拖了没生效，卡两秒又跳回原位」。
            if (!seekInFlight && raw > hiRaw) {
                hiRaw = raw;
                hiWall = now;
            }
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
                // 时长同样读缓存：这里原来是直调播放器实例的 getDuration()，
                // 而这条外推路径会在控制点轮询时被走到 —— 一旦 native 挂起，
                // 就是又一次「HTTP 线程堵在 native 上」。缓存值由 onPrepared 垫底、
                // 探针线程刷新，语义等价。
                int dur = cachedDurationMs;
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

    /**
     * 是否真的在播（暂停 / 未就绪 / 释放中都不算）—— 位置外推的前提。
     *
     * <p>读的是探针线程写下的缓存，不碰 native：这条判据会被控制点轮询
     * （HTTP 线程）与界面刷新（主线程）走到，直调 {@code isPlaying()}
     * 就是给 native 挂起留了两个新的堵点。
     */
    private boolean isActivelyPlaying() {
        if (userPaused || player == null || playerReleased || nativePlayerDead || !prepared) {
            return false;
        }
        return cachedPlaying;
    }

    /**
     * 当前时长（毫秒）。<b>读缓存，不碰 native。</b>
     *
     * <p>三个调用方全都不该被 native 拖住：界面每 0.5 秒判一次
     * 「有没有内容」（{@code MainActivity.isPlaying()}）、控制点回读
     * {@code GetMediaInfo}/{@code GetPositionInfo}、以及服务层 {@code onSeek}
     * 的越界保护。原实现三处都直调 native —— 主线程那一处正是 ANR 里被冻死的地方。
     *
     * <p>缓存由 onPrepared 垫底（那时 native 一定没挂），之后探针线程每 250ms 刷新。
     */
    public int getDuration() {
        if (player == null || playerReleased || nativePlayerDead || !prepared) {
            // 同 getPosition：错误态/已释放的播放器碰一下就是 -38 循环
            return 0;
        }
        return cachedDurationMs;
    }

    public void setVolume(float volume) {
        // 先记下来再下发：重连会重建 MediaPlayer 实例，不记的话音量会丢。
        this.volume = Math.max(0f, Math.min(1f, volume));
        applyVolume();
        // 框架流推送（§7.6 第③层）：在普通 Android 设备上，实例音量是**相对系统流音量的
        // 乘数**，系统流很低时控制点怎么调都听不出来 —— 所以显式设音量时把 STREAM_MUSIC 一起推到位。
        // ⚠️ 本机三通路实测（2026-10-04）：这台海信盒子厂商音频绕过框架混音器，
        // STREAM_MUSIC 不参与实际响度（见 applySystemVolume 注释与 todo §7.24）——
        // 保留推送是为跨设备正确性，本机真实音量靠上面 applyVolume() 那条实例下发。
        //
        // 刻意**只在这里**做、不放进 applyVolume()：applyVolume() 还会在重建
        // 播放器实例时被重放（见它的注释），而重放不该去动框架流 ——
        // 用户刚在盒子上按过音量，不该因为一次断流重连就被拽回控制点的值。
        applySystemVolume();
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
     * prepare 是否还挂着（prepareAsync 已发出、onPrepared/onError 未到）。
     * 服务层的「视频准备中」判据用它 —— 界面不自己猜播放进度。
     */
    public boolean isPreparing() {
        return preparing;
    }

    /**
     * 把「音量 + 静音」这两个状态一起下发给播放器。
     *
     * <p>只留这一个出口：任何一处单独调 {@code player.setVolume()} 都会漏掉静音，
     * 于是出现「设了静音、改一下音量就又有声音了」这类只在特定顺序下复现的 bug。
     */
    private void applyVolume() {
        if (player == null || playerReleased || nativePlayerDead) {
            // 批 0 取证：「音量调不动」的判据 ② —— 若这里提前 return，
            // 说明音量只是被记下来了、根本没下发到播放器。把三个闸门各自的值
            // 一起打出来，就能区分是哪一道挡住的（player 为 null / 已 release /
            // native 已死），不用再猜。
            Log.i(TAG, "取证 音量未下发（提前 return）：player=" + (player != null)
                    + " released=" + playerReleased + " dead=" + nativePlayerDead
                    + " 目标=" + Math.round(volume * 100f));
            return;
        }
        try {
            float v = muted ? 0f : volume;
            player.setVolume(v, v);
            // 批 0 取证：确确实实下发到了播放器实例。配合上面那条，
            // 就能把「控制点说了 / 我们记了 / 播放器收到了」三层分开看。
            Log.i(TAG, "取证 音量已下发: " + v + "（muted=" + muted + "）");
        } catch (Exception e) {
            Log.w(TAG, "应用音量失败", e);
        }
    }

    /**
     * 把当前音量推到框架 {@code STREAM_MUSIC} 流上。
     *
     * <p><b>⚠️ 本机真机实测订正（2026-10-04）—— 这台电视上它【不参与实际响度】</b>：
     * 三通路取证确立（详见 todo.md §7.24）——① 遥控器音量走海信固件/硬件通路，
     * 完全不进 Android（按音量减×3，框架五条流纹丝不动）；② 真正管耳朵的是
     * {@code player.setVolume}（厂商 CmpbPlayer 把实例值转发进真实响度通路）；
     * ③ 本方法推的 STREAM_MUSIC 框架流，虽然 {@code service call} 能精确读回，
     * 但播放中 {@code media.audio_flinger} 显示 active tracks 空、Output thread
     * {@code total writes: 0} 而视频仍真实出声 —— 厂商音频绕过了框架混音器，
     * STREAM_MUSIC 在这台机上是**空转的装饰流**。
     *
     * <p><b>那为什么还留着这段</b>：跨设备正确性。换一台不绕过混音器的普通
     * Android 设备，实例音量确实是「相对系统流音量的乘数」，系统流为 0 时
     * 拉满实例也没声 —— 那时这一推是有意义的兜底。本机不生效不等于代码错，
     * 只是这台平台的事实。日志措辞按此订正：不再声称「系统音量已下发」。
     *
     * <p><b>与静音的分工不变</b>：静音仍只走播放器实例（{@code applyVolume()} 里的
     * {@code v = 0}）。不把系统流压到 0，是因为那会把整台盒子的声音一起静掉
     * （别的应用也跟着哑），而且取消静音还得记住原值再还原。
     *
     * <p><b>GetVolume 仍回读 {@link #volume}</b>（控制点设过的那个值），不是系统流
     * 档位换算回来的数 —— 否则用户在盒子上按一下遥控器，控制点那边的音量条就会自己跳。
     */
    private void applySystemVolume() {
        int streamMax = streamMaxVolume();
        int target = PlaybackPolicy.systemStreamVolumeFor(Math.round(volume * 100f), streamMax);
        if (target < 0) {
            // -1 = 拿不到流上限（个别 ROM 返回 0 / 负数）。什么都别做，但要说出来：
            // 「这次没动框架流」和「动了但本机不参与混音」在真机上长得一样，只有日志能分开。
            Log.i(TAG, "取证 框架流未推送（拿不到 STREAM_MUSIC 上限=" + streamMax + "）");
            return;
        }
        AudioManager am = audioManager();
        if (am == null) {
            return;
        }
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            // 措辞订正（2026-10-04）：这里推的是框架 STREAM_MUSIC 流，本机真机实测
            // 【不参与实际响度】（厂商音频绕过混音器）。原来写「系统音量已下发」
            // 会让人误以为动了电视真实音量 —— 用户当场指出「你没改系统的音量」正是
            // 被这句误导。如实写成「框架流已推送」，并注明本机不生效、仅为跨设备兼容。
            Log.i(TAG, "取证 框架流已推送: STREAM_MUSIC=" + target + "/" + streamMax
                    + "（本机不参与实际响度，真实音量见上一条 音量已下发）");
        } catch (Throwable t) {
            // 个别 ROM 会要求 MODIFY_AUDIO_SETTINGS。本项目**刻意不声明**它
            // （权限面最小，见 AndroidManifest 的注释）；真机若在这里打出
            // SecurityException，再回来补那一条权限 —— 那时就有实测依据了。
            Log.w(TAG, "推送框架流音量失败（若为 SecurityException，说明本机要求 "
                    + "MODIFY_AUDIO_SETTINGS）", t);
        }
    }

    /** 懒取 AudioManager；拿不到返回 null（不抛）。 */
    private AudioManager audioManager() {
        if (audioManager == null && context != null) {
            try {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            } catch (Throwable t) {
                Log.w(TAG, "取 AudioManager 失败", t);
            }
        }
        return audioManager;
    }

    /** {@code STREAM_MUSIC} 的最大档位；读不到返回 -1（由映射函数兜住，表示"别动"）。 */
    private int streamMaxVolume() {
        AudioManager am = audioManager();
        if (am == null) {
            return -1;
        }
        try {
            return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            Log.w(TAG, "读 STREAM_MUSIC 上限失败", t);
            return -1;
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
     * 换片源时作废「按地址记的宽高账」—— 除 {@code url} 之外三个字段全清。
     *
     * <p>新片源在探完之前若还拿着上一部的宽高去摆软件信箱，黑边方向直接错
     * （横屏视频被摆成竖屏）。探测结果是**按地址**成立的，地址一变就必须全清，
     * 等新地址探完再落账。
     *
     * <p><b>为什么抽成方法而不是内联在 {@link #play} 里</b>：清账这条路有**两个**
     * 入口 —— SOAP 换片走 {@code play()}，播放列表续播（onCompletion 接棒）直接
     * {@code currentUrl = u; startInternal();}，**不经过 play()**。当初就漏了后者：
     * 视频→视频续播时上一部的宽高还挂在新片上（QA 复审②）。两个入口调同一个方法，
     * 判据只有一份，不会再漂移。
     *
     * <p>判据用「不等于才清」：同一个地址重发 Set（拖拽进度条那类幂等路径）时
     * {@code aspectUrl} 仍是它，清了会把已探到的宽高白白丢掉、再探一次。
     *
     * <p><b>必须和落账用同一把锁</b>（{@code MediaPlayerController.this}）：
     * 清账与「校验 + 写入」是两对跨线程的读写，不进同一临界区就没有
     * happens-before —— 旧宽高可能盖到新片源上。见 {@link #maybeProbeAspect}
     * 纪律第 3 条。{@code play()} 本身是 synchronized，这里是可重入的。
     */
    private synchronized void clearAspectUnless(String url) {
        if (!url.equals(aspectUrl)) {
            videoAspect = null;
            // 超限标志与宽高同生共死：换片源一起作废。只清宽高的话，上一条
            // 超限流的标志会挂在新片源上 —— 提示条对着一条合规的流喊超限。
            aspectDpbExceeds = false;
            aspectUrl = null;
            aspectProbing = null;
        }
    }

    /**
     * 当前片源的 DPB 预检是否超限（真机表现＝黑屏有声）；A3 提示条据此亮文案。
     *
     * <p>{@code false} 有两种含义：「判过、合规」与「没判出来（HLS / 非 H.264 /
     * 解不出）」—— 对提示条而言处置相同（不亮），所以不必区分。<b>解不出一律
     * 不许伪装成超限</b>（null 红线的延伸：宁可不提示，也不指错方向）。
     */
    public boolean isAspectDpbExceeds() {
        return aspectDpbExceeds;
    }

    /**
     * 当前片源的真实视频宽高（软件信箱用）；{@code null} = 还没探到 / 探不到 / 不是直连 MP4。
     *
     * <p>界面据此按比例摆 SurfaceView —— 判据的权威在这里（探测与按地址记账都在
     * 控制器内），界面不自己猜，与 {@code isAudioOnly()} / {@code isVideoPending()}
     * 同一条纪律。
     */
    public int[] getVideoAspect() {
        return videoAspect;
    }

    /**
     * 异步探测当前片源的真实宽高（软件信箱用）。
     *
     * <p>纪律：
     * <ol>
     *   <li><b>按地址记账</b>：{@link #aspectUrl}（已探完）或 {@link #aspectProbing}
     *       （探测中）等于这个地址就早退 —— 同一个地址不重复探、不并发探。</li>
     *   <li><b>只对非 m3u8 有意义</b>：这里**不**重复挡 m3u8，交给
     *       {@link VideoAspectProbe#probe} 自己挡 —— 红线只留一处，两处判据迟早漂移。</li>
     *   <li><b>结果切回主线程再落账，且「校验 + 写入」整体持锁</b>：posted 的
     *       Runnable 跑在主线程，而清账的 {@code play()} 跑在 SOAP 线程 ——
     *       两边都是「先校验地址、再写字段」的两步操作，只靠 volatile **没有
     *       happens-before**：清账可能恰好插在校验与写入之间，旧片源的宽高
     *       就盖到了新片源头上（黑边方向反）。必须与清账用**同一把锁**
     *       （{@code MediaPlayerController.this}）把两步捆成一个临界区 ——
     *       换锁、或只锁其中一边，这个竞态就回来了。</li>
     *   <li><b>失败静默</b>：探到 {@code null} 也照样记 {@link #aspectUrl} ——
     *       表示"这个地址探过了、没有"，免得每个 tick 反复探。</li>
     * </ol>
     */
    private void maybeProbeAspect(final String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        // 探完了 / 正在探 —— 同一个地址都不再发第二次。
        if (url.equals(aspectUrl) || url.equals(aspectProbing)) {
            return;
        }
        aspectProbing = url;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final VideoAspectProbe.Result r = VideoAspectProbe.probeResult(url);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 「校验 + 写入」整体进临界区 —— 与 play()（synchronized）、
                        // clearAspectUnless（synchronized）同一把锁。
                        // volatile 只保证单字段读写的可见性，挡不住
                        // 「校验通过 → 清账插进来 → 写入」这种复合操作被切开。
                        synchronized (MediaPlayerController.this) {
                            // 排队期间可能已换片 / 停止：只有当前地址仍是它才落账。
                            if (!url.equals(currentUrl)) {
                                // 这次探测已过期。若占位仍是它，摘掉，免得这个地址
                                // 以后再被当成"正在探"而永远不探（换片会清账，但
                                // 停止→重投同一个地址这条路上要靠这里兜住）。
                                if (url.equals(aspectProbing)) {
                                    aspectProbing = null;
                                }
                                return;
                            }
                            videoAspect = r.size;
                            // 超限标志与宽高**同一临界区、同一次探测**落账 ——
                            // 拆成两次写的话，清账能插在中间，提示条拿着旧流的
                            // 超限标志配新流的宽高（或反过来）。
                            aspectDpbExceeds = r.dpbExceeds;
                            aspectUrl = url;
                            aspectProbing = null;
                        }
                    }
                });
            }
        }, "aspect-probe");
        t.setDaemon(true);
        t.start();
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

    /**
     * 生命周期结束时的整体释放（服务销毁）。
     *
     * <p>加 {@code synchronized} 是为了与 {@link #startInternal()} 同锁 ——
     * 它是 {@code releasePlayer()} 三个调用方里**唯一不持锁**的那个
     * （另两个 {@code play()} / {@code stop()} 本身就是 synchronized）。
     * 不加的话，一次「销毁」与一次「正在建实例」并发，照样能把 player 置空。
     */
    public synchronized void release() {
        handler.removeCallbacksAndMessages(null);
        pendingRetry = null;
        releasePlayer(null);
    }

    /**
     * 释放当前实例并清掉一切与它绑定的状态。
     *
     * <p><b>native 的 {@code release()} 永远交给后台线程</b>，调用方立刻返回 ——
     * 这条纪律没有例外，理由见方法体里那段注释。
     *
     * <p><b>没有实例时本方法必须是真的空操作</b>（§7.31）：既不动 native，
     * 也不清任何状态。下面那串清理只属于「真的释放掉了一个实例」这条路径 ——
     * 否则「释放完成回调重入 startInternal」这条最常走的路会把控制点在窗口里
     * 刚暂存的 seek 抹掉。方法体里 null 早退排在清理**之前**，就是为了这个。
     *
     * @param onReleased 释放完成后回主线程执行的动作（调用方靠它接着往下走）；
     *                   {@code null} = 不需要后续动作（停止 / 退出路径）。
     * @return {@code true} = 本来就没有实例可释放，调用方可以直接继续
     *         （<b>且什么都没被改动</b>）；
     *         {@code false} = 释放已交给后台线程，等 {@code onReleased} 回来再继续。
     */
    private boolean releasePlayer(Runnable onReleased) {
        final MediaPlayer doomed;
        // ---- 状态清理 + 摘引用整体进实例锁 ----
        // 必须与 startInternal()（synchronized）互斥：后者**持锁**跑完「建实例 →
        // 配置实例」的全过程；这里若能在它中间把 player 置空，就会在
        // new MediaPlayer() 与 setScreenOnWhilePlaying() 之间抛 NPE。
        // 真机取证：`setDataSource 完成: 距释放 -14ms` —— 负 14 毫秒说明
        // setDataSource 期间另有一次释放把 lastReleaseAtMs 推到了"未来"，
        // 紧接着 657 行的 setScreenOnWhilePlaying(true) 就 NPE 了。
        //
        // 临界区里**不碰 native**（release 仍一律交给后台线程，见下），
        // 所以「native 挂起占住实例锁」那条老风险不会从这里回来。
        synchronized (this) {
            // ---- 「没有实例可释放」必须**真的什么都不做**（§7.31）----
            //
            // 这个早退原来排在下面那串状态清理**之后**，于是「没实例」这条路径
            // 照样会清状态。而它恰恰是最常被走到的一条：startInternal() 等后台
            // 释放时先 return（见该方法里 `if (!releasePlayer(...)) return;`），
            // 释放完成的回调再进来一次 —— 第二次 player 已经是 null。
            //
            // 控制点在这段窗口（实测 39–55ms）里发来的 Seek 会被暂存进
            // pendingSeekMs，随即被这里的清理静默抹掉：日志有「seek 请求早于
            // prepare，已暂存」、**没有**「prepare 完成，补发暂存的 seek」，
            // 用户看到的是「拖了进度条，电视从头发」。
            //
            // 语义上本来也不该清：下面每一项都是**与实例绑定**的状态
            // （外推锚点、假 EOS 水位线、采样缓存、探针），实例都没有了，
            // 谈不上"与它绑定的状态"。真正的释放路径（下面 player != null 那段）
            // 一个字节都没少。
            if (player == null) {
                // 本来就没有实例：没什么可释放的，调用方直接往下走。
                return true;
            }
            prepared = false;
            // 释放后 native 播放器不可再碰（任何方法调用都会触发 -38 错误回调）
            playerReleased = true;
            // 外推锚点随实例一起作废 —— 不清的话，新片源开头的正常位置
            // 会被当成「冻结」而误触发外推。
            hiRaw = -1;
            hiWall = 0;
            // 「假 EOS」水位线随实例一起作废：新实例是另一次播放（重连 / 换片 / 续播），
            // 不清的话上一次播放的远位置会让新播放的假 EOS 判据直接失灵。
            maxPlayedMs = -1;
            // 实例都没了，"准备中"和"待决的 seek"也就失去了载体。
            // 不清的话，下一次播放的「当前位置」会一直报上一次拖到的那个位置 ——
            // 控制点看到的进度条是上一部片子的。
            preparing = false;
            pendingSeekMs = -1L;
            // 采样缓存与探针随实例一起作废 —— 不清的话，新片源起播前界面会先显示
            // 上一部的位置/时长（那正是"面板闪一下"的来源）。
            cachedPositionMs = 0;
            cachedDurationMs = 0;
            cachedPlaying = false;
            cachedSampleAtMs = 0;
            stopSampler();
            probeInFlightOwner = null;
            probeInFlightSinceMs = 0;
            // 视频尺寸复查同理：载体（MediaPlayer 实例）没了就必须摘掉，
            // 否则过期复查会对着已释放的实例跑 —— 判定套到新片源上。
            cancelVideoRecheck();
            doomed = player;
            // 先摘引用：后台释放期间任何访问器都不该再看到这个实例
            player = null;
        }
        // ---- native 的 release() 一律交给后台线程，**没有例外** ----
        //
        // 这条纪律是 2026-10-04 用真机 ANR 转储换来的，与 seekTo() 是同一个模子：
        //
        //   "upnp-conn" tid=15 NATIVE
        //     at android.media.MediaPlayer._release(Native Method)
        //     at b.a.B0(...)   ← 就是这里
        //   "main" tid=1 MONITOR
        //     - waiting to lock <...> held by tid=15 (upnp-conn)
        //
        // 厂商栈（海信 MTK / CmpbPlayer）对「DPB 溢出、硬件已放弃视频解码」那路流，
        // release() 会**永久挂起**（实测 pid 4424 / 1718：到 ANR、到进程被杀都没返回）。
        // 而 releasePlayer() 是被 play()（synchronized）/ stop() / release() 调的 ——
        // 同步释放等于让**调用方持着控制器实例锁**去等一个不返回的 native 调用：
        // ① 后续所有控制指令（Play / SetAVTransportURI / SetVolume）排在那把锁后面，
        //    控制点看到「投上了但一直加载中」；
        // ② 主线程一按键（onKeyDown 要同一把锁）立刻 ANR；
        // ③ 系统最终杀进程。三条都在真机上实测到了。
        //
        // 原来只有「**探针**已挂起」（isProbeWedged()，看的是采样超时）才走后台 ——
        // 那条判据覆盖不到「release 自己挂起」，因为 release 不经过探针。
        // 现在无条件走后台：release 从此只可能拖住它自己那条线程。
        //
        // 顺序没有变：新实例仍然只在旧实例释放完**之后**才创建（由 onReleased 驱动），
        // 变的只是"谁在等"。
        //
        // 视频蓝屏修复 F1：这里**不再** player.reset()。
        //
        // 真机 A/B 日志（`.agent/video-bluescreen-plan.md` §1）定案：厂商栈
        // （海信 CmpbPlayer / MTK）的 reset 是**异步**的（reset_nosync），
        // 旧实例这次 teardown 会落到紧接着 new MediaPlayer() 的 prepareAsync
        // 头上 —— mReseted 标志跨实例共享，于是新实例的 prepare 被判成
        // "already reset" 成空操作（无回调），直到看门狗重建才出画面：
        // 表现就是「投视频先蓝屏 30 秒」。
        //
        // reset() 的唯一语义是「复位实例以便复用」，而这里 player 随即置
        // null、下一轮永远 new 一个 —— 它既多余又是竞态源头。直接 release()
        // 是官方推荐的释放方式。取证日志量「teardown → prepare」的实际间隔。
        final long teardownAt = System.currentTimeMillis();
        releaseStartedAtMs = teardownAt;
        releaseStuckReported = false;
        // 有回调 = 释放完成后要接着建实例 —— 把这段空窗期标出来（见 buildPending）。
        // 没有回调（Stop / 退出）就没有后续建实例，不该挡住别的路径。
        buildPending = (onReleased != null);
        Log.i(TAG, "释放播放器: release 开始（后台线程，reset 已移除）");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doomed.release();
                } catch (Exception e) {
                    Log.w(TAG, "后台释放 MediaPlayer 出错", e);
                }
                long doneAt = System.currentTimeMillis();
                lastReleaseAtMs = doneAt;
                // 先清「在飞」标记再回主线程：判据读的就是它。
                releaseStartedAtMs = 0L;
                Log.i(TAG, "释放播放器: release 结束，耗时 " + (doneAt - teardownAt) + "ms");
                if (onReleased != null) {
                    handler.post(onReleased);
                }
            }
        }, "juping-release");
        t.setDaemon(true);
        t.start();
        // 挂一个「挂死判定」：到点还没回来就如实上报一次（见 releaseStuckWatchdog）。
        // 排在这里而不是后台线程里 —— 后台线程正是那个可能永远不往下走的地方。
        handler.removeCallbacks(releaseStuckWatchdog);
        handler.postDelayed(releaseStuckWatchdog, PlaybackPolicy.RELEASE_STUCK_MS);
        return false;
    }

    private void notifyState(String state) {
        if (listener != null) {
            listener.onStateChanged(state);
        }
    }
}
