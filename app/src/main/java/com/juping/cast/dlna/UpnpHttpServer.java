package com.juping.cast.dlna;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UPnP 的 HTTP + SOAP 服务端。
 *
 * <p>SSDP 负责「被搜到」，这个类负责「搜到之后的一切」：
 * <ul>
 *   <li>GET  /upnp/device.xml          —— 设备描述，控制点靠它了解我们是什么设备</li>
 *   <li>GET  /upnp/{service}.xml       —— 服务描述（SCPD），控制点靠它了解能调哪些指令</li>
 *   <li>POST /upnp/control/{service}   —— 控制指令（SetAVTransportURI / Play / Pause / Stop / Seek）</li>
 *   <li>SUBSCRIBE / UNSUBSCRIBE        —— 事件订阅（GENA），交给 {@link EventDispatcher} 真正推 NOTIFY</li>
 * </ul>
 *
 * <p>用 {@link CommandHandler} 把「协议解析」和「业务动作」解耦：
 * 这个类只负责把 SOAP 报文翻译成方法调用，具体播放行为交给上层。
 */
public class UpnpHttpServer extends Thread {

    private static final String TAG = "UpnpHttpServer";

    /** 控制点发来的业务指令回调 */
    public interface CommandHandler {
        /** SetAVTransportURI —— 收到要播放的 URL，这是投屏的入口 */
        void onSetUri(String uri, String metadata);

        void onPlay();

        void onPause();

        void onStop();

        void onSeek(long positionMs);

        /** GetPositionInfo 用 */
        long getPositionMs();

        long getDurationMs();

        /** GetTransportInfo 用，返回 UPnP 标准状态串 */
        String getTransportState();

        /**
         * GetTransportInfo 用 —— 传输状态是否出错（UPnP 标准串：
         * {@code OK} / {@code ERROR_OCCURRED}）。
         *
         * <p>它必须和事件里的 {@code TransportStatus} 用<b>同一个判据</b>。
         * 之前这里没有这个方法，GetTransportInfo 把 CurrentTransportStatus
         * 写死成 {@code OK}，而事件那边已经改成如实报 —— 于是同一个设备、
         * 同一个时刻，两个接口给出相反的答案：靠轮询的控制点以为一切正常，
         * 靠事件的控制点知道在出错。排查时两边说法不一致，反而把线索搅浑。
         *
         * @return 非 null、非空的标准串
         */
        String getTransportStatus();

        /**
         * GetMediaInfo / GetPositionInfo 用 —— 当前正在播放的媒体 URL。
         *
         * <p>这个方法之前漏在接口外面，导致 GetMediaInfo 永远回一个空的
         * {@code <CurrentURI></CurrentURI>}，**哪怕视频正在播**。规范要求
         * CurrentURI 反映当前媒体；只有 {@code NO_MEDIA_PRESENT} 时才允许为空。
         *
         * <p>后果不是"少一个字段"这么轻：部分投屏 SDK 会在 SetAVTransportURI
         * 之后回读 GetMediaInfo，拿 CurrentURI 与自己刚推的地址比对，不一致就
         * 判定"这台设备没接收成功"，于是把画面停在手机上不投了。
         *
         * @return 当前媒体 URL；无媒体时返回空串（不是 null）
         */
        String getCurrentUri();

        /**
         * GetMediaInfo / GetPositionInfo 用 —— 当前媒体的元数据原文。
         *
         * <p>必须回**控制点推来的那一份**，不能回空、也不能回我们自己重拼的。
         * 部分控制点（尤其依赖回读确认的那些）会拿它和自己刚推的比对 ——
         * 回空的话它会认为"这台设备没接收成功"，画面留在手机上不投了；
         * 回一个语义等价但格式不同的版本，同样可能被判成不一致。
         *
         * @return 元数据原文；无媒体时返回空串（不是 null）
         */
        String getCurrentMetadata();

        void onSetVolume(int volume0to100);

        int getVolume0to100();

        /**
         * SetMute —— 设置静音。
         *
         * <p>这个方法之前**整个漏了**：{@code SetMute} 写在 {@code KNOWN_ACTIONS}
         * 里，但 {@code dispatch()} 没有对应分支，于是控制点按静音什么都不会发生。
         * 而 {@code GetMute} 又写死回 {@code 0}（未静音）—— 控制点按完静音回读一次，
         * 看到的是"没静音"，把开关又画回去。**和已经修过的 GetVolume 恒回 100
         * 是同一个 bug**，只是漏了静音那半边。
         */
        void onSetMute(boolean mute);

        /** GetMute 用。必须反映真实状态，不能恒回 false */
        boolean getMute();
    }

    private final int port;
    private final String uuid;
    private final String friendlyName;
    /**
     * 应用版本名（如 "0.1.4"）。
     *
     * <p>device.xml 的 {@code modelNumber} 与响应的 {@code Server} 头都来自它。
     * 之前这里写死 "1.0"：排障时在 device.xml 里看到的版本号和实际装的不一致，
     * 「对着版本号复现问题」就成了一句空话。由 {@code DlnaRendererService} 从
     * {@code BuildConfig.VERSION_NAME} 传入 —— 构造注入而不是静态读，是为了
     * 让协议测试能喂入自己的测试版本并断言它（见 drive.py 的 modelNumber 断言）。
     */
    private final String versionName;
    private final CommandHandler handler;

    /** GENA 事件分发。订阅状态、SEQ、超时都在这里面。 */
    private final EventDispatcher events;

    private volatile boolean running = true;
    private ServerSocket serverSocket;

    /**
     * 请求体上限（字节）。
     *
     * <p>为什么必须有：{@code Content-Length} 是**控制点说了算**的。原来直接
     * {@code new byte[contentLength]} —— 声明 2GB 就当场 OutOfMemoryError。
     * 这台盒子只有 0.6GB 内存，一次就够把整个进程干掉，SSDP 一起陪葬。
     * 一个畸形的（或恶意的）请求就能做到，不需要什么高深攻击。
     *
     * <p>256KB 的依据：DLNA 的 body 只有 SOAP 指令和 DIDL-Lite 元数据，
     * 中文片名再多也就几 KB。UPnP 规范里也没有大 body 的用法。
     */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /**
     * 同时在处理的连接数上限。
     *
     * <p>每个连接一个线程，而线程默认栈 1MB —— 0.6GB 的盒子上，
     * 无上限的话一个端口扫描器（或一个卡住的异常控制点）就能把内存吃光，
     * 连累的是整个进程：SSDP 也一起死。
     *
     * <p>16 足够：控制点并发量本来就极低（同一时刻通常 1~2 条），
     * 留了一个数量级的余量。
     */
    private static final int MAX_CONNECTIONS = 16;

    /** 当前活跃连接数。加一在 {@link #run()}，减一在 {@link #handleConnection} 的 finally */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 端口是否已经真正绑上。
     *
     * <p>存在的理由：SSDP 和 HTTP 是两条独立的链路，**死一条另一条照活**。
     * HTTP 没绑上时，手机搜得到设备（SSDP 正常应答），却取不到 device.xml、
     * 一条 SOAP 指令都发不进来 —— 表现就是「搜得到但投不上去」。
     * 界面必须能把这种情况如实报出来，而不是一律显示"已就绪"。
     */
    private volatile boolean bound;

    public UpnpHttpServer(int port, String uuid, String friendlyName, String versionName,
                          CommandHandler handler, EventDispatcher.EventSource eventSource) {
        super("upnp-http");
        setDaemon(true);
        this.port = port;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.versionName = versionName;
        this.handler = handler;
        this.events = new EventDispatcher(uuid, eventSource);
    }

    /**
     * Server 头里的产品段。
     *
     * <p>之前 "Juping/1.0" 以字符串字面量散落在四处（writeSimple /
     * SUBSCRIBE 响应 / UNSUBSCRIBE 响应 / writeStatic）—— 升版本时漏改
     * 任何一处，同一个设备在不同协议路径上就报两个版本号。收口成一个
     * 方法，与 {@link #versionName} 单一来源。SSDP 侧的 SERVER 头在
     * {@link SsdpResponder}，那边同样收构造注入的版本号。
     */
    private String serverProduct() {
        return "Juping/" + versionName;
    }

    /**
     * 状态变了，推给订阅了该服务的控制点。
     *
     * <p>业务层只调这一个方法，不必自己持有 {@link EventDispatcher} ——
     * 订阅的生死由 HTTP 层管，业务层管不着也不该管。
     */
    public void notifyEvent(String service) {
        events.notifyAll(service);
    }

    /** 暴露给测试与排障用（看当前有几个订阅者）。 */
    public EventDispatcher getEventDispatcher() {
        return events;
    }

