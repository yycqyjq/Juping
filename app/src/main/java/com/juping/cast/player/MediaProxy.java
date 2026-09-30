package com.juping.cast.player;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;

/**
 * 本地预取缓冲代理 —— 把富余的局域网带宽兑换成「数据已在电视本地」。
 *
 * <p>投屏地址不再直接喂给 MediaPlayer，而是喂给 {@code 127.0.0.1} 上的
 * 这个代理；代理用全速预取维护一个环形缓冲窗口，播放器永远读本地回环。
 * 收益（真机 Hisense Vision-TV 实测背景）：老 AwesomePlayer 取数保守，
 * 起播/Seek 靠它自己拉流会卡数秒；预取后 Seek 落在已缓冲区间立即出画面。
 *
 * <p>设计参照成熟案例（fr.maxcom LocalSingleHttpServer、hellogv HttpGetProxy）：
 * <ul>
 *   <li>只监听 127.0.0.1，端口由系统动态分配 —— 局域网里不可见；</li>
 *   <li>MediaPlayer 的 Seek 表现为「断开旧连接、带 Range 头重连」，
 *       所以**每个请求独立服务**，按 Range 重新对位；</li>
 *   <li>同一个源的前后请求**共享缓冲窗口**：Seek 落在已预取区间内
 *       不重新联网，直接从内存出数据。</li>
 * </ul>
 *
 * <p>内存纪律：环形缓冲容量来自 {@link PlaybackPolicy#PROXY_BUFFER_BYTES}
 * （0.6GB 的盒子上给了 8MB，绝不落盘、绝不超限）。
 * 换片源 / Stop 时 {@link #reset()} 立刻释放；**断流重连（releasePlayer）
 * 特意不清** —— 缓冲活过重连，正是它的价值所在。
 *
 * <p>限制：只代理渐进式字节流（mp4 等带 Range 语义的 HTTP 源）；
 * HLS（m3u8）由调用方 bypass（播放器自带分片逻辑，代理按字节寻址没意义）。
 */
public final class MediaProxy extends Thread {

    private static final String TAG = "MediaProxy";

    private final int capacity;
    private ServerSocket server;
    private volatile int port = -1;
    private volatile String sourceUrl;
    private volatile long epoch;
    private volatile Socket activeClient;
    private volatile boolean running;
    private Thread serveThread;

    // ---- 会话窗口状态（仅当前 serve 线程在 epoch 匹配时读写） ----
    private final byte[] buf;      // 环形缓冲
    private long winStart;         // 窗口首字节对应的源偏移
    private int winLen;            // 窗口内有效字节数
    private long total = -1;       // 源总长（从 Content-Range/Length 学来）
    private String contentType = "application/octet-stream";
    private InputStream src;       // 源顺序流
    private long srcNext;          // 源流的下一个字节偏移
    private boolean srcEof;

    public MediaProxy(int capacityBytes) {
        super("media-proxy");
        setDaemon(true);
        capacity = capacityBytes;
        buf = new byte[capacityBytes];
    }

    /**
     * 把远端地址换成本地代理地址（幂等：同一个源重复调用会**保留缓冲**）。
     * 监听启动失败时原样返回 —— 降级直连，绝不能因为代理而投不了屏。
     */
    public synchronized String localize(String url) {
        ensureStarted();
        if (server == null) {
            return url;
        }
        setSource(url);
        return "http://127.0.0.1:" + port + "/media";
    }

    /** 释放会话与缓冲（换片 / Stop 时调用；重连路径**不要**调） */
    public synchronized void reset() {
        sourceUrl = null;
        epoch++;
        closeActiveClient();
        winStart = 0;
        winLen = 0;
        total = -1;
        closeSource();
    }

