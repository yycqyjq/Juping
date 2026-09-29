package com.juping.cast.dlna;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 *   <li>SUBSCRIBE                      —— 事件订阅，这里只应答不真正推送</li>
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

        void onSetVolume(int volume0to100);

        int getVolume0to100();
    }

    private final int port;
    private final String uuid;
    private final String friendlyName;
    private final CommandHandler handler;

    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public UpnpHttpServer(int port, String uuid, String friendlyName, CommandHandler handler) {
        super("upnp-http");
        setDaemon(true);
        this.port = port;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.handler = handler;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
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
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"), 8192);

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            Log.d(TAG, "<< " + requestLine);

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String soapAction = null;
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                // 必须指定 Locale.ROOT。HTTP 头名是 ASCII 协议字段，不属于任何自然语言。
                // 用默认 locale 的话，土耳其语环境里 "Content-Length".toLowerCase() 会得到
                // "content-length" 之外的怪东西（I -> ı），头名匹配不上 → 读不到 body →
                // SOAP 控制命令全部静默失败，而日志里看起来一切正常。
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = parseInt(line.substring(15).trim(), 0);
                } else if (lower.startsWith("soapaction:")) {
                    soapAction = line.substring(11).trim().replace("\"", "");
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                body = new String(buf, 0, read);
            }

            OutputStream out = socket.getOutputStream();
            if ("GET".equals(method)) {
                handleGet(path, out);
            } else if ("POST".equals(method)) {
                handlePost(path, soapAction, body, out);
            } else if ("SUBSCRIBE".equals(method) || "UNSUBSCRIBE".equals(method)) {
                // 只应答，不真正推送事件。绝大多数控制点能容忍这一点。
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "SID: uuid:" + uuid + "\r\n"
                        + "TIMEOUT: Second-1800\r\n"
                        + "Content-Length: 0\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
            } else if ("HEAD".equals(method)) {
                writeSimple(out, "200 OK", "text/xml", "");
            } else {
                writeSimple(out, "405 Method Not Allowed", "text/plain", "");
            }
            out.flush();
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

    // --------------------------------------------------------------- POST

    private void handlePost(String path, String soapAction, String body, OutputStream out)
            throws IOException {
        String service = lastSegment(path);
        String action = extractActionName(soapAction, body);
        Log.i(TAG, "控制指令: service=" + service + " action=" + action);

        if (action == null) {
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
            handler.onSeek(parseTimeToMs(get(args, "Target")));
        } else if ("SetVolume".equals(action)) {
            handler.onSetVolume(parseInt(get(args, "DesiredVolume"), 100));
        }
    }

    /** 各 action 需要回什么参数 */
    private String responseArgs(String action) {
        if ("GetTransportInfo".equals(action)) {
            return "<CurrentTransportState>" + handler.getTransportState() + "</CurrentTransportState>"
                    + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                    + "<CurrentSpeed>1</CurrentSpeed>";
        }
        if ("GetPositionInfo".equals(action)) {
            return "<Track>1</Track>"
                    + "<TrackDuration>" + formatTime(handler.getDurationMs()) + "</TrackDuration>"
                    + "<TrackMetaData></TrackMetaData>"
                    + "<TrackURI></TrackURI>"
                    + "<RelTime>" + formatTime(handler.getPositionMs()) + "</RelTime>"
                    + "<AbsTime>" + formatTime(handler.getPositionMs()) + "</AbsTime>"
                    + "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>";
        }
        if ("GetMediaInfo".equals(action)) {
            return "<NrTracks>1</NrTracks>"
                    + "<MediaDuration>" + formatTime(handler.getDurationMs()) + "</MediaDuration>"
                    + "<CurrentURI></CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                    + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                    + "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                    + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
        }
        if ("GetVolume".equals(action)) {
            return "<CurrentVolume>" + handler.getVolume0to100() + "</CurrentVolume>";
        }
        if ("GetMute".equals(action)) {
            return "<CurrentMute>0</CurrentMute>";
        }
        if ("GetProtocolInfo".equals(action)) {
            // 声明能吃的格式。0.6GB 内存 + MT5880 的现实决定了必须「保守声明」：
            // 声明过宽 → 控制点推来解不动或解不了的流 → 直接卡死或黑屏。
            // 所以刻意不声明 MKV / MPEG-PS 这类容器解析吃内存的格式。
            return "<Source></Source>"
                    + "<Sink>http-get:*:video/mp4:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,"
                    + "http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*,"
                    + "http-get:*:image/jpeg:*,"
                    + "http-get:*:image/png:*</Sink>";
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
                    return body.substring(open + 3, end).trim();
                }
            }
        }
        return null;
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

    /** UPnP 时间格式 HH:MM:SS → 毫秒 */
    private static long parseTimeToMs(String t) {
        try {
            String[] p = t.split(":");
            if (p.length == 3) {
                return (Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60
                        + Long.parseLong(p[2])) * 1000L;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /** 毫秒 → UPnP 时间格式 */
    private static String formatTime(long ms) {
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

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    public void shutdown() {
        running = false;
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
