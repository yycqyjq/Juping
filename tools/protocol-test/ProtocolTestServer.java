import com.juping.cast.dlna.DidlLite;
import com.juping.cast.dlna.EventDispatcher;
import com.juping.cast.dlna.SsdpResponder;
import com.juping.cast.dlna.UpnpHttpServer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * 协议测试用的服务端。
 *
 * <p>把**真实的** UpnpHttpServer 和 SsdpResponder 跑在桌面 JVM 上，
 * 配一个「记录型」业务回调 —— 每条指令连同参数都写进日志文件，测试驱动再去核对。
 *
 * <p>这样验证的是完整链路：
 * TCP → HTTP 头解析 → SOAP 解析 → 指令分发 → 业务回调拿到正确参数。
 * 中间任何一环出错都会在日志里露出来。
 *
 * <p>SSDP 那半边同样跑真货：真的 bind 端口、真的收 M-SEARCH、真的构造应答。
 * 驱动用**单播** UDP 把 M-SEARCH 发到该端口即可 ——
 * 绑定在通配地址上的 socket 一样能收到单播包，于是整套逻辑都能在桌面上验证，
 * 不必依赖组播（组播在不同平台/网络环境下的行为差异很大，不适合做自动化断言）。
 *
 * <p>因此 READY 只要求「端口绑上了」，**不要求** joinGroup 成功：CI（Azure VM
 * 不转发组播）上 joinGroup 必失败，但端口能绑、单播能收，测试照样跑得通。
 * 组播是否加入作为独立事实单独报告（stderr 警告 + READY 行末尾一列）。
 *
 * <p>用法：java ProtocolTestServer &lt;http端口&gt; &lt;指令日志路径&gt; &lt;ssdp端口&gt;
 */
public class ProtocolTestServer {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    /**
     * 测试版本号。喂给 UpnpHttpServer / SsdpResponder，驱动据此断言
     * device.xml 的 modelNumber 与响应的 Server 头 —— 与真实构建里
     * BuildConfig.VERSION_NAME 的注入路径是同一个构造参数。
     */
    static final String TEST_VERSION = "9.9.9";

    /** 固定的返回値，测试驱动按这些值来断言 */
    private static final long FAKE_POSITION_MS = 123456L;   // 00:02:03
    private static final long FAKE_DURATION_MS = 7200000L;  // 02:00:00

    /**
     * 测试图标的尺寸。
     *
     * <p>刻意用一个"不像任何真实图标"的尺寸（13×7）：驱动要核对
     * device.xml 里声明的宽高与 PNG 里 IHDR 报的宽高是否一致，
     * 如果两边都写死 48，那么"声明跟着实际走"这件事就验不出来。
     */
    private static final int ICON_W = 13;
    private static final int ICON_H = 7;

    private static volatile String transportState = "NO_MEDIA_PRESENT";
    private static volatile String currentUri = "";
    private static volatile int volume = 42;

    /**
     * 静音状态。和音量一样是**独立**的一格 —— 这正是被测点：
     * "静音"不是"音量 0"，两个状态必须分开记。
     *
     * <p>驱动会 SetMute(true) 之后回读 GetMute，还会检查事件里的 Mute；
     * 只要实现里哪一处写死了常量，那几条断言就会红。
     */
    private static volatile boolean muted = false;

    /**
     * 出错态模拟。
     *
     * <p>片源地址里带 {@code boom} 就置上，{@code Stop} 清掉 —— 驱动据此把靶机
     * 推到「正在出错」这一格，验证 {@code GetTransportInfo} 的
     * {@code CurrentTransportStatus} 与事件里的 {@code TransportStatus}
     * <b>在出错时也一致</b>。
     *
     * <p>不这么做的话，两个接口都恒回 {@code OK}，那条「两边一致」的断言
     * 在实现把其中一个写死时照样绿 —— 等于没测。
     */
    private static volatile String lastError = "";

    /**
     * 最近一次收到的元数据原文。
     *
     * <p>真实服务里也是**原样存、原样回读** —— 靶机必须同样保真，
     * 否则测出来的"回读一致"在真机上不成立（这正是「测试替身不能比被测对象宽容」
     * 那条纪律的另一面：也不能比它更"理想"）。
     */
    private static volatile String currentMetadata = "";

    /**
     * 业务回调里要能触发事件推送（和真实服务一样：状态一变就 notifyEvent）。
     * 服务对象本身在 handler 之后才构造出来，所以用个静态引用兜一下。
     */
    private static volatile UpnpHttpServer SERVER;

