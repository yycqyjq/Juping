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

        void onSetVolume(int volume0to100);

        int getVolume0to100();
    }

    private final int port;
    private final String uuid;
    private final String friendlyName;
    private final CommandHandler handler;

    /** GENA 事件分发。订阅状态、SEQ、超时都在这里面。 */
    private final EventDispatcher events;

    private volatile boolean running = true;
    private ServerSocket serverSocket;

    /**
     * 端口是否已经真正绑上。
     *
     * <p>存在的理由：SSDP 和 HTTP 是两条独立的链路，**死一条另一条照活**。
     * HTTP 没绑上时，手机搜得到设备（SSDP 正常应答），却取不到 device.xml、
     * 一条 SOAP 指令都发不进来 —— 表现就是「搜得到但投不上去」。
     * 界面必须能把这种情况如实报出来，而不是一律显示"已就绪"。
     */
    private volatile boolean bound;

    public UpnpHttpServer(int port, String uuid, String friendlyName, CommandHandler handler,
                          EventDispatcher.EventSource eventSource) {
        super("upnp-http");
        setDaemon(true);
        this.port = port;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.handler = handler;
        this.events = new EventDispatcher(uuid, eventSource);
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
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleConnection(socket);
                    }
                }, "upnp-conn").start();
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
                writeSimple(out, "200 OK", "text/xml", "");
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

    private void handleGet(String path, OutputStream out) throws IOException {
        if (path.startsWith("/upnp/device.xml") || path.equals("/")) {
            writeSimple(out, "200 OK", "text/xml; charset=\"utf-8\"", buildDeviceDescription());
        } else if (path.contains("AVTransport.xml")) {
            writeSimple(out, "200 OK", "text/xml; charset=\"utf-8\"", SCPD_AV_TRANSPORT);
        } else if (path.contains("ConnectionManager.xml")) {
            writeSimple(out, "200 OK", "text/xml; charset=\"utf-8\"", SCPD_CONNECTION_MANAGER);
        } else if (path.contains("RenderingControl.xml")) {
            writeSimple(out, "200 OK", "text/xml; charset=\"utf-8\"", SCPD_RENDERING_CONTROL);
        } else {
            writeSimple(out, "404 Not Found", "text/plain", "");
        }
    }

    private String buildDeviceDescription() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n"
                + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                + "  <device>\n"
                + "    <deviceType>" + SsdpResponder.DEVICE_TYPE + "</deviceType>\n"
                + "    <friendlyName>" + friendlyName + "</friendlyName>\n"
                + "    <manufacturer>Juping</manufacturer>\n"
                + "    <modelName>Juping Receiver</modelName>\n"
                + "    <modelNumber>1.0</modelNumber>\n"
                + "    <UDN>uuid:" + uuid + "</UDN>\n"
                + "    <serviceList>\n"
                + serviceEntry("AVTransport") + serviceEntry("ConnectionManager")
                + serviceEntry("RenderingControl")
                + "    </serviceList>\n"
                + "  </device>\n"
                + "</root>\n";
    }

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
                + "Server: Android UPnP/1.0 Juping/1.0\r\n\r\n";
        out.write(resp.getBytes("UTF-8"));
    }

    /** 无 body 的状态响应。412 / 404 / 200 都用它。 */
    private void writeStatus(OutputStream out, String status) throws IOException {
        String resp = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "Server: Android UPnP/1.0 Juping/1.0\r\n\r\n";
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
            // AVTransport
            "SetAVTransportURI", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions",
            "Stop", "Play", "Pause", "Seek", "Next", "Previous",
            "SetPlayMode", "SetPlaySpeed",
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
            dispatch(service, action, args);
            writeSoapResponse(out, service, action, responseArgs(action));
        } catch (Exception e) {
            Log.e(TAG, "执行指令失败: " + action, e);
            writeSoapFault(out, service, "501", "Action Failed");
        }
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
        }
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
     */
    public static final String SINK_PROTOCOL_INFO =
            "http-get:*:video/mp4:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,"
                    + "http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*,"
                    + "http-get:*:image/jpeg:*,"
                    + "http-get:*:image/png:*";

    /** 各 action 需要回什么参数 */
    private String responseArgs(String action) {
        if ("GetTransportInfo".equals(action)) {
            return "<CurrentTransportState>" + handler.getTransportState() + "</CurrentTransportState>"
                    + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                    + "<CurrentSpeed>1</CurrentSpeed>";
        }
        if ("GetPositionInfo".equals(action)) {
            // Track 按规范是"当前选中轨号，无选中时为 0"。无媒体却回 1，
            // 会让控制点认为已经选中了第一轨。
            String uri = handler.getCurrentUri();
            if (uri == null) {
                uri = "";
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
                    + "<TrackMetaData></TrackMetaData>"
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
            boolean hasMedia = uri.length() > 0;
            return "<NrTracks>" + (hasMedia ? 1 : 0) + "</NrTracks>"
                    + "<MediaDuration>" + formatTime(handler.getDurationMs()) + "</MediaDuration>"
                    + "<CurrentURI>" + escapeXml(uri) + "</CurrentURI>"
                    + "<CurrentURIMetaData></CurrentURIMetaData>"
                    + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                    + "<PlayMedium>" + (hasMedia ? "NETWORK" : "NONE") + "</PlayMedium>"
                    + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                    + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
        }
        if ("GetVolume".equals(action)) {
            return "<CurrentVolume>" + handler.getVolume0to100() + "</CurrentVolume>";
        }
        if ("GetMute".equals(action)) {
            return "<CurrentMute>0</CurrentMute>";
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

    /** 朴素地抽出 &lt;Tag&gt;value&lt;/Tag&gt; 形式的参数 */
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
            String tag = body.substring(open + 1, close).trim();
            if (tag.length() == 0 || tag.startsWith("/") || tag.startsWith("?")
                    || tag.contains(":") || tag.startsWith("!")) {
                idx = close + 1;
                continue;
            }
            int end = body.indexOf("</" + tag + ">", close);
            if (end < 0) {
                idx = close + 1;
                continue;
            }
            map.put(tag, unescapeXml(body.substring(close + 1, end)));
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
        sb.append("Server: Android UPnP/1.0 Juping/1.0\r\n");
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

    private static final String SCPD_AV_TRANSPORT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("SetAVTransportURI", "InstanceID", "CurrentURI", "CurrentURIMetaData")
                    + action("GetMediaInfo", "InstanceID")
                    + action("GetTransportInfo", "InstanceID")
                    + action("GetPositionInfo", "InstanceID")
                    + action("Play", "InstanceID", "Speed")
                    + action("Pause", "InstanceID")
                    + action("Stop", "InstanceID")
                    + action("Seek", "InstanceID", "Unit", "Target")
                    + " </actionList>\n"
                    + " <serviceStateTable>\n"
                    + stateVar("TransportState", "string", true)
                    + stateVar("TransportStatus", "string", true)
                    + stateVar("CurrentTrackURI", "string", true)
                    + stateVar("CurrentTrackDuration", "string", true)
                    // 当前位置也必须声明为可事件化：一部分控制点（国产投屏 SDK 居多）
                    // 不轮询 GetPositionInfo，而是靠事件里的 RelativeTimePosition
                    // 更新进度条。SCPD 里不声明的话，事件体里就算给了它也不会用。
                    + stateVar("RelativeTimePosition", "string", true)
                    + stateVar("CurrentURI", "string", false)
                    + stateVar("CurrentURIMetaData", "string", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_CONNECTION_MANAGER =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("GetProtocolInfo")
                    + action("GetCurrentConnectionIDs")
                    + action("GetCurrentConnectionInfo", "ConnectionID")
                    + " </actionList>\n"
                    // ConnectionManager 在标准里也有可事件化变量，控制点常订阅它。
                    // 声明了就必须在 eventedVars 里如实给值，否则控制点收到的是一份
                    // 缺字段的事件体 —— 比不订阅更糟。
                    + " <serviceStateTable>\n"
                    + stateVar("SourceProtocolInfo", "string", true)
                    + stateVar("SinkProtocolInfo", "string", true)
                    + stateVar("CurrentConnectionIDs", "string", true)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_RENDERING_CONTROL =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("GetVolume", "InstanceID", "Channel")
                    + action("SetVolume", "InstanceID", "Channel", "DesiredVolume")
                    + action("GetMute", "InstanceID", "Channel")
                    + action("SetMute", "InstanceID", "Channel", "DesiredMute")
                    + " </actionList>\n"
                    // 声明 Volume / Mute 是「可事件化」的 —— 控制点订阅后，
                    // 音量一变就能收到 NOTIFY。原来这里一张 stateVariable 表都没有，
                    // 于是控制点订阅 RenderingControl 拿到的是空事件集。
                    + " <serviceStateTable>\n"
                    + stateVar("Volume", "ui2", true)
                    + stateVar("Mute", "boolean", true)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static String action(String name, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <action><name>").append(name).append("</name>");
        if (args.length > 0) {
            sb.append("<argumentList>");
            for (int i = 0; i < args.length; i++) {
                sb.append("<argument><name>").append(args[i]).append("</name>")
                        .append("<direction>in</direction><relatedStateVariable>")
                        .append(args[i]).append("</relatedStateVariable></argument>");
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
