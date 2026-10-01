package com.juping.cast.web;

import java.io.File;

/**
 * 一条扫描到的 APK 记录（批 3.5）。
 *
 * <p><b>为什么 scan 阶段只填前四个字段</b>：扫描内核 {@link ApkScan} 要能在桌面 JVM 上
 * 跑断言，就必须**零 Android 依赖** —— 而「包名 / 应用名 / 版本」只能靠
 * {@code PackageManager.getPackageArchiveInfo()} 读，那是 Android 的。
 * 所以把这两件事分开：{@code ApkScan} 只填 {@link #file}/{@link #name}/
 * {@link #size}/{@link #modified}（都来自 {@code stat}，不读文件内容），
 * 后三个字段留给 {@code ApkScanner} 在 Android 侧补。
 *
 * <p>补不上（zip 中央目录坏、不是合法 APK）时后三个字段保持 {@code null} ——
 * 列表退回显示文件名，而不是整条消失：用户至少能看到"这里有个 apk"。
 */
public final class ApkEntry {

    /** APK 文件本身 */
    public final File file;
    /** 文件名（含扩展名），取自 {@code File.getName()} */
    public final String name;
    /** 字节数 */
    public final long size;
    /** 最后修改时间（毫秒） */
    public final long modified;

    /** 包名；由 {@code ApkScanner} 补，读不出来为 {@code null} */
    public String packageName;
    /** 应用名（{@code getApplicationLabel}）；读不出来为 {@code null} */
    public String label;
    /** 版本名；读不出来为 {@code null} */
    public String versionName;

    public ApkEntry(File file, String name, long size, long modified) {
        this.file = file;
        this.name = name;
        this.size = size;
        this.modified = modified;
    }
}