    /** 状态变了就推事件 —— 真实服务里是 DlnaRendererService#notifyEvent，语义一致。 */
    private static void push(String service) {
        UpnpHttpServer s = SERVER;
        if (s != null) {
            s.notifyEvent(service);
        }
    }

    /**
     * DidlLite 解析器自检 —— 在起服务**之前**跑。
     *
     * <p>放这里而不是单独写一个测试类：协议闸门反正要编译并运行这个文件，
     * 挂在这里 = 解析器坏了协议闸门直接红，不需要新增任何脚本和闸门。
     * 断言失败抛 AssertionError 退出（非 0），run.sh 会同样拦下来。
     */
    private static void didlSelftest() {
        // 标题 / 艺术家，标准命名空间写法
        String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\"><dc:title>夜曲</dc:title>"
                + "<upnp:artist>周杰伦</upnp:artist>"
                + "<upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>";
        expect("dc:title", "夜曲", DidlLite.title(didl));
        expect("upnp:artist", "周杰伦", DidlLite.artist(didl));

        // 无前缀写法也要认 —— 换个控制点就不带前缀，漏了的话界面上
        // 会**悄悄**退回显示文件名，不报错、没人发现
        expect("无前缀 title", "Don't Stop", DidlLite.title("<item><title>Don't Stop</title></item>"));

        // 实体反转义。&amp; 只能替换一次（先换它会让 &amp;lt; 二次替换成 <）；
        // &#39; 是 DIDL 里单引号的常见写法，歌名带单引号非常常见
        expect("实体反转义", "Tom & Jerry '07",
                DidlLite.title("<item><dc:title>Tom &amp; Jerry &#39;07</dc:title></item>"));

        // 数字实体的十六进制写法
        expect("十六进制实体", "'", DidlLite.title("<title>&#x27;</title>"));

        // 空串 / null / 没有该元素 —— 一律空串，不抛异常。
        // 界面靠"空串"判断要不要回退到文件名，抛异常会掀翻整条投屏流程
        expect("空元数据", "", DidlLite.title(""));
        expect("null 元数据", "", DidlLite.title(null));
        expect("没有标题元素", "", DidlLite.title("<item><upnp:artist>x</upnp:artist></item>"));

        // 未知实体原样保留 —— 悄悄吃掉会让问题藏起来，暴露出来才好修
        expect("未知实体原样保留", "&nbsp;", DidlLite.title("<title>&nbsp;</title>"));

        System.out.println("  DidlLite 自检：9 / 9 通过");
    }

