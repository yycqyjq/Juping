package com.juping.cast.player;

import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 直连 MP4 的**真实视频宽高**探测器 —— 只发两次 HTTP Range，把字节交给
 * {@link Mp4Aspect} 解析，解析逻辑本身不依赖任何 Android API。
 *
 * <p><b>它为什么存在</b>：这台电视（海信 MTK5880 / Android 4.0.4）对
 * <b>HLS</b> 走厂商自研的信箱计算链，比例本来就是对的；而<b>直连 MP4</b>
 * （抖音、腾讯偶尔给的 {@code .f632}）没有这条链，厂商 native 直接
 * {@code set overscan!!!} 把画面放大铺满 —— 竖屏视频被撑满全屏。控制面已穷尽
 * （零 overscan 调用、57 个 service 无 aspect 类、API 15 无 settings list），
 * 只能在<b>我们这一层</b>按真实宽高摆 SurfaceView（软件信箱）。
 *
 * <p>要摆对，就得先知道真实宽高。而 {@code MediaPlayer.getVideoWidth()} 在这台
 * 盒子上**恒为 0**（画面走厂商硬件图层，框架不知道尺寸，真机实测过），所以只能
 * 自己从 MP4 容器里读 tkhd —— 见 {@link Mp4Aspect}。
 *
 * <p><b>红线：HLS 绝不碰</b>。{@code .m3u8} 一出现就立刻返回 {@code null} ——
 * 厂商那条链算得对，我们再摆一次就是**双重信箱**。这是整个方案的红线。
 *
 * <p><b>纪律：探测是可选优化</b>。任何失败（网络、格式、超时）都静默返回
 * {@code null}，让调用方退回全屏 —— 不许抛、不许弹错误、不许猜一个值。
 * 探测失败绝不能影响播放本身。
 *
 * <p><b>为什么能只发两次请求</b>：faststart 的文件 moov 在头部窗口里；没做
 * faststart 的文件 moov 在尾部 —— 先用头窗口碰运气，命中就收工；只有确实需要
 * 且总长度已知时才发第二次。不是 MP4（buf[4..8) 不是 {@code ftyp}）在第一发
 * 就返回，连第二次都省了。
 */
public final class VideoAspectProbe {

    private static final String TAG = "VideoAspectProbe";

    /** 连接超时：探测是可选优化，绝不能因为探测把播放拖住。 */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    /** 读超时：同上。 */
    private static final int READ_TIMEOUT_MS = 3000;

    /** 合法宽高下界。低于它多半是读到了垃圾字节（见 {@link #probe} 的尺寸闸）。 */
    private static final int MIN_DIM = 16;
    /** 合法宽高上界。{@code stsdSize} 读到音频 entry 的垃圾字节时会给出荒谬值。 */
    private static final int MAX_DIM = 8192;

    private VideoAspectProbe() {
    }

