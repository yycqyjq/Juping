package com.juping.cast.player;

/**
 * 播放重连策略 —— 纯决策逻辑，不依赖任何 Android 类。
 *
 * <h3>为什么要单独拆出来</h3>
 * 「不断联」是这个项目对用户的核心承诺，而它的实现全在几个数字和判断上：
 * 退避多久、卡死多久算卡死、什么时候该放弃。
 * 这些逻辑埋在 {@link MediaPlayerController} 里就没法验证 ——
 * 那个类要 {@code android.media.MediaPlayer} 和 {@code android.view.Surface}，
 * 在桌面上跑不起来。
 *
 * <p>拆出来之后它是一段纯粹的 Java：可以脱离 Android 编译、直接跑断言，
 * 甚至能把「反复卡死」这种要几分钟才在真机上出现一轮的场景，在毫秒内模拟完。
 *
 * <h3>这里定下的两条纪律</h3>
 * <ol>
 *   <li><b>卡死计数只在「播放有实际进展」时归零</b>，不在 prepare 成功时归零。
 *       否则「能 prepare 但立刻卡死」的流（URL 合法、CDN 限速、分段 404）
 *       每一轮都会把计数清零，熔断永远不触发 —— 变成无限重连，
 *       而重连本身又会把刚有起色的播放打断。详见
 *       {@link #shouldStopRetryingStalls(int)}。</li>
 *   <li><b>点播和直播用不同阈值</b>。直播流的 {@code getCurrentPosition()}
 *       可能长时间不增长甚至恒为 0，用点播阈值会把正常播放误判成卡死。</li>
 * </ol>
 */
public final class PlaybackPolicy {

    /** 看门狗轮询间隔 */
    public static final long WATCHDOG_INTERVAL_MS = 5000L;

    /**
     * 视频尺寸的延迟复查间隔。
     *
     * <p>为什么需要：部分老芯片（如 MTK 5880）在 {@code onPrepared} 回调时
     * {@code getVideoWidth()} 仍返回 0 —— 视频尺寸要等首帧解码才就绪。
     * 只在 onPrepared 判一次的话，视频流会被误判成纯音频，
     * 电视上对着视频弹「音乐投屏」卡片。
     *
     * <p>节奏：先快后慢 —— 第一次 {@link #VIDEO_RECHECK_FIRST_MS}（首帧通常
     * 几十到几百毫秒内就绪），仍没有则等 {@link #VIDEO_RECHECK_SECOND_MS}
     * 再试一次，到 {@link #VIDEO_RECHECK_MAX_ATTEMPTS} 次就认命
     * （那多半真是纯音频流，判成音乐卡片本来就是对的）。
     */
    public static final long VIDEO_RECHECK_FIRST_MS = 500L;
    public static final long VIDEO_RECHECK_SECOND_MS = 2000L;
    public static final int VIDEO_RECHECK_MAX_ATTEMPTS = 2;

    /**
     * 厂商 info 事件码：「这段视频的**画面**编码本机解不了」。
     *
     * <p>真机实证（海信 MTK 4.0.4，2026-10-01）：手机投一段 HEVC 录像，电视上
     * **一片纯黑、声音正常、进度照走**，厂商侧日志是
     * <pre>
     *   IMTK_PB_CTRL_EVENT_PLAYBACK_ERROR, u4Data = 2
     *   send event: MTK_MEDIA_INFO_VID_CODEC_NOT_SUPPORT
     * </pre>
     * 画面流**根本没被打开** —— 对照同一台盒子上的 H.264 文件，日志里有
     * {@code open stream type ST_VIDEO} / {@code set video codec(VID_ENC_H264)}
     * / {@code SVCTX_NTFY_CODE_VIDEO_FMT_UPDATE}，而 HEVC 那份只有
     * {@code open stream type ST_AUDIO}，从头到尾没有 VS_EOS。
     *
     * <p>厂商把这件事当 **info 事件**发，不中断播放：应用收不到
     * {@code onError}，照样报「含视频画面」，用户在电视前完全无从判断
     * 「是坏了、还是编码不支持」。这个事件经框架转发的形态就是
     * {@code MediaPlayer.OnInfoListener.onInfo(what=0x8003, extra=0)}。
     *
     * <p><b>为什么不能改用 {@code getVideoWidth()}</b>：本轮真机对照实测（同一台
     * 盒子，各跑 2~3 遍）——
     * <pre>
     *                        HEVC（画面解不了）   H.264（画面正常）
     *   getVideoWidth()@2s        0x0                  0x0
     *   getVideoWidth()@5s        0x0                  0x0
     *   onVideoSizeChanged        从不                  从不
     *   info what=0x8003          每次都发              从不
     * </pre>
     * 也就是说 {@code getVideoWidth()} 在这台盒子上**恒为 0**（画面走厂商硬件
     * 图层，框架压根不知道尺寸），拿它当判据会把正常视频一起冤枉成"编码不支持"
     * —— 这个误报是实测抓到的，不是推测。{@code what=0x8003} 是唯一能区分两者的信号。
     *
     * <p>Mango TV 之类第三方应用看不到这个码；它是 MTK 私有区间
     * （{@code >= 0x8000}，标准 {@code MEDIA_INFO_*} 最大只到 802）。
     */
    public static final int INFO_VIDEO_CODEC_NOT_SUPPORT = 0x8003;