    private static void expect(String what, String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("DidlLite 自检失败: " + what
                    + " 期望<" + want + "> 实际<" + got + ">");
        }
    }

    public static void main(String[] args) throws Exception {
        didlSelftest();

        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 49152;
        final String callLog = args.length > 1 ? args[1] : "/tmp/juping-calls.log";
        // SSDP 用临时端口，不去抢 1900 —— 免得和机器上真的 SSDP 服务打架
        final int ssdpPort = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        final PrintWriter log = new PrintWriter(new FileWriter(callLog, false), true);

        UpnpHttpServer.CommandHandler handler = new UpnpHttpServer.CommandHandler() {
            private void rec(String... fields) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < fields.length; i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    // 把换行和竖线转义，保证一行一条记录
                    sb.append(fields[i] == null ? "<null>"
                            : fields[i].replace("\\", "\\\\")
                                      .replace("|", "\\p")
                                      .replace("\r", "\\r")
                                      .replace("\n", "\\n"));
                }
                log.println(sb);
            }

            @Override
            public void onSetUri(String uri, String metadata) {
                rec("SetAVTransportURI", uri, metadata);
                // 和真实服务保持一致：收到 URI 就记下来，GetMediaInfo 要回读它
                currentUri = uri == null ? "" : uri;
                // 元数据同样原样记住，供 GetMediaInfo / GetPositionInfo 回读
                currentMetadata = metadata == null ? "" : metadata;
                // 新片源到达，「下一曲」作废（与真实服务一致）
                nextUri = "";
                nextUriMetadata = "";
                // 片源带 boom → 模拟一个播放错误（真实服务里是 onError 置的）
                lastError = (uri != null && uri.contains("boom")) ? "模拟播放错误" : "";
                transportState = "TRANSITIONING";
                push("AVTransport");
            }

            private String nextUri = "";
            private String nextUriMetadata = "";

            @Override
            public void onSetNextUri(String uri, String metadata) {
                nextUri = uri == null ? "" : uri;
                nextUriMetadata = metadata == null ? "" : metadata;
                rec("SetNextAVTransportURI", nextUri, nextUriMetadata);
                transportState = "TRANSITIONING";
                push("AVTransport");
            }

            @Override
            public String getNextUri() {
                return nextUri;
            }

            @Override
            public String getNextUriMetadata() {
                return nextUriMetadata;
            }

            @Override
            public String buildStatusJson() {
                return "{\"state\":\"" + transportState
                        + "\",\"currentUri\":\"" + currentUri + "\"}";
            }

            @Override
            public void onPlay() {
                rec("Play");
                // 忠实模拟真实服务：没有媒体时 MediaPlayerController.resume() 是空操作
                // （stop() 已把 currentUrl 清成 null），状态不会变成 PLAYING。
                // 不模拟这一点，靶机就能被驱动到真机到不了的状态 —— 后面的
                // dlna-probe 会据此报出一条根本不存在的「不一致」，把人引去查假 bug。
                if (currentUri.length() == 0) {
                    return;
                }
                transportState = "PLAYING";
                push("AVTransport");
            }

            @Override
            public void onPause() {
                rec("Pause");
                // 同理：pause() 只在真的在播时才生效
                if (!"PLAYING".equals(transportState)) {
                    return;
                }
                transportState = "PAUSED_PLAYBACK";
                push("AVTransport");
            }

            @Override
            public void onStop() {
                rec("Stop");
                currentUri = "";
                // 元数据一起清 —— 和真实服务保持一致：留着的话，
                // GetMediaInfo 会在"没有媒体"的时候回一份上一部片子的元数据
                currentMetadata = "";
                // 下一曲队列一并作废（与真实服务一致：Stop 清歌单）
                nextUri = "";
                nextUriMetadata = "";
                lastError = "";
                transportState = "STOPPED";
                push("AVTransport");
            }

            @Override
            public void onSeek(long positionMs) {
                rec("Seek", String.valueOf(positionMs));
            }

            @Override
            public long getPositionMs() {
                return FAKE_POSITION_MS;
            }

            @Override
            public long getDurationMs() {
                return FAKE_DURATION_MS;
            }

            @Override
            public String getTransportState() {
                return transportState;
            }

            @Override
            public String getTransportStatus() {
                // 与事件里的 TransportStatus 共用同一个判据 —— 这正是被测点。
                return lastError.length() > 0 ? "ERROR_OCCURRED" : "OK";
            }

            @Override
            public String getCurrentUri() {
                return currentUri;
            }

            @Override
            public String getCurrentMetadata() {
                return currentMetadata;
            }

            @Override
            public void onSetVolume(int volume0to100) {
                volume = volume0to100;
                rec("SetVolume", String.valueOf(volume0to100));
                push("RenderingControl");
            }

            @Override
            public int getVolume0to100() {
                return volume;
            }

            @Override
            public void onSetMute(boolean mute) {
                muted = mute;
                rec("SetMute", mute ? "1" : "0");
                push("RenderingControl");
            }

            @Override
            public boolean getMute() {
                return muted;
            }
        };

        // 事件源：和真实服务一样，如实汇报当前状态。
        // 驱动会订阅之后改状态，再核对收到的 NOTIFY 里字段对不对。
        EventDispatcher.EventSource source = new EventDispatcher.EventSource() {
            @Override
            public Map<String, String> eventedVars(String service) {
                Map<String, String> vars = new HashMap<String, String>();
                if ("AVTransport".equals(service)) {
                    vars.put("TransportState", transportState);
                    // 与 GetTransportInfo 的 CurrentTransportStatus **同源** ——
                    // 真实服务里两边都走 DlnaRendererService.getTransportStatus()。
                    // 靶机也这么写，驱动才能验证"两个接口说法一致"。
                    vars.put("TransportStatus", handler.getTransportStatus());
                    vars.put("CurrentTrackURI", currentUri);
                    vars.put("CurrentTrackDuration", UpnpHttpServer.formatTime(FAKE_DURATION_MS));
                    return vars;
                }
                if ("RenderingControl".equals(service)) {
                    vars.put("Volume", String.valueOf(volume));
                    // 和真实服务一致：报真实静音状态，不是写死的常量
                    vars.put("Mute", muted ? "1" : "0");
                    return vars;
                }
                if ("ConnectionManager".equals(service)) {
                    vars.put("SourceProtocolInfo", "");
                    vars.put("SinkProtocolInfo", UpnpHttpServer.SINK_PROTOCOL_INFO);
                    vars.put("CurrentConnectionIDs", "0");
                    return vars;
                }
                return vars;
            }
        };

        // 版本号用 9.9.9 而不是照抄真实版本：drive.py 断言 device.xml 的
        // modelNumber 与 SERVER 头就是这个值 —— 这能证明「版本号真的从
        // 构造函数贯通到了协议层」，而不是两处碰巧都写着同一个常量。
        UpnpHttpServer server = new UpnpHttpServer(port, UUID, "聚屏-TESTBOX", TEST_VERSION,
                handler, source);
        SERVER = server;

        // 给一份真图标，让驱动能验"声明了就必须给得出"这条纪律：
        //   · device.xml 里出现 iconList，且宽高与这里传的一致；
        //   · /upnp/icon.png 回 200 + image/png，且**字节一个不差**。
        // 用真 PNG 而不是随便几个字节 —— 后者验不出"字节有没有被 UTF-8 编坏"
        // 这个真实的坑（PNG 里大量字节不是合法 UTF-8 序列）。
        byte[] icon = makeTestPng(ICON_W, ICON_H);
        server.setIcon(icon, ICON_W, ICON_H);

        server.start();

        // 只把 HTTP **实际监听**的端口交给 SSDP —— LOCATION 由响应器在
        // **绑上组播之后**用实际绑定的那张网卡的 IPv4 拼出来（和真实服务完全一致）。
        // 端口取 getPort() 而不是构造时那个入参：绑定时被占会回退，
        // 入参就不一定是真正在听的那个了（与生产代码 DlnaRendererService 一致）。
        // 驱动会顺着它去抓 device.xml，这一步正是「搜到了却投不了屏」的典型断点。
        SsdpResponder ssdp = new SsdpResponder(UUID, server.getPort(), "Android/4.0.4", ssdpPort,
                TEST_VERSION);
        ssdp.start();

        // 等两个服务真的起来，再告诉驱动可以开始了
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.getPort())) {
                break;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        // 给 SSDP 线程一点时间完成 bind（以及可能的 joinGroup）。
        // 它现在带重试，正常情况下第一次就成；这里多等一会儿是为了让
        // 「第一次失败、第二次成功」这种平台差异不会把测试变成偶发红。
        long ssdpDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < ssdpDeadline && !ssdp.isPortBound()) {
            Thread.sleep(50);
        }

        // 驱动只需要**端口能收单播** —— 不要求加入组播。
        //
        // CI（GitHub runner = Azure VM）的网络不转发组播，joinGroup 必失败；
        // 但端口照样能绑、单播照样能收。把「端口可用」与「组播已加入」拆开，
        // 靶机就不会再因为「组播绑不上」而 exit 3 —— 这正是 protocol 闸门
        // 在 CI 上一直红的根因。
        int actualSsdpPort = ssdp.getLocalPort();
        if (actualSsdpPort <= 0) {
            System.err.println("SSDP 端口未绑定成功（网卡选择失败？）");
            System.err.flush();
            System.exit(3);
        }

        // 组播是否加入**单独报告**：不影响测试能否跑，但要让人一眼看出
        // 现在是「单播模式」还是「真机同款的组播模式」—— 而不是靠猜。
        if (!ssdp.isMulticastJoined()) {
            System.err.println("警告：SSDP 未加入组播组（端口已绑，仅单播可用）——"
                    + "CI / 无组播环境下正常；本机模拟见 JUPING_SSDP_NO_MULTICAST=1");
            System.err.flush();
        }

        String location = ssdp.getLocation();
        if (location == null || location.length() == 0) {
            System.err.println("SSDP 绑上了但没算出 LOCATION");
            System.err.flush();
            System.exit(3);
        }

        // READY <http端口> <实际ssdp端口> <location> <绑定网卡> <绑定网卡的IPv4> <组播状态>
        //
        // 后三列都是**新增**的，加在末尾是为了不破坏 run.sh 里按 $3/$5/$6 的解析。
        // 第 5/6 列让驱动核对 LOCATION 里的 IP 真的来自**实际绑定的那张网卡**
        // （而不是另一张）—— 正是「组播从 eth0 收、却告诉手机去 wlan0 取描述」
        // 那个「搜到了却投不了屏」的故障点。第 7 列是组播状态（multicast /
        // no-multicast），让「单播模式」在日志里一眼可见。
        System.out.println("READY " + server.getPort() + " " + actualSsdpPort + " " + location
                + " " + ssdp.getBoundInterfaceName() + " " + ssdp.getBoundIp()
                + " " + (ssdp.isMulticastJoined() ? "multicast" : "no-multicast"));
        System.out.flush();

        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * 生成一张真的 PNG。
     *
     * <p>桌面 JVM 有 {@code ImageIO}，直接用就行 —— 这个类只在
     * tools/protocol-test 里用，不会进 APK，所以可以放心依赖 java.awt。
     * 安卓侧的对应实现是 {@code Bitmap.compress()}（见 DlnaRendererService）。
     */
    private static byte[] makeTestPng(int w, int h) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
