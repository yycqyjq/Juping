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

        // ⑦ Seek 中途重连 —— 真实 MediaPlayer 的姿势：**断开旧连接、带 Range 重连**，
        // 而不是把上一个响应读满。
        //
        // 上面 ①~⑥ 都是「读满 EOF」：客户端一定在重连前把窗口吃空
        // （serve 里 winStart 会被推进到片尾、winLen 归零），于是每个新请求看到的
        // 都是**空窗口**，走 reset 分支（winStart 与 pos 对齐）—— 这条盲区正是
        // 「读满」姿势压不出来的。
        //
        // 真实播放器只读一点点就 Seek：请求 A（Range:0-）只读 64KB 就断开，A 的
        // serve 线程在源 I/O 或缓冲推进里停下，留下一个**非空窗口**。请求 B 立刻
        // 带 Range 重连，offset 落在 [A.winStart, A.winStart+A.winLen] 内 →
        // B 走「复用缓冲」分支（inWindow=true、不复位）。这条分支有两个坑：
        //   1) 窗口前缀记账 —— B 从 offset 起写，但 winStart 还在 offset 之前，
        //      消费记账（winLen -= n; winStart = pos）会把窗口末端虚增，一路写到
        //      超过片尾 → 字节数多出来（修复前实测 len=1114112/1097152）；
        //   2) 线程重叠 —— A 的 serve 没退干净时 B 又起一条，两条一起推进同一份
        //      无锁窗口状态 → 字节错乱（小窗口下 10/10 复现，两个 serve 线程并发）。
        // 差一个字节就是花屏，而这里对的是逐字节。
        partialRead(local, 0, 64 * 1024);
        got = fetch(local, 1000000, -1);
        eqBytes("⑦ Seek 中途重连（A 读 64KB 即断 → B 带 Range 重连）字节一致",
                got, src, 1000000, src.length - 1000000);

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

    /**
     * 只读前 n 字节就断开 —— 模拟 MediaPlayer 起播读一点就 Seek。
     *
     * <p>刻意**不读到 EOF、不读完**：真实播放器就是这样（读够起播数据就断开旧连接、
     * 带新的 Range 重连）。这正是 ①~⑥「读满」姿势覆盖不到的路径 —— 旧 serve
     * 线程不会因为客户端读完而自然结束，会在**非空窗口**上被下一个请求撞上。
     */
    private static void partialRead(String local, long offset, int n) throws IOException {
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
        byte[] b = new byte[n];
        int total = 0;
        while (total < n) {
            int r = in.read(b, 0, n - total);
            if (r < 0) {
                break;
            }
            total += r;
        }
        // 不读完、不主动关输入流 —— 直接丢弃连接（等价于播放器 Seek 时断开旧连接）
        c.disconnect();
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