    /**
     * 这个 info 事件码是不是「画面编码解不了」。
     *
     * <p>放进纯逻辑层是为了能脱离 Android 跑断言 —— 判据在 {@code PlaybackPolicy}
     * 里可离线验证，接线上报在服务层，与项目里错误分类的做法一致。
     */
    public static boolean isVideoCodecUnsupportedInfo(int what) {
        return what == INFO_VIDEO_CODEC_NOT_SUPPORT;
    }

    /**
     * 位置冻结判定阈值：播放中原始位置连续这么久不变，就认为媒体时钟停摆
     * （海信 MTK 4.0.4 实测：Seek 后 getCurrentPosition() 永远停在 Seek 点，
     * 画面却在继续播），改用「最后位置 + 墙钟流逝」外推上报。
     *
     * <p>2 秒起步是留缓冲：正常播放位置每次轮询都在变，只有真停摆才会
     * 连续 2 秒纹丝不动；太短的话一次偶发的解码停顿就会被误判成时钟死掉。
     */
    public static final long POSITION_FREEZE_EXTRAPOLATE_MS = 2000L;

    /**
     * 本地预取代理的环形缓冲容量。
     *
     * <p>8MB 的依据：0.6GB 的盒子 + largeHeap，8MB 是安全线；
     * 对 2Mbps 的常见投屏码率相当于 32 秒的内容余量 —— Seek 落在这个
     * 窗口内立即出画面，落在窗口外才需要重新对源取数。
     */
    public static final int PROXY_BUFFER_BYTES = 8 * 1024 * 1024;

    /**
     * 本地预取代理开关（当前默认关）。
     *
     * <p>桌面字节一致性测试 11/11 通过，但在海信 CmpbPlayer（自研播放器栈）
     * 真机上：代理连接后立即被对方断开（EPIPE），prepare 卡死；超时降级直连
     * 后 CmpbPlayer 的直连也出现异常挂起 —— 厂商栈是黑盒，两个症状都还没
     * 定位到根因。在根因清楚之前默认关：**先保住已验证可靠的直连路径**，
     * 机制和测试全部保留，打开开关即可继续真机迭代。
     */
    public static final boolean PROXY_ENABLED = false;

    /**
     * 代理路径 prepare 的超时降级阈值。
     *
     * <p>厂商自研播放器栈对 127.0.0.1 代理的行为不可控（海信 CmpbPlayer
     * 实测：连接后立即断开，prepare 永远不完成）。代理是为标准 MediaPlayer
     * 准备的增强，不能让不兼容的厂商栈把投屏永远卡在 TRANSITIONING ——
     * 超时就记下这个地址降级直连。
     */
    public static final long PREPARE_PROXY_TIMEOUT_MS = 10000L;

    /**
     * Auto-Stop 的离开判定阈值（借鉴 gmrender-resurrect 的 --auto-stop）。
     *
     * <p>120 秒的依据：事件订阅的授予超时最长 300s（EventDispatcher 上限），
     * 控制点续订周期可能长达数百秒 —— 阈值必须大于订阅周期才不会误停；
     * 同时也不能太长（否则「手机退出电视还在播」要等好几分钟才停）。
     * 120 秒 + 30 秒检查节拍 = 最迟约 2.5 分钟停止。
     */
    public static final long AUTO_STOP_AFTER_MS = 120000L;

