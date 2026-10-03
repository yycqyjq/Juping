package com.juping.cast.dlna;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

        /**
         * SetNextAVTransportURI —— 播放列表/连续播放：预告「下一曲」，
         * 当前曲目自然播完后由渲染器自动续播（BubbleUPnP 的歌单连播依赖它）。
         */
        void onSetNextUri(String uri, String metadata);

        /** GetMediaInfo 的 NextURI 用；无下一曲时返回空串（不是 null） */
        String getNextUri();

        /** GetMediaInfo 的 NextURIMetaData 用；无下一曲时返回空串（不是 null） */
        String getNextUriMetadata();

        /**
         * /status 诊断页用 —— 返回设备当前状态的 JSON 串。
         * 手机浏览器可达即可排障，不需要 adb（借鉴 gmrender 生态的
         * upnp-display 思路：渲染器的「显示屏」就是浏览器）。
         */
        String buildStatusJson();

        /**
         * 设备描述 {@code presentationURL} 用 —— 本机在局域网里的地址。
         *
         * <p>必须与 LOCATION **同源**：都取「组播实际绑上的那张网卡」的 IPv4
         * （{@code DlnaRendererService.getLocalIp()} → {@code SsdpResponder#getBoundIp()}）。
         * 两处各挑各的 —— 比如一边用候选列表第一张网卡、一边用真正绑上的那张 ——
         * 就会出现「手机搜得到设备，点开设备页面却打不开」，和 LOCATION
         * 那个坑是同一个（见 {@code NetUtil} 的类注释）。
         *
         * <p>为什么由服务层提供而不是 HTTP 层自己探测：网卡选择的唯一出处
         * 在服务层。在这里再探测一次，等于把「组播绑哪张」和「告诉浏览器去
         * 哪儿打开页面」拆成两次独立选择。
         */
        String getLocalIp();
    }

    /**
     * 网页端点（上传页 / 上传 / 文件列表 / 投送）。
     *
     * <p>定义成接口、而不是直接引用 {@code com.juping.cast.web} 里的实现，是为了让
     * **协议闸门不必把整个 web 包编进来**：那套测试只补了一个 {@code android.util.Log}
     * 替身就能编 dlna 包（见 tools/protocol-test/run.sh 的说明），而 web 包要 Context、
     * 要文件系统。协议闸门只管 DLNA，不该被上传功能牵连 —— 一旦牵连，
     * 那道闸门就再也不是"零 Android 依赖"了。
     */
    public interface WebEndpoints {
        /**
         * @return 响应；返回 {@code null} 表示「这条路径不是我管的」，交回 DLNA 既有逻辑
         */
        WebResponse handle(String method, String path, String accept, String contentType,
                           String range, int contentLength, InputStream in);
    }

    /**
     * 网页端点的响应。**只描述"回什么"，不负责怎么写出** —— 响应头里那句
     * {@code Server: … Juping/<版本>} 的版本号是单一事实来源（{@link #serverProduct()}），
     * 在别处再写一份就会在升版本时漏改，同一个设备在不同路径上报两个版本号。
     */
    public static final class WebResponse {
        public final String status;
        public final String contentType;
        /** 文本响应体（HTML / JSON）。文件响应时为 null */
        public final String body;

        /**
         * 文件响应。非 null 时忽略 {@link #body}，从文件里按偏移流式写出。
         *
         * <p><b>为什么不能拿 {@link #body} 装媒体</b>：它是 String，
         * 写出去时按 UTF-8 编码 —— 二进制经过这一趟必然损坏（非法字节序列
         * 会被替换成 U+FFFD）。而上传的视频必须一个字节不差地喂给播放器。
         */
        public final File file;
        /** 文件响应的起始偏移（0 = 从头） */
        public final long offset;
        /** 文件响应的字节数 */
        public final long length;

        public WebResponse(String status, String contentType, String body) {
            this(status, contentType, body, null, 0, 0);
        }

        public WebResponse(String status, String contentType, File file,
                           long offset, long length) {
            this(status, contentType, null, file, offset, length);
        }

        private WebResponse(String status, String contentType, String body, File file,
                            long offset, long length) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.file = file;
            this.offset = offset;
            this.length = length;
        }
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

    /**
     * 网页端点。**可以为 null** —— 协议闸门与桌面测试不关心上传功能，
     * 传 null 就是纯粹的历史行为（DLNA 一条不少）。
     */
    private final WebEndpoints web;

    /** GENA 事件分发。订阅状态、SEQ、超时都在这里面。 */
    private final EventDispatcher events;

    private volatile boolean running = true;
    private ServerSocket serverSocket;

    /**
     * 最近一次收到控制指令（POST）的时刻 —— Auto-Stop 的「还有没有人在控制」
     * 判据。SUBSCRIBE 续订不算指令（那由订阅存活状态另行表达）。
     */
    private volatile long lastControlAt = System.currentTimeMillis();

    /** 距最后一次控制指令的毫秒数（Auto-Stop 判据用） */
    public long millisSinceLastControl() {
        return System.currentTimeMillis() - lastControlAt;
    }

    /** 当前投递得通的（未过期且回调可达）事件订阅数（Auto-Stop 判据用） */
    public int aliveSubscriberCount() {
        return events.aliveSubscriberCount();
    }

    /** 最近一次 SUBSCRIBE（新建或续订）的时刻（Auto-Stop 判据用） */
    public long lastSubscribeAt() {
        return events.lastSubscribeAt();
    }

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

    /**
     * 实际监听的端口。
     *
     * <p>正常等于构造时给的首选端口；首选端口被别人占了就自动上移
     * （见 {@link #bindWithFallback()}），此时这里是**真正在听**的那一个。
     * LOCATION、界面显示、{@code /status} 都必须以它为准 —— 谁要是拿首选端口
     * 去拼地址，一旦触发回退就会把控制点指到一个没人监听的端口上，
     * 正是「搜得到却投不了」。
     */
    private volatile int actualPort = -1;

    /**
     * 首选端口被占时，向上试探的次数。
     *
     * <p>49152 是 UPnP 惯例端口，而老电视上厂家自带的 DLNA 栈很可能也占它
     * （已实测海信 {@code com.hisense.an} 抢 1900）。绑不上就只有 HTTP 层死掉、
     * SSDP 照活 —— 表现是「手机搜得到设备，但一点投屏就失败」，重启 App 也救不回。
     * 向上试若干个足够避开这种抢占，又不会去占无关的端口。
     */
    private static final int PORT_BIND_TRIES = 20;

    public UpnpHttpServer(int port, String uuid, String friendlyName, String versionName,
                          CommandHandler handler, EventDispatcher.EventSource eventSource,
                          WebEndpoints web) {
        super("upnp-http");
        setDaemon(true);
        this.port = port;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.versionName = versionName;
        this.handler = handler;
        this.web = web;
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

    /**
     * 实际监听的端口（首选端口被占时是回退后的那个）。
     *
     * <p>{@code start()} 里同步完成绑定，所以调用它之前先 {@code start()} 一次，
     * 拿到的就是确定值。还没绑上时退回首选端口 —— 那只是为了日志/界面有个可打印的数，
     * 不代表这个端口可用（可用性要看 {@link #isBound()}）。
     */
    public int getPort() {
        int p = actualPort;
        return p > 0 ? p : port;
    }

    /**
     * 启动。**绑定在这一步同步完成**（含端口回退），accept 循环才丢给线程。
     *
     * <p>为什么必须同步绑：LOCATION 里的端口要和 HTTP 真正在听的端口**是同一个**。
     * 原来绑定发生在子线程里，调用方在构造 SSDP 时只能提前写一个固定端口 ——
     * 一旦这里触发回退，手机拿到的地址就指向一个没人监听的端口，
     * 表现正是「搜得到设备但一点投屏就失败」。同步绑定之后，
     * {@link #getPort()} 在 start() 返回时就已经是确定值。
     */
    @Override
    public synchronized void start() {
        if (!bindWithFallback()) {
            // 候选端口全被占：不起 accept 线程。此时 isAlive() 与 isBound() 都是 false，
            // 服务层的看门狗会按既有路径重建 —— 等于带退避的重试，不必在这里自旋。
            return;
        }
        super.start();
    }

    /**
     * 绑定监听端口，首选端口被占就往上试。
     *
     * @return 是否绑上；false 表示候选端口全被占，HTTP 层不可用
     */
    private boolean bindWithFallback() {
        for (int i = 0; i < PORT_BIND_TRIES; i++) {
            int candidate = port + i;
            final ServerSocket s;
            try {
                s = new ServerSocket(candidate);
            } catch (IOException e) {
                Log.w(TAG, "端口 " + candidate + " 绑定失败: " + e.getMessage());
                continue;
            }
            serverSocket = s;
            // 绑上之后必须**立刻**复查 running。
            //
            // 竞态：shutdown() 可能正好落在「构造 ServerSocket」与「这一句」之间。
            // 那一刻 serverSocket 字段还是 null，shutdown 里那句 close 被跳过，
            // 而这个线程紧接着就把端口绑上了 —— 端口被永久占住。
            // 下次服务启动时 bind 直接失败，HTTP 层彻底死掉，
            // 而 SSDP 还活着 —— 表现就是「手机搜得到设备，但一点投屏就失败」，
            // 且重启 App 也救不回来（端口一直占着）。
            if (!running) {
                closeQuietly(s);
                serverSocket = null;
                Log.i(TAG, "启动途中已被要求关闭，端口 " + candidate + " 已释放");
                return false;
            }
            actualPort = candidate;
            bound = true;
            if (i > 0) {
                Log.w(TAG, "首选端口 " + port + " 被占，已回退到 " + candidate);
            }
            Log.i(TAG, "UPnP HTTP 服务已启动，端口 " + candidate);
            return true;
        }
        Log.e(TAG, "端口 " + port + "~" + (port + PORT_BIND_TRIES - 1)
                + " 全部绑定失败，HTTP 层不可用（SSDP 仍在，控制点会「搜得到但投不了」）");
        return false;
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try {
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
            // 网页端点要用的三个头：Accept 用来把 "/" 分给浏览器而不是 device.xml，
            // Content-Type 用来取 multipart 的 boundary，Range 用来支持媒体拖拽。
            String acceptHeader = null;
            String contentTypeHeader = null;
            String rangeHeader = null;
            // ---- 三类「取证头」：只收集，不改变任何处理逻辑 ----
            // 芒果 TV 投屏卡在「连接中」的归因全靠这几个：Expect 决定客户端会不会
            // 等 100-continue（G1）、Transfer-Encoding 决定它是不是 chunked（G2）、
            // User-Agent 决定到底是不是它。
            String uaHeader = null;
            String expectHeader = null;
            String transferEncodingHeader = null;
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
                    contentLength = parseContentLength(line.substring(15).trim());
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
                } else if (lower.startsWith("accept:")) {
                    acceptHeader = line.substring(7).trim();
                } else if (lower.startsWith("content-type:")) {
                    contentTypeHeader = line.substring(13).trim();
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                } else if (lower.startsWith("user-agent:")) {
                    uaHeader = line.substring(11).trim();
                } else if (lower.startsWith("expect:")) {
                    expectHeader = line.substring(7).trim();
                } else if (lower.startsWith("transfer-encoding:")) {
                    transferEncodingHeader = line.substring(18).trim();
                }
            }

            // ---- 批 0 取证：请求头摘要 ----
            // INFO 只记「异常形态」—— POST 控制面、或带 Expect / Transfer-Encoding 的
            // 那次请求。其余（高频轮询 GET / SUBSCRIBE）走 DEBUG，否则会把日志刷爆。
            // 判据见 .agent/mangotv-compat-plan.md §7 的 A/B/C/D/E 五条。
            {
                String headSummary = method + " " + path + " len=" + contentLength
                        + " ua=" + uaHeader
                        + " expect=" + expectHeader
                        + " te=" + transferEncodingHeader;
                if ("POST".equals(method) || expectHeader != null
                        || transferEncodingHeader != null) {
                    Log.i(TAG, "取证 请求头: " + headSummary);
                } else {
                    Log.d(TAG, "取证 请求头: " + headSummary);
                }
            }

            // ---- 网页端点先分流，再走 DLNA ----
            //
            // 位置是硬要求，必须在下面 MAX_BODY_BYTES 那道上限检查**之前**：
            // MAX_BODY_BYTES 是 256KB（给 SOAP / GENA 用的），而网页上传的 body
            // 是 GB 级的 —— 顺序颠倒，每一个上传都会被 413 挡掉。
            // 更不能让它走到下面那条一次性分配整个 body 的路径上（0.6GB 内存的盒子当场 OOM）。
            // 同一纪律的另一半见 tools/policy-test/run.sh 里 handleConnection 的顺序断言。
            if (web != null) {
                WebResponse wr = web.handle(method, path, acceptHeader, contentTypeHeader,
                        rangeHeader, contentLength, in);
                if (wr != null) {
                    OutputStream webOut = socket.getOutputStream();
                    // HEAD 与 GET 必须"除了没有 body 之外完全一致"（RFC 7231 §4.3.2）——
                    // 头里的 Content-Length / Content-Type 照报，只是不写 body。
                    // 上传的媒体要靠这一条：播放器探测 Content-Type 时先发 HEAD，
                    // 回错了它就把视频当音频（这台 MTK 盒子 getVideoWidth() 恒为 0）。
                    boolean noBody = "HEAD".equals(method);
                    if (wr.file != null) {
                        writeMedia(webOut, wr, noBody);
                    } else {
                        writeSimple(webOut, wr.status, wr.contentType, wr.body, noBody);
                    }
                    webOut.flush();
                    return;
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
            // body 读不满（客户端声明了 N 字节却少发/不发）时，**仍然回一条响应**。
            // 原来这里一声不响地往下走，body 是截断的、控制点那头只看到连接被关，
            // 表现就是「一直连接中」（它既拿不到成功也拿不到失败）。回 400 并记下
            // 期望/实收字节数 —— 这正是判据 B「body 是空的」要看的证据。
            if (read < contentLength) {
                Log.w(TAG, "取证 body 读不满：期望 " + contentLength + " 字节，实收 " + read
                        + " 字节，回 400（不静默关连接）");
                OutputStream shortOut = socket.getOutputStream();
                writeSimple(shortOut, "400 Bad Request", "text/plain", "");
                shortOut.flush();
                return;
            }
            // body 才是可能含非 ASCII 的部分，按 UTF-8 解
            String body = new String(bodyBytes, 0, read, "UTF-8");
            // 原始 body 摘要（DEBUG，截断前 200 字符）。判据 B/C 要看「body 是空的」
            // 还是「动作元素前缀不是 u:」—— 只在 DEBUG 级，高频轮询不会刷屏。
            Log.d(TAG, "取证 body(" + read + "/" + contentLength + "): "
                    + (body.length() > 200 ? body.substring(0, 200) + "…" : body));

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
                    utf8(DlnaDescription.deviceDescription(uuid, friendlyName, versionName,
                            getPort(), handler.getLocalIp(),
                            iconPng, iconWidth, iconHeight)));
        }
        // 三份 SCPD 的路由与设备描述里的服务清单**同源**（DlnaDescription.SERVICES）：
        // 声明了哪个服务，这里就一定能取到它那份 SCPD —— 不可能出现"device.xml 里
        // 声明了服务、SCPDURL 却 404"。原来这三段是手写的，加第四个服务时极易漏改。
        for (int i = 0; i < DlnaDescription.SERVICES.length; i++) {
            String shortName = DlnaDescription.SERVICES[i];
            if (path.contains(shortName + ".xml")) {
                return new StaticResource(true, "text/xml; charset=\"utf-8\"",
                        utf8(DlnaDescription.scpdFor(shortName)));
            }
        }
        if (DlnaDescription.ICON_PATH.equals(path)) {
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
        // /status 诊断页：手机浏览器可达的 JSON 状态（排障不需要 adb）。
        // 放在静态路由之前 —— 它是动态资源，永远 200。
        if (path.equals("/status")) {
            String body = handler.buildStatusJson();
            if (body == null || body.length() == 0) {
                body = "{}";
            }
            writeSimple(out, "200 OK", "application/json; charset=\"utf-8\"", body);
            return;
        }
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
        // 批 0 取证：判据 D（G5 播放线程被拖后）靠「收到 SetAVTransportURI」与
        // 「已回 200」两条日志的时间差算间隔。
        Log.d(TAG, "取证 已回 " + (r.found ? "200 OK" : "404 Not Found"));
    }

    // ------------------------------------------------------------- 设备图标

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

    // ------------------------------------------------- SUBSCRIBE（GENA 事件）

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
        if (!DlnaDescription.isKnownService(service)) {
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

    private void handlePost(String path, String soapAction, String body, OutputStream out)
            throws IOException {
        lastControlAt = System.currentTimeMillis();
        String service = lastSegment(path);
        String action = extractActionName(soapAction, body);
        Log.i(TAG, "控制指令: service=" + service + " action=" + action);

        if (action == null || !DlnaDescription.isKnownAction(action)) {
            Log.w(TAG, "不支持的 action，回 401 Fault: " + action);
            writeSoapFault(out, service, "401", "Invalid Action");
            return;
        }

        try {
            Map<String, String> args = extractArguments(body);

            // 批 0 取证：把 RenderingControl 的入参原样记下来。
            // 「音量调不动」的判据 ①（控制点到底发没发）就靠这一行 ——
            // 它发的若走 dB 通道（SetVolumeDB），会先在 isKnownAction 被挡下、
            // 记成「不支持的 action，回 401 Fault: SetVolumeDB」，两行一起看即可定位。
            if ("RenderingControl".equals(service)) {
                Log.i(TAG, "取证 音量指令: " + action + " " + args);
            }

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
        if ("Play".equals(action)) {
            // Play 的 in:Speed 是**声明过的**参数（不声明会让 Cling 系控制点
            // 连 Play 都发不出来，见 SCPD 里那段注释），既然声明了就得对它负责：
            // 这台盒子没有变速播放能力，非 1x 如实回 701，**不静默按 1x 播**。
            // 规范里 AllowedValue 只有 1 和 1/2，所以 "1" 与缺省之外都是 1/2。
            String speed = get(args, "Speed");
            return (speed.length() == 0 || "1".equals(speed))
                    ? null : "没有变速播放能力，收到 Speed=" + speed;
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
        } else if ("SetNextAVTransportURI".equals(action)) {
            // 播放列表/连续播放：当前曲目播完后自动续播下一曲
            handler.onSetNextUri(get(args, "NextURI"), get(args, "NextURIMetaData"));
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
     * <p><b>为什么现在有 image/*</b>：曾经声明过又被撤掉，当时整套实现里根本没有
     * 图片这条路 —— {@code kindOf()} 只认 audioItem / videoItem，而
     * {@code MediaPlayer} 本身也不解图片，声明了却做不到，控制点（相册、
     * 文件管理器）就会把图片推过来然后必然失败。
     *
     * <p>现在图片这条路补齐了：{@code kindOf()} 认 {@code object.item.imageItem}，
     * 界面用 {@code BitmapFactory} 解码后画在 ImageView 上，**不经过
     * MediaPlayer**。清单必须与实现严格一致 —— 这是控制点判断"能不能推给我"
     * 的唯一依据：宁可让它一开始就说"这台设备不支持"，也不要收下再失败；
     * 反过来，做到了却不声明，控制点就不会把照片推过来。
     *
     * <p>只声明 jpeg / png 两种，不多声明 gif / webp：解码通道
     * （{@code BitmapFactory}）虽然认得它们，但**控制点据此推流的概率极低**，
     * 而清单每多一项，就是多一条"声明了却没人验证过"的路径。
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
                    + "<NextURI>" + escapeXml(handler.getNextUri()) + "</NextURI>"
                    + "<NextURIMetaData>" + escapeXml(handler.getNextUriMetadata()) + "</NextURIMetaData>"
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
        writeSimple(out, status, contentType, body, false);
    }

    /**
     * @param noBody HEAD 请求：头照报（Content-Length 与 GET 一致），只是不写 body
     */
    private void writeSimple(OutputStream out, String status, String contentType, String body,
                             boolean noBody) throws IOException {
        byte[] payload = (body == null) ? new byte[0] : body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(payload.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Server: Android UPnP/1.0 " + serverProduct() + "\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (!noBody) {
            out.write(payload);
        }
        // 批 0 取证：一行「已回 <状态码>」。判据 D 靠它算「收到指令」到「响应写出」
        // 的间隔；判据 A/B（客户端发了 Expect/chunked，我们没接住）靠它确认
        // 到底有没有回过东西。
        Log.d(TAG, "取证 已回 " + status);
    }

    /**
     * 把本地文件按 Range 流式写出去（网页上传的东西投屏时取的就是这条路径）。
     *
     * <p><b>为什么播放必须走 HTTP，而不是 {@code file://}</b>：真机实测（192.168.1.8，
     * 2026-10-01）{@code /data/data/<包名>/files/uploads} 是 {@code drwx------}，
     * 而真正去 open 这个文件的是**另一个进程** mediaserver（uid media）——
     * 它既不是我们的 uid、也没有目录的通行位，只会拿到一句
     * {@code error (1, -2147483648)}，电视上什么都不放。
     * 改由我们自己的进程以 HTTP 提供，权限问题就不存在了。
     *
     * <p>顺带解决第二件事：Content-Type 探测（见
     * {@code MediaPlayerController#scheduleContentTypeProbe}）需要一个能响应 HEAD
     * 的 **http** 地址。这台 MTK 5880 上 {@code getVideoWidth()} 恒返回 0，
     * 少了这一步，上传的视频会被判成纯音频 —— 画面正常放、界面却显示音乐卡片。
     *
     * <p>Range 不是可选功能：MP4 的拖拽与续播依赖按字节寻址，
     * 没有它控制点一拖进度条就会从头重放。
     */
    private void writeMedia(OutputStream out, WebResponse wr, boolean noBody) throws IOException {
        long total = wr.file.length();
        long start = (wr.offset < 0) ? 0 : wr.offset;
        if (start > total) {
            start = total;
        }
        long len = (wr.length < 0) ? total - start : wr.length;
        if (start + len > total) {
            len = total - start;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(wr.status).append("\r\n");
        sb.append("Content-Type: ").append(wr.contentType).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        // 必须声明：不声明的话播放器不知道能按字节取，拖进度条就只能重下。
        sb.append("Accept-Ranges: bytes\r\n");
        if (start > 0 || len < total) {
            sb.append("Content-Range: bytes ").append(start).append('-')
              .append(start + Math.max(len, 1) - 1).append('/').append(total).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("Server: Android UPnP/1.0 " + serverProduct() + "\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (noBody || len == 0) {
            return;
        }
        FileInputStream fis = new FileInputStream(wr.file);
        try {
            long skipped = 0;
            while (skipped < start) {
                // FileInputStream.skip 允许少跳（返回 0 也不代表到末尾），必须循环。
                long n = fis.skip(start - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = len;
            while (remaining > 0) {
                int want = (int) Math.min((long) buf.length, remaining);
                int n = fis.read(buf, 0, want);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
        } finally {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
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
     * 解析 {@code Content-Length}。
     *
     * <p><b>超出 int 时钳到 Integer.MAX_VALUE，不能当"解析失败 = 0"</b>：
     * 真机实测（192.168.1.8，2026-10-01）一个声明 {@code Content-Length: 2200000000}
     * 的请求被当成 0，于是回给客户端的是「411 必须带 Content-Length」——
     * 而它明明带了。这是守卫在**谎报**：客户端会照着自己的理解去改请求，
     * 改对不了，因为真正的原因（太大）它一个字都没被告知。
     * 钳住之后走到下面那道上限检查，得到的就是正确的 413。
     */
    private static int parseContentLength(String s) {
        try {
            long n = Long.parseLong(s.trim());
            if (n > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) n;
        } catch (Exception e) {
            return 0;
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
     * AVTransport 事件文档（{@code LastChange} 的值）所用的 XML 命名空间。
     *
     * <p>规范里 AVTransport:1 的事件文档根元素就落在这个命名空间下，
     * 与设备描述、SCPD 的命名空间都不同 —— 三者不能混用。
     */
    public static final String AVT_EVENT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/";

    /**
     * 组一份 AVTransport 的 {@code LastChange} 事件文档。
     *
     * <p>UPnP AV 里 AVTransport 的事件**不是**逐个变量推的：SCPD 中只有
     * {@code LastChange} 一个变量声明为 {@code sendEvents="yes"}，其余
     * （{@code TransportState} / {@code CurrentTrackURI} …）都是 {@code "no"}。
     * 变化的内容以**一段 XML 文档**塞进 {@code LastChange} 的**值**里，
     * 每台实例包在 {@code <InstanceID val="0">} 内，变量写成带 {@code val}
     * 属性的空元素。
     *
     * <p>为什么必须照规范来：Cling 系控制点（芒果 TV 实测）按运行时取回的
     * SCPD 生成桩，事件里出现未声明的变量会被**整条忽略**。逐变量推送时
     * 它收不到 {@code TransportState}，手机上的按钮/进度条就不跟着走。
     *
     * <p><b>转义是两层</b>：这里转义的是**变量值**（视频 CDN 的 URL 几乎必然
     * 带 {@code &}，不转就会把这段文档本身写坏）；{@link EventDispatcher} 组装
     * 事件体时再把整段文档转义一次塞进 {@code <LastChange>}。少一层，
     * 控制点那边就是整条事件解析失败。
     *
     * <p>每次推的是**全量**（规范说的是"变化量"，我们推全部事件化变量）：
     * 控制点按变量逐项合并，多给已知项没有任何副作用；而"订阅即推全量"
     * 本来就是规范硬要求，走同一条路径反而少一处分支。
     *
     * @param transportState     {@code TransportState} 值
     * @param transportStatus    {@code TransportStatus} 值
     * @param trackUri           {@code CurrentTrackURI} 值（无媒体时为空串）
     * @param trackDuration      {@code CurrentTrackDuration} 值（{@code HH:MM:SS}）
     * @param relTimePosition    {@code RelativeTimePosition} 值（{@code HH:MM:SS}）
     */
    public static String avtLastChange(String transportState, String transportStatus,
            String trackUri, String trackDuration, String relTimePosition) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<Event xmlns=\"").append(AVT_EVENT_NS).append("\">");
        sb.append("<InstanceID val=\"0\">");
        avtVar(sb, "TransportState", transportState);
        avtVar(sb, "TransportStatus", transportStatus);
        avtVar(sb, "CurrentTrackURI", trackUri);
        avtVar(sb, "CurrentTrackDuration", trackDuration);
        avtVar(sb, "RelativeTimePosition", relTimePosition);
        sb.append("</InstanceID></Event>");
        return sb.toString();
    }

    /** 事件文档里的一个变量：{@code <名字 val="值"/>}，值先转义。 */
    private static void avtVar(StringBuilder sb, String name, String value) {
        sb.append('<').append(name).append(" val=\"")
                .append(escapeXml(value)).append("\"/>");
    }

    /**
     * XML 转义。**嵌 URL 时必须过这一道**。
     *
     * <p>视频 CDN 的地址几乎必然带查询串，例如
     * {@code http://cdn/x.mp4?token=abc&expire=123}。这个 {@code &} 直接写进
     * {@code <CurrentURI>} 会让整份 SOAP 响应变成非法 XML —— 控制点那边不是
     * "这一项读不到"，而是**整条报文解析失败**，表现为投屏后立刻报错。
     *
     * <p>实现已移到 {@link DlnaDescription#escapeXml(String)}。搬家的理由：
     * 设备描述里也要转义（friendlyName / uuid / 主机名），而**两处的规则必须
     * 完全一致**（{@code &} 最先替换，否则会把刚生成的实体再转一遍）。
     * 实现只有一份，这里留个转发是因为调用方（SOAP 响应、事件体）散在本类各处。
     */
    public static String escapeXml(String s) {
        return DlnaDescription.escapeXml(s);
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

}
