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

    /**
     * 每次接受新连接递增 —— 用来**顶掉**上一条 serve 线程。
     *
     * <p>本类的窗口状态是无锁共享的，设计前提是「同一时刻只有一条 serve 线程碰它」。
     * 光靠关旧客户端 socket 保证不了这一点（旧线程可能卡在源 I/O 上），所以：
     * {@code run()} 先 {@code ++serveGen} 让旧线程在循环里察觉自己已作废并立刻收工，
     * 再 {@link #supersedePrevServe()} 等它真正退出，最后才起新线程。
     */
    private volatile long serveGen;

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
            // 每请求一线程；新请求**顶掉**旧请求 —— 但必须先让旧线程真正退出，
            // 否则两条 serve 会并发碰同一份窗口状态（src / winStart / winLen /
            // total / buf…），表现为字节错乱、投屏花屏。
            //
            // Seek 在播放器看来就是「断开旧连接、带 Range 重连」，所以每次新请求
            // 都必须先顶掉上一条 —— 这正是原来漏掉的一步。
            supersedePrevServe();
            final Socket c = client;
            final long myGen = ++serveGen;
            activeClient = c;
            serveThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(c, myGen);
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

    /**
     * 顶掉上一条 serve 线程，并**等它真正退出**再返回。
     *
     * <p>为什么不能只关旧客户端 socket：旧线程可能正卡在**源 I/O** 上
     * （{@link #openSource} 的 {@code getResponseCode()} 或 {@link #fillMore}
     * 的 {@code src.read()}）—— 关客户端叫不醒它。必须连旧源流一起关，
     * 它才会立刻抛错退出。这是本方法相对 {@link #preemptOldClient()} 的关键差别。
     *
     * <p>为什么要 join：窗口状态无锁共享，前提是「同一时刻只有一条 serve 线程
     * 碰它」。旧线程没退干净就起新线程，就会两条线程一起推进窗口 → 字节错乱
     * （CI 上真实复现过：{@code len=1114112/1097152}）。join 把「旧线程已退出」
     * 变成新线程开工的**前置条件**，这个前提才真正成立。
     */
    private void supersedePrevServe() {
        Thread prev = serveThread;
        Socket old = activeClient;
        activeClient = null;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
        // 连旧源流一起关 —— 否则卡在 src.read() 的旧线程收不到任何信号。
        // 用「保留 EOF 标志」的版本：窗口（winStart/winLen）本就是要留给下一个
        // 请求复用的，而 srcEof 记的是「这块窗口是否已到源尾」，同样有效、同样
        // 该留。若在这里用会复位 srcEof 的 closeSource()，下一个命中窗口的请求
        // 会白开一次空源连接（srcEof=true 时窗口末端已在片尾，根本无需再取数）。
        closeSourceKeepEof();
        if (prev != null && prev != Thread.currentThread()) {
            try {
                // 正常会立刻返回（旧线程已被上面的关闭唤醒）。给个上限兜底：
                // 万一源端 close 不生效，也不至于把 accept 循环永久卡死。
                prev.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
     * 关闭源流但**保留** {@link #srcEof}。
     *
     * <p>{@link #closeSource()} 会把 {@code srcEof} 复位成 false —— 那是给
     * 「换源 / 换偏移、要重新取数」准备的。而 {@link #fillMore()} 读到源尾时
     * 需要的是「关掉流、但记住已经到尾了」。原来那里写成
     * {@code srcEof = true; closeSource();}，标志被当场抹掉，于是源到尾之后
     * **每个 32KB 写回都重开一次源连接**（末尾那次是「客户端已拿全数据、
     * serve 还空转重连」）—— 既浪费带宽，又拉长了旧 serve 线程的存活窗口，
     * 是并发竞态的放大器。这里单独留一个「保留 EOF 标志」的关闭。
     */
    private void closeSourceKeepEof() {
        if (src != null) {
            try {
                src.close();
            } catch (IOException ignored) {
            }
            src = null;
        }
    }

    /**
     * 服务一个客户端请求：解析 Range → 对位窗口 → 边填边出，直到 EOS。
     */
    private void serve(Socket client, long myGen) {
        try {
            client.setSoTimeout(0);
            long offset = parseRange(client);
            Log.i(TAG, "服务请求 offset=" + offset + "（gen=" + myGen + "）");
            // 开工前先确认自己没被更新的连接顶掉 —— 作废就别碰任何窗口状态。
            if (myGen != serveGen) {
                client.close();
                return;
            }
            String url = sourceUrl;
            long mySrc = epoch;
            if (url == null) {
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
            } else if (offset > winStart) {
                // **Seek 命中预取缓冲**（本代理的核心价值路径）：请求偏移落在窗口
                // 内部、但不是窗口首字节。此时必须把 [winStart, offset) 这段前缀
                // 从窗口里丢掉，让 winStart 与随后的 pos **对齐**。
                //
                // 不丢会怎样：下面的消费记账是「winLen -= n; winStart = pos」，
                // 它隐含前提 pos == winStart。若带着前缀就开写，pos 一上来就比
                // winStart 大 (offset - winStart)，每写一块 winStart 前移 n 而
                // winLen 只减 n —— 窗口末端 (winStart + winLen) 被**虚增**
                // (offset - winStart)。虚增之后写回循环里对 n 的夹取
                // (winStart + winLen - pos) 随之失效，会一路写到超过片尾 total 的
                // 偏移，客户端收到的字节数多于应得 → 花屏。CI 上真实复现过：
                // len=1114112/1097152（多出一个 32KB 块）。
                winLen -= (int) (offset - winStart);
                winStart = offset;
            }
            if (src == null && !srcEof) {
                openSource(url, winStart + winLen);
            }
            writeHeaders(client, offset);
            OutputStream out = client.getOutputStream();
            long pos = offset;
            while (running && mySrc == epoch) {
                // 被更新的连接顶掉 → 立刻收工，绝不再碰窗口状态。
                // 这是「旧线程退出」的主动信号：不依赖关闭 socket 触发的异常，
                // 让旧线程在它自己的循环里就能干净地停手。
                if (myGen != serveGen) {
                    break;
                }
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
                // 不能走会清 srcEof 的 closeSource()（它把标志复位成 false）——
                // 那样 EOF 标志被当场抹掉，源到尾之后每个 32KB 写回都重开一次源连接。
                srcEof = true;
                closeSourceKeepEof();
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