    /**
     * prepare 卡死的重建阈值：prepareAsync 发出后这么久还没回调
     * （onPrepared/onError 都没有），判定媒体服务卡死，重建播放器自救。
     *
     * <p>海信 CmpbPlayer 实测：连续切歌后媒体服务可能卡死 —— 手机 Set+Play
     * 照发，电视端 prepare 永不完成（手机卡在加载、Stop 也没反应）。
     * 重建播放器会拿到全新的 CmpbPlayer 实例，多数情况能自救。
     *
     * <p><b>10 秒（原 30 秒）</b>：真机 A/B 日志确认「换片必卡满 30 秒再出画面」的
     * 根因是旧实例 teardown 与新实例 prepare 的异步竞态（修复见
     * `.agent/video-bluescreen-plan.md`，releasePlayer 已不再 reset()）。这个阈值
     * 降级为纯安全网：万一仍中招，用户最多等 10 秒而不是 30 秒。
     * 10 秒 = 正常 prepare（1-2 秒）的 5-10 倍余量；不建议再往下调 ——
     * 窗口太窄会把大 HLS 首片 / 慢 CDN 的真慢 prepare 误杀成卡死。
     */
    public static final long PREPARE_STUCK_REBUILD_MS = 10000L;

    /**
     * 换片时「旧实例 teardown → 新实例 prepare」之间的沉降等待（F3 备用，
     * <b>当前未接线</b>）。真机实测厂商 reset_nosync 落在 ≈470ms，取 500ms 留余量。
     * 是否启用取决于真机验收：F1 去掉 reset() 后若 `already reset` 仍出现，
     * 再把它接进 startInternal（换片时 postDelayed，冷投零延迟）。
     */
    public static final long SWITCH_SETTLE_MS = 500L;

    /** prepare 卡死后最多重建几次；用完如实报 ERROR（提示重启电视） */
    public static final int PREPARE_STUCK_MAX_REBUILDS = 2;

    /** 点播流（时长已知）的卡死判定阈值 */
    public static final long STALL_THRESHOLD_VOD_MS = 20000L;

    /** 直播流 / 时长未知流的卡死判定阈值 */
    public static final long STALL_THRESHOLD_LIVE_MS = 60000L;

    /**
     * 「起播了，但一直没出声」的宽限期。
     *
     * <p>与 {@link #STALL_THRESHOLD_VOD_MS} 的区别是<b>两件不同的事</b>：
     * 那个管「播着播着停了」（位置前进过、又不动了），这个管「压根没起来」
     * （位置从始至终是 0）。后者不需要那么长的观察窗口 —— 位置从来没动过，
     * 就不存在「本来在播、被我误杀」的可能。
     *
     * <p>真机证据（秋殇，mp3）：上一首播完，控制点 0.6 秒后送来下一首，
     * prepare 成功（时长 267000ms 已知）、状态报 PLAYING，但位置从
     * 18:06:00.108 到 18:06:25.122 一直冻在 0ms —— 全程静音。通用阈值
     * 20s + 看门狗 5s 才把它捞到，用户感受就是「切到下一首了但没播放」。
     */
    public static final long NOT_STARTED_GRACE_MS = 8000L;

    /** 错误重连的最大次数 */
    public static final int MAX_RETRY = 5;

    /** 连续卡死重连的上限 */
    public static final int MAX_STALL_RETRY = 3;

    private PlaybackPolicy() {
    }

    /**
     * 是不是直播 / 分段流。
     *
     * <p>判据是「时长已知与否」：HLS 直播的 duration 可能为 0 或随时间变化，
     * 而点播文件的 duration 在 prepare 完成时就是确定的。
     */
    public static boolean isLiveStream(int durationMs) {
        return durationMs <= 0;
    }

    /** 该用哪个卡死阈值 */
    public static long stallThresholdMs(int durationMs) {
        return isLiveStream(durationMs) ? STALL_THRESHOLD_LIVE_MS : STALL_THRESHOLD_VOD_MS;
    }

    /**
     * 第 {@code retryCount} 次重连（从 0 计）该等多久：1s → 2s → 4s → 8s → 16s。
     *
     * @return 等待毫秒数；<b>-1 表示次数已用尽，应当放弃</b>
     */
    public static long retryDelayMs(int retryCount) {
        if (retryCount < 0 || retryCount >= MAX_RETRY) {
            return -1L;
        }
        return 1000L << retryCount;
    }

    /**
     * 播放位置多久没动算卡死。
     *
     * <p>调用方需要自己先确认「位置确实和上次一样」—— 这个函数只看时间。
     */
    public static boolean isStalled(long elapsedSinceProgressMs, int durationMs) {
        return elapsedSinceProgressMs > stallThresholdMs(durationMs);
    }