    /**
     * 探测 {@code url} 指向的直连 MP4 的视频宽高；探不到返回 {@code null}。
     *
     * <p>调用方拿到 {@code null} 一律按「全屏」处理 —— 探测失败是常态（HLS、
     * 非 MP4、网络不通、服务器不支持 Range），绝不能因此影响播放。
     *
     * @return {@code {width, height}}，或 {@code null}
     */
    public static int[] probe(String url) {
        // 地址无效 / 不是 http(s) 流：没什么可探的。
        if (url == null || url.length() == 0) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        // **红线**：HLS（.m3u8）走厂商自研的信箱链，比例本来就是对的 ——
        // 我们再去摆一次就是双重信箱。看到就立刻放弃，连请求都不发。
        // toLowerCase 必须带 Locale.US：无参版在土耳其语环境里
        // 'I' → 'ı'（无点 i），"M3U8" 会漏网（敏感大小写判断的教科书坑）。
        // 实测 .M3U8 拿大写时无参版拦不住 —— 虽然后果只是白发一次请求
        // （m3u8 是文本、looksLikeMp4 不匹配照样返 null），但红线就该是红线。
        if (url.toLowerCase(java.util.Locale.US).contains(".m3u8")) {
            return null;
        }
        try {
            // 头窗口：faststart 的文件 moov 就在前面。总长度顺手从响应里拿。
            Window head = readWindow(url, 0, Mp4Aspect.WINDOW_BYTES - 1);
            if (head == null || !looksLikeMp4(head.buf, head.len)) {
                // 不是 MP4（MP3 / AAC / 未知格式）：头四个字节一读就知道，
                // 没必要再发第二次请求。
                return null;
            }
            int[] size = Mp4Aspect.parse(head.buf, head.len);
            // 尾窗口：没做 faststart 的文件 moov 在文件尾部。只有「总长度已知
            // 且比一个窗口长」时才值得再发一次 —— 拿不到总长度就放弃尾部请求
            // （乱猜一个偏移只会读到更没用的字节）。
            if (size == null && head.total > Mp4Aspect.WINDOW_BYTES) {
                long start = head.total - Mp4Aspect.WINDOW_BYTES;
                Window tail = readWindow(url, start, head.total - 1);
                if (tail != null) {
                    size = Mp4Aspect.parse(tail.buf, tail.len);
                }
            }
            if (size == null) {
                return null;
            }
            // 尺寸合法性闸：stsdSize 读到音频 entry 的垃圾字节时会给出荒谬值。
            // 宁可全屏，也不摆一个错的信箱 —— 摆错比不摆更难看。
            if (size[0] < MIN_DIM || size[0] > MAX_DIM
                    || size[1] < MIN_DIM || size[1] > MAX_DIM) {
                return null;
            }
            // 成功才打日志。**只打域名，不打完整 URL** —— 抖音/腾讯这类地址常带
            // token 与过期时间，签名本身就是播放凭证，落进日志等于泄漏。
            Log.i(TAG, "探测到直连 MP4 真实宽高 " + size[0] + "x" + size[1]
                    + "（域名 " + hostOf(url) + "）");
            return size;
        } catch (Exception e) {
            // 任何异常都静默退回全屏：探测是可选优化，失败不能影响播放本身。
            return null;
        }
    }

    /**
     * 发一次 Range 请求，把响应体读进一个窗口大小的缓冲区。
     *
     * @param start 起始字节（含）
     * @param end   结束字节（含）
     * @return 读到的窗口；连接失败会抛异常，由 {@link #probe} 兜住
     */
    private static Window readWindow(String url, long start, long end) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            // 跟随重定向：CDN 常把请求 302 到真实节点。
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            byte[] buf = new byte[Mp4Aspect.WINDOW_BYTES];
            int len = 0;
            in = c.getInputStream();
            int n;
            while (len < buf.length && (n = in.read(buf, len, buf.length - len)) > 0) {
                len += n;
            }
            // 总长度优先从 Content-Range 的 "/total" 拿（Range 请求的规范回法），
            // 退而求其次才用 Content-Length（仅当这是从头开始的请求时它才等于总长）。
            long total = parseTotal(c, start);
            return new Window(buf, len, total);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 从响应里解析文件总长度；拿不到返回 -1。
     *
     * <p>{@code Content-Range: bytes a-b/total} 优先 —— 它是 Range 请求下
     * 唯一权威的总长来源。{@code Content-Length} 只在「从头请求」时等于总长，
     * 尾部窗口请求里的它只是本次响应体长度，不能当总长用。
     */
    private static long parseTotal(HttpURLConnection c, long start) {
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash >= 0) {
                String t = cr.substring(slash + 1).trim();
                if (!"*".equals(t)) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (start == 0) {
            String cl = c.getHeaderField("Content-Length");
            if (cl != null) {
                try {
                    return Long.parseLong(cl.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1L;
    }

    /** MP4 的第一个 box 通常是 ftyp —— buf[4..8) 就是它的 fourcc。 */
    private static boolean looksLikeMp4(byte[] b, int len) {
        return len >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }

    /** 取 URL 的域名（去掉 userinfo），只给日志用 —— 避免把带 token 的完整地址打出去。 */
    private static String hostOf(String url) {
        int i = url.indexOf("://");
        int start = i >= 0 ? i + 3 : 0;
        int end = url.indexOf('/', start);
        String host = end >= 0 ? url.substring(start, end) : url.substring(start);
        int at = host.indexOf('@');
        return at >= 0 ? host.substring(at + 1) : host;
    }

    /** 一次 Range 请求的产物：窗口字节、有效长度、文件总长（未知为 -1）。 */
    private static final class Window {
        final byte[] buf;
        final int len;
        final long total;

        Window(byte[] buf, int len, long total) {
            this.buf = buf;
            this.len = len;
            this.total = total;
        }
    }
}
