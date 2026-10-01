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
     * @param roots      扫描根（通常来自 {@code ApkScanner.roots()}）；null/空 → 返回空表
     * @param deadlineMs 绝对截止时刻（{@code System.currentTimeMillis()} 口径）；
     *                   到点即停
     * @param maxDepth   深度上限（根 = 0）；≤0 时只扫根这一层
     * @param maxResults 条数上限；≤0 时返回空表
     * @param cancel     取消回调；可为 null
     * @return 命中的 APK（只填 file/name/size/modified），按遍历顺序
     */
    public static List<ApkEntry> scan(List<File> roots, long deadlineMs, int maxDepth,
                                      int maxResults, Cancel cancel) {
        List<ApkEntry> out = new ArrayList<ApkEntry>();
        if (roots == null || roots.isEmpty() || maxResults <= 0) {
            return out;
        }
        // 已访问目录的真实路径集合 —— 防「同一卷挂多处 / 软链自指」把同一棵树扫两遍甚至死循环
        Set<String> visited = new HashSet<String>();
        List<Frame> stack = new ArrayList<Frame>();
        // 倒着压栈，弹出时才是 roots 的给定顺序
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.add(new Frame(roots.get(i), 0));
        }
        while (!stack.isEmpty()) {
            if (cancel != null && cancel.isCancelled()) {
                break;
            }
            if (System.currentTimeMillis() > deadlineMs) {
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
                        return out;     // 到顶即停
                    }
                }
            }
            for (int i = subdirs.size() - 1; i >= 0; i--) {
                stack.add(new Frame(subdirs.get(i), f.depth + 1));
            }
        }
        return out;
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
}