    /** 停止监听并断开服务线程（测试收尾用） */
    public synchronized void shutdown() {
        running = false;
        epoch++;
        closeActiveClient();
        closeSource();
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    private synchronized void setSource(String url) {
        if (url.equals(sourceUrl)) {
            return;   // 同源幂等：缓冲必须活着，这正是本类的价值
        }
        sourceUrl = url;
        epoch++;
        closeActiveClient();
        winStart = 0;
        winLen = 0;
        total = -1;
        closeSource();
    }

    private void ensureStarted() {
        if (server != null) {
            return;
        }
        try {
            server = new ServerSocket(0, 4,
                    InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            port = server.getLocalPort();
            running = true;
            start();
            Log.i(TAG, "本地预取代理已就绪 127.0.0.1:" + port
                    + "（缓冲 " + (capacity / 1024 / 1024) + "MB）");
        } catch (Exception e) {
            Log.w(TAG, "本地预取代理启动失败（降级直连）", e);
            server = null;
            port = -1;
        }
    }

    @Override
    public void run() {
        while (running) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                // **不能 break**：accept 偶发异常（fd 短暂紧张等）就永久退出的话，
                // 播放器连 127.0.0.1 会直接被拒，表现为「永远 TRANSITIONING」
                // 而日志里什么都看不到 —— 真机踩过。退避后继续监听。
                if (!running) {
                    break;
                }
                Log.w(TAG, "accept 异常（退避后继续）: " + e.getMessage());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
                continue;
            }
            if (!running) {
                break;
            }
            // 每请求一线程；新请求会抢占旧流（closeActiveClient）。
            // Seek 即重连，旧流通常已被播放器自己关掉，这里只是兜底。
            final Socket c = client;
            final long myEpoch = epoch;
            preemptOldClient();
            activeClient = c;
            serveThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(c, myEpoch);
                }
            }, "proxy-serve");
            serveThread.start();
        }
        Log.w(TAG, "代理监听线程退出");
    }

    /** 关掉正在服务的旧客户端（新请求抢占） */
    private void preemptOldClient() {
        Socket old = activeClient;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeActiveClient() {
        preemptOldClient();
    }

    private void closeSource() {
        if (src != null) {
            try {
                src.close();
            } catch (IOException ignored) {
            }
            src = null;
        }
        srcEof = false;
    }

    /**
     * 服务一个客户端请求：解析 Range → 对位窗口 → 边填边出，直到 EOS。
     */
    private void serve(Socket client, long myEpoch) {
        try {
            client.setSoTimeout(0);
            long offset = parseRange(client);
            Log.i(TAG, "服务请求 offset=" + offset + "（epoch=" + myEpoch + "）");
            String url = sourceUrl;
            long mySrc = epoch;
            if (url == null || mySrc != myEpoch) {
                client.close();
                return;
            }
            // 窗口对位：请求偏移在窗口内 → 复用缓冲（Seek 秒回）；
            // 在窗口外 → 丢弃窗口、从该偏移重新对源取数。
            boolean inWindow = offset >= winStart
                    && offset <= (long) winStart + winLen;
            if (!inWindow) {
                winStart = offset;
                winLen = 0;
                total = -1;
                closeSource();
            }
            if (src == null && !srcEof) {
                openSource(url, winStart + winLen);
            }
            writeHeaders(client, offset);
            OutputStream out = client.getOutputStream();
            long pos = offset;
            while (running && mySrc == epoch) {
                if (total > 0 && pos >= total) {
                    break;   // 片尾：EOS
                }
                if (pos >= (long) winStart + winLen) {
                    if (srcEof) {
                        break;   // 源取尽且窗口出尽 → EOS
                    }
                    if (!fillMore()) {
                        break;   // 取数失败：断开客户端，交给重连逻辑恢复
                    }
                    continue;
                }
                int idx = (int) (pos % capacity);
                int n = (int) Math.min((long) winStart + winLen - pos,
                        capacity - idx);
                n = Math.min(n, 32 * 1024);
                out.write(buf, idx, n);
                pos += n;
                // 消费推进窗口起点，腾出预取空间（先按 n 缩窗，再前移起点）
                winLen -= n;
                winStart = pos;
                // 顺手把窗口填满 —— 带宽快于播放速度时，预取始终领先
                fillMore();
            }
        } catch (Exception e) {
            // 客户端主动断开（Seek/Stop/EOS）会走到这 —— 正常路径，静默即可
            Log.i(TAG, "服务结束: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 从源流往窗口里填数据，直到窗口满或源尽。
     *
     * @return true = 窗口已尽力填满或源正常到尾；false = 取数失败
     *        （调用方应断开客户端，交给播放器的重连逻辑恢复 ——
     *         绝不能把「取数失败」伪装成「正常播完」截断数据）。
     */
    private boolean fillMore() throws IOException {
        while (winLen < capacity && !srcEof) {
            if (src == null) {
                openSource(sourceUrl, winStart + winLen);
                if (src == null) {
                    return false;   // 开源失败：断开客户端，让重连逻辑接手
                }
            }
            int idx = (int) ((winStart + winLen) % capacity);
            int room = Math.min(capacity - winLen, capacity - idx);
            int n = src.read(buf, idx, Math.min(room, 64 * 1024));
            if (n < 0) {
                srcEof = true;
                closeSource();
                return true;
            }
            winLen += n;
            srcNext += n;
        }
        return true;
    }

    /** 按偏移打开源流（Range 请求），并学习总长与 Content-Type */
    private void openSource(String url, long offset) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                Log.w(TAG, "源响应 " + code + "（offset=" + offset + "）");
                c.disconnect();
                src = null;
                return;
            }
            src = c.getInputStream();
            srcNext = offset;
            String type = c.getContentType();
            if (type != null) {
                contentType = type;
            }
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.indexOf('/');
                    if (slash >= 0) {
                        total = Long.parseLong(cr.substring(slash + 1));
                    }
                }
            } else {
                long cl = c.getContentLength();
                total = (offset == 0 && cl > 0) ? cl : -1;
            }
            srcEof = false;
        } catch (Exception e) {
            Log.w(TAG, "开源失败（offset=" + offset + "）: " + e.getMessage());
            src = null;
            // 刻意**不置 srcEof**：取数失败 ≠ 源已到尾。fillMore 返回 false
            // 让 serve 断开客户端，交给播放器的重连逻辑恢复。
        }
    }

    private void writeHeaders(Socket client, long offset) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (offset == 0) {
            sb.append("HTTP/1.1 200 OK\r\n");
            if (total > 0) {
                sb.append("Content-Length: ").append(total).append("\r\n");
            }
        } else {
            sb.append("HTTP/1.1 206 Partial Content\r\n");
            if (total > 0) {
                sb.append("Content-Range: bytes ").append(offset)
                  .append('-').append(total - 1).append('/').append(total).append("\r\n");
                sb.append("Content-Length: ").append(total - offset).append("\r\n");
            }
        }
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Connection: close\r\n\r\n");
        client.getOutputStream().write(sb.toString().getBytes("UTF-8"));
    }

    /** 解析 Range: bytes=N-，无 Range 头返回 0 */
    private static long parseRange(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] b = new byte[4096];
        while (sb.indexOf("\r\n\r\n") < 0 && sb.length() < 16384) {
            int n = in.read(b);
            if (n < 0) {
                break;
            }
            sb.append(new String(b, 0, n, "UTF-8"));
        }
        String req = sb.toString();
        if (req.startsWith("HEAD")) {
            throw new IOException("HEAD 不支持");
        }
        int i = req.indexOf("Range: bytes=");
        if (i < 0) {
            return 0;
        }
        int s = i + "Range: bytes=".length();
        int dash = req.indexOf('-', s);
        try {
            return Long.parseLong(req.substring(s, dash).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
