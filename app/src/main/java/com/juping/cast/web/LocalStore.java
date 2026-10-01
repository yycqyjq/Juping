package com.juping.cast.web;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 网页上传的落盘位置，以及「这台机器的存储版图长什么样」。
 *
 * <p><b>落盘位置为什么是内部存储（{@code getFilesDir()}）而不是外部（{@code getExternalFilesDir()}）</b>：
 * 方案里原本写的是"外部优先 + 回退内部"，那是在**没看过这台盒子**的情况下定的。
 * 真机 adb 一探（2026-10-01，192.168.1.8），事实是：
 *
 * <pre>
 * /dev/block/vold/sda1  vfat  →  /mnt/sdcard        &lt;- Cenda USB Flash Disk，removable=1
 * /dev/block/mmcblk0p7  ext4  →  /data              &lt;- 内部，2.2GB 可用
 * </pre>
 *
 * <p>也就是说在这台电视上，<b>Android 的「外部存储」就是那支 U 盘本身</b>
 * （{@code Environment.getExternalStorageDirectory()} = {@code /mnt/sdcard}）。
 * 于是：
 * <ul>
 *   <li>按原方案写外部 → 上传的东西全落在 U 盘上，**拔盘就没了**，而且和
 *       "U 盘只读"那条定案自相矛盾（同一块盘一边只读一边写）；</li>
 *   <li>没有插 U 盘时 {@code getExternalFilesDir()} 还可能返回 {@code null}。</li>
 * </ul>
 * 所以改为**内部优先**：{@code getFilesDir()} 永久免权限，且与 U 盘彻底解耦 ——
 * 上传只写盒子自己的存储，U 盘只管只读浏览（§5.5）。
 *
 * <p>顺带一个好处：写内部存储**不需要** {@code WRITE_EXTERNAL_STORAGE}，
 * 所以这一步不动 AndroidManifest —— 少一次权限变更就少一次真机风险。
 * （U 盘**读**浏览那一步仍然需要它，见方案 §5.5，那是批 3 的事。）
 */
public final class LocalStore {

    private static final String TAG = "LocalStore";

    /** 上传目录名（挂在 {@code getFilesDir()} 下） */
    private static final String UPLOAD_DIR = "uploads";

    /** 文件名长度上限。留出富余，别让一个超长名字把整个目录项撑爆。 */
    private static final int NAME_LIMIT = 120;

    /** 可移动卷认得的文件系统类型 */
    private static final String[] REMOVABLE_FS = {
            "vfat", "msdos", "exfat", "ntfs", "tntfs", "texfat", "fuseblk",
    };

    private final File root;
    private final String rootCanonical;

