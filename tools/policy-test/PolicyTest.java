import com.juping.cast.player.Mp4Aspect;
import com.juping.cast.player.PlaybackPolicy;

import java.util.Arrays;

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

        System.out.println("\n── 2.5 「起播了但一直没出声」的判据 ──");
        System.out.println("   与卡死是两件事：卡死是「位置前进过、又停了」，");
        System.out.println("   这个是「位置从头到尾是 0」—— 后者不该等 20 秒那道阈值。");

        check("就绪 + 位置恒 0 + 刚够宽限 8s → 判「没起播」",
                PlaybackPolicy.isNotStarted(true, false, 0, 267000, 8000L), "elapsed=8000");
        check("位置恒 0 但只过了 7.999s → 还没到，再等等",
                !PlaybackPolicy.isNotStarted(true, false, 0, 267000, 7999L), "elapsed=7999");
        check("位置动过 1ms → 不是「没起播」（那是卡死，交给 20s 阈值）",
                !PlaybackPolicy.isNotStarted(true, false, 1, 267000, 60000L), "pos=1");
        check("还没 prepare 完 → 不判（此时位置本来就是 0）",
                !PlaybackPolicy.isNotStarted(false, false, 0, 267000, 60000L), "prepared=false");
        check("用户主动暂停 → 不判（暂停时位置当然不动）",
                !PlaybackPolicy.isNotStarted(true, true, 0, 267000, 60000L), "userPaused=true");
        check("直播（时长 0）位置长时间为 0 → 不判（直播位置本来就可能恒 0）",
                !PlaybackPolicy.isNotStarted(true, false, 0, 0, 120000L), "duration=0");
        check("时长未知（-1）按直播处理 → 同样不判",
                !PlaybackPolicy.isNotStarted(true, false, 0, -1, 120000L), "duration=-1");

        System.out.println("\n── 2.6 「重建后补发 seek」的次数上限 ──");
        System.out.println("   厂商栈对个别文件直接拒绝 seek，补发过去照样失败、照样来假 EOS；");
        System.out.println("   没有上限就是「重建 → 补发 → 又失败 → 又重建」的死循环。");

        check("还没补发过 → 允许补发一次", PlaybackPolicy.canReplaySeekAfterRebuild(0), "count=0");
        check("已经补发 1 次 → 不再补发（否则死循环）",
                !PlaybackPolicy.canReplaySeekAfterRebuild(1), "count=1");
        check("已经补发 2 次 → 更不该补发",
                !PlaybackPolicy.canReplaySeekAfterRebuild(2), "count=2");
        check("上限常量为 1（补发一次仍失败就丢）", PlaybackPolicy.MAX_SEEK_REPLAY == 1,
                "MAX_SEEK_REPLAY=" + PlaybackPolicy.MAX_SEEK_REPLAY);

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

        System.out.println("\n── 10. SetAVTransportURI 的幂等判定（「拖拽蓝屏重连」的正解）──");
        System.out.println("   控制点拖进度条时会重发同一个 URL，后面再跟一条 Seek。");
        System.out.println("   无条件「释放 + 重建」的话，每次拖拽都会闪一下蓝屏、位置归零。");

        final String ua = "http://192.168.1.9:8192/media/a.mp4";
        final String ub = "http://192.168.1.9:8192/media/b.mp4";

        check("同地址 + 已就绪 → 不重建（拖拽走的就是这一格）",
                !PlaybackPolicy.shouldRebuild(ua, ua, true, false),
                "prepared=true：播放器还在，同一个地址不该动它");
        check("同地址 + 准备中 → 不重建",
                !PlaybackPolicy.shouldRebuild(ua, ua, false, true),
                "prepareAsync 已发、回调未到 —— 这时候重建会把正在准备的实例掐掉");
        check("【关键】同地址 + 播放器已释放 → 仍然重建",
                PlaybackPolicy.shouldRebuild(ua, ua, false, false),
                "出过错、已被 release 的播放器必须允许重建 —— 只比 URL 的实现"
                + "会把这一格也拦掉，于是控制点重发同地址就再也救不回来了");
        check("换地址 → 重建（换片必须切）",
                PlaybackPolicy.shouldRebuild(ub, ua, true, false),
                "地址不同，哪怕当前正在播也要切");
        check("当前没有地址（首次投屏）→ 重建",
                PlaybackPolicy.shouldRebuild(ua, null, false, false),
                "currentUrl 为 null 时 equals 给 false，走重建 —— 这是对的");
        check("空地址 → 不重建（什么都不做）",
                !PlaybackPolicy.shouldRebuild("", ua, true, false),
                "空地址不是「换片」，是无效指令");
        check("null 地址 → 不重建（防御性）",
                !PlaybackPolicy.shouldRebuild(null, ua, true, false),
                "别让 NPE 在这里冒出来");

        System.out.println("\n── 11. 反向验证：两代旧实现各错一格，新逻辑要同时躲开 ──");

        check("【反向验证】旧逻辑（有地址就重建）在同地址 + 已就绪时也会重建",
                oldUrlBlindWouldRebuild(ua),
                "旧代码没有幂等判断，SetAVTransportURI 一到就 startInternal()。"
                + "而第 10 节第一条断言要求的正是这一格**不重建** —— 两者结论相反");
        check("【反向验证】只比 URL 的实现会误拦「同地址 + 已释放」那一格",
                !urlOnlyWouldRebuild(ua, ua),
                "它给 false（不重建），而第 10 节第三条要求的是重建 —— "
                + "这正是判据必须同时看地址和状态的原因");
        check("【反向验证】「有地址就重建」与「只比 URL」确实是两个不同的错法",
                oldUrlBlindWouldRebuild(ua) != urlOnlyWouldRebuild(ua, ua),
                "前者在该拦的格上重建（拖拽闪蓝屏），后者在该放的格上不重建"
                + "（救不回来）—— 新逻辑必须同时躲开这两个坑");
        check("【反向验证】两个旧错法在「同地址 + 已就绪」这一格给出同样的错误答案",
                oldUrlBlindWouldRebuild(ua) && !urlOnlyWouldRebuild(ua, ua),
                "都答「重建」—— 而这正是拖拽时闪一下蓝屏、位置归零的原因");

        // 注意：这一节刻意**不调用** PlaybackPolicy —— 它只证明「旧错法确实会错」。
        // 语义断言全部在第 10 节，两边不重叠，破坏性证伪时才能一处破坏只红一条。

        System.out.println("\n── 12. 「假 EOS」判据（厂商层把「切输入源」报成了播完）──");
        System.out.println("   厂商栈会把「释放旧播放器资源」和「切换输入源」规范化成一次 EOS");
        System.out.println("   （日志原文 This is adt event,the Normal event type is EOS!!!），");
        System.out.println("   框架据此回调 onCompletion —— 而那时位置离总时长还差得远。");

        // ① 还在 prepare：定义上不可能播完。
        // 这一格就是用户报的「投完视频再投音频，首次 Set 直接变 STOPPED」——
        // 真机上 PREPARING 与 STOPPED 之间没有 PLAYING，onPrepared 从未到达。
        check("还在 prepare → 判为假 EOS",
                PlaybackPolicy.isSpuriousCompletion(true, 300L, -1, 271000),
                "preparing=true：prepare 回调都没到，谈不上播完");
        check("还在 prepare 且时长未知 → 仍是假 EOS",
                PlaybackPolicy.isSpuriousCompletion(true, 300L, -1, 0),
                "不能因为 duration=0 就放行 —— 否则「首次 Set 即 STOPPED」照样漏网。"
                + "preparing 这一条必须排在时长判定**之前**");

        // ② 真机那一幕：271 秒的歌，起播半秒就报播完。
        check("271s 的音频只播了 0.5s 就报播完 → 假 EOS",
                PlaybackPolicy.isSpuriousCompletion(false, 500L, 300, 271000),
                "真机 14:47:40 那一幕：位置 ≈0.3s / 时长 271s");

        // ③ 真播完与边界 —— 这一组是「别误伤自然播完」的护栏。
        check("真播完（位置≈时长）→ 不是假 EOS",
                !PlaybackPolicy.isSpuriousCompletion(false, 20000L, 271000, 271000),
                "这是必须保护的路径：正常放完的歌不能被判成假 EOS 去重连");
        check("剩余恰好 10s → 不是假 EOS（判定用严格大于）",
                !PlaybackPolicy.isSpuriousCompletion(false, 20000L, 261000, 271000),
                "差 10000ms");
        check("剩余 10001ms → 是假 EOS（与上一条只差 1 毫秒）",
                PlaybackPolicy.isSpuriousCompletion(false, 20000L, 260999, 271000),
                "差 10001ms —— 差一毫秒结论必须相反");

        // ④ 两道闸：时长闸与时间闸。
        check("时长 < 20s → 不做判定（短流元数据最不可靠）",
                !PlaybackPolicy.isSpuriousCompletion(false, 500L, 0, 15000),
                "duration=15000");
        check("起播已 61s 才报播完 → 不做判定（保护自然播完）",
                !PlaybackPolicy.isSpuriousCompletion(false, 61000L, 1000, 271000),
                "elapsed=61000 —— 真播完一个长视频必然远超 60s，"
                + "而厂商的误报真机 8 次全在起播 1s 内");
        check("从未采到位置（-1）→ 按 0 处理 → 假 EOS",
                PlaybackPolicy.isSpuriousCompletion(false, 500L, -1, 271000),
                "宁可重连一次，也不要凭空报一个 STOPPED");

        // ⑤ 反向验证：旧实现（不看位置、照实报 STOPPED）在真机那一幕确实误报。
        check("【反向验证】旧实现照实报 STOPPED，而新判据判它是假 EOS",
                oldAlwaysStops() && PlaybackPolicy.isSpuriousCompletion(false, 500L, 300, 271000),
                "新旧结论相反 —— 这条断言能区分对错，不是恒真");

        System.out.println("\n── 13. 直连 MP4 的真实宽高解析（软件信箱的判据）──");
        System.out.println("   这台电视对直连 MP4 没有信箱计算链，厂商 native 会把画面放大铺满；");
        System.out.println("   而 getVideoWidth() 在本机恒为 0，只能从 MP4 的 tkhd 里读。");
        System.out.println("   下面用字节现造 MP4（不读任何文件），覆盖横屏 / 旋转 / stsd / v1 / 尾部 moov。");

        byte[] c1 = moovOf(videoTrak(0, 1920, 1080, false));
        int[] r1 = Mp4Aspect.parse(c1, c1.length);
        check("横屏 tkhd 1920×1080 → 原样返回 {1920,1080}",
                r1 != null && r1[0] == 1920 && r1[1] == 1080, "得到 " + fmt(r1));

        byte[] c2 = moovOf(videoTrak(0, 1280, 720, true));
        int[] r2 = Mp4Aspect.parse(c2, c2.length);
        check("旋转 90° 的 matrix（a=0,d=0,b/c≠0）→ 交换成 {720,1280}",
                r2 != null && r2[0] == 720 && r2[1] == 1280, "得到 " + fmt(r2));

        byte[] c3 = moovOf(trakWithStsd(720, 1280));
        int[] r3 = Mp4Aspect.parse(c3, c3.length);
        check("tkhd 宽高为 0 → 退到 stsd/avc1 的 720×1280",
                r3 != null && r3[0] == 720 && r3[1] == 1280, "得到 " + fmt(r3));

        byte[] c4 = moovOf(videoTrak(0, 0, 0, false), videoTrak(0, 1920, 1080, false));
        int[] r4 = Mp4Aspect.parse(c4, c4.length);
        check("第一条 trak 是音频（tkhd 宽高 0）、第二条视频 → 仍返回视频尺寸",
                r4 != null && r4[0] == 1920 && r4[1] == 1080, "得到 " + fmt(r4));

        byte[] c5 = moovOf(videoTrak(1, 3840, 2160, false));
        int[] r5 = Mp4Aspect.parse(c5, c5.length);
        check("tkhd v1（version=1，宽高 +96/+100）3840×2160 → 原样返回",
                r5 != null && r5[0] == 3840 && r5[1] == 2160, "得到 " + fmt(r5));

        byte[] c6 = tailWindow(moovOf(videoTrak(0, 720, 1280, false)));
        int[] r6 = Mp4Aspect.parse(c6, c6.length);
        check("moov 在尾部（窗口开头是 mdat 载荷）→ 仍能解析",
                r6 != null && r6[0] == 720 && r6[1] == 1280, "得到 " + fmt(r6));

        byte[] garbage = new byte[128];
        Arrays.fill(garbage, (byte) 0xAB);
        check("垃圾字节 → null（不是 MP4 就别猜）",
                Mp4Aspect.parse(garbage, garbage.length) == null, "整段 0xAB");
        byte[] ftypOnly = box("ftyp", new byte[16]);
        check("只有 ftyp 没有 moov → null",
                Mp4Aspect.parse(ftypOnly, ftypOnly.length) == null, "ftyp + 16 字节载荷");

        byte[] full = moovOf(videoTrak(0, 1920, 1080, false));
        byte[] trunc = Arrays.copyOf(full, full.length - 20);
        check("moov 不完整落在窗口内 → null（不读窗口外的垃圾）",
                Mp4Aspect.parse(trunc, trunc.length) == null,
                "声明 " + full.length + " 字节、实际只有 " + trunc.length);

        // ---- 信箱盒子：fitInside（QA 复审①，按面板缩放而不是按原始像素摆）----
        int[] f1 = Mp4Aspect.fitInside(720, 1280, 1920, 1080);
        check("fitInside 竖屏 720×1280 @1920×1080 → {608,1080}（缩进面板，不出框）",
                f1 != null && f1[0] == 608 && f1[1] == 1080, "得到 " + fmt(f1));
        int[] f2 = Mp4Aspect.fitInside(1920, 1080, 1920, 1080);
        check("fitInside 横屏 1920×1080 @1920×1080 → {1920,1080}（正好铺满）",
                f2 != null && f2[0] == 1920 && f2[1] == 1080, "得到 " + fmt(f2));
        int[] f3 = Mp4Aspect.fitInside(320, 240, 1920, 1080);
        check("fitInside 小视频 320×240 @1920×1080 → {1440,1080}（等比放大到贴边）",
                f3 != null && f3[0] == 1440 && f3[1] == 1080, "得到 " + fmt(f3));
        check("fitInside 入参 0 / 负数 → null（面板没量到就退回全屏，不猜值）",
                Mp4Aspect.fitInside(0, 1280, 1920, 1080) == null
                && Mp4Aspect.fitInside(720, -1, 1920, 1080) == null
                && Mp4Aspect.fitInside(720, 1280, 0, 1080) == null
                && Mp4Aspect.fitInside(720, 1280, 1920, -5) == null,
                "某一路返回了非 null");

        // ---- stsd 兜底的 fourcc 白名单（QA 复审④：音频 entry 不许当视觉尺寸）----
        // mp4a 的 entry +32/+34 摆成 0x0500/0x02D0 正好读成 {1280,720} ——
        // 落在合法区间内，MIN/MAX 闸挡不住，只有认 fourcc 才挡得住。
        byte[] audioMoov = moovOf(box("trak", concat(
                box("tkhd", tkhdPayload(0, 0, 0, false)),
                box("mdia", box("minf", box("stbl", stsdBoxOfAudio(1280, 720)))))));
        int[] rAudio = Mp4Aspect.parse(audioMoov, audioMoov.length);
        check("stsd 音频 entry（mp4a，+32/+34 恰为 1280×720）→ null（fourcc 白名单拒收）",
                rAudio == null, "得到 " + fmt(rAudio) + " —— 假尺寸混过了区间闸");

        System.out.println("\n── 14. native 采样缓存与「挂起」判据（修 A）──");
        System.out.println("   厂商栈（海信 MTK）的 seekTo 会挂住不返回，握着实例的 native 串行锁 ——");
        System.out.println("   同实例的 getCurrentPosition/getDuration 全部跟着堵，主线程一碰就是 ANR");
        System.out.println("   （真机取证 /data/anr/traces.txt，pid 1733）。");
        System.out.println("   修法：native 只由一条探针线程读，界面/看门狗/控制点读缓存。");
        System.out.println("   下面钉的是「什么时候算挂了」「什么时候该重建」。");

        check("采样间隔是秒级进度条能接受的粒度（≤500ms）",
                PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS > 0
                && PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS <= 500L,
                "得到 " + PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS + "ms");
        check("挂起阈值严格大于采样间隔（否则正常的一拍就会被误判成挂起）",
                PlaybackPolicy.NATIVE_PROBE_STUCK_MS
                > PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS,
                "间隔 " + PlaybackPolicy.POSITION_SAMPLE_INTERVAL_MS
                + "ms，阈值 " + PlaybackPolicy.NATIVE_PROBE_STUCK_MS + "ms");

        check("采样刚好到阈值 → 还没判挂起（判据用严格大于）",
                !PlaybackPolicy.isSampleStale(PlaybackPolicy.NATIVE_PROBE_STUCK_MS),
                "sinceLast=" + PlaybackPolicy.NATIVE_PROBE_STUCK_MS);
        check("采样超过阈值 → 判挂起",
                PlaybackPolicy.isSampleStale(PlaybackPolicy.NATIVE_PROBE_STUCK_MS + 1),
                "sinceLast=" + (PlaybackPolicy.NATIVE_PROBE_STUCK_MS + 1));

        long expired = PlaybackPolicy.SEEK_PENDING_TIMEOUT_MS + 1;
        check("seek 在飞 + 已超时 + 还有补发预算 → 该重建",
                PlaybackPolicy.shouldRebuildOnSeekTimeout(true, expired, 0),
                "这是「拖了没反应、播放器僵住」唯一的自动出路");
        check("seek 不在飞 → 不重建（哪怕时间早过了）",
                !PlaybackPolicy.shouldRebuildOnSeekTimeout(false, expired, 0),
                "没有待决 seek 时的重建是纯粹的打扰");
        check("seek 还没超时 → 不重建（老芯片上一次 seek 要好几秒）",
                !PlaybackPolicy.shouldRebuildOnSeekTimeout(true,
                        PlaybackPolicy.SEEK_PENDING_TIMEOUT_MS, 0),
                "提前重建会把播放拉回开头 —— 用户看到的是「拖了一下，电视跳回去了」");
        check("补发预算用尽 → 不重建（厂商直接拒绝这个 seek）",
                !PlaybackPolicy.shouldRebuildOnSeekTimeout(true, expired,
                        PlaybackPolicy.MAX_SEEK_REPLAY),
                "再重建就是死循环（真机连续 51 轮）");

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
     * 第一代旧实现的等价函数，**仅用于反向验证**：有地址就重建。
     *
     * <p>旧代码里 {@code SetAVTransportURI} 的处理是「收到地址就
     * {@code startInternal()}」，根本没有幂等这一说。于是控制点拖一次
     * 进度条就重建一次播放器 —— 视频层关一次、缓冲一次、位置归零一次。
     *
     * <p>留着它是为了让「新逻辑不重建」那条断言**能红**。
     */
    static boolean oldUrlBlindWouldRebuild(String url) {
        return url != null && url.length() > 0;
    }

    /**
     * 第二代旧实现的等价函数，**仅用于反向验证**：只比地址。
     *
     * <p>这是修幂等时最容易写成的样子 —— 只比 URL 相不相同。
     * 它在「拖拽」那一格是对的（同地址 → 不重建），但在
     * 「播放器出过错、已被释放」那一格是错的：控制点重发同地址想救回来，
     * 而它给的是「不重建」，于是永远救不回来。
     *
     * <p>两条反向验证函数摆在一起，才能说明正确判据为什么
     * **必须同时看地址和播放器状态**。
     */
    static boolean urlOnlyWouldRebuild(String newUrl, String currentUrl) {
        return !(newUrl != null && newUrl.equals(currentUrl));
    }

    /**
     * 旧实现（加「假 EOS」判据之前）的 onCompletion 行为，**仅用于反向验证**：
     * 位置多近都照实报「播完」—— 末尾就是一句 {@code notifyState("STOPPED")}，
     * 完全不看「播到哪了」，厂商层报什么就信什么。
     *
     * <p>留着它是为了让第 12 节那条反向断言**能红**：只有旧实现确实会误报、
     * 新旧结论确实相反，那条断言才有证伪力。
     *
     * @return 恒为 true —— 旧实现无条件报播完
     */
    static boolean oldAlwaysStops() {
        return true;
    }

    // ------------------------------------------------------------------
    // 合成 MP4（给 Mp4Aspect 的行为断言用）—— 不读任何文件，全部现造字节
    // ------------------------------------------------------------------

    /** 造一个 box：8 字节头（4 字节大端长度 + 4 字节 fourcc）+ 载荷。 */
    static byte[] box(String type, byte[] payload) {
        byte[] b = new byte[8 + payload.length];
        putInt(b, 0, b.length);
        b[4] = (byte) type.charAt(0);
        b[5] = (byte) type.charAt(1);
        b[6] = (byte) type.charAt(2);
        b[7] = (byte) type.charAt(3);
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** moov box，装若干 trak。 */
    static byte[] moovOf(byte[]... traks) {
        return box("moov", concat(traks));
    }

    /** 一条视频 trak：trak &gt; tkhd。 */
    static byte[] videoTrak(int version, int w, int h, boolean rot90) {
        return box("trak", box("tkhd", tkhdPayload(version, w, h, rot90)));
    }

    /**
     * tkhd 的载荷（不含 8 字节 box 头）。
     *
     * <p>偏移按 box 起始算：v0 matrix 在 +48、宽高在 +84/+88；v1 因
     * creation/modification/duration 各从 32 位变 64 位（各 +4），matrix 移到
     * +60、宽高移到 +96/+100。载荷里各减去 8 字节 box 头。
     */
    static byte[] tkhdPayload(int version, int w, int h, boolean rot90) {
        byte[] p = new byte[(version == 1) ? 96 : 84];
        p[0] = (byte) version;
        int mOff = (version == 1) ? 52 : 40;
        // 9 个 16.16 定点数：a,b,u,c,d,v,x,y,w。1.0 = 0x00010000。
        // 恒等：a=d=1。旋转 90°：a=d=0、b=c=1（Mp4Aspect 据此交换宽高）。
        putInt(p, mOff, rot90 ? 0 : 0x00010000);          // a
        putInt(p, mOff + 4, rot90 ? 0x00010000 : 0);      // b
        putInt(p, mOff + 12, rot90 ? 0x00010000 : 0);     // c
        putInt(p, mOff + 16, rot90 ? 0 : 0x00010000);     // d
        int wOff = (version == 1) ? 88 : 76;
        int hOff = (version == 1) ? 92 : 80;
        putInt(p, wOff, w << 16);                          // 16.16 定点
        putInt(p, hOff, h << 16);
        return p;
    }

    /** tkhd 宽高为 0 的 trak：trak &gt; tkhd + mdia&gt;minf&gt;stbl&gt;stsd&gt;avc1。 */
    static byte[] trakWithStsd(int w, int h) {
        return box("trak", concat(
                box("tkhd", tkhdPayload(0, 0, 0, false)),
                box("mdia", box("minf", box("stbl", stsdBox(w, h))))));
    }

    /** stsd：version/flags(4) + entry_count(4) + avc1。 */
    static byte[] stsdBox(int w, int h) {
        byte[] entry = avc1Box(w, h);
        byte[] p = new byte[8 + entry.length];
        putInt(p, 4, 1);                                   // entry_count = 1
        System.arraycopy(entry, 0, p, 8, entry.length);
        return box("stsd", p);
    }

    /**
     * 载荷布局同 {@link #stsdBox}，但第一个 entry 是 mp4a（音频），且把它的
     * +32/+34 摆成 {@code w}/{@code h} —— 用来证明 fourcc 白名单挡得住
     * 「假尺寸恰好落在合法区间」这种最难缠的假阳性。
     */
    static byte[] stsdBoxOfAudio(int w, int h) {
        byte[] p = new byte[40];
        putShort(p, 24, w);                                // entry +32
        putShort(p, 26, h);                                // entry +34
        byte[] entry = box("mp4a", p);
        byte[] payload = new byte[8 + entry.length];
        putInt(payload, 4, 1);                             // entry_count = 1
        System.arraycopy(entry, 0, payload, 8, entry.length);
        return box("stsd", payload);
    }

    /** avc1：VisualSampleEntry，宽高在 entry 起始 +32/+34（16 位无符号）。 */
    static byte[] avc1Box(int w, int h) {
        byte[] p = new byte[40];
        putShort(p, 24, w);                                // entry +32
        putShort(p, 26, h);                                // entry +34
        return box("avc1", p);
    }

    /** 把 moov 放到「窗口开头是 mdat 载荷」的位置，逼 Mp4Aspect 走 scanForMoov 回退。 */
    static byte[] tailWindow(byte[] moov) {
        // 尾部窗口的开头落在 mdat 载荷**中间** —— 不是 box 边界，头 4 字节是
        // 随机载荷（这里用 0xAB 模拟，含高位为 1 的字节）。findTopLevel 必须
        // 判定「这里不是 box 边界」而返回 null，逼 parse 走 scanForMoov 回退。
        byte[] filler = new byte[200];
        Arrays.fill(filler, (byte) 0xAB);
        return concat(filler, moov);
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    /** 只给失败信息用的可读格式。 */
    static String fmt(int[] size) {
        return size == null ? "null" : "{" + size[0] + "," + size[1] + "}";
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