    /**
     * HTTP 服务是否真的在监听。
     *
     * <p>没绑上 = 手机取不到设备描述、发不进 SOAP 指令，投屏必然失败。
     * 界面据此如实显示状态 —— 只看 SSDP 是发现不了这个故障的，
     * 而它的症状（搜得到设备却投不上去）恰恰最容易被误判成"手机的问题"。
     */
    public boolean isBound() {
        return bound && serverSocket != null && !serverSocket.isClosed();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            // bind 成功之后必须**立刻**复查 running。
            //
            // 竞态：shutdown() 可能正好落在「构造 ServerSocket」与「这一句」之间。
            // 那一刻 serverSocket 字段还是 null，shutdown 里那句 close 被跳过，
            // 而这个线程紧接着就把 49152 绑上了 —— 端口被永久占住。
            // 下次服务启动时 bind 直接 EADDRINUSE，HTTP 层彻底死掉，
            // 而 SSDP 还活着 —— 表现就是「手机搜得到设备，但一点投屏就失败」，
            // 且重启 App 也救不回来（端口一直占着）。
            if (!running) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
                Log.i(TAG, "启动途中已被要求关闭，端口 " + port + " 已释放");
                return;
            }
            bound = true;
            Log.i(TAG, "UPnP HTTP 服务已启动，端口 " + port);
            while (running) {
                final Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    if (!running) {
                        break;
                    }
                    Log.w(TAG, "accept 异常: " + e.getMessage());
                    continue;
                }
                // 每个连接一个线程。控制点并发量很低，这样足够且实现最简单。
                //
                // 但必须**有上限**：线程默认栈 1MB，而老盒子只有 0.6GB 内存。
                // 无上限的话一个端口扫描就能把内存吃光，整个进程（连带 SSDP）一起死。
                // 到顶时直接关掉连接 —— 比排一个无限长的队、最后 OOM 强得多。
                if (activeConnections.get() >= MAX_CONNECTIONS) {
                    Log.w(TAG, "并发连接已达上限 " + MAX_CONNECTIONS + "，拒绝新连接");
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                activeConnections.incrementAndGet();
                boolean started = false;
                try {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleConnection(socket);
                        }
                    }, "upnp-conn").start();
                    started = true;
                } finally {
                    // 线程没起来就把计数还回去 —— 不然几次失败之后计数永远顶在上限，
                    // HTTP 层从此一个连接都不接，表现为「搜得到设备却投不了屏」。
                    if (!started) {
                        activeConnections.decrementAndGet();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP 服务异常退出", e);
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(10000);

            // 整个请求按**字节**读，不要用 BufferedReader。
            //
            // 这里踩过一个致命的坑：Content-Length 是**字节数**，而 Reader 数的是
            // **字符数**。用 `in.read(char[], off, contentLength)` 的话，只要 body 里有
            // 非 ASCII 字符（UTF-8 下一个汉字占 3 字节），读到的字符数就永远凑不够
            // contentLength，于是阻塞在 read 上直到 socket 超时 —— 结果是连接被关掉，
            // **一个字节的响应都不发**，控制点那边表现为「投屏失败」。
            //
            // 而 SetAVTransportURI 的 CurrentURIMetaData 是一段 DIDL-Lite XML，
            // 里面几乎必然带着中文片名。也就是说这个 bug 会让绝大多数中文视频
            // 的投屏直接失败，而日志里只留下一句 Read timed out。
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);

            // ---- 逐字节读请求头，直到空行 ----
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream(1024);
            int c;
            int p1 = -1, p2 = -1, p3 = -1;
            while ((c = in.read()) >= 0) {
                headBuf.write(c);
                if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') {
                    break;
                }
                p3 = p2;
                p2 = p1;
                p1 = c;
            }

            // 请求头是纯 ASCII（字段名与值都是 token / URI），用 ISO-8859-1 解不会有
            // 编码歧义，也不会因为字节被当成非法 UTF-8 而替换成 U+FFFD
            String head = new String(headBuf.toByteArray(), "ISO-8859-1");
            String[] headLines = head.split("\r\n");
            if (headLines.length == 0 || headLines[0].trim().length() == 0) {
                return;
            }
            String requestLine = headLines[0];
            Log.d(TAG, "<< " + requestLine);

            // 用 \s+ 而不是 " "：请求行里多余的空格（"GET  /x HTTP/1.1"）
            // 会让 split(" ") 切出空串，path 变成 ""，直接 404。
            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String path = normalizePath(parts[1]);

            int contentLength = 0;
            String soapAction = null;
            // ---- GENA 订阅用的头。SUBSCRIBE 没有 body，全部信息都在这几个头里 ----
            String callbackHeader = null;   // <http://ip:port/path>，可含多个
            String ntHeader = null;         // 新订阅必带，值应为 upnp:event
            String sidHeader = null;        // 续订 / 退订必带
            String timeoutHeader = null;    // Second-1800 或 Second-infinite
            for (int i = 1; i < headLines.length; i++) {
                String line = headLines[i];
                if (line.length() == 0) {
                    continue;
                }
                // 必须指定 Locale.ROOT。HTTP 头名是 ASCII 协议字段，不属于任何自然语言。
                // 用默认 locale 的话，土耳其语环境里 "Content-Length".toLowerCase() 会得到
                // "content-length" 之外的怪东西（I -> ı），头名匹配不上 → 读不到 body →
                // SOAP 控制命令全部静默失败，而日志里看起来一切正常。
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = parseInt(line.substring(15).trim(), 0);
                } else if (lower.startsWith("soapaction:")) {
                    soapAction = line.substring(11).trim().replace("\"", "");
                } else if (lower.startsWith("callback:")) {
                    callbackHeader = line.substring(9).trim();
                } else if (lower.startsWith("nt:")) {
                    ntHeader = line.substring(3).trim();
                } else if (lower.startsWith("sid:")) {
                    sidHeader = line.substring(4).trim();
                } else if (lower.startsWith("timeout:")) {
                    timeoutHeader = line.substring(8).trim();
                }
            }

            // ---- 先卡住 body 上限，再分配内存 ----
            //
            // 顺序是硬要求：先 new byte[contentLength] 再检查的话，
            // 检查本身就已经 OOM 了。Content-Length 完全由控制点决定，
            // 一个畸形请求声明 2GB 就够把 0.6GB 的盒子当场打死。
            if (contentLength < 0) {
                // 负的 Content-Length 不是合法值。当 0 处理 —— 直接拿去 new byte[]
                // 会抛 NegativeArraySizeException，白打一条吓人的异常日志。
                contentLength = 0;
            }
            if (contentLength > MAX_BODY_BYTES) {
                Log.w(TAG, "Content-Length=" + contentLength + " 超过上限 "
                        + MAX_BODY_BYTES + "，拒绝（畸形或异常请求）");
                OutputStream reject = socket.getOutputStream();
                writeSimple(reject, "413 Request Entity Too Large", "text/plain", "");
                reject.flush();
                return;
            }

            // ---- 按字节精确读 body：一个字节不多、一个字节不少 ----
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(bodyBytes, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            // body 才是可能含非 ASCII 的部分，按 UTF-8 解
            String body = new String(bodyBytes, 0, read, "UTF-8");

            OutputStream out = socket.getOutputStream();
            String initialSid = null;
            if ("GET".equals(method)) {
                handleGet(path, out);
            } else if ("POST".equals(method)) {
                handlePost(path, soapAction, body, out);
            } else if ("SUBSCRIBE".equals(method)) {
                initialSid = handleSubscribe(path, callbackHeader, ntHeader, sidHeader,
                        timeoutHeader, out);
            } else if ("UNSUBSCRIBE".equals(method)) {
                handleUnsubscribe(sidHeader, out);
            } else if ("HEAD".equals(method)) {
                handleHead(path, out);
            } else {
                writeSimple(out, "405 Method Not Allowed", "text/plain", "");
            }
            out.flush();
            // 初始事件必须在 200 响应**真正发出去之后**再推。
            // 控制点是拿响应里的 SID 来认这条 NOTIFY 的；早到就会被当成未知 SID
            // 丢掉，于是它永远拿不到初始状态。详见 EventDispatcher#subscribe。
            if (initialSid != null) {
                events.fireInitial(initialSid);
            }
        } catch (Exception e) {
            Log.w(TAG, "处理连接出错", e);
        } finally {
            // 计数必须在这里还 —— 和 run() 里的 incrementAndGet 配对。
            // 漏掉的话计数只增不减，十几次之后 HTTP 层就再也不接连接了。
            activeConnections.decrementAndGet();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- GET

    /**
     * 把请求目标规整成「以 / 开头的路径」。
     *
     * <p>HTTP/1.1 允许请求行里写**绝对形式**的 URI（RFC 7230 §5.3.2）：
     * <pre>GET http://192.168.1.50:49152/upnp/device.xml HTTP/1.1</pre>
     * 而服务端**必须**接受这种写法。部分控制点（尤其是嵌入式协议栈和
     * 某些 Windows 组件）确实这么发 —— 原来直接拿 parts[1] 当路径比较，
     * 绝对形式下 startsWith("/upnp/device.xml") 为假，于是设备描述返回 404，
     * 手机端表现为「搜到了设备但投不了屏」，而日志里只有一句 404。
     */
    private static String normalizePath(String target) {
        if (target == null || target.length() == 0) {
            return "/";
        }
        // 绝对形式：剥掉 scheme://authority 前缀
        int scheme = target.indexOf("://");
        if (scheme >= 0) {
            int slash = target.indexOf('/', scheme + 3);
            target = (slash >= 0) ? target.substring(slash) : "/";
        }
        // 丢掉查询串和片段，它们不参与路由
        int cut = target.indexOf('?');
        if (cut < 0) {
            cut = target.indexOf('#');
        }
        if (cut >= 0) {
            target = target.substring(0, cut);
        }
        if (target.length() == 0) {
            return "/";
        }
        // 容忍没写前导斜杠的写法
        return target.startsWith("/") ? target : "/" + target;
    }

    /**
     * GET / HEAD 的静态资源解析结果。
     *
     * <p>HTTP 规范（RFC 7231 §4.3.2）要求 HEAD 与 GET **除了没有 body 之外
     * 完全一致**——状态码、Content-Type、Content-Length 都必须相同。之前
     * HEAD 一律回「200 + Content-Length: 0」：有控制点用 HEAD 探测
     * device.xml / 图标，拿到长度 0 会把「0 字节的描述」当成有效结果，
     * 或者干脆判定设备异常。路由逻辑必须与 GET 同源，不能写两份。
     */
    private static final class StaticResource {
        final boolean found;
        final String contentType;
        final byte[] payload;

        StaticResource(boolean found, String contentType, byte[] payload) {
            this.found = found;
            this.contentType = contentType;
            this.payload = payload;
        }
    }

    /** GET 与 HEAD 共用的路由判断。改动路由必须只改这一处。 */
    private StaticResource resolveStatic(String path) {
        if (path.startsWith("/upnp/device.xml") || path.equals("/")) {
            return new StaticResource(true, "text/xml; charset=\"utf-8\"",
                    utf8(buildDeviceDescription()));
        }
        if (path.contains("AVTransport.xml")) {
            return new StaticResource(true, "text/xml; charset=\"utf-8\"", utf8(SCPD_AV_TRANSPORT));
        }
        if (path.contains("ConnectionManager.xml")) {
            return new StaticResource(true, "text/xml; charset=\"utf-8\"", utf8(SCPD_CONNECTION_MANAGER));
        }
        if (path.contains("RenderingControl.xml")) {
            return new StaticResource(true, "text/xml; charset=\"utf-8\"", utf8(SCPD_RENDERING_CONTROL));
        }
        if (ICON_PATH.equals(path)) {
            // 图标走字节，不走 String —— PNG 用 UTF-8 编一遍再解回来会被
            // 替换字符毁掉，控制点拿到的就不是一张图了。
            byte[] png = iconPng;
            if (png == null) {
                // 没图标。**必须是 404**，不能回一个空 body 的 200 ——
                // 后者会让控制点以为"图片是 0 字节"，行为比 404 更难预料。
                return new StaticResource(false, "text/plain", new byte[0]);
            }
            return new StaticResource(true, "image/png", png);
        }
        return new StaticResource(false, "text/plain", new byte[0]);
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    private void handleGet(String path, OutputStream out) throws IOException {
        writeStatic(out, resolveStatic(path), true);
    }

    private void handleHead(String path, OutputStream out) throws IOException {
        writeStatic(out, resolveStatic(path), false);
    }

    /**
     * 写 GET / HEAD 响应。{@code withBody=false} 时只写头 ——
     * 但 Content-Length 保留 GET 的值，这正是 HEAD 语义的全部意义。
     */
    private void writeStatic(OutputStream out, StaticResource r, boolean withBody)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.found ? "200 OK" : "404 Not Found").append("\r\n");
        sb.append("Content-Type: ").append(r.contentType).append("\r\n");
        sb.append("Content-Length: ").append(r.payload.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Server: Android UPnP/1.0 ").append(serverProduct()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (withBody && r.payload.length > 0) {
            out.write(r.payload);
        }
    }

    // ------------------------------------------------------------- 设备图标

    /**
     * 设备图标的路径。
     *
     * <p>只服务**一张**图，尺寸以 {@link #setIcon} 传进来的实际像素为准。
     *
     * <p>为什么不按密度声明四档（48/72/96/144）：{@code R.drawable.ic_launcher}
     * 在运行时**只会解析成当前屏幕密度的那一张** —— 四档拿到的是同一个 Bitmap。
     * 声明四个尺寸就是在撒谎，而控制点会照声明去挑，挑中的那张尺寸对不上。
     */
    private static final String ICON_PATH = "/upnp/icon.png";

    /**
     * 图标 PNG 字节。{@code null} 表示没有 —— 此时设备描述里
     * **完全不声明 iconList**，上面那个路径也就永远是 404。
     */
    private volatile byte[] iconPng;
    private volatile int iconWidth;
    private volatile int iconHeight;

    /**
     * 提供设备图标（可选）。
     *
     * <p>协议层拿不到 Android 资源，图标必须由业务层解码好递进来。
     *
     * <p>为什么"没有"比"声明一个取不到的地址"好：控制点拿到 device.xml 之后
     * 会**真的去 GET** iconList 里那个地址。404 在它的日志里就是一条
     * 「设备描述与实现不一致」的记录。声明了就要给得出，给不出就别声明 ——
     * 和 {@link #SINK_PROTOCOL_INFO} 是同一条纪律。
     *
     * <p>参数不合法时**静默忽略**（保持"没有图标"）：图标是纯装饰，
     * 绝不能因为它让设备描述出不来 —— device.xml 拉不到等于整个设备不可用。
     */
    public void setIcon(byte[] png, int width, int height) {
        if (png == null || png.length == 0 || width <= 0 || height <= 0) {
            return;
        }
        this.iconWidth = width;
        this.iconHeight = height;
        this.iconPng = png;
    }

    /** 项目主页。厂商 URL 与型号 URL 都指向它 —— 这是这台设备真正的"出处" */
    private static final String PROJECT_URL = "https://github.com/yycqyjq/Juping";

    /**
     * DLNA 设备类别声明。
     *
     * <p>{@code DMR-1.50} = Digital Media Renderer，DLNA 1.5 版规范里的渲染器类别。
     *
     * <p>这一条**必须有**。部分控制点（较新的国产投屏 SDK 尤其）先看这个标记，
     * 认不出就不把设备列进投屏列表 —— 表现为「SSDP 明明应答了，列表里却没有」，
     * 而其余字段写得再全也没用。缺了它是最容易被忽略、后果又最彻底的一种缺。
     *
     * <p>只声明 {@code DMR-1.50}，**不声明** {@code M-DMR-1.50}：后者是
     * DLNA Mobile 的类别，声明了会让控制点按移动设备的规则来对待我们
     * （比如假定有触摸屏、假定省电策略不同）。不是移动设备就别领那个标记。
     */
    private static final String DLNA_DOC = "DMR-1.50";

    /**
     * 设备描述（DDD）—— 控制点了解"这台设备是什么"的唯一来源。
     *
     * <p><b>元素顺序不是随便排的。</b>UPnP 的 device-1-0 schema 对
     * {@code <device>} 的子元素定死了顺序，严格按 schema 校验的控制点
     * （部分嵌入式协议栈）会因为顺序错而**整份描述解析失败** —— 不是
     * "少读一个字段"，是这台设备在它眼里不存在。顺序取自 UDA 1.0 的
     * device-1-0 schema：
     * <pre>
     *   deviceType, friendlyName, manufacturer, manufacturerURL?, modelDescription?,
     *   modelName, modelNumber?, modelURL?, serialNumber?, UDN, UPC?,
     *   iconList?, serviceList?, deviceList?, presentationURL?, (其它命名空间)*
     * </pre>
     * {@code dlna:X_DLNADOC} 属于最后那类"其它命名空间"，所以放最后 ——
     * MiniDLNA 与多数商用 DMR 的实际排法也是如此。
     */
    private String buildDeviceDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n")
                .append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
                .append("  <device>\n")
                .append("    <deviceType>").append(SsdpResponder.DEVICE_TYPE).append("</deviceType>\n")
                // friendlyName 是用户自己可能改过的（将来若要支持改名），
                // 所以必须转义 —— 一个 & 就能让整份描述变成非法 XML。
                .append("    <friendlyName>").append(escapeXml(friendlyName))
                .append("</friendlyName>\n")
                .append("    <manufacturer>Juping</manufacturer>\n")
                .append("    <manufacturerURL>").append(PROJECT_URL).append("</manufacturerURL>\n")
                .append("    <modelDescription>DLNA/UPnP 投屏接收端</modelDescription>\n")
                .append("    <modelName>Juping Receiver</modelName>\n")
                .append("    <modelNumber>").append(escapeXml(versionName))
                .append("</modelNumber>\n")
                .append("    <modelURL>").append(PROJECT_URL).append("</modelURL>\n")
                // serialNumber 用设备自己的 UDN 值。它本来就是"这台设备在这个
                // 网络里的唯一编号"，而且跨重启稳定（UUID 持久化在 SharedPreferences 里）。
                // 编一个假的流水号没有任何好处 —— 排障时能对上号才有意义。
                .append("    <serialNumber>").append(escapeXml(uuid)).append("</serialNumber>\n")
                .append("    <UDN>uuid:").append(uuid).append("</UDN>\n");
        // UPC：我们不是零售商品，没有 UPC 码。**刻意不写** ——
        // 编一个假码没有任何好处，而 schema 里它是可选的。
        appendIconList(sb);
        sb.append("    <serviceList>\n")
                .append(serviceEntry("AVTransport"))
                .append(serviceEntry("ConnectionManager"))
                .append(serviceEntry("RenderingControl"))
                .append("    </serviceList>\n")
                // presentationURL：**刻意不声明**。它只有一个含义 ——
                // "用浏览器打开这里看设备信息"。我们没有任何 Web 界面，
                // 写 "/" 只会把 device.xml 本身喂给浏览器（一屏原始 XML）。
                // schema 里它是可选的：不声明，控制点就不画那个按钮；
                // 画一个点开是乱码的按钮，比没有按钮糟。
                .append("    <dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
                .append(DLNA_DOC).append("</dlna:X_DLNADOC>\n")
                .append("  </device>\n")
                .append("</root>\n");
        return sb.toString();
    }

    /**
     * 有图标才写 iconList。
     *
     * <p>{@code <icon>} 的子元素顺序同样是 schema 定死的：
     * mimetype, width, height, depth, url。
     *
     * <p>{@code depth} 报 32：图标是 PNG RGBA（8 位/通道 × 4 通道），
     * 这是**实测值**，不是照抄别人的 24。控制点一般不校验它，
     * 但既然写了就写真的 —— 这个项目里没有"随手填一个看着合理"的字段。
     */
    private void appendIconList(StringBuilder sb) {
        if (iconPng == null) {
            return;
        }
        sb.append("    <iconList>\n")
                .append("      <icon>\n")
                .append("        <mimetype>image/png</mimetype>\n")
                .append("        <width>").append(iconWidth).append("</width>\n")
                .append("        <height>").append(iconHeight).append("</height>\n")
                .append("        <depth>32</depth>\n")
                .append("        <url>").append(ICON_PATH).append("</url>\n")
                .append("      </icon>\n")
                .append("    </iconList>\n");
    }

    /**
     * 一条 service 记录。
     *
     * <p><b>子元素顺序也是 schema 定死的</b>：serviceType → serviceId →
     * SCPDURL → controlURL → eventSubURL。已按 UDA 1.0 的 device-1-0 schema
     * 核对，并与 gmrender-resurrect（成熟 DMR 实现）的实际输出一致。
     *
     * <p>MiniDLNA 用的是 controlURL → eventSubURL → SCPDURL，那是它的历史写法，
     * 多数控制点宽容接受，但没有理由跟着走。
     */
    private String serviceEntry(String shortName) {
        String type = "urn:schemas-upnp-org:service:" + shortName + ":1";
        return "      <service>\n"
                + "        <serviceType>" + type + "</serviceType>\n"
                + "        <serviceId>urn:upnp-org:serviceId:" + shortName + "</serviceId>\n"
                + "        <SCPDURL>/upnp/" + shortName + ".xml</SCPDURL>\n"
                + "        <controlURL>/upnp/control/" + shortName + "</controlURL>\n"
                + "        <eventSubURL>/upnp/event/" + shortName + "</eventSubURL>\n"
                + "      </service>\n";
    }

    // ------------------------------------------------- SUBSCRIBE（GENA 事件）

    /**
     * 我们在 device.xml 里声明了这三个服务，SUBSCRIBE 只能指向它们。
     *
     * <p>不校验的话，控制点拼错一个服务名也能拿到 SID，然后永远收不到事件 ——
     * 它只会觉得"设备事件坏了"，而日志里一切正常。宁可当场回 404。
     */
    private static boolean isKnownService(String s) {
        return "AVTransport".equals(s) || "ConnectionManager".equals(s)
                || "RenderingControl".equals(s);
    }

    /**
     * 处理 SUBSCRIBE。同一个方法名承担两种语义，靠头区分
     * （UPnP Device Architecture 1.0 §4.1）：
     * <ul>
     *   <li>带 CALLBACK、不带 SID —— **新订阅**</li>
     *   <li>带 SID、不带 CALLBACK —— **续订**</li>
     * </ul>
     * 两者都有或都没有都是非法请求，回 412。
     *
     * @return 新订阅的 SID —— 调用方必须在写完 200 响应之后再推初始事件；
     *         续订或失败时返回 null（失败响应已经在这里写掉了）
     */
    private String handleSubscribe(String path, String callback, String nt, String sid,
                                   String timeout, OutputStream out) throws IOException {
        String service = lastSegment(path);
        boolean hasCallback = callback != null && callback.length() > 0;
        boolean hasSid = sid != null && sid.length() > 0;

        if (hasCallback == hasSid) {
            Log.w(TAG, "SUBSCRIBE 头非法：CALLBACK 有=" + hasCallback + "，SID 有=" + hasSid);
            writeStatus(out, "412 Precondition Failed");
            return null;
        }
        if (!isKnownService(service)) {
            Log.w(TAG, "SUBSCRIBE 指向未知服务: " + path);
            writeStatus(out, "404 Not Found");
            return null;
        }

        if (hasSid) {
            long t = events.renew(sid, timeout);
            if (t < 0) {
                Log.w(TAG, "续订被拒，SID 不认识: " + sid);
                writeStatus(out, "412 Precondition Failed");
                return null;
            }
            writeSubscribeOk(out, sid, t);
            return null;
        }

        // NT 必须是 upnp:event。缺失时宽容处理 —— 少数控制点不发这个头，
        // 为了一个可推断的字段把订阅拒掉不值得；但值不对就必须拒。
        if (nt != null && nt.length() > 0 && !"upnp:event".equals(nt)) {
            Log.w(TAG, "SUBSCRIBE 的 NT 不是 upnp:event: " + nt);
            writeStatus(out, "412 Precondition Failed");
            return null;
        }

        String newSid = events.subscribe(service, callback, timeout);
        if (newSid == null) {
            Log.w(TAG, "CALLBACK 里解析不出可用地址: " + callback);
            writeStatus(out, "412 Precondition Failed");
            return null;
        }
        writeSubscribeOk(out, newSid, events.timeoutOf(newSid));
        return newSid;
    }

    private void handleUnsubscribe(String sid, OutputStream out) throws IOException {
        if (sid == null || sid.length() == 0) {
            writeStatus(out, "412 Precondition Failed");
            return;
        }
        if (!events.unsubscribe(sid)) {
            // 规范：SID 不认识时回 412，而不是 404 —— 404 是"路径不存在"，
            // 而这里路径是对的，只是订阅已经没了（过期或已退订）。
            Log.w(TAG, "退订被拒，SID 不认识: " + sid);
            writeStatus(out, "412 Precondition Failed");
            return;
        }
        writeStatus(out, "200 OK");
    }

    private void writeSubscribeOk(OutputStream out, String sid, long timeoutSec)
            throws IOException {
        String resp = "HTTP/1.1 200 OK\r\n"
                + "SID: " + sid + "\r\n"
                + "TIMEOUT: Second-" + timeoutSec + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "Server: Android UPnP/1.0 " + serverProduct() + "\r\n\r\n";
        out.write(resp.getBytes("UTF-8"));
    }

    /** 无 body 的状态响应。412 / 404 / 200 都用它。 */
    private void writeStatus(OutputStream out, String status) throws IOException {
        String resp = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "Server: Android UPnP/1.0 " + serverProduct() + "\r\n\r\n";
        out.write(resp.getBytes("UTF-8"));
    }

    // --------------------------------------------------------------- POST

    /**
     * 能应答的 action 白名单。不在这张表里的按 UPnP 规范回 401 Fault。
     *
     * <p>为什么要有这张表：原来对未知 action 是「回 200 + 空参数」。
     * 后果是控制点发来一个我们根本不认识的指令，却收到一个语法上合法、
     * 语义上毫无意义的响应 —— 手机端只能显示一个笼统的失败，问题无从定位。
     * 规范要求回 401 Invalid Action，这样控制点至少能给出准确的原因。
     *
     * <p>表里除了真正实现的指令，还刻意收了一批「无副作用的探测指令」
     * （GetTransportSettings / GetDeviceCapabilities / ListPresets 等）——
     * 它们不需要真正做什么，但控制点经常会先问一遍。对这些回 401 会让
     * 手机端误判成「这台设备有问题」，所以给它们一个合法的空响应。
     */
    private static final String[] KNOWN_ACTIONS = {
            // AVTransport —— 与 SCPD_AV_TRANSPORT 的 actionList 一一对应。
            // 两边必须同步：SCPD 里没有的 action 控制点不会发（写了也是死的），
            // 而 SCPD 里有、这张表里没有的会被回 401（明明声明支持却做不到）。
            "SetAVTransportURI", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions",
            "Stop", "Play", "Pause", "Seek", "Next", "Previous", "SetPlayMode",
            // ConnectionManager
            "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo",
            // RenderingControl
            "GetVolume", "SetVolume", "GetMute", "SetMute", "ListPresets", "SelectPreset",
    };

    private static boolean isKnownAction(String action) {
        for (int i = 0; i < KNOWN_ACTIONS.length; i++) {
            if (KNOWN_ACTIONS[i].equals(action)) {
                return true;
            }
        }
        return false;
    }

    private void handlePost(String path, String soapAction, String body, OutputStream out)
            throws IOException {
        String service = lastSegment(path);
        String action = extractActionName(soapAction, body);
        Log.i(TAG, "控制指令: service=" + service + " action=" + action);

        if (action == null || !isKnownAction(action)) {
            Log.w(TAG, "不支持的 action，回 401 Fault: " + action);
            writeSoapFault(out, service, "401", "Invalid Action");
            return;
        }

        try {
            Map<String, String> args = extractArguments(body);

            // ---- 「语法合法、但我们做不到」的指令必须如实回 701 ----
            // Next / Previous 在 AVTransport:1 里是**必选** action（规范要求它们存在），
            // 但没有播放列表时正确的回应是 701 Transition not available。
            // 原来它们落到 dispatch 的空分支、回一个 200 空响应 ——
            // 控制点据此以为"切歌成功"，界面上却什么都没发生。
            String reason = notApplicableReason(action, args);
            if (reason != null) {
                Log.i(TAG, action + " 不适用，回 701：" + reason);
                writeSoapFault(out, service, "701", "Transition not available");
                return;
            }

            dispatch(service, action, args);
            writeSoapResponse(out, service, action, responseArgs(action));
        } catch (Exception e) {
            Log.e(TAG, "执行指令失败: " + action, e);
            writeSoapFault(out, service, "501", "Action Failed");
        }
    }

    /**
     * 这个 action 是不是「语法合法、但我们做不到」——是则返回原因，否则 null。
     *
     * <p>判据刻意收得很紧：**只放行"设成我们本来就处于的状态"**。
     * 比如 SetPlayMode 收到 NORMAL 就回 200 —— 我们本来就是 NORMAL，
     * 回 701 只会让控制点弹一个莫名其妙的错误；收到 SHUFFLE 才回 701。
     * 反过来的话（一律回 200）就是假装支持，控制点会把界面画成"已设为随机播放"。
     */
    private static String notApplicableReason(String action, Map<String, String> args) {
        if ("Next".equals(action) || "Previous".equals(action)) {
            return "没有播放列表，谈不上下一首 / 上一首";
        }
        if ("SetPlayMode".equals(action)) {
            String mode = get(args, "NewPlayMode");
            return "NORMAL".equals(mode) ? null : "只支持 NORMAL，收到 " + mode;
        }
        if ("SelectPreset".equals(action)) {
            String preset = get(args, "PresetName");
            return "FactoryDefaults".equals(preset) ? null : "只有 FactoryDefaults，收到 " + preset;
        }
        return null;
    }

    /** 把 SOAP 指令映射到业务回调 —— 协议与业务的分界线就在这 */
    private void dispatch(String service, String action, Map<String, String> args) {
        if ("SetAVTransportURI".equals(action)) {
            // 这就是投屏的入口：手机把视频 URL 推过来
            handler.onSetUri(get(args, "CurrentURI"), get(args, "CurrentURIMetaData"));
        } else if ("Play".equals(action)) {
            handler.onPlay();
        } else if ("Pause".equals(action)) {
            handler.onPause();
        } else if ("Stop".equals(action)) {
            handler.onStop();
        } else if ("Seek".equals(action)) {
            // Unit 不是时间轴时（TRACK_NR 之类），Target 是**曲目号**而不是时刻。
            // 当成时刻去解析会把「切下一曲」变成「跳回开头」，所以直接忽略。
            String unit = get(args, "Unit");
            if (unit.length() != 0 && !"REL_TIME".equals(unit) && !"ABS_TIME".equals(unit)) {
                Log.i(TAG, "Seek Unit=" + unit + " 不是时间轴，已忽略");
            } else {
                long ms = parseTimeToMs(get(args, "Target"));
                if (ms >= 0) {
                    handler.onSeek(ms);
                } else {
                    // 解析不出来就**什么都不做**。
                    // 绝不能把 -1 当成 0 下发 —— 那是「跳回开头」，比不动作糟得多。
                    Log.w(TAG, "Seek Target 无法解析，已忽略: " + get(args, "Target"));
                }
            }
        } else if ("SetVolume".equals(action)) {
            handler.onSetVolume(parseInt(get(args, "DesiredVolume"), 100));
        } else if ("SetMute".equals(action)) {
            // DesiredMute 是 UPnP 的 boolean："1" / "true" 为静音，其余为取消。
            handler.onSetMute(parseBoolean(get(args, "DesiredMute")));
        }
    }

    /**
     * UPnP 的 boolean。
     *
     * <p>规范里 boolean 有**六个**合法取值：{@code 0} / {@code false} / {@code no}
     * 为假，{@code 1} / {@code true} / {@code yes} 为真，大小写不敏感。
     *
     * <p>只认 {@code 1} 和 {@code true} 是不够的 —— 规范明确允许 {@code yes}，
     * 而确实有控制点发它。漏掉 {@code yes} 的后果不是"静音不生效"，
     * 而是**方向反了**：用户按静音，声音反而回来了。这比不支持更糟。
     *
     * <p>其余一切（包括空串）按 false 处理。理由：静音是"打开声音"那一侧，
     * 而一个解析不出来的值最可能是压根没带这个参数 —— 让声音出来
     * 比让用户突然没声音好。
     */
    private static boolean parseBoolean(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    /**
     * 能吃的格式清单。
     *
     * <p>0.6GB 内存 + MT5880 的现实决定了必须「保守声明」：声明过宽 →
     * 控制点推来解不动或解不了的流 → 直接卡死或黑屏。所以刻意不声明
     * MKV / MPEG-PS 这类容器解析吃内存的格式。
     *
     * <p>公开成常量是因为它有两个出口：GetProtocolInfo 的响应，以及
     * ConnectionManager 事件里的 {@code SinkProtocolInfo}。**必须是同一份字符串** ——
     * 各写一份，改一处忘一处，控制点就会看到"声明的"和"事件报的"不一致。
     *
     * <p><b>为什么这里没有 image/*</b>：曾经声明过 {@code image/jpeg} 与
     * {@code image/png}，但整套实现里根本没有图片这条路 —— {@code kindOf()}
     * 只认 audioItem / videoItem，而 {@code MediaPlayer} 本身也不解图片。
     * 声明了却做不到，控制点（相册、文件管理器）就会把图片推过来，
     * 然后必然失败，用户看到的是"投屏坏了"。**这份清单是控制点判断
     * "能不能推给我"的唯一依据，所以它必须与实现严格一致** ——
     * 宁可让控制点一开始就说"这台设备不支持"，也不要收下再失败。
     */
    public static final String SINK_PROTOCOL_INFO =
            "http-get:*:video/mp4:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,"
                    + "http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*";

    /** 各 action 需要回什么参数 */
    private String responseArgs(String action) {
        if ("GetTransportInfo".equals(action)) {
            // CurrentTransportStatus 必须来自 handler，不能写死 ——
            // 写死的话它就和事件里的 TransportStatus 各说各话。
            String status = handler.getTransportStatus();
            if (status == null || status.length() == 0) {
                // 兜底成 OK：规范里这是常态，而一个空串会让控制点拿到非法值。
                // 但真走到这里说明实现方漏了，日志留个痕迹。
                Log.w(TAG, "handler 没给 TransportStatus，兜底成 OK");
                status = "OK";
            }
            return "<CurrentTransportState>" + handler.getTransportState() + "</CurrentTransportState>"
                    + "<CurrentTransportStatus>" + status + "</CurrentTransportStatus>"
                    + "<CurrentSpeed>1</CurrentSpeed>";
        }
        if ("GetPositionInfo".equals(action)) {
            // Track 按规范是"当前选中轨号，无选中时为 0"。无媒体却回 1，
            // 会让控制点认为已经选中了第一轨。
            String uri = handler.getCurrentUri();
            if (uri == null) {
                uri = "";
            }
            String metadata = handler.getCurrentMetadata();
            if (metadata == null) {
                metadata = "";
            }
            boolean hasMedia = uri.length() > 0;
            // 只读一次。原来 RelTime 与 AbsTime 各调一次 handler，
            // 每次都穿过整个链路去问 MediaPlayer —— 高频轮询下这是白费的开销。
            long posMs = handler.getPositionMs();
            long durMs = handler.getDurationMs();
            // 诊断开关：默认**不打**（isLoggable 为 false 时零开销），
            // 需要时执行
            //     adb shell setprop log.tag.UpnpHttpServer DEBUG
            // 再抓一次 logcat 就能看到控制点每次轮询到底拿到了什么。
            //
            // 为什么必须留这个口子：「手机上进度条不动」这类问题**只能**靠这三个数
            // 定位 —— 到底是控制点压根没来轮询，还是来了但我们报的值不对。
            // 而无条件打日志不行：位置轮询是每秒一次的高频动作，
            // 在 0.6GB 的盒子上会刷屏并拖慢响应。
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "GetPositionInfo → RelTime=" + formatTime(posMs)
                        + " TrackDuration=" + formatTime(durMs)
                        + " hasMedia=" + hasMedia);
            }
            return "<Track>" + (hasMedia ? 1 : 0) + "</Track>"
                    + "<TrackDuration>" + formatTime(durMs) + "</TrackDuration>"
                    // TrackMetaData 与 GetMediaInfo 的 CurrentURIMetaData 是同一份东西
                    // （规范里都指"当前媒体的元数据"），必须同源 —— 两处各回各的
                    // 会出现"同一个媒体、两个接口给的元数据不一样"。
                    // 同样要转义：它是一段嵌在 SOAP 里的 XML。
                    + "<TrackMetaData>" + escapeXml(metadata) + "</TrackMetaData>"
                    + "<TrackURI>" + escapeXml(uri) + "</TrackURI>"
                    + "<RelTime>" + formatTime(posMs) + "</RelTime>"
                    + "<AbsTime>" + formatTime(posMs) + "</AbsTime>"
                    + "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>";
        }
        if ("GetMediaInfo".equals(action)) {
            // 无媒体时 CurrentURI 允许为空，但 NrTracks 必须如实回 0 —— 控制点会拿
            // NrTracks 判断"这台设备上现在有没有内容"，谎报 1 会让它以为已经有片子了。
            String uri = handler.getCurrentUri();
            if (uri == null) {
                uri = "";
            }
            String meta = handler.getCurrentMetadata();
            if (meta == null) {
                meta = "";
            }
            boolean hasMedia = uri.length() > 0;
            return "<NrTracks>" + (hasMedia ? 1 : 0) + "</NrTracks>"
                    + "<MediaDuration>" + formatTime(handler.getDurationMs()) + "</MediaDuration>"
                    + "<CurrentURI>" + escapeXml(uri) + "</CurrentURI>"
                    // 必须转义：元数据本身就是一段 XML（带 < > "），
                    // 原样塞进 SOAP 响应会让**整条响应变成非法 XML** ——
                    // 控制点那边是"整条报文解析失败"，而不是"少个字段"。
                    + "<CurrentURIMetaData>" + escapeXml(meta) + "</CurrentURIMetaData>"
                    + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                    + "<PlayMedium>" + (hasMedia ? "NETWORK" : "NONE") + "</PlayMedium>"
                    + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                    + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
        }
        if ("GetVolume".equals(action)) {
            return "<CurrentVolume>" + handler.getVolume0to100() + "</CurrentVolume>";
        }
        if ("GetMute".equals(action)) {
            // 不能恒回 0 —— 那等于对着控制点撒谎（见 CommandHandler.getMute 的说明）。
            return "<CurrentMute>" + (handler.getMute() ? "1" : "0") + "</CurrentMute>";
        }
        if ("GetProtocolInfo".equals(action)) {
            return "<Source></Source>"
                    + "<Sink>" + SINK_PROTOCOL_INFO + "</Sink>";
        }
        if ("GetCurrentConnectionIDs".equals(action)) {
            return "<ConnectionIDs>0</ConnectionIDs>";
        }
        if ("GetCurrentConnectionInfo".equals(action)) {
            return "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>"
                    + "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>"
                    + "<PeerConnectionID>-1</PeerConnectionID>"
                    + "<Direction>Input</Direction><Status>OK</Status>";
        }
        // ---- 下面这几个是「探测型」指令，控制点常问，但不需要真正做什么。
        //      回一个语法合法的空壳，比回 401 Fault 更不容易让手机端误判设备有问题。----
        if ("GetTransportSettings".equals(action)) {
            return "<PlayMode>NORMAL</PlayMode>"
                    + "<RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>";
        }
        if ("GetDeviceCapabilities".equals(action)) {
            return "<PlayMedia>NETWORK</PlayMedia>"
                    + "<RecMedia>NOT_IMPLEMENTED</RecMedia>"
                    + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>";
        }
        if ("GetCurrentTransportActions".equals(action)) {
            // 如实声明支持的动作，控制点据此决定要不要把暂停键画出来
            return "<Actions>Play,Stop,Pause,Seek</Actions>";
        }
        if ("ListPresets".equals(action)) {
            return "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>";
        }
        return "";
    }

    // -------------------------------------------------------- SOAP 报文工具

    /** 从 SOAPAction 头或 body 首个元素里取 action 名 */
    private String extractActionName(String soapAction, String body) {
        if (soapAction != null && soapAction.length() > 0) {
            int hash = soapAction.lastIndexOf('#');
            String name = (hash >= 0) ? soapAction.substring(hash + 1) : soapAction;
            if (name.length() > 0) {
                return name;
            }
        }
        if (body != null) {
            int open = body.indexOf("<u:");
            if (open >= 0) {
                int end = body.indexOf('>', open);
                if (end > open) {
                    // 只取元素名，不能把属性一起吞进来。
                    // 原始报文形如：<u:GetTransportInfo xmlns:u="urn:...">
                    // 如果直接 substring 到 '>'，拿到的会是
                    //   GetTransportInfo xmlns:u="urn:..."
                    // 这个字符串既匹配不上任何 action，也会被原样写进响应的标签名里，
                    // 生成一份畸形 XML。
                    int i = open + 3;
                    while (i < end && isXmlNameChar(body.charAt(i))) {
                        i++;
                    }
                    String name = body.substring(open + 3, i);
                    return name.length() > 0 ? name : null;
                }
            }
        }
        return null;
    }

    /** XML 元素名允许的字符 */
    private static boolean isXmlNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == ':';
    }

    /**
     * 抽出 SOAP 报文里的动作参数。
     *
     * <p>匹配策略<b>照抄成熟 UPnP 栈</b>（jUPnP / Cling 的
     * {@code SOAPActionProcessorImpl#getMatchingNodes}）：按**剥掉命名空间
     * 前缀后的标签名**匹配，<b>完全无视属性</b>，值取元素文本内容。理由：
     * <ul>
     *   <li>UPnP SOAP 参数允许带属性（Platinum 系控制点发
     *       {@code <CurrentURI val="http://...">}），之前只认
     *       {@code <Tag>value</Tag>}，带属性的参数被静默丢弃 ——
     *       {@code SetAVTransportURI} 表现为缺 URI 投不上，日志看不出原因；</li>
     *   <li>个别协议栈连参数都带前缀（{@code <u:CurrentURI>}），jUPnP 连这个
     *       都兼容（{@code getUnprefixedNodeName}），照做没有坏处 ——
     *       多出来的键（如动作包装元素 {@code u:Play} → "Play"）没人读，
     *       dispatch 只认已知参数名。</li>
     * </ul>
     *
     * <p>容错保持与旧版一致：自闭合 {@code <Tag/>}、找不到闭合标签的、
     * 注释与声明，全部跳过；值里出现裸 {@code <}（正常不会发生 —— DIDL
     * 元数据在 SOAP 里是转义过的）会找不到闭合而自然丢弃。
     */
    private Map<String, String> extractArguments(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null) {
            return map;
        }
        int idx = 0;
        while (true) {
            int open = body.indexOf('<', idx);
            if (open < 0) {
                break;
            }
            int close = body.indexOf('>', open);
            if (close < 0) {
                break;
            }
            String raw = body.substring(open + 1, close).trim();
            idx = close + 1;
            // 声明 / 注释 / 闭合 / 自闭合：都不是参数
            if (raw.length() == 0 || raw.startsWith("/") || raw.startsWith("?")
                    || raw.startsWith("!") || raw.endsWith("/")) {
                continue;
            }
            // 剥属性：标签名取第一个空白符之前的部分
            String name = raw;
            for (int i = 0; i < raw.length(); i++) {
                if (Character.isWhitespace(raw.charAt(i))) {
                    name = raw.substring(0, i);
                    break;
                }
            }
            // 剥命名空间前缀（与 jUPnP getUnprefixedNodeName 一致）
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
            if (name.length() == 0) {
                continue;
            }
            // 闭合标签：优先按原文找（带前缀），找不到再按剥后的名字找
            int end = body.indexOf("</" + raw + ">", idx);
            if (end < 0 && !raw.equals(name)) {
                end = body.indexOf("</" + name + ">", idx);
            }
            if (end < 0) {
                continue;
            }
            String inner = body.substring(idx, end);
            if (inner.indexOf('<') >= 0) {
                // 容器元素（s:Envelope / s:Body / u:动作名）：里面还嵌着标签。
                // **不能**把整个子树记成它的值，更不能把扫描位置跳到闭合标签之后
                // —— 那样真正的参数（都在容器里面）会被整体吞掉。继续往里扫。
                continue;
            }
            map.put(name, unescapeXml(inner));
            idx = end;
        }
        return map;
    }

    private void writeSoapResponse(OutputStream out, String service, String action, String args)
            throws IOException {
        String type = "urn:schemas-upnp-org:service:" + service + ":1";
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n"
                + "  <s:Body>\n"
                + "    <u:" + action + "Response xmlns:u=\"" + type + "\">"
                + args
                + "</u:" + action + "Response>\n"
                + "  </s:Body>\n"
                + "</s:Envelope>\n";
        writeSimple(out, "200 OK", "text/xml; charset=\"utf-8\"", xml);
    }

    private void writeSoapFault(OutputStream out, String service, String code, String desc)
            throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n"
                + "  <s:Body><s:Fault>\n"
                + "    <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>\n"
                + "    <detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">\n"
                + "      <errorCode>" + code + "</errorCode><errorDescription>" + desc
                + "</errorDescription>\n"
                + "    </UPnPError></detail>\n"
                + "  </s:Fault></s:Body>\n"
                + "</s:Envelope>\n";
        writeSimple(out, "500 Internal Server Error", "text/xml; charset=\"utf-8\"", xml);
    }

    private void writeSimple(OutputStream out, String status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(payload.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Server: Android UPnP/1.0 " + serverProduct() + "\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(payload);
    }

    // ------------------------------------------------------------ 小工具

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return (i >= 0) ? path.substring(i + 1) : path;
    }

    private static String get(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? "" : v;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 解析 UPnP 的时长字符串为毫秒。**解析不出来时返回 -1，而不是 0。**
     *
     * <p>规范里 {@code REL_TIME} 的格式是 {@code H+:MM:SS[.F+]} ——
     * **小数部分是合法的**，而且安卓侧不少投屏 SDK 就是按 {@code 00:10:30.000}
     * 发的（ISO 8601 / java.time 的默认输出长这样）。
     *
     * <p>这里踩过一次真坑，必须写下来。原来的实现只认 {@code H:MM:SS}，
     * 遇到 {@code "."} 抛 {@code NumberFormatException}，被
     * {@code catch (Exception ignored)} 吞掉后 {@code return 0}。于是：
     *
     * <ul>
     *   <li>用户在手机上把进度条拖到 10:30 → 电视**跳回开头**；</li>
     *   <li>控制点接着回读 {@code GetPositionInfo}，拿到 {@code RelTime=00:00:00}，
     *       于是手机自己的进度条也弹回 0 —— 用户看到的就是「拖拽不同步」；</li>
     *   <li>而日志里一个字都没有，因为异常被吞了。</li>
     * </ul>
     *
     * <p><b>教训：任何「解析失败」都必须与「解析出 0」区分开。</b>
     * 对时刻来说 0 是一个完全合法、且语义极重的值（跳到开头），
     * 把它当兜底值等于把"看不懂"翻译成"从头开始"。
     */
    private static long parseTimeToMs(String t) {
        if (t == null) {
            return -1L;
        }
        String s = t.trim();
        if (s.length() == 0) {
            return -1L;
        }
        // 小数部分按毫秒折算：.5 → 500ms，.25 → 250ms，.125 → 125ms。
        // 全程整数运算 —— 这里刻意不用 Double.parseDouble：
        // 它会接受 "NaN" / "Infinity"，而 NaN 转成 long 是 0，
        // 于是又绕回「看不懂 → 跳到开头」那个坑里。
        int dot = s.indexOf('.');
        long fracMs = 0L;
        if (dot >= 0) {
            String frac = s.substring(dot + 1);
            s = s.substring(0, dot);
            for (int i = 0; i < frac.length() && i < 3; i++) {
                char c = frac.charAt(i);
                if (c < '0' || c > '9') {
                    return -1L;             // 小数位里混了非数字 → 整条都不合法
                }
                fracMs = fracMs * 10 + (c - '0');
            }
            for (int i = frac.length(); i < 3; i++) {
                fracMs *= 10;               // 补零到毫秒位
            }
        }
        String[] p = s.split(":");
        if (p.length != 3) {
            return -1L;
        }
        try {
            long h = Long.parseLong(p[0].trim());
            long m = Long.parseLong(p[1].trim());
            long sec = Long.parseLong(p[2].trim());
            if (h < 0 || m < 0 || sec < 0) {
                return -1L;
            }
            return (h * 3600L + m * 60L + sec) * 1000L + fracMs;
        } catch (NumberFormatException e) {
            Log.w(TAG, "Seek Target 无法解析: " + t);
            return -1L;
        }
    }

    /**
     * 毫秒 → UPnP 时间格式 HH:MM:SS。
     *
     * <p>公开出来是因为业务层也要用：AVTransport 事件里的
     * {@code CurrentTrackDuration} 和 GetPositionInfo 的 {@code TrackDuration}
     * 是同一个值，格式必须一致 —— 各写一份迟早会漂。
     */
    public static String formatTime(long ms) {
        if (ms <= 0) {
            return "00:00:00";
        }
        long totalSec = ms / 1000;
        // 指定 Locale.ROOT：%d 在阿拉伯语等 locale 下会输出阿拉伯-印度数字（٠١٢…），
        // 手机端拿到 "٠٠:٠١:٢٣" 这种时长字符串会直接解析失败。
        // 协议字段必须锁定成 ASCII。
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    /**
     * XML 转义。**嵌 URL 时必须过这一道**。
     *
     * <p>视频 CDN 的地址几乎必然带查询串，例如
     * {@code http://cdn/x.mp4?token=abc&expire=123}。这个 {@code &} 直接写进
     * {@code <CurrentURI>} 会让整份 SOAP 响应变成非法 XML —— 控制点那边不是
     * "这一项读不到"，而是**整条报文解析失败**，表现为投屏后立刻报错。
     * 同理 {@code <} {@code >} 出现在带签名的 URL 里也不罕见。
     *
     * <p>{@code &} 必须最先替换，否则会把后面刚生成的实体再转一遍
     * （{@code &lt;} → {@code &amp;lt;}）。
     *
     * <p>公开出来是给 {@link EventDispatcher} 用的：事件体里同样嵌着带
     * {@code &} 的媒体 URL，转义规则**必须和 SOAP 响应完全一致**。
     */
    public static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    public void shutdown() {
        running = false;
        // 先撤"已就绪"标志、再关 socket：反过来的话，close 与 isBound()
        // 之间有一个瞬间是"端口已经关了，状态却还说在监听"。
        bound = false;
        // 事件分发器有自己的线程池，必须一并关掉，否则它会挂着不放。
        events.shutdown();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    // --------------------------------------------------------- SCPD 模板
    //
    // 这三份 XML 是**控制点了解"能调哪些指令"的唯一依据**。写错的后果不是
    // "少一个功能"，而是控制点整份解析失败 —— 严格按 SCPD 校验的协议栈
    // （Cling / jUPnP 系、BubbleUPnP 等）会直接判定这个服务不可用，于是设备
    // 在列表里是灰的、点不动。宽松的（多数国产 SDK）不看这些也能用，
    // 所以这类 bug 只在部分控制点上暴露，最容易被误判成"那台手机的问题"。
    //
    // 本轮改掉的两个硬伤：
    //
    //  1. relatedStateVariable **必须**指向 serviceStateTable 里真实存在的变量名。
    //     原来直接拿参数名当变量名，生成的是
    //         <relatedStateVariable>InstanceID</relatedStateVariable>
    //     而表里没有叫 InstanceID 的变量（正确名是 A_ARG_TYPE_InstanceID）。
    //     这是上面说的那种"整份解析失败"。
    //
    //  2. **out 参数原来一个都没声明。** 规范要求每个 action 的出参都列出来，
    //     控制点靠它知道响应里该有哪些字段、以及去哪个变量取值。
    //     缺了它，控制点拿到响应也不知道怎么读。
    //
    // 另外补上了原来漏在 SCPD 外面、但代码里已经在处理的 action
    // （GetDeviceCapabilities / GetTransportSettings / GetCurrentTransportActions /
    //  Next / Previous / SetPlayMode，以及 RenderingControl 的 ListPresets /
    //  SelectPreset）—— 控制点**不会发 SCPD 里没写的 action**，
    // 所以这些代码原本是死的。
    //
    // action 清单与变量表照 UPnP 官方的 AVTransport:1 / RenderingControl:1 /
    // ConnectionManager:1 SCPD 模板来，并与 Platinum UPnP SDK
    // （Source/Devices/MediaRenderer/AVTransportSCPD.xml 等）逐条核对过。
    // 刻意**没有**收录 SetPlaySpeed —— 它不是 AVTransport:1 的标准 action，
    // 写进 :1 的 SCPD 本身就是错的（原来它还同时出现在 KNOWN_ACTIONS 里）。

    private static final String SCPD_AV_TRANSPORT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("SetAVTransportURI",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:CurrentURI:AVTransportURI",
                            "in:CurrentURIMetaData:AVTransportURIMetaData")
                    + action("GetMediaInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:NrTracks:NumberOfTracks",
                            "out:MediaDuration:CurrentMediaDuration",
                            "out:CurrentURI:AVTransportURI",
                            "out:CurrentURIMetaData:AVTransportURIMetaData",
                            "out:NextURI:NextAVTransportURI",
                            "out:NextURIMetaData:NextAVTransportURIMetaData",
                            "out:PlayMedium:PlaybackStorageMedium",
                            "out:RecordMedium:RecordStorageMedium",
                            "out:WriteStatus:RecordMediumWriteStatus")
                    + action("GetTransportInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:CurrentTransportState:TransportState",
                            "out:CurrentTransportStatus:TransportStatus",
                            "out:CurrentSpeed:TransportPlaySpeed")
                    + action("GetPositionInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:Track:CurrentTrack",
                            "out:TrackDuration:CurrentTrackDuration",
                            "out:TrackMetaData:CurrentTrackMetaData",
                            "out:TrackURI:CurrentTrackURI",
                            "out:RelTime:RelativeTimePosition",
                            "out:AbsTime:AbsoluteTimePosition",
                            "out:RelCount:RelativeCounterPosition",
                            "out:AbsCount:AbsoluteCounterPosition")
                    + action("GetDeviceCapabilities",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:PlayMedia:PossiblePlaybackStorageMedia",
                            "out:RecMedia:PossibleRecordStorageMedia",
                            "out:RecQualityModes:PossibleRecordQualityModes")
                    + action("GetTransportSettings",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:PlayMode:CurrentPlayMode",
                            "out:RecQualityMode:CurrentRecordQualityMode")
                    + action("GetCurrentTransportActions",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:Actions:CurrentTransportActions")
                    + action("Stop", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("Play",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Speed:TransportPlaySpeed")
                    + action("Pause", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("Seek",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Unit:A_ARG_TYPE_SeekMode",
                            "in:Target:A_ARG_TYPE_SeekTarget")
                    // Next / Previous 在规范里是**必选** action（可以回 701，
                    // 但不能不存在）—— 所以它们必须写在 SCPD 里。
                    // 代码侧由 notApplicableReason() 回 701。
                    + action("Next", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("Previous", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("SetPlayMode",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:NewPlayMode:CurrentPlayMode")
                    + " </actionList>\n"
                    + " <serviceStateTable>\n"
                    // ---- 声明为可事件化的，**必须**与 DlnaRendererService
                    //      .eventedVars("AVTransport") 给出的键完全一致。
                    //      多一个：控制点会一直等一个永远不来的值；
                    //      少一个：事件体里带了它，控制点按 SCPD 直接忽略。
                    //      两边各写一份，靠 tools/policy-test 的守卫钉住。
                    + stateVar("TransportState", "string", true)
                    + stateVar("TransportStatus", "string", true)
                    + stateVar("CurrentTrackURI", "string", true)
                    + stateVar("CurrentTrackDuration", "string", true)
                    // 当前位置也必须声明为可事件化：一部分控制点（国产投屏 SDK 居多）
                    // 不轮询 GetPositionInfo，而是靠事件里的 RelativeTimePosition
                    // 更新进度条。SCPD 里不声明的话，事件体里就算给了它也不会用。
                    + stateVar("RelativeTimePosition", "string", true)
                    // ---- 下面这些是"被 out 参数引用到"的变量，规范要求它们
                    //      必须出现在表里，但不需要事件化（sendEvents="no"）。----
                    + stateVar("PlaybackStorageMedium", "string", false)
                    + stateVar("RecordStorageMedium", "string", false)
                    + stateVar("PossiblePlaybackStorageMedia", "string", false)
                    + stateVar("PossibleRecordStorageMedia", "string", false)
                    + stateVar("CurrentPlayMode", "string", false)
                    + stateVar("TransportPlaySpeed", "string", false)
                    + stateVar("RecordMediumWriteStatus", "string", false)
                    + stateVar("CurrentRecordQualityMode", "string", false)
                    + stateVar("PossibleRecordQualityModes", "string", false)
                    + stateVar("NumberOfTracks", "ui4", false)
                    + stateVar("CurrentTrack", "ui4", false)
                    + stateVar("CurrentMediaDuration", "string", false)
                    + stateVar("CurrentTrackMetaData", "string", false)
                    + stateVar("AVTransportURI", "string", false)
                    + stateVar("AVTransportURIMetaData", "string", false)
                    + stateVar("NextAVTransportURI", "string", false)
                    + stateVar("NextAVTransportURIMetaData", "string", false)
                    + stateVar("AbsoluteTimePosition", "string", false)
                    + stateVar("RelativeCounterPosition", "i4", false)
                    + stateVar("AbsoluteCounterPosition", "i4", false)
                    + stateVar("CurrentTransportActions", "string", false)
                    // A_ARG_TYPE_* 是"参数类型"变量，规范里统一用这个前缀。
                    + stateVar("A_ARG_TYPE_InstanceID", "ui4", false)
                    + stateVar("A_ARG_TYPE_SeekMode", "string", false)
                    + stateVar("A_ARG_TYPE_SeekTarget", "string", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_CONNECTION_MANAGER =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("GetProtocolInfo",
                            "out:Source:SourceProtocolInfo",
                            "out:Sink:SinkProtocolInfo")
                    + action("GetCurrentConnectionIDs",
                            "out:ConnectionIDs:CurrentConnectionIDs")
                    + action("GetCurrentConnectionInfo",
                            "in:ConnectionID:A_ARG_TYPE_ConnectionID",
                            "out:RcsID:A_ARG_TYPE_RcsID",
                            "out:AVTransportID:A_ARG_TYPE_AVTransportID",
                            "out:ProtocolInfo:A_ARG_TYPE_ProtocolInfo",
                            "out:PeerConnectionManager:A_ARG_TYPE_ConnectionManager",
                            "out:PeerConnectionID:A_ARG_TYPE_ConnectionID",
                            "out:Direction:A_ARG_TYPE_Direction",
                            "out:Status:A_ARG_TYPE_ConnectionStatus")
                    + " </actionList>\n"
                    // ConnectionManager 在标准里也有可事件化变量，控制点常订阅它。
                    // 声明了就必须在 eventedVars 里如实给值，否则控制点收到的是一份
                    // 缺字段的事件体 —— 比不订阅更糟。
                    + " <serviceStateTable>\n"
                    + stateVar("SourceProtocolInfo", "string", true)
                    + stateVar("SinkProtocolInfo", "string", true)
                    + stateVar("CurrentConnectionIDs", "string", true)
                    + stateVar("A_ARG_TYPE_ConnectionStatus", "string", false)
                    + stateVar("A_ARG_TYPE_ConnectionManager", "string", false)
                    + stateVar("A_ARG_TYPE_Direction", "string", false)
                    + stateVar("A_ARG_TYPE_ProtocolInfo", "string", false)
                    + stateVar("A_ARG_TYPE_ConnectionID", "i4", false)
                    + stateVar("A_ARG_TYPE_AVTransportID", "i4", false)
                    + stateVar("A_ARG_TYPE_RcsID", "i4", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_RENDERING_CONTROL =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    // ListPresets / SelectPreset 属于 RenderingControl:1（不在 AVTransport 里）。
                    // 原来 KNOWN_ACTIONS 和 responseArgs 都处理了它们，SCPD 里却没声明 ——
                    // 而控制点不会发 SCPD 里没有的 action，所以那两段代码是死的。
                    + action("ListPresets",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:CurrentPresetNameList:PresetNameList")
                    + action("SelectPreset",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:PresetName:A_ARG_TYPE_PresetName")
                    + action("GetVolume",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "out:CurrentVolume:Volume")
                    + action("SetVolume",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "in:DesiredVolume:Volume")
                    + action("GetMute",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "out:CurrentMute:Mute")
                    + action("SetMute",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "in:DesiredMute:Mute")
                    + " </actionList>\n"
                    // 声明 Volume / Mute 是「可事件化」的 —— 控制点订阅后，
                    // 音量/静音一变就能收到 NOTIFY。原来这里一张 stateVariable 表都没有，
                    // 于是控制点订阅 RenderingControl 拿到的是空事件集。
                    + " <serviceStateTable>\n"
                    + stateVar("Volume", "ui2", true)
                    + stateVar("Mute", "boolean", true)
                    + stateVar("PresetNameList", "string", false)
                    + stateVar("A_ARG_TYPE_InstanceID", "ui4", false)
                    + stateVar("A_ARG_TYPE_Channel", "string", false)
                    + stateVar("A_ARG_TYPE_PresetName", "string", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    /**
     * 生成一条 {@code <action>}。
     *
     * <p>参数按 {@code "方向:参数名:关联状态变量"} 写，例如
     * {@code "out:CurrentVolume:Volume"}、{@code "in:InstanceID:A_ARG_TYPE_InstanceID"}。
     *
     * <p>为什么把第三个字段做成**必填**而不是从参数名推：规范要求
     * {@code relatedStateVariable} 指向 serviceStateTable 里真实存在的变量，
     * 而参数名与变量名**经常不一样**（{@code CurrentVolume} → {@code Volume}、
     * {@code InstanceID} → {@code A_ARG_TYPE_InstanceID}）。
     * 按参数名推就是本类原来那个 bug 的成因。写成三段之后，
     * 少写一段会当场抛异常（而不是静默生成一份畸形 SCPD）。
     */
    private static String action(String name, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <action><name>").append(name).append("</name>");
        if (args.length > 0) {
            sb.append("<argumentList>");
            for (int i = 0; i < args.length; i++) {
                String[] p = args[i].split(":");
                if (p.length != 3) {
                    // 宁可当场炸掉。静态初始化失败会让 UpnpHttpServer 类加载不出来，
                    // 服务一起起不来 —— 声音很大，但**好过静默生成一份畸形 SCPD**：
                    // 后者只在部分控制点上表现为"设备是灰的"，极难定位。
                    // 而它一定会在 tools/protocol-test 里被抓到（那一轮会 GET 三份 SCPD）。
                    throw new IllegalArgumentException(
                            "SCPD 参数必须写成 方向:参数名:状态变量，收到: " + args[i]);
                }
                sb.append("<argument><name>").append(p[1]).append("</name>")
                        .append("<direction>").append(p[0]).append("</direction>")
                        .append("<relatedStateVariable>").append(p[2])
                        .append("</relatedStateVariable></argument>");
            }
            sb.append("</argumentList>");
        }
        sb.append("</action>\n");
        return sb.toString();
    }

    private static String stateVar(String name, String type, boolean events) {
        return "  <stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\">"
                + "<name>" + name + "</name><dataType>" + type + "</dataType></stateVariable>\n";
    }
}
