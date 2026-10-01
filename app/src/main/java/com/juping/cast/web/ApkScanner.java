package com.juping.cast.web;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * APK 扫描的 Android 胶水（批 3.5）：枚举存储卷 + 补元数据 + 后台线程。
 *
 * <p>与 {@link ApkScan}（纯逻辑内核）分工明确：内核只管「怎么遍历、怎么收窄」，
 * 本类管「往哪儿看、怎么读元数据、跑在哪个线程」—— 后者都要 Context / Android API，
 * 没法进桌面断言，所以薄薄一层，逻辑尽量下沉到内核。
 *
 * <p><b>三条硬要求</b>（详见方案 §5）：
 * <ul>
 *   <li>扫描跑在**专用线程**，不是主线程（会 ANR）、也不是 HTTP 连接线程
 *       （那条连接会被占死，手机端一直转圈）；</li>
 *   <li>新扫描**取消旧扫描**（代次号）—— 连点刷新不堆叠线程；</li>
 *   <li>结果**按代次缓存** —— 每次进页面都全量重扫，大 U 盘上体验很差。</li>
 * </ul>
 */
public final class ApkScanner {

    private static final String TAG = "ApkScanner";

    /**
     * 候选挂载点补充。
     *
     * <p>{@code /proc/mounts} 是主判据（{@link LocalStore#removableMounts()}），
     * 但个别 ROM 的 vold 配置不在其中出现 —— 硬编码候选「永远会漏」，只作补充，
     * 且逐个用 {@code isDirectory() && canRead()} 校验后才收。
     */
    private static final String[] CANDIDATE_MOUNTS = {
            "/mnt/usbhost/Storage01", "/mnt/usbhost/Storage02",
            "/mnt/usb", "/mnt/usbdisk", "/mnt/external_sd", "/mnt/sdcard2",
    };

    private final Context ctx;

    /** 代次：每次 {@link #scanAsync} 递增，旧线程据此自废（不写缓存、不回调）。 */
    private volatile long generation = 0;

    private final Object lock = new Object();
    /** 最近一次扫描结果；null = 从未扫过。 */
    private List<ApkEntry> cache;
    /** 上次结果是否被截断（到顶或超时）。 */
    private boolean cacheTruncated;
    /** 上次结果是否因**超时**被截断（与到顶区分，页面文案不同）。 */
    private boolean cacheTimedOut;
    private boolean scanning;