    /**
     * 「起播了，但一直没出声」—— 该立刻重建播放器，而不是等通用卡死阈值。
     *
     * <p>厂商栈偶发地出现「prepare 成功、状态报 PLAYING，音频却根本没推出去」：
     * 位置从起播那一刻起恒为 0，一帧都不走。这不是"卡了一下"，是"没开始"。
     *
     * <p>三个必须同时成立的条件，各有理由：
     * <ul>
     *   <li>{@code prepared && !userPaused}：还没就绪时位置本来就是 0；用户主动
     *       暂停时位置也不动 —— 这两种都不是故障。</li>
     *   <li>{@code positionMs <= 0}：位置动过（哪怕只有 1ms）就说明音频通道是通的，
     *       那是"卡死"而不是"没起来"，交给 {@link #isStalled} 用长阈值判。</li>
     *   <li>{@code !isLiveStream}：直播的位置<strong>本来就可能长时间是 0</strong>
     *       （还没拿到首个时间戳）。把直播算进来的话，正常直播会被反复重建。</li>
     * </ul>
     */
    public static boolean isNotStarted(boolean prepared, boolean userPaused,
                                       long positionMs, int durationMs,
                                       long elapsedSincePlayMs) {
        if (!prepared || userPaused) {
            return false;
        }
        if (positionMs > 0) {
            return false;
        }
        if (isLiveStream(durationMs)) {
            return false;
        }
        return elapsedSincePlayMs >= NOT_STARTED_GRACE_MS;
    }

    /**
     * 连续卡死到这个程度就该停手了。
     *
     * <p><b>为什么必须有一个上限</b>：卡死和网络瞬断不一样，重连不一定能救回来。
     * 如果流本身有问题（CDN 限速、分段缺失、码率超出老芯片能力），
     * 就会陷入「卡死 → 重连 → 能 prepare → 立刻又卡死」的循环。
     * 每轮重连都会打断一次刚恢复的播放，用户体验比直接报错更差。
     *
     * <p><b>所以计数只能在「播放有实际进展」时归零</b>。
     * 如果在 prepare 成功时归零，上面那个循环就永远不会触顶 ——
     * 这正是本项目踩过的坑：代码注释写着「避免无限重连」，
     * 而实现是无限重连。
     */
    public static boolean shouldStopRetryingStalls(int consecutiveStalls) {
        return consecutiveStalls > MAX_STALL_RETRY;
    }

    /**
     * 看门狗判定卡死需要等多久才可能触发。
     *
     * <p>轮询间隔会给判定引入最多一个间隔的延迟 —— 实际触发时间是
     * {@code 阈值} 到 {@code 阈值 + 轮询间隔} 之间。返回上限供测试与文档用。
     */
    public static long stallDetectionUpperBoundMs(int durationMs) {
        return stallThresholdMs(durationMs) + WATCHDOG_INTERVAL_MS;
    }

    // ------------------------------------------------------- Play 指令该怎么落地

    /** Play 到达时的处置方式，见 {@link #playAction}。 */
    public static final int PLAY_NONE = 0;
    public static final int PLAY_START = 1;
    public static final int PLAY_PREPARE = 2;
    public static final int PLAY_WAIT = 3;

    /**
     * 收到 Play 时该怎么做。
     *
     * <p><b>为什么需要这个决策，而不是"有 URL 就重启"</b>：
     * DLNA 控制点（腾讯视频 / B站 这类）投屏时是把
     * {@code SetAVTransportURI} 和 {@code Play} <b>连着发</b>的，间隔只有几十毫秒，
     * 而 {@code prepareAsync()} 是异步的、要几百毫秒到几秒。
     *
     * <p>于是 Play 到达时十有八九还在准备中。这时候如果按「有 URL 就
     * {@code startInternal()}」处理，就会把<b>正在准备的那个 MediaPlayer 释放掉重建</b> ——
     * 两条指令互相拆台，谁先谁后全看 prepare 的快慢。
     * 用户看到的就是「有时候投得上、有时候投不上」。
     *
     * <p>而 {@code onPrepared} 回调里本来就会 {@code start()}，
     * 所以「准备中」的正确处置是<b>什么都不做</b>，让这一次准备自己走完。
     *
     * @param prepared 是否已经就绪
     * @param preparing 是否正在 prepare（prepareAsync 已发、回调未到）
     * @param hasUrl 当前是否有片源地址
     * @return {@link #PLAY_START} / {@link #PLAY_PREPARE} / {@link #PLAY_WAIT} / {@link #PLAY_NONE}
     */
    public static int playAction(boolean prepared, boolean preparing, boolean hasUrl) {
        if (prepared) {
            return PLAY_START;
        }
        if (preparing) {
            return PLAY_WAIT;
        }
        return hasUrl ? PLAY_PREPARE : PLAY_NONE;
    }

    // ------------------------------------------- SetAVTransportURI 该不该重建

