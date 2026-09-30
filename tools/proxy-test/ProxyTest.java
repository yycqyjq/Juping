import com.juping.cast.player.MediaProxy;
import com.juping.cast.player.PlaybackPolicy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MediaProxy 桌面测试：用 JDK 自带 HttpServer 当片源，站在 MediaPlayer
 * 的视角把真实请求序列走一遍。
 *
 * <p>断言的 oracle 只有一条：**代理吐出的字节流必须与源字节流逐字节一致** ——
 * 无论全量拉、Range 拖、来回 Seek，拼接结果都不许差一个字节。
 * 差一个字节，画面就是花屏；这类错误编译和运行都不报，只有对字节才能抓到。
 */
public final class ProxyTest {

    private static final List<String> fails = new ArrayList<String>();
    private static final List<String> names = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        byte[] src = makeSource(2 * 1024 * 1024);
        HttpServer origin = startOrigin(src);
        int originPort = origin.getAddress().getPort();
        String sourceUrl = "http://127.0.0.1:" + originPort + "/video.mp4";

        MediaProxy proxy = new MediaProxy(PlaybackPolicy.PROXY_BUFFER_BYTES);
        String local = proxy.localize(sourceUrl);
        if (local.equals(sourceUrl)) {
            fail("代理地址未生效（localize 原样返回了源地址）", local);
        }
        if (!local.startsWith("http://127.0.0.1:")) {
            fail("代理必须只听 127.0.0.1", local);
        }

        // ① 全量拉（MediaPlayer 起播的标准姿势：GET + Range: bytes=0-）
        System.out.println("marker: test1 begin");
        System.out.flush();
        byte[] got = fetch(local, 0, -1);
        System.out.println("marker: test1 fetched " + got.length);
        System.out.flush();
        eqBytes("① 全量拉与源逐字节一致", got, src, 0, src.length);

        // ② Seek 到中段（落在已预取区间内 → 必须立即从缓冲出数据）
        got = fetch(local, 1000000, -1);
        eqBytes("② Range 1MB 处 Seek 字节一致", got, src, 1000000, src.length - 1000000);

        // ③ Seek 到更后面（窗口外 → 代理需重新对源取数）
        got = fetch(local, 1500000, -1);
        eqBytes("③ Range 1.5MB 处 Seek 字节一致", got, src, 1500000, src.length - 1500000);

        // ④ 只取最后一个字节（EOS 边界）
        got = fetch(local, src.length - 1, -1);
        eqBytes("④ 末字节 Range 一致", got, src, src.length - 1, 1);

        // ⑤ 往回拖（窗口回退 → 丢弃旧窗口重新取数，字节仍要一致）
        got = fetch(local, 0, -1);
        eqBytes("⑤ 回拖到 0 再全量拉一致", got, src, 0, src.length);

        // ⑥ 小口多次读（模拟控制点频繁轮询下的读放大数据流）
        for (int off = 0; off < 300000; off += 65536) {
            int expect = src.length - off;   // Range bytes=off- 会一直给到片尾
            got = fetch(local, off, expect);
            eqBytes("⑥ 分段读 @" + off, got, src, off, expect);
        }

        proxy.shutdown();
        origin.stop(0);

        System.out.println();
        System.out.println("代理一致性：" + (names.size() - fails.size()) + " / " + names.size() + " 通过");
        if (!fails.isEmpty()) {
            System.out.println("失败项：");
            for (String f : fails) {
                System.out.println("  · " + f);
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- 工具

    private static byte[] makeSource(int size) {
        byte[] b = new byte[size];
        new Random(42).nextBytes(b);
        return b;
    }

    /** 带Range支持的片源：单 handler，按 Range 切片回 206 */
    private static HttpServer startOrigin(byte[] src) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    String range = ex.getRequestHeaders().getFirst("Range");
                    long start = 0;
                    boolean partial = false;
                    if (range != null && range.startsWith("bytes=")) {
                        String s = range.substring(6);
                        int dash = s.indexOf('-');
                        start = Long.parseLong(s.substring(0, dash).trim());
                        partial = true;
                    }
                    long end = src.length - 1;
                    ex.getResponseHeaders().set("Content-Type", "video/mp4");
                    ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                    byte[] body;
                    if (partial) {
                        body = new byte[(int) (end - start + 1)];
                        System.arraycopy(src, (int) start, body, 0, body.length);
                        ex.getResponseHeaders().set("Content-Range",
                                "bytes " + start + "-" + end + "/" + src.length);
                        ex.sendResponseHeaders(206, body.length);
                    } else {
                        body = src;
                        ex.sendResponseHeaders(200, body.length);
                    }
                    OutputStream os = ex.getResponseBody();
                    os.write(body);
                    os.close();
                } catch (IOException ignored) {
                    // 客户端提前断开（代理抢占旧流时会发生）
                }
            }
        });
        server.start();
        return server;
    }

    /** GET 指定偏移（带 Range 头），读完整响应体；expectLen>=0 时校验长度 */
    private static byte[] fetch(String local, long offset, int expectLen) throws IOException {
        URL u = new URL(local);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        if (offset > 0) {
            c.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        int code = c.getResponseCode();
        if (code != 200 && code != 206) {
            throw new IOException("HTTP " + code + " @offset=" + offset);
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        in.close();
        byte[] data = out.toByteArray();
        if (expectLen >= 0 && data.length != expectLen) {
            throw new IOException("长度不符 @" + offset + ": " + data.length + " != " + expectLen);
        }
        return data;
    }

    private static void eqBytes(String name, byte[] got, byte[] src, int off, int len) {
        boolean ok = got.length == len;
        if (ok) {
            for (int i = 0; i < len; i++) {
                if (got[i] != src[off + i]) {
                    ok = false;
                    break;
                }
            }
        }
        report(name, ok, ok ? "len=" + len : ("len=" + got.length + "/" + len + "（字节不一致）"));
    }

    private static void report(String name, boolean ok, String detail) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name
                + (detail.isEmpty() ? "" : "\n         " + detail));
        System.out.flush();
        names.add(name);
        if (!ok) {
            fails.add(name);
        }
    }

    private static void fail(String name, String detail) {
        report(name, false, detail);
    }

    private ProxyTest() {
    }
}