    public ApkScanner(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** 扫描完成回调（在扫描线程上调用，实现方若碰 UI 要自己切回主线程）。 */
    public interface Callback {
        void onResult(List<ApkEntry> entries, boolean truncated);
    }

    /**
     * 扫描根：可移动卷 ∪ 外部存储 ∪ 候选，canonical 去重，排除私有/系统目录。
     *
     * <p>「去重按真实路径」是关键：同一支 U 盘常被挂多处（这台电视上 {@code sda1}
     * 同时挂在 {@code /mnt/sdcard} 与 {@code /mnt/secure/asec}），
     * 不去重会把同一棵树扫两遍甚至（软链自指时）扫不完。
     */
    public List<File> roots() {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        List<String> mounts = LocalStore.removableMounts();
        for (int i = 0; i < mounts.size(); i++) {
            addRoot(out, seen, new File(mounts.get(i)));
        }
        try {
            addRoot(out, seen, Environment.getExternalStorageDirectory());
        } catch (Throwable t) {
            Log.w(TAG, "取外部存储目录失败", t);
        }
        for (int i = 0; i < CANDIDATE_MOUNTS.length; i++) {
            addRoot(out, seen, new File(CANDIDATE_MOUNTS[i]));
        }
        return out;
    }

    private void addRoot(List<File> out, Set<String> seen, File f) {
        if (f == null || !f.isDirectory() || !f.canRead()) {
            return;
        }
        String canon = canonical(f);
        if (canon == null || isExcluded(canon)) {
            return;
        }
        if (!seen.add(canon)) {
            return;
        }
        out.add(f);
    }

    /**
     * 排除「扫了也没用 / 不该扫」的目录。
     *
     * <p>应用私有目录（上传落盘处，{@code drwx------}）必须排除：即便扫到那里的
     * {@code .apk}，**系统安装器是另一个进程，读不到** —— 只会给用户一个
     * 「点了没反应」的按钮（与 {@code mediaserver} 穿不进私有目录是同一类问题）。
     * 与需求方「只扫外接设备、不扫盒子内部存储」的收窄一致。
     */
    private boolean isExcluded(String canon) {
        String priv = canonical(ctx.getFilesDir());
        if (priv != null && (canon.equals(priv) || under(canon, priv))) {
            return true;
        }
        return under(canon, "/data") || under(canon, "/proc")
                || under(canon, "/sys") || under(canon, "/dev");
    }

    private static boolean under(String path, String root) {
        if (path.equals(root)) {
            return true;
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        return path.startsWith(prefix);
    }

    /**
     * 同步扫描（含补元数据）。
     *
     * <p>跑在**调用者线程**上 —— 生产路径请用 {@link #scanAsync}，别在连接线程上调它。
     *
     * @return 扫描结果（含命中条目与**停因** —— 超时 / 到顶 / 取消都要如实带回去）
     */
    public ApkScan.Result scanNow() {
        List<File> rs = roots();
        long deadline = System.currentTimeMillis() + ApkScan.MAX_MILLIS;
        ApkScan.Result res = ApkScan.scan(rs, deadline, ApkScan.MAX_DEPTH,
                ApkScan.MAX_RESULTS, null);
        // 补元数据也吃时间预算：getPackageArchiveInfo 只读 zip 中央目录（不整包读入），
        // 但 300 个包挨个读也不便宜。超时就停，剩下的留 null —— 列表退回显示文件名。
        for (int i = 0; i < res.entries.size(); i++) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            enrich(res.entries.get(i));
        }
        return res;
    }

    /**
     * 异步扫描：开专用线程，结果按代次写缓存；新扫描会作废旧扫描。
     *
     * @param cb 完成回调（可为 null）
     */
    public void scanAsync(final Callback cb) {
        final long myGen = ++generation;
        synchronized (lock) {
            scanning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ApkScan.Result res = scanNow();
                // 截断判据来自内核的**停因**，不再按条数猜 —— 超时（没扫完）也必须算截断，
                // 否则慢盘 / 大树下会把「没扫完」误报成「扫全了」。
                boolean trunc = res.truncated();
                boolean fresh;
                synchronized (lock) {
                    fresh = (myGen == generation);
                    if (fresh) {
                        cache = res.entries;
                        cacheTruncated = trunc;
                        cacheTimedOut = res.timedOut;
                        scanning = false;
                    }
                }
                if (fresh && cb != null) {
                    cb.onResult(res.entries, trunc);
                }
            }
        }, "apk-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 从未扫过、且当前没在扫 → 起一次扫描。用于「进页面自动扫一次」。 */
    public void startScanIfNeeded() {
        synchronized (lock) {
            if (scanning || cache != null) {
                return;
            }
        }
        scanAsync(null);
    }

    /** 最近一次扫描结果；从未扫过返回 null。 */
    public List<ApkEntry> cached() {
        synchronized (lock) {
            return cache;
        }
    }

    public boolean isScanning() {
        synchronized (lock) {
            return scanning;
        }
    }

    public boolean isTruncated() {
        synchronized (lock) {
            return cacheTruncated;
        }
    }

    /**
     * 上次结果是否因**超时**被截断（8 秒预算用尽）。
     *
     * <p>与「到顶」区分开是为了文案：到顶是「只显示前 N 个（已达上限）」，
     * 超时是「扫描超时，结果可能不全，点刷新重试」—— 后者还值得再试一次。
     */
    public boolean isTimedOut() {
        synchronized (lock) {
            return cacheTimedOut;
        }
    }

    /**
     * 补「包名 / 应用名 / 版本」。读不出来就留 null（列表退回显示文件名）。
     *
     * <p>用 {@code getPackageArchiveInfo} 而不是把 APK 当 zip 自己解析：
     * 它只读 zip 中央目录，内存占用与文件大小无关（一个包几十 MB，
     * 整包读入在 0.6GB 的盒子上就是 OOM）。
     */
    private void enrich(ApkEntry e) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(e.file.getAbsolutePath(), 0);
            if (pi == null) {
                return;     // 不是合法 APK / 中央目录坏
            }
            e.packageName = pi.packageName;
            e.versionName = pi.versionName;
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                // 不设这两个，getApplicationLabel 会因找不到资源而退回包名 ——
                // 列表里就全是 com.xxx.yyy，认不出是哪个 App。
                ai.sourceDir = e.file.getAbsolutePath();
                ai.publicSourceDir = e.file.getAbsolutePath();
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null) {
                    e.label = label.toString();
                }
            }
        } catch (Throwable t) {
            // 老 ROM 上读坏包偶发抛异常 —— 只记日志，绝不让一条坏包拖垮整次扫描
            Log.w(TAG, "读 APK 元数据失败：" + e.name, t);
        }
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }
}