    /**
     * 这次 {@code SetAVTransportURI} 要不要重建播放器。
     *
     * <p><b>为什么需要这个决策，而不是"收到地址就重建"</b>：
     * 控制点（腾讯视频这类）**拖拽进度条时**会重发 {@code SetAVTransportURI}，
     * 传的还是<b>同一个 URL</b>，后面再跟一条 Seek。无条件「释放 + 重建」的话，
     * 每次拖拽都会：
     * <ol>
     *   <li>释放 MediaPlayer → 视频层关闭 → 电视上闪一下蓝屏</li>
     *   <li>重新 {@code prepareAsync()} → 重新缓冲、位置归零 ——
     *       用户看到的就是"断开重连"</li>
     * </ol>
     *
     * <p>UPnP AVTransport:1 规范也站在这一边：传入与 {@code CurrentURI}
     * <b>相同</b>的地址时，设备不应改变传输状态。
     *
     * <p><b>判据必须同时看地址和播放器状态</b>：只比 URL 的话，一个出过错、
     * 已经被释放掉的播放器会永远重建不起来 —— 重发同地址就再也救不回来了。
     *
     * @param newUrl     这次要播的地址
     * @param currentUrl 当前正在播 / 正在准备的地址
     * @param prepared   播放器是否已就绪
     * @param preparing  播放器是否正在准备
     * @return true 表示需要重建播放器
     */
    public static boolean shouldRebuild(String newUrl, String currentUrl,
                                        boolean prepared, boolean preparing) {
        if (newUrl == null || newUrl.length() == 0) {
            return false;   // 空地址什么都不做
        }
        if (newUrl.equals(currentUrl) && (prepared || preparing)) {
            return false;   // 同地址、播放器还在 —— 幂等忽略
        }
        return true;
    }

    // ------------------------------------------------------- Seek 的"待决"判定

    /**
     * seek 落地的判定容差。
     *
     * <p>{@code MediaPlayer.seekTo()} 是**异步**的：调用返回时位置还没变，
     * 而老芯片上 seek 到未缓冲的位置要几百毫秒到几秒。这期间
     * {@code getCurrentPosition()} 返回的还是<b>旧位置</b> ——
     * 手机轮询 {@code GetPositionInfo} 拿到旧值，进度条会被拉回去。
     *
     * <p>所以这段时间对外报「目标位置」。什么时候算落地？真实位置追到目标附近即可。
     * 留 2 秒容差：seek 的落点本来就有精度误差，控制点按百分比算目标也有取整误差。
     */
    public static final long SEEK_SETTLE_TOLERANCE_MS = 2000L;

    /**
     * seek 待决的最长时限。
     *
     * <p>兜底用：万一这次 seek 永远落不了地（流本身有问题、目标点不可达），
     * 不能一直对外报乐观值 —— 那是**谎报军情**。超时后老老实实报真实位置，
     * 让控制点和用户看到"它确实没动"。
     */
    public static final long SEEK_PENDING_TIMEOUT_MS = 15000L;

    /** 真实位置是否已经追到目标附近 —— 到了就说明这次 seek 落地了 */
    public static boolean isSeekSettled(int rawPositionMs, long targetMs) {
        // 转成 long 再减：int 溢出会让差值翻号，settled 判定跟着反掉
        return Math.abs((long) rawPositionMs - targetMs) <= SEEK_SETTLE_TOLERANCE_MS;
    }

    /** seek 待决是否已超时（超时就放弃乐观值，报真实的） */
    public static boolean isSeekExpired(long elapsedSinceSeekMs) {
        return elapsedSinceSeekMs > SEEK_PENDING_TIMEOUT_MS;
    }

    // ------------------------------------------------- native 采样与「挂起」探测

    /**
     * native 探针的采样间隔（毫秒）。
     *
     * <h3>为什么 native 只能由一条探针线程读</h3>
     *
     * <p>厂商栈（海信 MTK）的 {@code MediaPlayer.seekTo()} 会<b>挂住不返回</b>，
     * 而它挂住时握着播放器的 native 串行锁 —— 同一个实例上的
     * {@code getCurrentPosition()} / {@code getDuration()} 会跟着一起堵死。
     * 真机 ANR 取证（{@code /data/anr/traces.txt}，pid 1733）里，主线程正是堵在
     * {@code getDuration()} 上被冻住：界面卡死，所有控制指令（Seek / Stop /
     * SetAVTransportURI）一起无响应，7 个以上的 upnp-conn 线程排在同一个
     * 实例锁后面。
     *
     * <p>所以 native 读只留**一条探针线程**这一个入口，结果放进缓存；
     * 界面、看门狗、控制点回读一律读缓存 —— 主线程从此不碰 native，
     * 挂起的那次 seek 最多拖住探针，拖不住 UI。
     *
     * <p>250ms 一拍：进度条是秒级的，够用；0.6GB 的盒子上这点开销可以忽略。
     */
    public static final long POSITION_SAMPLE_INTERVAL_MS = 250L;

