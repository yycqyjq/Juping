import com.juping.cast.web.ApkEntry;
import com.juping.cast.web.ApkScan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ApkScan} 的纯逻辑断言（批 3.5）—— 用临时目录造各种树，不需要真机、不需要 U 盘。
 *
 * <p>断言的都是**真机上极难复现**的边界：跳过规则漏一个目录、深度上限没生效、
 * 结果上限不封顶、时间预算不退出、取消标志失灵、软链/重复卷导致同一棵树扫两遍 ——
 * 这些在大 U 盘上表现为「卡住 / 内存暴涨 / 同一批包列两遍」，桌面用一个小目录就能钉死。
 *
 * <p>计数与 {@link WebTest} 共用（调 {@code WebTest.check}），所以整套 web 闸门的
 * 汇总行 {@code 网页逻辑：N / N} 会把这里的断言一起算进去。
 */
final class ApkScanTest {

    private static final List<File> TEMP = new ArrayList<File>();

    private ApkScanTest() {
    }

    static void run() throws Exception {
        System.out.println("\n── 8. APK 扫描内核（外接存储，纯逻辑）──");

        // ---- 扩展名判定（大小写不统一是真实盘上的常态）----
        WebTest.check("isApk：.apk 认", ApkScan.isApk("a.apk"));
        WebTest.check("isApk：.APK / .Apk 也认（大小写不敏感）",
                ApkScan.isApk("A.APK") && ApkScan.isApk("b.Apk"));
        WebTest.check("isApk：非 apk / 无扩展名 / 以点结尾 / null 都不认",
                !ApkScan.isApk("a.mp4") && !ApkScan.isApk("apk")
                        && !ApkScan.isApk("x.") && !ApkScan.isApk(null));

        File root = mkTempDir("apkscan");

        // ---- 基本命中 + 大小写 + 非 apk 忽略 ----
        touch(new File(root, "a.apk"), 10);
        touch(new File(root, "B.APK"), 20);
        touch(new File(root, "readme.txt"), 5);
        List<ApkEntry> r1 = scan(root, ApkScan.MAX_DEPTH, ApkScan.MAX_RESULTS);
        WebTest.check("基本扫描：找到 2 个 apk（.apk 与 .APK 都算）", r1.size() == 2,
                "找到 " + r1.size() + " 个");
        WebTest.check("非 apk 被忽略（readme.txt 不在结果里）", !containsName(r1, "readme.txt"));
        WebTest.check("entry 带上 size（来自 stat，不是读文件内容）",
                sizeOf(r1, "a.apk") == 10, "a.apk size=" + sizeOf(r1, "a.apk"));

        // ---- 目录跳过集合 ----
        File android = new File(root, "Android/data");
        android.mkdirs();
        touch(new File(android, "inside.apk"), 1);
        File hidden = new File(root, ".hidden");
        hidden.mkdirs();
        touch(new File(hidden, "h.apk"), 1);
        List<ApkEntry> r2 = scan(root, ApkScan.MAX_DEPTH, ApkScan.MAX_RESULTS);
        WebTest.check("SKIP_DIRS：Android/ 下的 apk 不被扫到", !containsName(r2, "inside.apk"));
        WebTest.check("隐藏目录（以 . 开头）被跳过", !containsName(r2, "h.apk"));

        // ---- 深度上限（root = 0）----
        File deep = root;
        for (int i = 0; i < 3; i++) {
            deep = new File(deep, "d" + i);
        }
        deep.mkdirs();
        touch(new File(deep, "deep.apk"), 1);
        WebTest.check("深度上限：maxDepth=3 时第 3 层的 apk 被扫到",
                containsName(scan(root, 3, ApkScan.MAX_RESULTS), "deep.apk"));
        WebTest.check("深度上限：maxDepth=2 时第 3 层的 apk 扫不到",
                !containsName(scan(root, 2, ApkScan.MAX_RESULTS), "deep.apk"));

        // ---- 结果上限 ----
        File cap = mkTempDir("apkcap");
        touch(new File(cap, "a.apk"), 1);
        touch(new File(cap, "b.apk"), 1);
        touch(new File(cap, "c.apk"), 1);
        List<ApkEntry> r4 = scan(cap, ApkScan.MAX_DEPTH, 2);
        WebTest.check("结果上限：maxResults=2 时只返回 2 条（到顶即停）", r4.size() == 2,
                "返回 " + r4.size());

        // ---- 时间预算（deadline 已过 → 立刻退出）----
        File big = mkTempDir("apkbig");
        touch(new File(big, "x.apk"), 1);
        List<ApkEntry> r5 = ApkScan.scan(list(big), 0L, ApkScan.MAX_DEPTH,
                ApkScan.MAX_RESULTS, null);
        WebTest.check("时间预算：deadline 已过 → 立即返回空表", r5.isEmpty());

        // ---- 取消标志 ----
        List<ApkEntry> r6 = ApkScan.scan(list(big), Long.MAX_VALUE, ApkScan.MAX_DEPTH,
                ApkScan.MAX_RESULTS, new ApkScan.Cancel() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });
        WebTest.check("取消标志：置位后不再收集（连点刷新不堆叠线程的底层保证）", r6.isEmpty());

