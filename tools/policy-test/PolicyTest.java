import com.juping.cast.player.PlaybackPolicy;

/**
 * 播放重连策略的一致性测试。
 *
 * <p>{@link PlaybackPolicy} 是纯逻辑、不依赖任何 Android 类，所以可以直接在
 * 桌面 JVM 上编译运行 —— 不必像协议层测试那样还要补 Log 替身。
 *
 * <p>这套测试的重点不是"参数是不是这几个数"，而是**边界**和**组合行为**：
 * <ul>
 *   <li>阈值判定在等号上怎么算（差一毫秒是天壤之别）</li>
 *   <li>熔断到底在第几次触发</li>
 *   <li>以及最关键的一条：把「卡死 → 重连 → 又卡死」这个循环**跑一遍**，
 *       证明它真的会停下来。真机上这个循环要几分钟才走一轮，
 *       而它是否终止，决定了用户体验是"自己恢复了"还是"一直闪断"。</li>
 * </ul>
 */
public class PolicyTest {

    private static int passed = 0;
    private static int total = 0;
    private static final StringBuilder FAILURES = new StringBuilder();

    static void check(String name, boolean ok, String detail) {
        total++;
        if (ok) {
            passed++;
        } else {
            FAILURES.append("  · ").append(name);
            if (detail != null && detail.length() > 0) {
                FAILURES.append("\n      ").append(detail);
            }
            FAILURES.append('\n');
        }
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name
                + (detail != null && detail.length() > 0 ? "\n         " + detail : ""));
    }

    public static void main(String[] args) {
        System.out.println("\n── 1. 点播 / 直播的判定与阈值 ──");

        check("时长已知 → 点播", !PlaybackPolicy.isLiveStream(7200000), "duration=7200000");
        check("时长为 0 → 直播", PlaybackPolicy.isLiveStream(0), "duration=0");
        check("时长为负 → 直播", PlaybackPolicy.isLiveStream(-1), "duration=-1");
        check("点播阈值 = 20s",
                PlaybackPolicy.stallThresholdMs(7200000) == 20000L,
                "得到 " + PlaybackPolicy.stallThresholdMs(7200000));
        check("直播阈值 = 60s",
                PlaybackPolicy.stallThresholdMs(0) == 60000L,
                "得到 " + PlaybackPolicy.stallThresholdMs(0));

        System.out.println("\n── 2. 卡死判定的边界（等号是分水岭）──");

        check("点播：19.999s 不动 → 还没卡死",
                !PlaybackPolicy.isStalled(19999L, 7200000), "elapsed=19999");
        check("点播：恰好 20s 不动 → 还没卡死（判定用严格大于）",
                !PlaybackPolicy.isStalled(20000L, 7200000), "elapsed=20000");
        check("点播：20.001s 不动 → 判定卡死",
                PlaybackPolicy.isStalled(20001L, 7200000), "elapsed=20001");

        check("直播：21s 不动 → 不判卡死（这是两档阈值的意义）",
                !PlaybackPolicy.isStalled(21000L, 0), "elapsed=21000，直播阈值 60s");
        check("直播：61s 不动 → 判定卡死",
                PlaybackPolicy.isStalled(61000L, 0), "elapsed=61000");

        check("判定延迟上限：点播 25s（阈值 20s + 轮询 5s）",
                PlaybackPolicy.stallDetectionUpperBoundMs(7200000) == 25000L,
                "得到 " + PlaybackPolicy.stallDetectionUpperBoundMs(7200000));

        System.out.println("\n── 3. 指数退避表 ──");

        long[] expected = {1000L, 2000L, 4000L, 8000L, 16000L};
        for (int i = 0; i < expected.length; i++) {
            check("第 " + (i + 1) + " 次重连延迟 " + expected[i] + "ms",
                    PlaybackPolicy.retryDelayMs(i) == expected[i],
                    "得到 " + PlaybackPolicy.retryDelayMs(i));
        }
        check("第 6 次重连 → 拒绝（-1，即已放弃）",
                PlaybackPolicy.retryDelayMs(5) == -1L,
                "得到 " + PlaybackPolicy.retryDelayMs(5));
        check("负数次数 → 拒绝（防御性）",
                PlaybackPolicy.retryDelayMs(-1) == -1L,
                "得到 " + PlaybackPolicy.retryDelayMs(-1));

        System.out.println("\n── 4. 卡死熔断的边界 ──");

        check("连续卡死 1 次 → 继续重连",
                !PlaybackPolicy.shouldStopRetryingStalls(1), "count=1");
        check("连续卡死 2 次 → 继续重连",
                !PlaybackPolicy.shouldStopRetryingStalls(2), "count=2");
        check("连续卡死 3 次 → 继续重连（刚好到上限）",
                !PlaybackPolicy.shouldStopRetryingStalls(3), "count=3");
        check("连续卡死 4 次 → 停手",
                PlaybackPolicy.shouldStopRetryingStalls(4), "count=4");

        System.out.println("\n── 5. 循环模拟：「能 prepare 但位置永远不动」的流 ──");
        System.out.println("   这条流是真实存在的：URL 合法、能拉到元数据，");
        System.out.println("   但 CDN 限速 / 分段缺失 / 码率超出老芯片能力，画面就是不动。");

        // 修好之后的实现：卡死计数只在「播放有实际进展」时归零
        int fixedReconnects = simulateStallLoop(false, 5000);
        check("修好之后：会在有限次重连后停手（不会无限重连）",
                fixedReconnects >= 0,
                "重连了 " + (fixedReconnects < 0 ? "∞（永不终止！）" : fixedReconnects + " 次后停手"));
        check("停手前恰好重连 " + PlaybackPolicy.MAX_STALL_RETRY + " 次",
                fixedReconnects == PlaybackPolicy.MAX_STALL_RETRY,
                "得到 " + fixedReconnects);

        // 旧实现：在 onPrepared 里也把 stallCount 清零
        int buggyReconnects = simulateStallLoop(true, 5000);
        check("【反向验证】旧写法（onPrepared 里清零）→ 确认它无限重连",
                buggyReconnects < 0,
                "旧写法在 5000 轮内没有停手 —— 证明这个测试能区分对错");

        System.out.println("\n── 6. 有进展就要归零（别把偶发抖动累计成熔断）──");

        // 卡死 2 次 → 恢复（位置前进）→ 再卡死 2 次 → 不应熔断
        int c = 0;
        c++;  // 卡死 1
        c++;  // 卡死 2
        c = 0;  // 位置前进 → 归零
        c++;  // 卡死 1
        c++;  // 卡死 2
        check("卡死2次→恢复→再卡死2次：不应熔断",
                !PlaybackPolicy.shouldStopRetryingStalls(c),
                "累计卡死 " + c + " 次，但中间恢复过，按「连续」定义不该停手");

        System.out.println("\n── 7. Play 到达时的处置决策（「重投失败」的正解）──");
        System.out.println("   控制点是把 SetAVTransportURI 和 Play 连着发的，间隔几十毫秒，");
        System.out.println("   而 prepareAsync 要几百毫秒以上 —— Play 到达时几乎必然还在准备中。");

        check("已就绪 → 直接 start",
                PlaybackPolicy.playAction(true, false, true) == PlaybackPolicy.PLAY_START,
                "prepared=true");
        check("【关键】准备中 → 什么都不做（绝不重建播放器）",
                PlaybackPolicy.playAction(false, true, true) == PlaybackPolicy.PLAY_WAIT,
                "旧实现这里会 startInternal() → releasePlayer()，把正在 prepare 的"
                + "实例掐掉重建 —— 两条指令互相拆台，表现为「有时候投不上去」");
        check("还没开始、但已有地址 → 该重新 prepare",
                PlaybackPolicy.playAction(false, false, true) == PlaybackPolicy.PLAY_PREPARE,
                "prepared=false, preparing=false, hasUrl=true");
        check("没地址也没在准备 → 什么都不做",
                PlaybackPolicy.playAction(false, false, false) == PlaybackPolicy.PLAY_NONE,
                "prepared=false, preparing=false, hasUrl=false");

        check("prepared 与 preparing 同时为真时，按「已就绪」处理",
                PlaybackPolicy.playAction(true, true, true) == PlaybackPolicy.PLAY_START,
                "理论上不该同时为真；真出现了也绝不能去重建");
        check("已就绪时，有没有地址都不重建",
                PlaybackPolicy.playAction(true, false, false) == PlaybackPolicy.PLAY_START,
                "地址可能刚被 stop() 清掉，但播放器还在 —— 直接 start 即可");
        check("准备中时，没有地址也不重建",
                PlaybackPolicy.playAction(false, true, false) == PlaybackPolicy.PLAY_WAIT,
                "准备中的实例不能动，这是唯一的处置");
        check("preparing 优先于「重新 prepare」",
                PlaybackPolicy.playAction(false, true, true) == PlaybackPolicy.PLAY_WAIT,
                "这一格就是重投失败的核心：有地址 + 准备中 ≠ 该重建");

        System.out.println("\n── 8. Seek「待决」的收敛判定（「拖拽不同步」的正解）──");

        check("真实位置 = 目标 → 已落地",
                PlaybackPolicy.isSeekSettled(630500, 630500L), "raw=630500 target=630500");
        check("差 1999ms → 容差内，算落地",
                PlaybackPolicy.isSeekSettled(630500 - 1999, 630500L), "差 1999ms");
        check("差 2000ms → 恰好边界，算落地",
                PlaybackPolicy.isSeekSettled(630500 - 2000, 630500L), "差 2000ms");
        check("差 2001ms → 超出容差，仍在待决",
                !PlaybackPolicy.isSeekSettled(630500 - 2001, 630500L), "差 2001ms");
        check("冲过目标但仍在容差内 → 算落地",
                PlaybackPolicy.isSeekSettled(630500 + 1500, 630500L), "差 +1500ms");
        check("位置还停在旧值 → 待决中（这正是要报乐观值的时刻）",
                !PlaybackPolicy.isSeekSettled(120000, 630500L),
                "seek 刚落地的瞬间就是这个状态：位置读到的还是旧的。"
                + "此时若直接报 raw，手机上的进度条会被拉回去");

        check("待决 14999ms → 还没超时",
                !PlaybackPolicy.isSeekExpired(14999L), "elapsed=14999");
        check("待决 15000ms → 恰好边界，还没超时（判定用严格大于）",
                !PlaybackPolicy.isSeekExpired(15000L), "elapsed=15000");
        check("待决 15001ms → 超时，交回真实位置",
                PlaybackPolicy.isSeekExpired(15001L),
                "超时后不能继续报乐观值 —— 那是谎报军情");

        System.out.println("\n── 9. 反向验证：旧逻辑在「准备中」时确实会重建 ──");
        System.out.println("   下面这组用旧实现的等价函数做对照。两条结论相反，");
        System.out.println("   才说明上面的断言真的能区分新旧 —— 否则它可能是个恒真断言。");

        check("【反向验证】旧逻辑（只看有没有地址）在「准备中 + 有地址」时会重建",
                oldResumeWouldRebuild(false, true),
                "旧代码：if (prepared) start(); else if (currentUrl != null) startInternal();");
        check("【反向验证】新逻辑在同样输入下不重建（两者结论相反）",
                PlaybackPolicy.playAction(false, true, true) != PlaybackPolicy.PLAY_PREPARE,
                "新逻辑给的是 PLAY_WAIT。新旧不同 → 这条断言能区分对错，不是恒真");
        check("【反向验证】旧逻辑在「没准备 + 有地址」时也重建（这一格两者一致）",
                oldResumeWouldRebuild(false, true)
                        && PlaybackPolicy.playAction(false, false, true) == PlaybackPolicy.PLAY_PREPARE,
                "真的什么都没开始时，重建才是对的 —— 修的是「准备中」那一格，别误伤这一格");

        System.out.println();
        System.out.println("=".repeat(62));
        System.out.println("播放策略：" + passed + " / " + total + " 通过");
        if (passed < total) {
            System.out.println("\n失败项：");
            System.out.print(FAILURES);
        }
        System.out.println("=".repeat(62));
        System.exit(passed == total ? 0 : 1);
    }

    /**
     * 旧实现（修之前）的 resume 决策，**仅用于反向验证**。
     *
     * <p>旧代码是：
     * <pre>if (player != null &amp;&amp; prepared) { player.start(); }
     * else if (currentUrl != null) { startInternal(); }</pre>
     * 它只看「有没有地址」，完全不知道「正在准备」这回事 ——
     * 于是 Play 一到就把正在 prepare 的实例 release 掉重建。
     *
     * <p>留着它是为了让「新逻辑不重建」那条断言**能红**：
     * 只有旧逻辑确实会重建、新旧结论确实相反，断言才有证伪力。
     *
     * @return true 表示旧逻辑会重建播放器
     */
    static boolean oldResumeWouldRebuild(boolean prepared, boolean hasUrl) {
        return !prepared && hasUrl;
    }

    /**
     * 模拟一条「能 prepare 但位置永远不动」的流。
     *
     * <p>时间按 {@link PlaybackPolicy#WATCHDOG_INTERVAL_MS} 一跳，位置恒为 0。
     * 每次判定卡死就重连一次，并模拟 {@code onPrepared} 的行为。
     *
     * @param resetStallOnPrepared true = 旧写法（在 onPrepared 里把卡死计数清零）
     * @return 停手前重连了多少次；<b>-1 表示在 maxRounds 轮内从未停手（无限重连）</b>
     */
    static int simulateStallLoop(boolean resetStallOnPrepared, int maxRounds) {
        int stallCount = 0;
        int reconnectCount = 0;
        long lastProgressAt = 0L;
        long now = 0L;
        int durationMs = 7200000;   // 点播：阈值 20s

        for (int round = 0; round < maxRounds; round++) {
            now += PlaybackPolicy.WATCHDOG_INTERVAL_MS;
            // 位置始终不变 —— 这就是"卡死"的定义
            if (!PlaybackPolicy.isStalled(now - lastProgressAt, durationMs)) {
                continue;
            }
            stallCount++;
            if (PlaybackPolicy.shouldStopRetryingStalls(stallCount)) {
                return reconnectCount;          // 正常终止
            }
            reconnectCount++;
            if (resetStallOnPrepared) {
                stallCount = 0;                 // 旧实现：prepare 成功就清零
            }
            lastProgressAt = now;               // 重连后重新计时
        }
        return -1;                              // 从未停手
    }
}