    /**
     * 探针在 native 里待超过这么久 = 这次 native 调用已经挂起。
     *
     * <p>正常平台上 {@code getCurrentPosition()} 是微秒级的，3000ms 只有
     * 「已经挂了」才可能达到。判出来之后**不再对旧实例做任何 native 调用**
     * （{@code release()} 也算）—— 那些调用会排在挂起的那次后面一起堵死，
     * 主线程一旦碰就又是一次 ANR。
     */
    public static final long NATIVE_PROBE_STUCK_MS = 3000L;

    /** 采样是否已经过期（探针被 native 卡住、缓存不再刷新）。 */
    public static boolean isSampleStale(long sinceLastSampleMs) {
        return sinceLastSampleMs > NATIVE_PROBE_STUCK_MS;
    }

    /**
     * seek 看门狗：这次 seek 该不该「重建播放器再补发一次」。
     *
     * <p>三种「不该」各自对应一个真机踩过的故障，缺一不可：
     * <ul>
     *   <li><b>不在飞</b> —— 没有待决 seek，重建是纯粹的打扰；</li>
     *   <li><b>没超时</b> —— 老芯片上一次 seek 要好几秒，提前重建会把播放拉回开头
     *       （用户看到的是「拖了一下，电视跳回去了」）；</li>
     *   <li><b>补发预算用尽</b> —— 厂商直接拒绝这个 seek 时，重建会变成死循环
     *       （真机连续 51 轮，见 {@link #MAX_SEEK_REPLAY}）。</li>
     * </ul>
     *
     * <p>「重建」不是这里做的：这里只回答该不该，动作在
     * {@code MediaPlayerController.checkStall()} 里走既有的 {@code scheduleRetry()}。
     */
    public static boolean shouldRebuildOnSeekTimeout(boolean seekInFlight,
                                                     long elapsedSinceSeekMs,
                                                     int replaysSoFar) {
        return seekInFlight
                && isSeekExpired(elapsedSinceSeekMs)
                && canReplaySeekAfterRebuild(replaysSoFar);
    }

    // --------------------------------------------------------- 播放错误的分类

    /**
     * 错误分类。
     *
     * <h3>为什么要分类，而不是直接把异常消息丢给用户看</h3>
     * 原来界面上显示的是 {@code "播放错误 what=1 extra=-1010"} 这种原样字符串 ——
     * 用户看不懂，也没法据此做任何决定。而这几类的**处置方式完全不同**：
     * <ul>
     *   <li>连不上 → 是网络的事，用户该去看看路由器</li>
     *   <li>服务器返回错误 → 是片源的事，换一个视频就行</li>
     *   <li>解不了 → 是这台盒子的能力边界，换格式或者换片源</li>
     *   <li>反复中断 → 已经放弃重连了，需要用户手动重投</li>
     * </ul>
     * 分类放在这里（而不是 UI 层）是因为它是**纯逻辑**：
     * {@code what}/{@code extra}/异常类型 → 分类，这段映射可以脱离 Android 跑断言。
     */
    public static final int ERR_NONE = 0;

    /** 连不上媒体服务器：网络不通、地址不可达、超时 */
    public static final int ERR_CONNECT = 1;

    /** 媒体服务器返回了错误：404 / 403 这类「地址在、内容取不到」 */
    public static final int ERR_SERVER = 2;

    /** 这台设备解不了这个格式：解码器不支持，或码流本身损坏 */
    public static final int ERR_DECODE = 3;

    /** 播放中断，正在重连（卡死看门狗判定） */
    public static final int ERR_STALLED = 4;

    /** 反复中断，已放弃重连 */
    public static final int ERR_GIVEUP = 5;

    /** 归不了类的兜底 */
    public static final int ERR_UNKNOWN = 6;

