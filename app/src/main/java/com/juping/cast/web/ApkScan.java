package com.juping.cast.web;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外接存储上「APK 安装包」的扫描内核（批 3.5）—— <b>纯逻辑，零 Android 依赖</b>。
 *
 * <p>为什么抽成纯逻辑：目标设备是 0.6GB 内存的老盒子，扫描要面对**可能很大的 U 盘**，
 * 而「跳过规则 / 深度 / 条数上限 / 时间预算 / 扩展名 / 去重 / 取消」全是策略 ——
 * 策略放这里，就能在桌面 JVM 上用临时目录把每条边界都断言一遍
 * （{@code tools/web-test/ApkScanTest.java}）。这与 {@code PlaybackPolicy} /
 * {@code MultipartLite} 是同一个路子。
 *
 * <p><b>几条硬纪律</b>：
 * <ul>
 *   <li><b>迭代而非递归</b>：深目录树在 0.6GB 设备上方法递归有 {@code StackOverflowError}
 *       风险，用显式栈遍历；</li>
 *   <li><b>不读文件内容</b>：扫描阶段只 {@code stat}（{@link File#length()} /
 *       {@link File#lastModified()}）。一个 APK 几十 MB，整包读入 = OOM；元数据交给
 *       {@code getPackageArchiveInfo()}（只读 zip 中央目录）；</li>
 *   <li><b>全程只读</b>：绝不写、绝不删外接卷（继承「U 盘只读」定案）；</li>
 *   <li><b>有上界</b>：深度 ≤ {@link #MAX_DEPTH}、条数 ≤ {@link #MAX_RESULTS}、
 *       时间 ≤ {@link #MAX_MILLIS}，到顶/超时即停并返回已得结果。</li>
 * </ul>
 */
public final class ApkScan {

    /**
     * 目录深度上限（扫描根 = 深度 0）。
     *
     * <p>挡住自造的异常深目录树 —— 那是保护时间与内存，不是功能限制：
     * 真实 U 盘上的安装包不会埋在 8 层目录以下。
     */
    public static final int MAX_DEPTH = 8;

    /**
     * 结果条数上限。到顶即停 —— 界定内存与 JSON 体积。
     *
     * <p>到顶时调用方（{@code ApkScanner}）据此把结果标成「已截断」，
     * 提示用户"只显示了前 N 条"。
     */
    public static final int MAX_RESULTS = 300;

    /**
     * 单次扫描的时间预算（毫秒）。超时即停，返回已得结果。
     *
     * <p>用户看到「部分结果 + 正在刷新」远好过对着转圈等一个没有上界的扫描。
     */
    public static final long MAX_MILLIS = 8000L;

    /**
     * 目录跳过集合：系统目录 / 回收站 / 加密镜像，不可能放用户要装的 APK。
     *
     * <p>跳过它们能省下大量 IO（尤其是 {@code Android/} 与
     * {@code System Volume Information/}）。另外**名字以 {@code .} 开头的隐藏目录**
     * 一律跳过（见 {@link #skipDir}）。
     */
    public static final String[] SKIP_DIRS = {
            "Android", "LOST.DIR", ".android_secure",
            "System Volume Information", "$RECYCLE.BIN", "obb",
    };

    /**
     * 取消回调。
     *
     * <p>用户连点「刷新」时，{@code ApkScanner} 会开新代次并让旧扫描尽早退出 ——
     * 否则会堆叠多个扫描线程一起啃同一个 U 盘。
     */
    public interface Cancel {
        boolean isCancelled();
    }

    private ApkScan() {
    }

    /**
     * 是不是一个 APK —— 按扩展名，<b>忽略大小写</b>。
     *
     * <p>用户从各种来源拷进 U 盘，{@code .APK} / {@code .Apk} 都得认，
     * 否则盘上明明有包却一个都列不出来。
     */
    public static boolean isApk(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;   // 没有扩展名 / 以点结尾
        }
        return "apk".equalsIgnoreCase(name.substring(dot + 1));
    }

    private static boolean skipDir(String name) {
        if (name.startsWith(".")) {
            return true;    // 隐藏目录
        }
        for (int i = 0; i < SKIP_DIRS.length; i++) {
            if (SKIP_DIRS[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 遍历所有根，收集 {@code .apk}。
     *
     * @param roots      扫描根（通常来自 {@code ApkScanner.roots()}）；null/空 → 返回空结果
     * @param deadlineMs 绝对截止时刻（{@code System.currentTimeMillis()} 口径）；
     *                   到点即停
     * @param maxDepth   深度上限（根 = 0）；≤0 时只扫根这一层
     * @param maxResults 条数上限；≤0 时返回空结果
     * @param cancel     取消回调；可为 null
     * @return 扫描结果（含命中条目与**停因**，见 {@link Result}）
     */
    public static Result scan(List<File> roots, long deadlineMs, int maxDepth,
                              int maxResults, Cancel cancel) {
        List<ApkEntry> out = new ArrayList<ApkEntry>();
        if (roots == null || roots.isEmpty() || maxResults <= 0) {
            return new Result(out, false, false, false);
        }
        // 已访问目录的真实路径集合 —— 防「同一卷挂多处 / 软链自指」把同一棵树扫两遍甚至死循环
        Set<String> visited = new HashSet<String>();
        List<Frame> stack = new ArrayList<Frame>();
        // 倒着压栈，弹出时才是 roots 的给定顺序
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.add(new Frame(roots.get(i), 0));
        }
        boolean timedOut = false;
        boolean cancelled = false;
        while (!stack.isEmpty()) {
            if (cancel != null && cancel.isCancelled()) {
                cancelled = true;
                break;
            }
            if (System.currentTimeMillis() > deadlineMs) {
                timedOut = true;
                break;
            }
            Frame f = stack.remove(stack.size() - 1);
            if (f.dir == null) {
                continue;
            }
            String canon = canonical(f.dir);
            if (canon != null && !visited.add(canon)) {
                continue;   // 这个目录（或它的软链别名）已经扫过了
            }
            File[] kids = f.dir.listFiles();
            if (kids == null) {
                continue;   // 不是目录 / 读不了（老 vfat 上偶发）
            }
            // 顺序确定 —— 让「到顶即停」的结果可复现（否则同一台机器两次刷新可能不同）
            Arrays.sort(kids, BY_NAME);
            List<File> subdirs = new ArrayList<File>();
            for (int i = 0; i < kids.length; i++) {
                File k = kids[i];
                if (k.isDirectory()) {
                    if (f.depth + 1 <= maxDepth && !skipDir(k.getName())) {
                        subdirs.add(k);
                    }
                } else if (k.isFile() && isApk(k.getName())) {
                    out.add(new ApkEntry(k, k.getName(), k.length(), k.lastModified()));
                    if (out.size() >= maxResults) {
                        // 到顶即停：不再往下递归 / 换卷 —— 0.6GB 设备上多扫一圈就是性能回归。
                        // 「有没有漏」不必再遍历，只看**已经 list 出来的**东西（见 moreRemaining）：
                        // 本目录剩余项里还有 apk/子目录、已收集的子目录、栈上待处理的目录，任一非空即
                        // 保守地认为还有 —— 宁可多报一次「已截断」，也不能漏报成「扫全了」。
                        return new Result(out, moreRemaining(kids, i + 1, subdirs, stack),
                                false, false);
                    }
                }
            }
            for (int i = subdirs.size() - 1; i >= 0; i--) {
                stack.add(new Frame(subdirs.get(i), f.depth + 1));
            }
        }
        return new Result(out, false, timedOut, cancelled);
    }

    /**
     * 到顶时判断「是否可能还有漏的」—— <b>只看已 list 出来的东西，不额外遍历</b>。
     *
     * <p>任一为真即保守地认为还有：本目录剩余项里还有 apk 或子目录、本目录已收集到子目录、
     * 栈上还有待处理的其它目录。都为空才是「确实扫完了、恰好 N 个」。
     */
    private static boolean moreRemaining(File[] kids, int from, List<File> subdirs,
                                         List<Frame> stack) {
        if (!subdirs.isEmpty() || !stack.isEmpty()) {
            return true;
        }
        for (int j = from; j < kids.length; j++) {
            File k = kids[j];
            if (k.isDirectory() || (k.isFile() && isApk(k.getName()))) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return null;    // 取不到真实路径：不参与去重，但照常扫（宁多扫不漏扫）
        }
    }

    /** 目录项按名字排序 —— 只为「结果顺序确定」，无业务含义 */
    private static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareTo(b.getName());
        }
    };

    /** 显式栈的一帧：目录 + 它的深度 */
    private static final class Frame {
        final File dir;
        final int depth;

        Frame(File dir, int depth) {
            this.dir = dir;
            this.depth = depth;
        }
    }

    /**
     * 一次扫描的结果：命中条目 + <b>停因</b>。
     *
     * <p><b>为什么必须带停因</b>：8 秒时间预算用尽时扫描会提前收手。若只回一个
     * {@code List}，调用方就只能按「条数是否到顶」猜 —— 慢盘 / 大树在 8 秒内没扫完
     * 时条数没到顶，于是被判成「扫全了」，用户对着**不全**的列表还以为都在。
     * 所以把「怎么停的」一并带回来。
     *
     * <p>三个停因里，{@link #hitResultCap}（收满）与 {@link #timedOut}（超时）都算
     * **截断**（{@link #truncated()}）；{@link #cancelled}（用户连点刷新 / 主动停）
     * 不算 —— 那是用户自己要停，不是结果不全。
     */
    public static final class Result {

        /** 命中的 APK（只填 file/name/size/modified） */
        public final List<ApkEntry> entries;
        /** 收满 {@link #MAX_RESULTS} 即停（后面可能还有） */
        public final boolean hitResultCap;
        /** 时间预算 {@link #MAX_MILLIS} 用尽即停（后面可能还有） */
        public final boolean timedOut;
        /** 被取消回调中断（用户连点刷新 / 主动停） */
        public final boolean cancelled;

        Result(List<ApkEntry> entries, boolean hitResultCap, boolean timedOut,
               boolean cancelled) {
            this.entries = entries;
            this.hitResultCap = hitResultCap;
            this.timedOut = timedOut;
            this.cancelled = cancelled;
        }

        /** 结果是否**不全**（到顶或超时）。取消不算 —— 用户自己要停。 */
        public boolean truncated() {
            return hitResultCap || timedOut;
        }
    }
}
