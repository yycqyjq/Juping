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
}
