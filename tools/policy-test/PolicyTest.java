import com.juping.cast.dlna.DlnaDescription;
import com.juping.cast.player.Mp4Aspect;
import com.juping.cast.player.PlaybackPolicy;
import com.juping.cast.player.RenderState;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

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

        // ---- 13b. H.264 DPB 容量：「有声无画」的判据（todo §7.21）----
        System.out.println("   下面的判据来自一个真机现象：B 站 1080P 投上去黑屏有声、360P 正常。");
        System.out.println("   根因不是分辨率本身，而是 max_num_ref_frames × 每帧宏块数 超出硬件 DPB 上限");
        System.out.println("   （本机 MT5880 只到 Level 4.0 = 32768 宏块）。硬件发现装不下就放弃视频、");
        System.out.println("   音频照放，而且**一个错误都不报** —— 这就是它最难查的地方。");
        System.out.println("   判据只能从 SPS 里读。下面先钉「真流解得对」，再钉「判据分得开对错」。");

        // ① 真实数据：B 站 360P 流的 SPS（从真机日志里的 URL 拉下来提取的，30 字节）。
        //    它**真的含两处 00 00 03**（emulation prevention）—— 剥不掉就会整体错位，
        //    而错位解出来的参考帧数往往仍是个 0~16 的"合理值"。这条夹具同时盖住两者。
        byte[] bili360Sps = {
                (byte) 0x67, (byte) 0x64, (byte) 0x00, (byte) 0x1E,
                (byte) 0xAC, (byte) 0xCA, (byte) 0x70, (byte) 0x28,
                (byte) 0x0B, (byte) 0xFE, (byte) 0x5C, (byte) 0x05,
                (byte) 0xA8, (byte) 0x08, (byte) 0x08, (byte) 0x0A,
                (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x03,
                (byte) 0x00, (byte) 0x78, (byte) 0x1E, (byte) 0x2C,
                (byte) 0x59, (byte) 0x4F,
        };
        byte[] moov360 = moovWithSps(bili360Sps);
        Mp4Aspect.Dpb d360 = Mp4Aspect.dpb(moov360, moov360.length);
        check("【真实流】B 站 360P 的 SPS → High / Level 3.0 / 40×23 宏块 / 6 参考帧",
                d360 != null && d360.profileIdc == 100 && d360.levelIdc == 30
                && d360.picWidthInMbs == 40 && d360.frameHeightInMbs == 23
                && d360.maxNumRefFrames == 6,
                d360 == null ? "null" : (d360.profileName() + " L" + d360.levelName()
                        + " " + d360.picWidthInMbs + "×" + d360.frameHeightInMbs
                        + " ref=" + d360.maxNumRefFrames));
        check("【真实流】B 站 360P 不超限（5520 < 32768）—— 与真机实测「画面正常」一致",
                d360 != null && d360.neededMbs() == 5520 && !d360.exceedsDevice(),
                d360 == null ? "null" : ("needed=" + d360.neededMbs()));

        // ② 判据的另一半：B 站 1080P 的形态（120×68 宏块 + 6 参考帧 = 48960）。
        //    注意流**自己标 Level 5.0**（允许 110400 宏块）—— 按它自己的标称，
        //    6 个参考帧完全合法。这正是它在别处能正常播、却在这台盒子上黑屏的原因：
        //    **标称等级骗得过应用，骗不过硬件。**
        byte[] moov1080 = moovWithSps(sps(100, 50, 6, 120, 68, 1));
        Mp4Aspect.Dpb d1080 = Mp4Aspect.dpb(moov1080, moov1080.length);
        check("【判据】1080P + 6 参考帧 → 需要 48960 宏块，超硬件上限 32768",
                d1080 != null && d1080.neededMbs() == 48960 && d1080.exceedsDevice(),
                d1080 == null ? "null" : ("needed=" + d1080.neededMbs()));
        check("【判据】同一条流按它**自己标的** Level 5.0 却完全合法（110400）",
                d1080 != null && d1080.declaredLevelMaxMbs() == 110400
                && d1080.neededMbs() < d1080.declaredLevelMaxMbs(),
                "所以判据**必须**用硬件上限，不能用流标称的 level");

        // ③ 对照：自造的 1080P 只有 2 个参考帧（16320）—— 真机上画面是正常的。
        //    分辨率一样、只是参考帧少，这就是"不是分辨率本身"的实证。
        byte[] moovRef2 = moovWithSps(sps(100, 40, 2, 120, 68, 1));
        Mp4Aspect.Dpb dRef2 = Mp4Aspect.dpb(moovRef2, moovRef2.length);
        check("【对照】1080P + 2 参考帧 → 16320，不超限（真机上画面正常）",
                dRef2 != null && dRef2.neededMbs() == 16320 && !dRef2.exceedsDevice(),
                dRef2 == null ? "null" : ("needed=" + dRef2.neededMbs()));

        // ④ 场编码：一帧占两倍宏块行数。漏掉 ×2 会把隔行 1080p 少算一半，
        //    于是"本该报警的超限"被判成正常 —— 静默错里最难发现的那一类。
        byte[] moovField = moovWithSps(sps(77, 40, 6, 120, 34, 0));
        Mp4Aspect.Dpb dField = Mp4Aspect.dpb(moovField, moovField.length);
        check("场编码（frame_mbs_only=0）→ 行数 ×2：34 行变 68 行、需要 48960",
                dField != null && dField.frameHeightInMbs == 68
                && dField.neededMbs() == 48960 && dField.exceedsDevice(),
                dField == null ? "null" : (dField.frameHeightInMbs + " 行 / needed="
                        + dField.neededMbs()));

        // ⑤ 合理性闸：位流错位时会解出"看着合法"的错值，只能由闸挡住
        byte[] moovBadRef = moovWithSps(sps(100, 40, 17, 120, 68, 1));
        check("max_num_ref_frames = 17（超 H.264 规范上限 16）→ null",
                Mp4Aspect.dpb(moovBadRef, moovBadRef.length) == null, "被放行了");
        byte[] moovBadW = moovWithSps(sps(100, 40, 2, 600, 68, 1));
        check("宏块列数 600（> 512，即 8192 像素）→ null",
                Mp4Aspect.dpb(moovBadW, moovBadW.length) == null, "被放行了");

        // ⑥ 只认 SPS：NAL header 的 nal_unit_type 必须 = 7。
        //    不验的话，PPS 的第一个字节 0x68 会被当成 profile_idc 解下去。
        byte[] ppsNal = sps(100, 40, 2, 120, 68, 1);
        ppsNal[0] = 0x68;                                  // nal_unit_type = 8 (PPS)
        byte[] moovPps = moovWithSps(ppsNal);
        check("NAL 头是 PPS（0x68）而不是 SPS（0x67）→ null",
                Mp4Aspect.dpb(moovPps, moovPps.length) == null, "把 PPS 当 SPS 解了");

        // ⑦ 没有 avcC → null（不猜）。avc1 里没有 avcC 在现实里是存在的。
        byte[] moovNoAvcC = moovOf(trakWithStsd(1920, 1080));
        check("avc1 里没有 avcC 子 box → null",
                Mp4Aspect.dpb(moovNoAvcC, moovNoAvcC.length) == null, "猜了一个值");

        // ⑧ 入参异常一律 null，不许抛（这个方法在播放路径上，抛出去会掀翻整条流程）
        check("dpb 对 null / 空 / 垃圾字节 → null（不抛异常）",
                Mp4Aspect.dpb(null, 0) == null
                && Mp4Aspect.dpb(new byte[0], 0) == null
                && Mp4Aspect.dpb(garbage, garbage.length) == null,
                "抛了异常或猜了值");

        // ⑨ level 表：查不到返回 0（"判不了"），而不是拿个像真值的 0 去比大小
        check("levelMaxDpbMbs 查表：L4.0=32768 / L3.0=8100 / L1b=396 / 未知=0",
                Mp4Aspect.levelMaxDpbMbs(40) == 32768
                && Mp4Aspect.levelMaxDpbMbs(30) == 8100
                && Mp4Aspect.levelMaxDpbMbs(9) == 396
                && Mp4Aspect.levelMaxDpbMbs(99) == 0,
                "查表值与 H.264 Table A-1 不符");

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

        // ---------------------------------------------------------------- 16
        System.out.println("\n── 16. 界面形态机（原来住在 MainActivity.currentMode 里）──");
        System.out.println("   这一节是 2026-10-03 结构重构的产物：形态判据从 UI 搬进纯逻辑层，");
        System.out.println("   于是宽限边界、图片优先、pending 记账这些**语义**第一次能被穷举。");
        System.out.println("   以前它们只能靠「钉住 MainActivity 源码里出现过某个字符串」来保护 ——");
        System.out.println("   那种守卫看不出逻辑错，只看得出代码被删。");

        // 无内容 → 空闲
        check("没有内容 → MODE_IDLE",
                PlaybackPolicy.modeOf(RenderState.empty(), new PlaybackPolicy.ModeMemory(), 0L)
                        == PlaybackPolicy.MODE_IDLE, "空快照");

        // 三种形态各自对上
        check("有内容 + 纯音频 → MODE_AUDIO",
                PlaybackPolicy.modeOf(snap(RenderState.KIND_AUDIO, true, false, 180000L),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_AUDIO, "音频");
        check("有内容 + 有画面 → MODE_VIDEO",
                PlaybackPolicy.modeOf(snap(RenderState.KIND_VIDEO, false, false, 180000L),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_VIDEO, "视频");
        check("有内容 + 静态图 → MODE_IMAGE",
                PlaybackPolicy.modeOf(snap(RenderState.KIND_IMAGE, false, false, 0L),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_IMAGE, "图片");

        // 图片优先于音频/视频 —— 图片既不是音频也没有 MediaPlayer 画面，
        // 落到那个二选一里只会被判成"视频"，界面去等一个永远不来的视频帧。
        check("图片优先：kind=IMAGE 且 audioOnly=true 时仍判图片（不被音频吃掉）",
                PlaybackPolicy.modeOf(snap(RenderState.KIND_IMAGE, true, false, 0L),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_IMAGE,
                "图片先于音频判，否则投图会显示音乐卡片");
        check("图片优先：kind=IMAGE 且 preparing 时仍是图片（pending 不吃图片）",
                PlaybackPolicy.modeOf(snap(RenderState.KIND_IMAGE, false, true, 0L),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_IMAGE,
                "否则投图会显示「正在准备视频…」");

        // 视频准备中 → 瞬态 pending，但**记账仍是 VIDEO**
        PlaybackPolicy.ModeMemory mPending = new PlaybackPolicy.ModeMemory();
        int pending = PlaybackPolicy.modeOf(snap(RenderState.KIND_VIDEO, false, true, 0L),
                mPending, 0L);
        check("视频 + prepare 挂着 → MODE_VIDEO_PENDING", pending == PlaybackPolicy.MODE_VIDEO_PENDING,
                "占位层据此藏 SurfaceView（空视频层在老 MTK 上是一屏蓝）");
        check("pending 的记账仍是 MODE_VIDEO（它不是一种新的播放形态）",
                mPending.lastPlayingMode == PlaybackPolicy.MODE_VIDEO,
                "记账被改成 PENDING 的话，宽限与退后台的判据会一起被带偏");

        // 宽限：音频播完的 1.5 秒内继续显示音乐卡片
        PlaybackPolicy.ModeMemory mGrace = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_AUDIO, true, false, 180000L), mGrace, 1000L);
        int held = PlaybackPolicy.modeOf(RenderState.empty(), mGrace, 1000L + 1499L);
        check("音频刚停止 + 宽限内（1499ms）→ 继续 MODE_AUDIO",
                held == PlaybackPolicy.MODE_AUDIO, "换歌不闪面板靠它");
        check("宽限期内 staleHeld 置位（界面据此冻结卡片）", mGrace.staleHeld,
                "不冻结的话卡片会被服务里已清空的字段重绘成空白");

        PlaybackPolicy.ModeMemory mEdge = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_AUDIO, true, false, 180000L), mEdge, 0L);
        check("宽限边界：恰好 1500ms → 不维持（判据用严格小于）",
                PlaybackPolicy.modeOf(RenderState.empty(), mEdge,
                        0L + PlaybackPolicy.UI_GRACE_MS) == PlaybackPolicy.MODE_IDLE,
                "差一毫秒是分水岭，必须钉住");

        // 宽限只对音频：视频/图片播完立刻回空闲
        // （视频态宽限会把「闪面板」换成「闪蓝屏」，还把蓝屏多留 1.5 秒）
        PlaybackPolicy.ModeMemory mVid = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_VIDEO, false, false, 180000L), mVid, 0L);
        check("宽限只对音频：视频播完 + 宽限内 → MODE_IDLE（不维持）",
                PlaybackPolicy.modeOf(RenderState.empty(), mVid, 100L) == PlaybackPolicy.MODE_IDLE,
                "视频宽限会把「闪面板」换成「闪蓝屏」");
        PlaybackPolicy.ModeMemory mImg = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_IMAGE, false, false, 0L), mImg, 0L);
        check("宽限只对音频：图片播完 + 宽限内 → MODE_IDLE（不维持）",
                PlaybackPolicy.modeOf(RenderState.empty(), mImg, 100L) == PlaybackPolicy.MODE_IDLE,
                "图片态本无闪蓝问题，不纳入宽限");

        // 宽限必须**会终结** —— 这是「判据必须会终结」铁律在 UI 侧的那一份
        PlaybackPolicy.ModeMemory mTerm = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_AUDIO, true, false, 180000L), mTerm, 0L);
        int rounds = 0;
        while (rounds < 1000
                && PlaybackPolicy.modeOf(RenderState.empty(), mTerm,
                        0L + (long) (rounds + 1) * 500L) != PlaybackPolicy.MODE_IDLE) {
            rounds++;
        }
        check("宽限必然终结：按 500ms 一拍推进，有限拍内回 MODE_IDLE",
                rounds < 1000 && rounds <= 3,
                "实际用了 " + (rounds + 1) + " 拍（1500ms / 500ms = 3）—— "
                        + "写成「只要 lastPlayingMode != IDLE 就维持」会永不回空闲");

        // 遥控器返回键 = 用户明确要结束，不吃宽限
        PlaybackPolicy.ModeMemory mUser = new PlaybackPolicy.ModeMemory();
        PlaybackPolicy.modeOf(snap(RenderState.KIND_AUDIO, true, false, 180000L), mUser, 0L);
        mUser.userInitiatedStop = true;
        check("用户主动停止 → 立即 MODE_IDLE（不吃宽限）",
                PlaybackPolicy.modeOf(RenderState.empty(), mUser, 10L) == PlaybackPolicy.MODE_IDLE,
                "遥控器返回是明确的结束意图，不该等 1.5 秒");
        check("「用户主动停止」标志被消费掉（不会误伤下一次）", !mUser.userInitiatedStop,
                "不消费的话，下一次控制点 Stop 也享受不到宽限");

        // hasContent 的兜底：时长已知但地址一时为空，仍算有内容
        check("hasContent 兜底：时长已知 + 无地址 → 仍算有内容（与旧 isPlaying 等价）",
                PlaybackPolicy.modeOf(
                        new RenderState(true, false, RenderState.KIND_VIDEO, false, false,
                                false, false, 0L, 180000L, 0, 0),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_VIDEO,
                "只看 URI 的话，收尾与重新投屏之间会闪一下空闲面板");
        check("没有播放器实例 → 不算有内容（哪怕有地址）",
                PlaybackPolicy.modeOf(
                        new RenderState(false, true, RenderState.KIND_VIDEO, false, false,
                                false, false, 0L, 0L, 0, 0),
                        new PlaybackPolicy.ModeMemory(), 0L) == PlaybackPolicy.MODE_IDLE,
                "服务未就绪时界面必须回空闲");

        // pending 的判据是「prepare 挂着」，不是「时长为 0」
        // —— 直播时长恒为 0，拿它当判据会把正常直播永远判成准备中
        check("pending 判据用 preparing，不用 duration==0（直播时长恒 0）",
                new RenderState(true, true, RenderState.KIND_VIDEO, false, false,
                        true, false, 0L, 0L, 0, 0).isVideoPending(),
                "拿时长为 0 当判据，占位层在直播上再也撤不掉");
        check("没有片源时不算 pending（地址已清 = 收尾了）",
                !new RenderState(true, false, RenderState.KIND_VIDEO, false, false,
                        true, false, 0L, 0L, 0, 0).isVideoPending(),
                "与旧实现的 currentUri 非空判据逐字对应");
        // 上一条的夹具里 preparing 与 duration==0 同时为真，两种判据都成立 ——
        // 区分不开。这一条才是「判据只看 preparing」的判别式：直播时长恒 0，
        // 但它 prepare 早就过了，绝不能被判成准备中。
        check("直播时长恒 0 但 prepare 已过 → 不算 pending（判据只看 preparing）",
                !new RenderState(true, true, RenderState.KIND_VIDEO, false, false,
                        false, false, 0L, 0L, 0, 0).isVideoPending(),
                "拿 duration==0 当判据的话，正常播放的直播会被永远判成准备中，"
                + "占位层再也撤不掉（铁律：判据必须会终结）");

        System.out.println("\n── 17. device.xml 与三份 SCPD 的真实结构（用 XML 解析器读，不是正则看字符串）──");
        System.out.println("   这一节是 C 重构（2026-10-03）的直接收益：描述模板从 UpnpHttpServer");
        System.out.println("   摘进 DlnaDescription 之后**零 Android 依赖**，于是桌面第一次能把它真的");
        System.out.println("   解析一遍。在那之前只能拿正则看「某个字符串在不在源码里」——");
        System.out.println("   那种守卫连「XML 是坏的」都看不出来。");
        System.out.println("   要盯的红线只有一条：**声明了就要给得出，给不出就别声明**。");
        System.out.println("   与协议闸门的分工：drive.py 也校验 device.xml / SCPD，但那是**走真实 HTTP");
        System.out.println("   取回来的字节**（路由、响应头、charset 全在链路上）；这一节校验的是");
        System.out.println("   **生成器本身** —— 不建包、不起 socket 就能跑，而且能覆盖链路上看不见的");
        System.out.println("   东西（dataType 白名单、sendEvents 取值、knownActions 返回的是不是副本）。");
        System.out.println("   两层不是重复：一层问「服务出来的对不对」，一层问「拼出来的对不对」。");

        String[] svc = DlnaDescription.SERVICES;
        Document[] docs = new Document[svc.length];
        for (int i = 0; i < svc.length; i++) {
            String xml = DlnaDescription.scpdFor(svc[i]);
            check("SCPD[" + svc[i] + "] 给得出（SERVICES 里声明了，scpdFor 就必须兑现）",
                    xml != null,
                    "scpdFor(\"" + svc[i] + "\") 返回 null —— 控制点 GET 这个 SCPDURL 会拿到 404，"
                            + "整个服务被判不可用");
            docs[i] = xml == null ? null : parseXml(xml);
            check("SCPD[" + svc[i] + "] 是合法 XML（标准解析器读得出）", docs[i] != null,
                    "解析失败：" + XML_ERR);
        }

        for (int i = 0; i < svc.length; i++) {
            if (docs[i] == null) {
                continue;
            }
            Element root = docs[i].getDocumentElement();
            check("SCPD[" + svc[i] + "] 根元素是 <scpd>，命名空间是 service-1-0",
                    "scpd".equals(root.getNodeName())
                            && "urn:schemas-upnp-org:service-1-0".equals(root.getNamespaceURI()),
                    "得到 <" + root.getNodeName() + "> ns=" + root.getNamespaceURI());
            check("SCPD[" + svc[i] + "] 的 specVersion 是 1.0",
                    "1".equals(textOf(docs[i], "major")) && "0".equals(textOf(docs[i], "minor")),
                    "major=" + textOf(docs[i], "major") + " minor=" + textOf(docs[i], "minor"));
        }

        // 最关键的一条：三份 SCPD 声明的 action 并集，必须与 action 白名单**集合相等**。
        // 少一个 → 声明了却回 401（控制点认为设备撒谎）；
        // 多一个 → 白名单里有、SCPD 里没有，控制点根本不会发，那段代码是死的。
        TreeSet<String> declared = new TreeSet<String>();
        int declaredCount = 0;
        for (int i = 0; i < svc.length; i++) {
            if (docs[i] == null) {
                continue;
            }
            NodeList as = docs[i].getElementsByTagName("action");
            for (int k = 0; k < as.getLength(); k++) {
                declared.add(textOfChild((Element) as.item(k), "name"));
                declaredCount++;
            }
        }
        TreeSet<String> known = new TreeSet<String>(Arrays.asList(DlnaDescription.knownActions()));
        check("SCPD 声明的 action 并集 == 白名单集合（声明了就要给得出，给不出就别声明）",
                declared.equals(known), diff(declared, known));
        check("同一个 action 不在两份 SCPD 里重复声明（并集大小 == 声明总数）",
                declared.size() == declaredCount,
                "并集 " + declared.size() + " 条 / 实际声明 " + declaredCount + " 条");

        // 每个 relatedStateVariable 都要指向**本 SCPD 里真实存在**的 stateVariable。
        // 历史 bug 正在这里：InstanceID 与 A_ARG_TYPE_InstanceID 一字之差，
        // 宽松的控制点照用，严格的控制点（Cling / jUPnP 系）整份 SCPD 解析失败。
        StringBuilder dangling = new StringBuilder();
        for (int i = 0; i < svc.length; i++) {
            if (docs[i] == null) {
                continue;
            }
            TreeSet<String> vars = new TreeSet<String>();
            NodeList vs = docs[i].getElementsByTagName("stateVariable");
            for (int k = 0; k < vs.getLength(); k++) {
                vars.add(textOfChild((Element) vs.item(k), "name"));
            }
            NodeList rel = docs[i].getElementsByTagName("relatedStateVariable");
            for (int k = 0; k < rel.getLength(); k++) {
                String v = rel.item(k).getTextContent().trim();
                if (!vars.contains(v)) {
                    dangling.append(svc[i]).append('/').append(v).append(' ');
                }
            }
        }
        check("每个 relatedStateVariable 都指向本 SCPD 里真实存在的 stateVariable",
                dangling.length() == 0,
                "悬空引用：" + dangling + "—— 严格的控制点会整份解析失败，设备在列表里变灰");

        TreeSet<String> types = new TreeSet<String>();
        StringBuilder badEvents = new StringBuilder();
        for (int i = 0; i < svc.length; i++) {
            if (docs[i] == null) {
                continue;
            }
            NodeList ds = docs[i].getElementsByTagName("dataType");
            for (int k = 0; k < ds.getLength(); k++) {
                types.add(ds.item(k).getTextContent().trim());
            }
            NodeList vs = docs[i].getElementsByTagName("stateVariable");
            for (int k = 0; k < vs.getLength(); k++) {
                Element v = (Element) vs.item(k);
                String se = v.getAttribute("sendEvents");
                if (!"yes".equals(se) && !"no".equals(se)) {
                    badEvents.append(svc[i]).append('/').append(textOfChild(v, "name"))
                            .append('=').append(se).append(' ');
                }
            }
        }
        TreeSet<String> allowedTypes = new TreeSet<String>(Arrays.asList(
                "string", "boolean", "i1", "i2", "i4", "int", "ui1", "ui2", "ui4", "uint",
                "r4", "r8", "number", "fixed.14.4", "float", "char",
                "date", "dateTime", "dateTime.tz", "time", "time.tz", "uri", "uuid",
                "bin.base64", "bin.hex"));
        TreeSet<String> strayTypes = new TreeSet<String>(types);
        strayTypes.removeAll(allowedTypes);
        check("所有 dataType 都在 UPnP 允许的类型表里", strayTypes.isEmpty(),
                "越界类型：" + strayTypes + "（表外的类型控制点无法解析，同样整份失败）");
        check("每个 stateVariable 的 sendEvents 只能是 yes / no", badEvents.length() == 0,
                "异常：" + badEvents);

        // 事件化的那三个变量必须是 yes —— 控制点靠订阅它们同步音量滑杆与静音键。
        Document rcDoc = docs[indexOf(svc, "RenderingControl")];
        if (rcDoc != null) {
            check("RenderingControl 的 Volume / Mute 声明为可事件化（sendEvents=yes）",
                    "yes".equals(eventsOf(rcDoc, "Volume"))
                            && "yes".equals(eventsOf(rcDoc, "Mute")),
                    "Volume=" + eventsOf(rcDoc, "Volume") + " Mute=" + eventsOf(rcDoc, "Mute")
                            + " —— 不是 yes 的话，控制点订阅后收不到音量变化");
        }

        // ---------------------------------------------------------- device.xml
        String uuid = "11111111-2222-3333-4444-555555555555";
        Document dd = parseXml(DlnaDescription.deviceDescription(
                uuid, "客厅盒子", "0.2.12", 50007, "192.168.1.7",
                new byte[]{1, 2, 3}, 96, 96));
        check("device.xml 是合法 XML（带图标的那条路径）", dd != null, "解析失败：" + XML_ERR);

        if (dd != null) {
            Element dev = (Element) dd.getElementsByTagName("device").item(0);
            List<String> got = childNames(dev);
            List<String> want = Arrays.asList(
                    "deviceType", "friendlyName", "manufacturer", "manufacturerURL",
                    "modelDescription", "modelName", "modelNumber", "modelURL",
                    "serialNumber", "UDN", "iconList", "serviceList", "presentationURL",
                    "dlna:X_DLNADOC");
            check("<device> 子元素顺序符合 device-1-0 schema", got.equals(want),
                    "得到   " + got + "\n      期望   " + want
                            + "\n      顺序错的控制点整份解析失败 —— 不是「少读一个字段」");

            check("device.xml 的 deviceType 与 SSDP 应答用的是同一个常量",
                    DlnaDescription.DEVICE_TYPE.equals(textOf(dd, "deviceType")),
                    "deviceType=" + textOf(dd, "deviceType") + " —— 与 SSDP 的 ST/USN 不一致时，"
                            + "控制点搜得到却认为「这不是我要的渲染器」，表现为投不进去");
            check("dlna:X_DLNADOC 声明为 DMR-1.50",
                    "DMR-1.50".equals(textOf(dd, "dlna:X_DLNADOC")),
                    "得到 " + textOf(dd, "dlna:X_DLNADOC"));

            NodeList icons = dd.getElementsByTagName("icon");
            check("有图标字节时 iconList 里恰好一个 <icon>，且子元素顺序 mimetype→width→height→depth→url",
                    icons.getLength() == 1
                            && childNames((Element) icons.item(0)).equals(Arrays.asList(
                                    "mimetype", "width", "height", "depth", "url")),
                    "图标数=" + icons.getLength() + " 子元素="
                            + (icons.getLength() == 1 ? childNames((Element) icons.item(0)) : "-"));

            // presentationURL 必须用**传进来的**端口与地址。
            // 端口有 fallback（49152 被厂家自带的 DLNA 栈占了就往上移），
            // 写死首选端口的话这个按钮会把浏览器指到一个没人监听的端口上。
            check("presentationURL 用的是传进来的 IP 与端口（不是写死的）",
                    "http://192.168.1.7:50007/".equals(textOf(dd, "presentationURL")),
                    "得到 " + textOf(dd, "presentationURL") + "（传入的是 192.168.1.7:50007）");

            NodeList svcs = dd.getElementsByTagName("service");
            check("device.xml 的 <service> 条目数 == SERVICES.length",
                    svcs.getLength() == DlnaDescription.SERVICES.length,
                    "条目 " + svcs.getLength() + " / SERVICES " + DlnaDescription.SERVICES.length);
            StringBuilder mismatch = new StringBuilder();
            StringBuilder unfilled = new StringBuilder();
            for (int i = 0; i < svcs.getLength(); i++) {
                Element se = (Element) svcs.item(i);
                String type = textOfChild(se, "serviceType");
                if (!("urn:schemas-upnp-org:service:" + svc[i] + ":1").equals(type)) {
                    mismatch.append(i).append(':').append(type).append(' ');
                }
                // 「声明了就要给得出」的**跨层**版本：device.xml 里写的 SCPDURL，
                // scpdFor 必须真的兑现。改一处漏一处就是「设备是灰的」。
                String url = textOfChild(se, "SCPDURL");
                String shortName = url == null ? ""
                        : url.replace("/upnp/", "").replace(".xml", "");
                if (!DlnaDescription.isKnownService(shortName)
                        || DlnaDescription.scpdFor(shortName) == null) {
                    unfilled.append(url).append(' ');
                }
            }
            check("device.xml 第 i 个 serviceType 与 SERVICES[i] 一一对应（顺序即声明）",
                    mismatch.length() == 0, "错位：" + mismatch);
            check("device.xml 里每个 SCPDURL 都能被 scpdFor 兑现（GET 不会 404）",
                    unfilled.length() == 0, "兑现不了：" + unfilled);
        }

        // 拿不到可用地址时不声明 presentationURL —— 声明了控制点就会真去打开
        // http://0.0.0.0/ ，那比没有按钮糟。
        Document dd0 = parseXml(DlnaDescription.deviceDescription(
                "u-0", "盒子", "1", 50007, "0.0.0.0", null, 0, 0));
        check("地址不可用（0.0.0.0）时不声明 presentationURL",
                dd0 != null && dd0.getElementsByTagName("presentationURL").getLength() == 0,
                "声明了控制点会真的去打开 http://0.0.0.0/ —— 一个点开是错的按钮比没有按钮糟");
        check("没有图标字节时不写 iconList",
                dd0 != null && dd0.getElementsByTagName("iconList").getLength() == 0,
                "iconList 存在但拿不到图标，控制点会显示一个破图");

        // 转义往返：解析出来的文本必须与传入的原值逐字相等。
        String tricky = "客厅 & 卧室 <A> \"引号\" '单引号'";
        Document ddEsc = parseXml(DlnaDescription.deviceDescription(
                "u-esc", tricky, "1.0&2", 50007, "10.0.0.1", null, 0, 0));
        check("friendlyName / modelNumber 里的 & < > \" ' 被转义（解析后文本与原值相等）",
                ddEsc != null && tricky.equals(textOf(ddEsc, "friendlyName"))
                        && "1.0&2".equals(textOf(ddEsc, "modelNumber")),
                "一个 & 就能让整份描述变成非法 XML —— 控制点不是「少读一个字段」"
                        + "而是整条报文解析失败" + (ddEsc == null ? "（本次直接解析失败：" + XML_ERR + "）" : ""));
        // 反向验证：证明上面那条不是恒真 —— 解析器真的会拒绝未转义的 &。
        check("反向验证：未转义的 & 确实让解析器报错（证明上一条测的是真东西）",
                parseXml("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root><a>x&y</a></root>") == null,
                "如果这个也能解析成功，说明解析器太宽松，上一条守卫等于没测");

        // 白名单自身的两条不变量。
        check("未知服务名：isKnownService=false 且 scpdFor=null（调用方据此回 404）",
                !DlnaDescription.isKnownService("Bogus")
                        && DlnaDescription.scpdFor("Bogus") == null,
                "不校验的话控制点拼错服务名也能拿到 SID，然后永远收不到事件");
        String[] copy = DlnaDescription.knownActions();
        String firstAction = copy[0];
        copy[0] = "Tampered";
        check("knownActions() 返回副本（改它改不到生产用的白名单）",
                DlnaDescription.isKnownAction(firstAction)
                        && !DlnaDescription.isKnownAction("Tampered"),
                "返回数组本身的话，断言代码能悄悄改掉生产用的白名单");

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

    // ---- avcC / SPS 合成（给 DPB 判据用）----------------------------

    /**
     * avc1 的 VisualSampleEntry 固定头（78 字节）+ avcC 子 box。
     *
     * <p>固定头长度不是随便写的：SampleEntry 8 字节 + pre_defined/reserved
     * 16 字节 + width/height/resolution 12 字节 + reserved/frame_count 6 字节 +
     * compressorname 32 字节 + depth/pre_defined 4 字节 = 78。子 box 从载荷
     * 偏移 78 起（也就是从 box 起点算 +86）—— 和 {@code Mp4Aspect.dpbFromTrak}
     * 里那个偏移必须对得上，对不上就一路读到别的东西。
     */
    static byte[] avc1BoxWithAvcC(int w, int h, byte[] sps) {
        byte[] head = new byte[78];
        putShort(head, 24, w);                             // entry +32
        putShort(head, 26, h);                             // entry +34
        return box("avc1", concat(head, box("avcC", avcCPayload(sps))));
    }

    /**
     * AVCDecoderConfigurationRecord 的载荷（不含 8 字节 box 头）。
     *
     * <pre>
     * +0 configurationVersion        = 1
     * +1 AVCProfileIndication
     * +2 profile_compatibility
     * +3 AVCLevelIndication
     * +4 6 bits reserved + lengthSizeMinusOne
     * +5 3 bits reserved + numOfSequenceParameterSets = 1
     * +6 SPS 长度（16 位）
     * +8 SPS NAL 本体
     * </pre>
     */
    static byte[] avcCPayload(byte[] sps) {
        byte[] p = new byte[8 + sps.length];
        p[0] = 1;                                          // configurationVersion
        p[1] = (byte) 100;                                 // AVCProfileIndication
        p[2] = 0;                                          // profile_compatibility
        p[3] = (byte) 30;                                  // AVCLevelIndication
        p[4] = (byte) 0xFF;                                // reserved + lengthSize=3
        p[5] = (byte) 0xE1;                                // reserved + numOfSPS=1
        putShort(p, 6, sps.length);
        System.arraycopy(sps, 0, p, 8, sps.length);
        return p;
    }

    /** stsd：version/flags(4) + entry_count(4) + avc1（含 avcC）。 */
    static byte[] stsdBoxWithAvcC(byte[] sps) {
        byte[] entry = avc1BoxWithAvcC(1920, 1080, sps);
        byte[] p = new byte[8 + entry.length];
        putInt(p, 4, 1);                                   // entry_count = 1
        System.arraycopy(entry, 0, p, 8, entry.length);
        return box("stsd", p);
    }

    /** 一份含 avcC/SPS 的完整 moov。 */
    static byte[] moovWithSps(byte[] sps) {
        return moovOf(box("trak", box("mdia", box("minf", box("stbl",
                stsdBoxWithAvcC(sps))))));
    }

    /**
     * 现造一条 SPS NAL（含 1 字节 NAL header）。
     *
     * <p>只写到 {@code frame_cropping_flag} 之前 —— 正好覆盖 {@code Mp4Aspect}
     * 的 DPB 解析要读的那一段（后面的裁剪与 VUI 与 DPB 容量无关）。
     *
     * @param widthMbs        宏块列数
     * @param heightMapUnits  宏块行数（**未**乘场编码的 2，由 frameMbsOnly 决定）
     * @param frameMbsOnly    1 = 逐行扫描；0 = 场编码（一帧两倍行数）
     */
    static byte[] sps(int profileIdc, int levelIdc, int maxRef,
                      int widthMbs, int heightMapUnits, int frameMbsOnly) {
        BitWriter w = new BitWriter();
        w.u(profileIdc, 8);
        w.u(0, 8);                       // constraint_setN_flags
        w.u(levelIdc, 8);
        w.ue(0);                         // seq_parameter_set_id
        if (isHighProfile(profileIdc)) {
            // High 系列多这一段。**漏掉它就整体错位** —— 所以合成夹具必须
            // 和被测实现认同一份 profile 名单，否则测的是"两个 bug 互相抵消"。
            w.ue(1);                     // chroma_format_idc = 1 (4:2:0)
            w.ue(0);                     // bit_depth_luma_minus8
            w.ue(0);                     // bit_depth_chroma_minus8
            w.u(0, 1);                   // qpprime_y_zero_transform_bypass_flag
            w.u(0, 1);                   // seq_scaling_matrix_present_flag
        }
        w.ue(0);                         // log2_max_frame_num_minus4
        w.ue(0);                         // pic_order_cnt_type = 0
        w.ue(0);                         // log2_max_pic_order_cnt_lsb_minus4
        w.ue(maxRef);                    // max_num_ref_frames
        w.u(0, 1);                       // gaps_in_frame_num_value_allowed_flag
        w.ue(widthMbs - 1);              // pic_width_in_mbs_minus1
        w.ue(heightMapUnits - 1);        // pic_height_in_map_units_minus1
        w.u(frameMbsOnly, 1);            // frame_mbs_only_flag
        if (frameMbsOnly == 0) {
            w.u(1, 1);                   // mb_adaptive_frame_field_flag
        }
        w.u(1, 1);                       // direct_8x8_inference_flag
        w.u(0, 1);                       // frame_cropping_flag
        w.u(0, 1);                       // vui_parameters_present_flag
        w.rbspTrailing();
        byte[] rbsp = w.toBytes();
        byte[] nal = new byte[1 + rbsp.length];
        nal[0] = 0x67;                   // NAL header：nal_unit_type = 7（SPS）
        System.arraycopy(rbsp, 0, nal, 1, rbsp.length);
        return nal;
    }

    static boolean isHighProfile(int p) {
        return p == 100 || p == 110 || p == 122 || p == 244 || p == 44 || p == 83
                || p == 86 || p == 118 || p == 128 || p == 138 || p == 139
                || p == 134 || p == 135;
    }

    /** 按位写的游标 —— 现造 SPS 位流用（被测实现是「按位读」，夹具就得「按位写」）。 */
    static final class BitWriter {
        private byte[] buf = new byte[64];
        private int bitPos;

        void u(int v, int n) {
            for (int i = n - 1; i >= 0; i--) {
                bit((v >> i) & 1);
            }
        }

        /**
         * 无符号 Exp-Golomb：把 {@code k+1} 写成「前导 0 个数 = 位宽-1」的形式。
         * 例：0→{@code 1}、1→{@code 010}、6→{@code 00111}。
         */
        void ue(int k) {
            int v = k + 1;
            int bits = 32 - Integer.numberOfLeadingZeros(v);
            for (int i = 0; i < bits - 1; i++) {
                bit(0);
            }
            u(v, bits);
        }

        /** 有符号 Exp-Golomb：0→0、1→1、-1→2、2→3、-2→4 … */
        void se(int k) {
            ue(k <= 0 ? -2 * k : 2 * k - 1);
        }

        private void bit(int b) {
            if ((bitPos >> 3) >= buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            if (b != 0) {
                buf[bitPos >> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
            bitPos++;
        }

        /** rbsp_trailing_bits：一个 1，再补 0 到字节边界。 */
        void rbspTrailing() {
            bit(1);
            while ((bitPos & 7) != 0) {
                bit(0);
            }
        }

        byte[] toBytes() {
            return Arrays.copyOf(buf, (bitPos + 7) / 8);
        }
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

    // ------------------------------------------------- 第 17 节的 XML 辅助
    //
    // 为什么不用正则：正则只能回答「某个字符串在不在」。而 device.xml / SCPD
    // 出问题的形态是**结构性的** —— 子元素顺序错、relatedStateVariable 悬空、
    // 属性值不合法。这些在正则眼里全都"字符串都在啊"，只有真正的解析器
    // 会当场拒绝。用解析器还顺带证明了「生成出来的确实是合法 XML」。

    /** 最近一次解析失败的原因；断言详情直接引用它。 */
    static String XML_ERR = "";

    /**
     * 用标准解析器读一段 XML。失败返回 {@code null} 并把原因写进 {@link #XML_ERR}。
     *
     * <p>显式关掉 DOCTYPE 与外部实体：这些 XML 全部由本项目自己生成，
     * 一个 DOCTYPE 都不该有 —— 顺手把 XXE 那类问题挡在门外。
     */
    static Document parseXml(String xml) {
        XML_ERR = "";
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignore) {
                // 换实现时这个 feature 名可能不认识 —— 不影响主流程。
            }
            try {
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignore) {
            }
            DocumentBuilder b = f.newDocumentBuilder();
            // 默认的 ErrorHandler 会把 [Fatal Error] 直接打到 stderr —— 而第 17 节
            // 有一条**故意**喂坏 XML 的反向验证，那行噪声会夹在断言输出里让人以为出错了。
            // 换成自己的处理器：warning/error 不吭声，fatalError 照抛（失败照样传上来）。
            b.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(org.xml.sax.SAXParseException e) {
                }

                public void error(org.xml.sax.SAXParseException e) {
                }

                public void fatalError(org.xml.sax.SAXParseException e)
                        throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            XML_ERR = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    /** 第一个同名元素的文本（去空白）；没有则返回 null。 */
    static String textOf(Document d, String tag) {
        NodeList nl = d.getElementsByTagName(tag);
        if (nl.getLength() == 0) {
            return null;
        }
        return nl.item(0).getTextContent().trim();
    }

    /** 某个元素的**直接子元素**里第一个同名元素的文本；没有则返回 null。 */
    static String textOfChild(Element e, String tag) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return null;
    }

    /** 直接子元素的名字，按出现顺序 —— 用来核对 schema 定死的顺序。 */
    static List<String> childNames(Element e) {
        List<String> out = new ArrayList<String>();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add(n.getNodeName());
            }
        }
        return out;
    }

    /** 某个 stateVariable 的 sendEvents 值；找不到则返回 null。 */
    static String eventsOf(Document d, String varName) {
        NodeList vs = d.getElementsByTagName("stateVariable");
        for (int i = 0; i < vs.getLength(); i++) {
            Element v = (Element) vs.item(i);
            if (varName.equals(textOfChild(v, "name"))) {
                return v.getAttribute("sendEvents");
            }
        }
        return null;
    }

    /** 服务名在 {@link DlnaDescription#SERVICES} 里的下标；找不到返回 -1。 */
    static int indexOf(String[] arr, String name) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 两个集合的差异，给断言详情用。 */
    static String diff(TreeSet<String> a, TreeSet<String> b) {
        TreeSet<String> onlyA = new TreeSet<String>(a);
        onlyA.removeAll(b);
        TreeSet<String> onlyB = new TreeSet<String>(b);
        onlyB.removeAll(a);
        return "SCPD 声明了、白名单里没有（控制点会发，我们却回 401 —— 声明了却做不到）：" + onlyA
                + "\n      白名单里有、SCPD 没声明（控制点根本不会发，那段代码是死的）：" + onlyB;
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

    /**
     * 造一个「有播放器、有片源、无错误、无宽高」的快照 —— 形态机断言的最常用形态。
     *
     * <p>只暴露会改变形态判定的那几项，其余（videoMissing / 错误 / 位置 / 宽高）
     * 对形态机没有影响，固定成中性值即可 —— 夹具越小，读断言的人越容易一眼看出
     * 「这个输入到底在测什么」。
     */
    static RenderState snap(int kind, boolean audioOnly, boolean preparing, long durationMs) {
        return new RenderState(true, true, kind, audioOnly, false, preparing, false,
                0L, durationMs, 0, 0);
    }
}