    public LocalStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), UPLOAD_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "上传目录建不出来，退回到应用私有目录：" + dir.getAbsolutePath());
            dir = context.getApplicationContext().getFilesDir();
        }
        root = dir;
        String canonical = null;
        try {
            canonical = root.getCanonicalPath();
        } catch (IOException e) {
            Log.w(TAG, "取上传目录的真实路径失败", e);
        }
        rootCanonical = canonical;
    }

    public File getRoot() {
        return root;
    }

    /**
     * 上传目录所在卷的可用字节数。
     *
     * @return 字节数；**读不出来时返回 -1** —— 调用方据此跳过空间预检。
     *         老设备上 {@code statvfs} 对某些文件系统会返回 0，把 0 当成
     *         "没空间了"会把正常上传全部误拒。
     */
    public long usableBytes() {
        long n = root.getUsableSpace();
        return n > 0 ? n : -1;
    }

    /** 上传目录所在卷的总字节数；读不出来返回 -1 */
    public long totalBytes() {
        long n = root.getTotalSpace();
        return n > 0 ? n : -1;
    }

    /** 已上传的文件名（按名字排序）。目录读不出来时返回空表，不抛异常。 */
    public List<String> list() {
        String[] names = root.list();
        List<String> out = new ArrayList<String>();
        if (names != null) {
            Arrays.sort(names);
            for (int i = 0; i < names.length; i++) {
                if (new File(root, names[i]).isFile()) {
                    out.add(names[i]);
                }
            }
        }
        return out;
    }

    /**
     * 把用户给的名字规整成一个**只在本目录内**的安全文件名。
     *
     * <p>为什么必须做：这个接口没有鉴权（定案如此，仅限局域网），而文件名完全由
     * 请求方提供。不做规整的话，{@code "../../../data/data/com.juping.cast/..."}
     * 这类名字可以直接读到应用私有数据。
     *
     * @return 规整后的名字；无法规整（空、全是被挡掉的字符）时返回 {@code null}
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        // 只取最后一段：目录上传时浏览器会给 "sub/dir/a.mp4"，路径部分要丢掉
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = (cut >= 0) ? raw.substring(cut + 1) : raw;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // 控制字符与文件系统敏感字符换成下划线；**其余原样保留** ——
            // 中文名必须能用，这是最基本的要求
            if (c < 0x20 || c == 0x7F || c == '/' || c == '\\' || c == ':'
                    || c == '*' || c == '?' || c == '"' || c == '<' || c == '>'
                    || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        // 开头的点会造出隐藏文件，"." / ".." 更是直接穿越
        while (s.startsWith(".")) {
            s = s.substring(1).trim();
        }
        if (s.length() == 0) {
            return null;
        }
        if (s.length() > NAME_LIMIT) {
            // 截**中间**，保住扩展名 —— 截尾巴会把 ".mp4" 切掉，
            // 之后播放器认不出格式，文件"存下来了但放不了"。
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && s.length() - dot <= 12) ? s.substring(dot) : "";
            String base = (ext.length() > 0) ? s.substring(0, dot) : s;
            int keep = NAME_LIMIT - ext.length();
            s = base.substring(0, Math.max(1, keep)) + ext;
        }
        return s;
    }

    /**
     * 由名字取到本目录内的文件。
     *
     * @return 文件；名字非法或规整后跑出目录时返回 {@code null}
     */
    public File fileFor(String name) {
        String safe = sanitize(name);
        if (safe == null) {
            return null;
        }
        File f = new File(root, safe);
        if (rootCanonical != null) {
            try {
                // 双保险。sanitize 已经挡掉了 ".."，但这一条检查**不随 sanitize 的
                // 改动而失效** —— 将来谁放宽了名字规则，这道闸还在。
                String c = f.getCanonicalPath();
                if (!c.startsWith(rootCanonical + File.separator)) {
                    Log.w(TAG, "文件名规整后仍在目录外，拒绝：" + c);
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return f;
    }

    /**
     * {@code target} 是否在 {@code root} 之下（含相等）—— 共享的**防目录穿越**判据。
     *
     * <p>批 3（U 盘只读浏览）与批 3.5（外接卷 APK 安装）共用同一条：路径完全由请求方
     * 提供，必须用**真实路径**（{@code getCanonicalPath()}，会解析 {@code ..} 与
     * 符号链接）做前缀校验，而不是字符串前缀 —— 后者能被
     * {@code /mnt/sdcard/../data/data/…} 这种名字绕过。
     *
     * @return 在 root 之下返回 true；任一取不到真实路径时返回 false（保守拒绝）
     */
    public static boolean isUnder(File root, File target) {
        if (root == null || target == null) {
            return false;
        }
        try {
            String rc = root.getCanonicalPath();
            String tc = target.getCanonicalPath();
            if (tc.equals(rc)) {
                return true;
            }
            // root 恰好是文件系统根（"//"）时不能拼出 "//"，兜一下
            String prefix = rc.endsWith(File.separator) ? rc : rc + File.separator;
            return tc.startsWith(prefix);
        } catch (IOException e) {
            return false;
        }
    }

    /** 取一个不会覆盖已有文件的落点（重名时加 {@code (1)}、{@code (2)} …） */
    public File uniqueFileFor(String safeName) {
        File f = new File(root, safeName);
        if (!f.exists()) {
            return f;
        }
        int dot = safeName.lastIndexOf('.');
        String base = (dot > 0) ? safeName.substring(0, dot) : safeName;
        String ext = (dot > 0) ? safeName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(root, base + "(" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(root, base + "(" + System.currentTimeMillis() + ")" + ext);
    }

    /**
     * 可移动卷（U 盘）的挂载点。
     *
     * <p>API 15 没有 {@code StorageManager.getStorageVolumes()}（那是 API 24），
     * 挂载点只能靠厂商的 vold 配置，**路径因厂商而异**。这里解析
     * {@code /proc/mounts} 而不是硬编码候选列表 —— 候选表永远会漏，
     * 而 mounts 是内核给出的既成事实。
     *
     * <p>判据是**设备路径在 {@code /dev/block/vold/} 下**：内部分区是
     * {@code mmcblk0p*}，一眼就能区分开，不依赖挂载点叫什么名字。
     */
    public static List<String> removableMounts() {
        List<String> out = new ArrayList<String>();
        List<String> devices = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(
                    new InputStreamReader(new FileInputStream("/proc/mounts"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length < 3) {
                    continue;
                }
                String dev = f[0];
                String mnt = f[1];
                String fs = f[2];
                if (!dev.startsWith("/dev/block/vold/")) {
                    continue;
                }
                if (!isRemovableFs(fs)) {
                    continue;
                }
                // 同一个设备常被挂多处（这台电视上 sda1 同时挂在 /mnt/sdcard 与
                // /mnt/secure/asec）。只认最外层那一个 —— 挂在 secure 下的是给
                // 加密应用用的镜像视图，不是用户看到的目录。
                if (mnt.startsWith("/mnt/secure") || mnt.startsWith("/mnt/asec")
                        || mnt.startsWith("/mnt/obb") || mnt.indexOf("/.android_secure") >= 0) {
                    continue;
                }
                if (devices.contains(dev)) {
                    continue;
                }
                devices.add(dev);
                out.add(mnt);
            }
        } catch (Exception e) {
            // 老设备上 /proc/mounts 读不到也不算致命 —— 只影响 U 盘那一块，
            // 上传/播放照常。所以只记日志，不外抛。
            Log.w(TAG, "读 /proc/mounts 失败，U 盘挂载点探测不到", e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isRemovableFs(String fs) {
        for (int i = 0; i < REMOVABLE_FS.length; i++) {
            if (REMOVABLE_FS[i].equals(fs)) {
                return true;
            }
        }
        return false;
    }
}