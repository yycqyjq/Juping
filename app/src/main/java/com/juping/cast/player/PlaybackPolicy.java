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

    /** 点播流（时长已知）的卡死判定阈值 */
    public static final long STALL_THRESHOLD_VOD_MS = 20000L;

    /** 直播流 / 时长未知流的卡死判定阈值 */
    public static final long STALL_THRESHOLD_LIVE_MS = 60000L;

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
}
