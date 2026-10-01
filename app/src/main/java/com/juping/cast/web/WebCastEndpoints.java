package com.juping.cast.web;

import android.util.Log;

import com.juping.cast.dlna.UpnpHttpServer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「扫码网页传文件投屏」的网页端点（批 1）。
 *
 * <table>
 *   <tr><td>{@code GET  /}</td><td>上传页（HTML）。⚠️ 只在请求头带 {@code Accept: text/html}
 *       时才认——见 {@link #handle}</td></tr>
 *   <tr><td>{@code POST /upload}</td><td>multipart 流式落盘（含**落盘前的剩余空间预检**）</td></tr>
 *   <tr><td>{@code GET  /files}</td><td>已上传文件列表（JSON）</td></tr>
 *   <tr><td>{@code GET  /media/<名字>}</td><td>把已上传的文件流式提供出去（支持 Range）——
 *       投屏播放时取的就是这个地址，理由见 {@code UpnpHttpServer#writeMedia}</td></tr>
 *   <tr><td>{@code POST /cast}</td><td>把已上传的文件投到电视上播放</td></tr>
 *   <tr><td>{@code POST /delete}</td><td>删掉一个已上传的文件（盒子上空间紧，必要）</td></tr>
 * </table>
 *
 * <p>共用既有 {@link UpnpHttpServer} 的同一个端口、同一个 socket —— 不新起监听。
 * 一台 0.6GB 的盒子经不起多一个 HTTP 服务，而且手机已经能访问这个端口了
 * （{@code /status} 就是为此存在的）。
 *
 * <p><b>为什么返回 {@link UpnpHttpServer.WebResponse} 而不是自己写响应头</b>：
 * 响应头里有一句 {@code Server: Android UPnP/1.0 Juping/<版本>}，版本号是
 * 单一事实来源（{@code serverProduct()}）。在这里再写一份，升版本时就会漏改，
 * 同一个设备在不同路径上报两个版本号 —— 那正是项目已经收口掉的那类漂移。
 */
public final class WebCastEndpoints implements UpnpHttpServer.WebEndpoints {

    private static final String TAG = "WebCast";

    /**
     * 单次上传请求的 body 上限。
     *
     * <p>2GB：{@code Content-Length} 是 int，最大 2.1GB，这里再收一道，
     * 免得有人拿"声明 2GB 的单请求"去试探。真正的把关在下面那条空间预检上 ——
     * 这台盒子的内部存储总共才 2.2GB 可用。
     */
    private static final long MAX_UPLOAD_BYTES = 2000L * 1024 * 1024;

    /**
     * 落盘前必须留出的余量。
     *
     * <p>为什么不能用满：{@code /data} 装的不只是上传的文件，还有应用自己的代码与
     * 数据库。把 {@code /data} 写到 0 字节空闲，坏的就不只是"传不上去"——
     * 应用自己都会起不来。16MB 是给系统留的喘气空间。
     */
    private static final long MIN_FREE_RESERVE = 16L * 1024 * 1024;

    /** {@code /cast} 这类小请求体的上限 */
    private static final int SMALL_BODY_LIMIT = 8 * 1024;

    /** 媒体提供路径的前缀（投屏播放的地址就长这样） */
    private static final String MEDIA_PREFIX = "/media/";

    /** 认不出来的扩展名一律 application/octet-stream（播放器还能靠内容嗅探） */
    private static final String DEFAULT_MIME = "application/octet-stream";

    /** 真正的播放动作，交给上层（DlnaRendererService） */
    public interface CastTarget {
        /** 把本地文件当普通投屏源播出去（复用既有 DLNA 渲染路径） */
        void castLocalFile(File file);
    }

    private final LocalStore store;
    private final CastTarget target;

    public WebCastEndpoints(LocalStore store, CastTarget target) {
        this.store = store;
        this.target = target;
    }

    /**
     * 处理一条请求。
     *
     * @return 响应；返回 {@code null} 表示「这条路径不是我管的」，交回 DLNA 既有逻辑
     */
    @Override
    public UpnpHttpServer.WebResponse handle(String method, String path, String accept,
                                             String contentType, String range,
                                             int contentLength, InputStream in) {
        if ("POST".equals(method)) {
            if ("/upload".equals(path)) {
                return upload(contentType, contentLength, in);
            }
            if ("/cast".equals(path)) {
                return cast(contentLength, in);
            }
            if ("/delete".equals(path)) {
                return delete(contentLength, in);
            }
            return null;
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            // HEAD 也要认 —— 播放器探测 Content-Type 时先发 HEAD（见 writeMedia 的注释）。
            // 路由与 GET 同源，body 由 UpnpHttpServer 那边按方法决定写不写。
            if (path.startsWith(MEDIA_PREFIX)) {
                return media(path.substring(MEDIA_PREFIX.length()), range);
            }
            if ("/files".equals(path)) {
                return ok("application/json; charset=\"utf-8\"", filesJson());
            }
            // ⚠️ "/" 是个两头堵的路径：DLNA 控制点把它当 device.xml 的别名
            // （历史行为，不能动），而浏览器才是来看上传页的。
            // 靠 Accept 头分流——浏览器必然带 text/html，控制点不带。
            // 不能假设"没有控制点会探测 /"，所以不能直接把 / 改成上传页。
            if ("/".equals(path) && accept != null && accept.contains("text/html")) {
                return ok("text/html; charset=\"utf-8\"", PAGE);
            }
            return null;
        }
        return null;
    }

    /**
     * {@code /status} 里的存储版图。排障不需要 adb，沿用既有取向。
     *
     * <p>「外部存储」那一条在这台盒子上尤其有意义：实测
     * {@code /mnt/sdcard} 就是那支 USB 闪存盘本身，所以要把**探测结果**打出来 ——
     * 纸面上推导不出来，只能看实机。
     */
    public String storageJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"uploadDir\":");
        quote(sb, store.getRoot().getAbsolutePath());
        sb.append(",\"usableBytes\":").append(store.usableBytes());
        sb.append(",\"totalBytes\":").append(store.totalBytes());
        sb.append(",\"removableMounts\":[");
        List<String> mounts = LocalStore.removableMounts();
        for (int i = 0; i < mounts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, mounts.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ------------------------------------------------------------- 上传

    private UpnpHttpServer.WebResponse upload(String contentType, int contentLength,
                                              InputStream in) {
        // ① 长度必须有。没有长度就没法在**落盘之前**把空间算清楚，而
        //    「传完了才发现没空间」对用户是最坏的结果：等了几分钟，白等。
        //    浏览器表单与 curl -F 都必带 Content-Length；chunked 不支持。
        if (contentLength <= 0) {
            return error(411, "Length Required", "上传必须带 Content-Length（不支持分块传输）");
        }
        // ② 单请求上限
        if (contentLength > MAX_UPLOAD_BYTES) {
            return error(413, "Request Entity Too Large",
                    "单次上传不能超过 " + mbStr(MAX_UPLOAD_BYTES));
        }
        String boundary = MultipartLite.boundaryOf(contentType);
        if (boundary == null) {
            return error(400, "Bad Request", "不是 multipart/form-data，或没带 boundary");
        }
        // ③ **落盘前先看剩余空间够不够**。
        //
        //    为什么放在这里、而不是"边写边查"或"写完再查"：
        //    · 写一半才发现不够 → 用户已经等了几分钟，而且磁盘上留一个半截文件；
        //    · 写完再查 → 更糟，盘已经被塞满了。
        //    请求体的长度是文件大小的**上界**（多文件时是各文件之和 + 一点头部开销），
        //    拿它和可用空间比，是偏保守的估计 —— 保守在这里是对的：宁可早一点
        //    告诉用户"空间不够"，也不要写到中途把盘填死。
        //
        //    usable <= 0 表示读不出来（老设备上 statvfs 对某些文件系统返回 0）。
        //    这时**跳过预检**：把"读不出来"当成"没空间了"会把正常上传全部误拒，
        //    而误拒比晚一点发现更让人摸不着头脑。
        long usable = store.usableBytes();
        if (usable > 0 && contentLength > usable - MIN_FREE_RESERVE) {
            return error(507, "Insufficient Storage",
                    "盒子剩余空间不足：可用 " + mbStr(usable) + "，本次需要约 "
                            + mbStr(contentLength) + "（还需保留 " + mbStr(MIN_FREE_RESERVE)
                            + " 余量）。可以先删掉一些已上传的文件");
        }

        UploadSink sink = new UploadSink();
        try {
            MultipartLite.parse(in, boundary, sink);
        } catch (IOException e) {
            Log.w(TAG, "上传解析中断（已落盘 " + sink.saved.size() + " 个文件）", e);
            if (sink.saved.isEmpty() && sink.failed.isEmpty()) {
                return error(400, "Bad Request", "上传数据不完整：" + e.getMessage());
            }
            // 已经存下来的部分**保留**，不删。缺的只是没传完的那些——
            // 把存好的也删掉，等于让用户连"传成功的那几个"也一起白费。
        }
        return ok("application/json; charset=\"utf-8\"", sink.toJson());
    }

    /**
     * 落盘。**整个类的风险都集中在这里**：段体是边读边写，内存占用与文件大小无关。
     *
     * <p>解析是单线程串行的（一条连接一个线程），所以"当前落点"用一个字段记就够。
     */
    private final class UploadSink implements MultipartLite.Sink {

        final List<String> saved = new ArrayList<String>();
        final List<String> failed = new ArrayList<String>();
        /** 当前分段的落点。{{@link #begin}} 里赋值，{{@link #end}} 里用掉并清空。 */
        private File pending;

        @Override
        public OutputStream begin(String fieldName, String fileName) throws IOException {
            String safe = LocalStore.sanitize(fileName);
            if (safe == null) {
                failed.add(display(fileName) + "：文件名不可用");
                return null;
            }
            // 每个文件**逐个**再查一次空间：一个请求里可能有多个分段，
            // 前面的文件已经把空间吃掉了，整请求那一次预检管不到后面的。
            long usableSpace = store.usableBytes();
            if (usableSpace > 0 && usableSpace <= MIN_FREE_RESERVE) {
                failed.add(safe + "：盒子空间不足");
                return null;
            }
            pending = store.uniqueFileFor(safe);
            // 64KB 缓冲：段体本身也是 64KB 一块读的，两层加起来 128KB，
            // 换来的是系统调用次数降到 1/16 —— 老 eMMC 上这个差别不小。
            return new BufferedOutputStream(new FileOutputStream(pending), 64 * 1024);
        }

        @Override
        public void end(String fieldName, String fileName, long bytes, String failedReason) {
            File f = pending;
            pending = null;
            if (f == null && failedReason == null) {
                return; // begin 里就拒了，原因已经记过
            }
            if (failedReason != null) {
                // 半截文件必须删掉。留着的话它看着像个能播的文件，
                // 用户点播放得到一句莫名其妙的"无法播放"——比没有文件更难排查。
                if (f != null && !f.delete()) {
                    Log.w(TAG, "删不掉写坏的半截文件：" + f.getAbsolutePath());
                }
                failed.add(display(fileName) + "：" + failedReason);
                return;
            }
            saved.add(f.getName());
            Log.i(TAG, "已接收上传：" + f.getName() + "（" + bytes + " 字节）");
        }

        @Override
        public void field(String name, String value) {
            // 上传页上没有普通表单字段（只发文件分段）。将来加（比如"上传后自动播放"开关）再说。
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"saved\":[");
            for (int i = 0; i < saved.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                quote(sb, saved.get(i));
            }
            sb.append("],\"failed\":[");
            for (int i = 0; i < failed.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                quote(sb, failed.get(i));
            }
            sb.append("],\"usableBytes\":").append(store.usableBytes());
            sb.append(",\"message\":");
            quote(sb, describe());
            sb.append('}');
            return sb.toString();
        }

        private String describe() {
            if (saved.isEmpty() && failed.isEmpty()) {
                return "没有收到文件";
            }
            if (failed.isEmpty()) {
                return "已上传 " + saved.size() + " 个文件";
            }
            return "已上传 " + saved.size() + " 个，另有 " + failed.size() + " 个失败";
        }
    }

    // ------------------------------------------------------------- 列表

    private String filesJson() {
        List<String> names = store.list();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"files\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, names.get(i));
        }
        sb.append("],\"usableBytes\":").append(store.usableBytes());
        sb.append(",\"totalBytes\":").append(store.totalBytes());
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------- 媒体提供

    /**
     * 把已上传的文件流式提供出去。**投屏播放取的就是这个地址。**
     *
     * <p>为什么不让播放器直接读 {@code file://}：文件在应用私有目录里，
     * 真正 open 它的是另一个进程 mediaserver，它穿不进来（真机实测的结论，
     * 详见 {@code UpnpHttpServer#writeMedia}）。由本进程经 HTTP 提供就没这问题。
     *
     * @param encodedName 路径里那一段（浏览器与播放器都会做百分号编码）
     * @param range       {@code Range} 头原文，没有则 null
     */
    private UpnpHttpServer.WebResponse media(String encodedName, String range) {
        String name;
        try {
            name = URLDecoder.decode(encodedName, "UTF-8");
        } catch (Exception e) {
            return error(400, "Bad Request", "文件名编码不合法");
        }
        // fileFor 里做了规整 + 真实路径前缀校验，穿越出目录的名字直接拿不到文件
        File f = (name == null) ? null : store.fileFor(name);
        if (f == null || !f.isFile()) {
            return error(404, "Not Found", "没有这个文件");
        }
        String ct = contentTypeOf(name);
        long total = f.length();
        long[] r = parseRange(range, total);
        if (r == null) {
            return new UpnpHttpServer.WebResponse("200 OK", ct, f, 0, total);
        }
        return new UpnpHttpServer.WebResponse("206 Partial Content", ct, f, r[0], r[1]);
    }

    /**
     * 解析单段 Range。
     *
     * <p>只认单段。多段（{@code bytes=0-9,20-29}）合法但极少见，而拼
     * {@code multipart/byteranges} 的代码量与出错面远大于收益 ——
     * 遇到多段退回全量 200 是 RFC 允许的（客户端自己会处理）。
     * 语法有问题时同样退回全量，而不是回 416：能播比严格的错误码重要。
     *
     * @return {起点, 长度}；没有 Range 或解析不出时返回 {@code null}
     */
    private static long[] parseRange(String header, long total) {
        if (header == null || total <= 0) {
            return null;
        }
        String h = header.trim();
        if (!h.startsWith("bytes=")) {
            return null;
        }
        String spec = h.substring(6).trim();
        if (spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            if (a.length() == 0) {
                // bytes=-N：最后 N 字节
                if (b.length() == 0) {
                    return null;
                }
                long n = Long.parseLong(b);
                if (n <= 0) {
                    return null;
                }
                if (n > total) {
                    n = total;
                }
                return new long[]{total - n, n};
            }
            long start = Long.parseLong(a);
            if (start >= total) {
                return null;
            }
            long end = (b.length() == 0) ? total - 1 : Long.parseLong(b);
            if (end < start) {
                return null;
            }
            if (end > total - 1) {
                end = total - 1;
            }
            return new long[]{start, end - start + 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按扩展名判 Content-Type。
     *
     * <p>这不是可有可无的礼貌：这台盒子（MTK 5880）的 {@code getVideoWidth()}
     * 恒返回 0，播放器只能靠**媒体服务器自述的 Content-Type** 判断"这是视频"。
     * 报成 {@code application/octet-stream}，视频就会被判成纯音频 ——
     * 画面在放，界面却弹一张音乐卡片。
     */
    private static String contentTypeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) {
            return DEFAULT_MIME;
        }
        String ext = n.substring(dot + 1);
        if ("mp4".equals(ext) || "m4v".equals(ext)) return "video/mp4";
        if ("mkv".equals(ext)) return "video/x-matroska";
        if ("webm".equals(ext)) return "video/webm";
        if ("avi".equals(ext)) return "video/x-msvideo";
        if ("mov".equals(ext)) return "video/quicktime";
        if ("ts".equals(ext)) return "video/mp2t";
        if ("flv".equals(ext)) return "video/x-flv";
        if ("wmv".equals(ext) || "asf".equals(ext)) return "video/x-ms-wmv";
        if ("3gp".equals(ext)) return "video/3gpp";
        if ("mpg".equals(ext) || "mpeg".equals(ext)) return "video/mpeg";
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("aac".equals(ext)) return "audio/aac";
        if ("wav".equals(ext)) return "audio/wav";
        if ("flac".equals(ext)) return "audio/flac";
        if ("ogg".equals(ext) || "oga".equals(ext)) return "audio/ogg";
        if ("wma".equals(ext)) return "audio/x-ms-wma";
        if ("ape".equals(ext)) return "audio/x-ape";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        return DEFAULT_MIME;
    }

    // ------------------------------------------------------------- 投送

    private UpnpHttpServer.WebResponse cast(int contentLength, InputStream in) {
        if (contentLength <= 0 || contentLength > SMALL_BODY_LIMIT) {
            return error(400, "Bad Request", "请求体不合法");
        }
        String name = formValue(readSmall(in, contentLength), "name");
        File f = (name == null) ? null : store.fileFor(name);
        if (f == null || !f.isFile()) {
            return error(404, "Not Found", "没有这个文件");
        }
        try {
            target.castLocalFile(f);
        } catch (RuntimeException e) {
            Log.e(TAG, "投送本地文件失败：" + f.getName(), e);
            return error(500, "Internal Server Error", "投送失败：" + e.getMessage());
        }
        return ok("application/json; charset=\"utf-8\"",
                "{\"message\":" + json(f.getName()) + "}");
    }

    // ------------------------------------------------------------- 删除

    /**
     * 删掉一个已上传的文件。
     *
     * <p><b>这是本功能唯一的破坏性动作</b>，所以三道闸都在前面拦着：
     * <ol>
     *   <li>名字过 {@link LocalStore#fileFor} —— sanitize 挡掉 {@code ..}，
     *       canonical 前缀再核一遍，跑出上传目录的名字拿不到文件；</li>
     *   <li>{@code isFile()} —— 目录删不掉（这条接口只删文件，不给"清空目录"的口子）；</li>
     *   <li>只在本目录内 —— {@code fileFor} 出来的对象必然在 {@code uploads} 下，
     *       也就是**只删得到我们自己存的东西**，U 盘（批 3，只读）与系统文件都不在范围内。</li>
     * </ol>
     */
    private UpnpHttpServer.WebResponse delete(int contentLength, InputStream in) {
        if (contentLength <= 0 || contentLength > SMALL_BODY_LIMIT) {
            return error(400, "Bad Request", "请求体不合法");
        }
        String name = formValue(readSmall(in, contentLength), "name");
        File f = (name == null) ? null : store.fileFor(name);
        if (f == null || !f.isFile()) {
            return error(404, "Not Found", "没有这个文件");
        }
        // 删之前先记住规整后的名字 —— 删完 f.getName() 依然可读，但用变量更清楚
        String stored = f.getName();
        if (!f.delete()) {
            // 老设备上 vfat / 只读挂载点会走到这里（正常不应该，因为落盘在内部存储）
            Log.w(TAG, "删不掉：" + f.getAbsolutePath());
            return error(500, "Internal Server Error", "删不掉 " + stored);
        }
        Log.i(TAG, "已删除：" + stored);
        return ok("application/json; charset=\"utf-8\"",
                "{\"message\":" + json("已删除 " + stored) + "}");
    }

    // ------------------------------------------------------------ 小工具

    private static UpnpHttpServer.WebResponse ok(String contentType, String body) {
        return new UpnpHttpServer.WebResponse("200 OK", contentType, body);
    }

    private static UpnpHttpServer.WebResponse error(int code, String reason, String message) {
        return new UpnpHttpServer.WebResponse(code + " " + reason,
                "application/json; charset=\"utf-8\"",
                "{\"message\":" + json(message) + "}");
    }

    /** 读一个已知很小、且已被长度上限卡住的请求体 */
    private static String readSmall(InputStream in, int contentLength) {
        byte[] buf = new byte[contentLength];
        int read = 0;
        try {
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (IOException e) {
            Log.w(TAG, "读请求体失败", e);
        }
        try {
            return new String(buf, 0, read, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(buf, 0, read);
        }
    }

    /** 从 {@code application/x-www-form-urlencoded} 体里取一个字段 */
    private static String formValue(String body, String key) {
        if (body == null || body.length() == 0) {
            return null;
        }
        String[] pairs = body.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0 || !key.equals(pairs[i].substring(0, eq))) {
                continue;
            }
            try {
                // URLDecoder 会把 '+' 解成空格，这正是表单编码的约定
                return URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return pairs[i].substring(eq + 1);
            }
        }
        return null;
    }

    /** 字节数 → 给人看的 MB，向上取整（宁可多报一点，也别让人以为够用） */
    private static String mbStr(long bytes) {
        return ((bytes + 1024L * 1024L - 1) / (1024L * 1024L)) + "MB";
    }

    /** 规整一下用于提示的名字（拿不到就用原样，别把提示也搞丢了） */
    private static String display(String fileName) {
        String safe = LocalStore.sanitize(fileName);
        return safe != null ? safe : String.valueOf(fileName);
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        quote(sb, s);
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 0x20 || c == 0x7F) {
                    sb.append("\\u00");
                    sb.append(HEX.charAt((c >> 4) & 0xF));
                    sb.append(HEX.charAt(c & 0xF));
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final String HEX = "0123456789abcdef";

    /**
     * 上传页。**内嵌、无外部资源** —— 盒子没有外网，一个 CDN 上的框架就足以让整页白屏。
     *
     * <p>批 1 的完整版：选文件 / 选文件夹（特性检测降级）→ 逐个上传（带进度）→
     * 列表（预览 · 投到电视 · 删除）→ 剩余空间。
     *
     * <p>几条刻意的写法：
     * <ul>
     *   <li><b>ES5</b>：手机上的老浏览器（以及微信内置浏览器）遇到 {@code () => {}} 或
     *       {@code fetch} 是**语法级报错**，整页白屏 —— 而白屏是这类页面最难排查的故障形态
     *       （服务端日志里一切正常）；</li>
     *   <li><b>逐个文件单独请求</b>：一个请求里塞多个文件，浏览器只能给整个请求一个进度，
     *       中途失败还会连已经传成功的部分一起丢；逐个传才能显示"第 3/8 个"，
     *       失败的也只影响那一个；</li>
     *   <li><b>文件夹上传必须特性检测</b>：{@code webkitdirectory} 在 iOS Safari 上要到
     *       18.4 才完整支持。不支持时**收起入口并说明**，绝不留一个"点了没反应"的按钮
     *       （那比没有这个功能更让人摸不着头脑）。</li>
     * </ul>
     */
    private static final String PAGE =
            "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\"><head>\n"
            + "<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>聚屏 · 上传投屏</title>\n"
            + "<style>\n"
            + "*{box-sizing:border-box}\n"
            + "body{margin:0;padding:16px;font:16px/1.5 -apple-system,\"PingFang SC\","
            + "\"Microsoft YaHei\",sans-serif;background:#111;color:#eee}\n"
            + "h1{font-size:20px;margin:0 0 4px}\n"
            + "#space{color:#9a9a9a;font-size:13px;margin:0 0 16px}\n"
            + ".box{border:1px dashed #444;border-radius:10px;padding:20px;text-align:center}\n"
            + "input[type=file]{color:#ccc;width:100%}\n"
            + "#dirbox{margin-top:14px}\n"
            + ".hint{color:#8a8a8a;font-size:12px;margin:12px 0 0}\n"
            + "button{margin-top:12px;padding:10px 22px;font-size:16px;border:0;"
            + "border-radius:8px;background:#3d7eff;color:#fff}\n"
            + "button:disabled{background:#333;color:#777}\n"
            + "#bar{height:6px;background:#2a2a2a;border-radius:3px;margin-top:14px;"
            + "overflow:hidden;display:none}\n"
            + "#fill{height:100%;width:0;background:#3d7eff;transition:width .15s}\n"
            + "#msg{margin-top:12px;font-size:14px;min-height:1.5em;color:#ffb454}\n"
            + "ul{list-style:none;padding:0;margin:22px 0 0}\n"
            + "li{display:flex;align-items:center;gap:10px;padding:10px 0;"
            + "border-top:1px solid #262626}\n"
            + "li span{flex:1;word-break:break-all;font-size:14px}\n"
            + "li button{margin:0;padding:6px 14px;font-size:14px}\n"
            + "li button.del{background:#3a2a2a;color:#ff9a9a}\n"
            + "li a{color:#7fb0ff;font-size:14px;text-decoration:none}\n"
            + "</style></head><body>\n"
            + "<h1>聚屏</h1>\n"
            + "<p id=\"space\">正在读取…</p>\n"
            + "<div class=\"box\">\n"
            + "  <input type=\"file\" id=\"f\" multiple>\n"
            + "  <div id=\"dirbox\" style=\"display:none\">\n"
            + "    <input type=\"file\" id=\"d\" webkitdirectory directory multiple>\n"
            + "  </div>\n"
            + "  <p class=\"hint\" id=\"hint\"></p>\n"
            + "  <button id=\"go\">上传</button>\n"
            + "</div>\n"
            + "<div id=\"bar\"><div id=\"fill\"></div></div>\n"
            + "<p id=\"msg\"></p>\n"
            + "<ul id=\"list\"></ul>\n"
            + "<script>\n"
            + "var f=document.getElementById('f'),db=document.getElementById('d'),"
            + "dirbox=document.getElementById('dirbox'),hint=document.getElementById('hint'),"
            + "go=document.getElementById('go'),msg=document.getElementById('msg'),"
            + "bar=document.getElementById('bar'),fill=document.getElementById('fill'),"
            + "list=document.getElementById('list'),space=document.getElementById('space');\n"
            + "function fmt(n){if(!(n>0))return '未知';var u=['B','KB','MB','GB'],i=0;"
            + "while(n>=1024&&i<3){n/=1024;i++}return n.toFixed(i?1:0)+u[i]}\n"
            + "function say(t){msg.textContent=t}\n"
            + "function enc(n){return encodeURIComponent(n)}\n"
            // 文件夹入口：能选就显示，不能选就说明为什么 —— 见类头"特性检测"那条
            + "try{if(!('webkitdirectory' in document.createElement('input')))"
            + "throw 0;dirbox.style.display='block';hint.textContent='也可以选中一整个文件夹一起传'}"
            + "catch(e){hint.textContent='当前浏览器不支持选文件夹，请一次多选文件'}\n"
            + "function load(){\n"
            + "  var x=new XMLHttpRequest();x.open('GET','/files');\n"
            + "  x.onload=function(){\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){return}\n"
            + "    space.textContent='剩余可用 '+fmt(d.usableBytes)+' / 共 '+fmt(d.totalBytes)"
            + "+' · 已上传 '+d.files.length+' 个文件';\n"
            + "    list.innerHTML='';\n"
            + "    for(var k=0;k<d.files.length;k++){list.appendChild(row(d.files[k]))}\n"
            + "  };\n"
            + "  x.send();\n"
            + "}\n"
            + "function row(n){\n"
            + "  var li=document.createElement('li');\n"
            + "  var s=document.createElement('span');s.textContent=n;li.appendChild(s);\n"
            // 预览直接开 /media/<名字>：图片/视频浏览器自己就能放，不必另写播放器
            + "  var a=document.createElement('a');a.textContent='预览';a.target='_blank';"
            + "a.href='/media/'+enc(n);li.appendChild(a);\n"
            + "  var b=document.createElement('button');b.textContent='投到电视';"
            + "b.onclick=function(){cast(n,b)};li.appendChild(b);\n"
            + "  var c=document.createElement('button');c.textContent='删除';c.className='del';"
            + "c.onclick=function(){del(n,c)};li.appendChild(c);\n"
            + "  return li;\n"
            + "}\n"
            + "function post(url,body,onload,onerror){\n"
            + "  var x=new XMLHttpRequest();x.open('POST',url);\n"
            + "  x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');\n"
            + "  x.onload=onload;x.onerror=onerror;x.send(body);\n"
            + "}\n"
            + "function cast(n,b){\n"
            + "  b.disabled=true;b.textContent='投送中';\n"
            + "  post('/cast','name='+enc(n),function(x){\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){d={message:'响应异常'}}\n"
            + "    say(x.status===200?('已投送 '+n):('投送失败：'+(d.message||x.status)));\n"
            + "    b.disabled=false;b.textContent='投到电视';\n"
            + "  },function(){say('投送请求发不出去');b.disabled=false;b.textContent='投到电视'});\n"
            + "}\n"
            + "function del(n,c){\n"
            + "  if(!confirm('删除「'+n+'」？删了就没了'))return;\n"
            + "  c.disabled=true;c.textContent='删除中';\n"
            + "  post('/delete','name='+enc(n),function(x){\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){d={message:'响应异常'}}\n"
            + "    say(x.status===200?('已删除 '+n):('删除失败：'+(d.message||x.status)));\n"
            + "    load();\n"
            + "  },function(){say('删除请求发不出去');c.disabled=false;c.textContent='删除'});\n"
            + "}\n"
            + "function picked(){\n"
            + "  var a=[],i;\n"
            + "  for(i=0;i<f.files.length;i++)a.push(f.files[i]);\n"
            + "  for(i=0;i<db.files.length;i++)a.push(db.files[i]);\n"
            + "  return a;\n"
            + "}\n"
            + "go.onclick=function(){\n"
            + "  var files=picked();\n"
            + "  if(!files.length){say('先选文件或文件夹');return}\n"
            + "  go.disabled=true;bar.style.display='block';fill.style.width='0';\n"
            + "  var i=0,ok=0,bad=0;\n"
            + "  function next(){\n"
            + "    if(i>=files.length){\n"
            + "      go.disabled=false;bar.style.display='none';\n"
            + "      say('上传完成：成功 '+ok+' 个'+(bad?('，失败 '+bad+' 个'):''));\n"
            + "      load();return;\n"
            + "    }\n"
            + "    var file=files[i],fd=new FormData();fd.append('file',file);\n"
            + "    say('正在上传 '+(i+1)+'/'+files.length+'：'+file.name);\n"
            + "    var x=new XMLHttpRequest();x.open('POST','/upload');\n"
            + "    x.upload.onprogress=function(e){if(e.lengthComputable)"
            + "fill.style.width=Math.round(e.loaded/e.total*100)+'%'};\n"
            + "    x.onload=function(){\n"
            + "      var d;try{d=JSON.parse(x.responseText)}catch(e){"
            + "d={message:'响应异常（'+x.status+'）',saved:[],failed:[]}}\n"
            + "      if(x.status!==200){bad++;say('第 '+(i+1)+' 个上传失败：'"
            + "+(d.message||x.status));i++;next();return}\n"
            + "      ok+=((d.saved&&d.saved.length)?d.saved.length:0);\n"
            + "      bad+=((d.failed&&d.failed.length)?d.failed.length:0);\n"
            + "      say('已上传 '+(i+1)+'/'+files.length+'：'+((d.saved||[]).join('、')||'（无）'));\n"
            + "      i++;fill.style.width='0';next();\n"
            + "    };\n"
            + "    x.onerror=function(){bad++;say('第 '+(i+1)+' 个上传中断');i++;next()};\n"
            + "    x.send(fd);\n"
            + "  }\n"
            + "  next();\n"
            + "};\n"
            + "load();\n"
            + "</script>\n"
            + "</body></html>\n";
}