        // ---- canonical 去重：同一目录给两次，只收一条 ----
        File dup = mkTempDir("apkdup");
        touch(new File(dup, "one.apk"), 1);
        List<File> twice = new ArrayList<File>();
        twice.add(dup);
        twice.add(dup);
        List<ApkEntry> r7 = ApkScan.scan(twice, Long.MAX_VALUE, ApkScan.MAX_DEPTH,
                ApkScan.MAX_RESULTS, null);
        WebTest.check("canonical 去重：同一目录给两次只收一条", r7.size() == 1,
                "收 " + r7.size());

        // ---- 上限常量存在（防被静默删掉）----
        WebTest.check("上限常量存在（深度 8 / 条数 300 / 时间 8000ms）",
                ApkScan.MAX_DEPTH == 8 && ApkScan.MAX_RESULTS == 300
                        && ApkScan.MAX_MILLIS == 8000L);

        cleanup();
    }

    // ------------------------------------------------------------ 小工具

    private static List<ApkEntry> scan(File root, int maxDepth, int maxResults) {
        return ApkScan.scan(list(root), Long.MAX_VALUE, maxDepth, maxResults, null);
    }

    private static List<File> list(File f) {
        List<File> l = new ArrayList<File>();
        l.add(f);
        return l;
    }

    private static boolean containsName(List<ApkEntry> es, String name) {
        for (int i = 0; i < es.size(); i++) {
            if (name.equals(es.get(i).name)) {
                return true;
            }
        }
        return false;
    }

    private static long sizeOf(List<ApkEntry> es, String name) {
        for (int i = 0; i < es.size(); i++) {
            if (name.equals(es.get(i).name)) {
                return es.get(i).size;
            }
        }
        return -1;
    }

    /** 造一个临时目录（登记，结束时统一清） */
    private static File mkTempDir(String prefix) throws Exception {
        File d = java.nio.file.Files.createTempDirectory(prefix).toFile();
        TEMP.add(d);
        return d;
    }

    /** 造一个指定字节数的文件（内容无所谓 —— 扫描只看 stat） */
    private static void touch(File f, int size) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) {
            p.mkdirs();
        }
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(new byte[size]);
        } finally {
            o.close();
        }
    }

    private static void cleanup() {
        for (int i = 0; i < TEMP.size(); i++) {
            rmrf(TEMP.get(i));
        }
        TEMP.clear();
    }

    private static void rmrf(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] ks = f.listFiles();
            if (ks != null) {
                for (int i = 0; i < ks.length; i++) {
                    rmrf(ks[i]);
                }
            }
        }
        f.delete();
    }
}