    /**
     * 把 {@code MediaPlayer.OnErrorListener} 的 {@code what}/{@code extra} 分类。
     *
     * <p><b>这里刻意用字面量而不是常量名</b>：{@code MEDIA_ERROR_UNSUPPORTED} /
     * {@code MALFORMED} / {@code IO} / {@code TIMED_OUT} 这几个常量都是
     * <b>API 17 (Android 4.2)</b> 才加进 {@code MediaPlayer} 的，
     * 而本机是 API 15 —— 引用常量名会在真机上直接 {@code NoSuchFieldError} 崩溃，
     * 而 lint / 编译期都看不出来（这正是本项目「对着 android.jar 逐条核」那道闸的用武之地）。
     * 数值取自 MediaPlayer 文档，长期稳定。
     */
    public static int classifyMediaError(int what, int extra) {
        if (extra == -1010 || extra == -1007) {
            return ERR_DECODE;      // UNSUPPORTED / MALFORMED
        }
        if (extra == -1004 || extra == -110) {
            return ERR_CONNECT;     // IO / TIMED_OUT
        }
        // what == 100 是 MEDIA_ERROR_SERVER_DIED —— 播放器所在的进程挂了，
        // 既不是网络也不是格式问题，归兜底。
        if (what == 100) {
            return ERR_UNKNOWN;
        }
        // what == 1（MEDIA_ERROR_UNKNOWN）是绝大多数情况的落点，
        // 而它的头号成因是「取不到流」（CDN 403、分段缺失、地址过期）。
        // 归到 UNKNOWN 而不是 CONNECT：宁可说「播放出错」，
        // 也不要让用户跑去重启路由器 —— 那多半没用。
        return ERR_UNKNOWN;
    }

    /**
     * 把起播时抛出的异常分类。
     *
     * <p>{@code FileNotFoundException} 要单独拎出来：{@code setDataSource()} 走 HTTP 时
     * 拿到 4xx 抛的就是它，含义是「服务器在，但这个内容取不到」，
     * 和「网络根本不通」是两件事 —— 前者用户该换个片源，后者该去看网络。
     * 顺序不能反：{@code FileNotFoundException} 是 {@code IOException} 的子类。
     */
    public static int classifyStartFailure(Throwable t) {
        if (t == null) {
            return ERR_UNKNOWN;
        }
        if (t instanceof java.io.FileNotFoundException) {
            return ERR_SERVER;
        }
        if (t instanceof java.io.IOException) {
            return ERR_CONNECT;
        }
        return ERR_UNKNOWN;
    }

    /**
     * 分类的英文名 —— 只给日志和断言用，不是给用户看的文案。
     *
     * <p>用户看到的文案在 {@code strings.xml} 里（要能本地化，而且
     * 纯逻辑层不该管界面措辞）。这里是给测试一个**稳定的判据**：
     * 断言分类结果时比对英文名，不会因为改了一句中文文案就红。
     */
    public static String errorKindName(int kind) {
        switch (kind) {
            case ERR_CONNECT: return "CONNECT";
            case ERR_SERVER:  return "SERVER";
            case ERR_DECODE:  return "DECODE";
            case ERR_STALLED: return "STALLED";
            case ERR_GIVEUP:  return "GIVEUP";
            case ERR_UNKNOWN: return "UNKNOWN";
            default:          return "NONE";
        }
    }

    // ------------------------------------------------- 「假 EOS」判据

    /**
     * 判定「播完」时，位置距离总时长还差多少毫秒就不算真播完。
     *
     * <p>10 秒的余量是给真实收尾留的：老芯片在自然播完前的最后一段
     * 位置上报会稀疏下来，而控制点给的 duration 与实际时长也常有
     * 几百毫秒到几秒的偏差（本项目实测 flower.mp4 报 00:06 / 实际 11s）。
     * 差得比 10 秒还多，就绝不可能真的是"播完了"。
     */
    public static final long SPURIOUS_EOS_MIN_REMAINING_MS = 10000L;

    /**
     * 时长小于这个值的流不做「假 EOS」判定。
     *
     * <p>短流（提示音、几十秒的短视频）的 duration 元数据质量最差，
     * 而它本来就该很快播完 —— 对它做判定，收益小、误伤大，不如不判定。
     */
    public static final int SPURIOUS_EOS_MIN_DURATION_MS = 20000;

    /**
     * 起播超过这么久，一律认定为「真播完」，不再做假 EOS 判定。
     *
     * <p><b>这是保护自然播完路径的最后一道闸</b>。判据只看「观察到的最大位置」，
     * 而位置是靠看门狗（每 5 秒一拍）和 {@code GetPositionInfo} 采样来的 ——
     * 万一采样错过了尾段（比如控制点一直没轮询、看门狗又恰好被判成 idle 跳过），
     * 最大位置就会停在中段，把一次**正常的播完**误判成假 EOS，
     * 于是"一首歌正常放完"变成 5 轮退避后报 ERROR —— 比现状更糟。
     *
     * <p>所以加一条时间闸：真的播完一个 3 分钟的视频，起播至今必然远超 60 秒；
     * 而厂商那个把"释放旧资源 / 切输入源"规范化成 EOS 的误报，
     * 全都在起播后 1 秒内发生（真机 8 次实测，最长 0.51 秒）。
     * 60 秒足以把两者彻底分开，又留了几十倍的余量。
     */
    public static final long SPURIOUS_EOS_EARLY_WINDOW_MS = 60000L;

    /**
     * 这次 {@code onCompletion} 是不是厂商层的「假 EOS」。
     *
     * <h3>为什么要判这个</h3>
     * 本机的厂商栈（海信 Vision-TV / MTK CmpbPlayer）会把
     * <b>「释放旧播放器资源」和「切换输入源」规范化成一次 EOS 事件</b>
     * （日志原文 {@code This is adt event,the Normal event type is EOS!!!}），
     * 框架据此回调 Java 层 {@code onCompletion}。真机实测 8 次，每次都在 1 秒内
     * 紧跟一个「播放状态: STOPPED」。
     *
     * <p>最典型的表现：<b>投完视频再投音频，首次 Set 直接变 STOPPED</b> ——
     * 因为切到音频要重新配置输出通道，触发了上面那次资源释放。
     * 手机侧显示"投上了"，电视侧却已经停了，用户以为投屏坏了。
     *
     * <p>正确的处置是：这明显不是"播完了"，应当重建播放器重试
     * （{@link #SPURIOUS_EOS_EARLY_WINDOW_MS} 的时间闸保证不会误伤真播完）。
     *
     * @param preparing         是否仍在 prepare（回调还没到）
     * @param elapsedSinceStartMs 起播至今的毫秒数
     * @param maxObservedPositionMs 本次播放观察到过的最远位置；从未采到传 -1
     * @param durationMs        总时长；未知传 0 或负
     * @return true 表示这是假 EOS，不该报 STOPPED，应当重连
     */
    public static boolean isSpuriousCompletion(boolean preparing, long elapsedSinceStartMs,
                                               int maxObservedPositionMs, int durationMs) {
        // ① 还没 prepare 完，谈不上「播完」。
        //
        // 这一条直接覆盖用户报的那个现象：14:47:40 那次 Set 之后，
        // PREPARING(.321) 与 STOPPED(.831) 之间**没有 PLAYING** ——
        // onPrepared 从未到达，preparing 仍为 true。
        // 定义上不可能播完，不必等任何采样。
        if (preparing) {
            return true;
        }
        // ② 时长未知或太短：判据的两个输入都不可靠，不做判定。
        if (durationMs < SPURIOUS_EOS_MIN_DURATION_MS) {
            return false;
        }
        // ③ 起播已久 —— 保护自然播完（见 SPURIOUS_EOS_EARLY_WINDOW_MS 的长注释）。
        if (elapsedSinceStartMs >= SPURIOUS_EOS_EARLY_WINDOW_MS) {
            return false;
        }
        // ④ 观察到的最大位置离总时长还差得远 → 不可能真播完。
        //
        // 从未采到位置（-1）按 0 处理：宁可把一次「采不到位置的短播放」
        // 判成假 EOS 去重连（重连至少能让它继续），也不要凭空报一个 STOPPED。
        int max = maxObservedPositionMs > 0 ? maxObservedPositionMs : 0;
        return (long) durationMs - max > SPURIOUS_EOS_MIN_REMAINING_MS;
    }

    /**
     * 「假 EOS 重建」后允许补发同一次 seek 的最大次数。
     *
     * <p><b>为什么必须有上限</b>：厂商栈对个别文件会<b>直接拒绝</b> seek ——
     * 真机（秋殇 mp3，267000ms）Seek 53000ms 时日志原文
     * {@code IMtkPb_Ctrl_TimeSeekMS() Failed !}、MTK 报 {@code ret -6}，
     * 随后照例来一次假 EOS。重建后 {@code onPrepared} 把这个 seek 原样补发，
     * 于是又失败、又来假 EOS、又重建……形成死循环（真机一轮约 1.5 秒，
     * 连续 51 轮不止；这段时间盒子既不播、又一直向控制点谎报"已到 53 秒"）。
     *
     * <p>补发过 {@link #MAX_SEEK_REPLAY} 次仍失败，就认定这个 seek 厂商做不到：
     * 丢掉它、照常从头播放、如实报真实位置；用户想重试再拖一次即可
     * （新的 {@code seekTo} 会把计数清零）。
     */
    public static final int MAX_SEEK_REPLAY = 1;

    /**
     * 重建后还能不能再补发一次 seek（超过上限就丢掉，避免无限重建）。
     *
     * @param replaysSoFar 本次 seek 已经补发过几次
     */
    public static boolean canReplaySeekAfterRebuild(int replaysSoFar) {
        return replaysSoFar < MAX_SEEK_REPLAY;
    }
}
