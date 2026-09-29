import com.juping.cast.dlna.SsdpResponder;
import com.juping.cast.dlna.UpnpHttpServer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

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
 * <p>SSDP 那半边同样跑真货：真的 bind 端口、真的 joinGroup、真的收 M-SEARCH、
 * 真的构造应答。驱动用**单播** UDP 把 M-SEARCH 发到该端口即可 ——
 * 绑定在通配地址上的 socket 一样能收到单播包，于是整套逻辑都能在桌面上验证，
 * 不必依赖组播（组播在不同平台/网络环境下的行为差异很大，不适合做自动化断言）。
 *
 * <p>用法：java ProtocolTestServer &lt;http端口&gt; &lt;指令日志路径&gt; &lt;ssdp端口&gt;
 */
public class ProtocolTestServer {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    /** 固定的返回値，测试驱动按这些值来断言 */
    private static final long FAKE_POSITION_MS = 123456L;   // 00:02:03
    private static final long FAKE_DURATION_MS = 7200000L;  // 02:00:00

    private static volatile String transportState = "NO_MEDIA_PRESENT";
    private static volatile String currentUri = "";
    private static volatile int volume = 42;

    public static void main(String[] args) throws Exception {
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
                transportState = "TRANSITIONING";
            }

            @Override
            public void onPlay() {
                rec("Play");
                transportState = "PLAYING";
            }

            @Override
            public void onPause() {
                rec("Pause");
                transportState = "PAUSED_PLAYBACK";
            }

            @Override
            public void onStop() {
                rec("Stop");
                currentUri = "";
                transportState = "STOPPED";
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
            public String getCurrentUri() {
                return currentUri;
            }

            @Override
            public void onSetVolume(int volume0to100) {
                volume = volume0to100;
                rec("SetVolume", String.valueOf(volume0to100));
            }

            @Override
            public int getVolume0to100() {
                return volume;
            }
        };

        UpnpHttpServer server = new UpnpHttpServer(port, UUID, "聚屏-TESTBOX", handler);
        server.start();

        // LOCATION 必须指向真实可达的设备描述地址 —— 驱动会顺着它去抓 device.xml，
        // 这一步正是「搜到了却投不了屏」的典型断点所在。
        String location = "http://127.0.0.1:" + port + "/upnp/device.xml";
        SsdpResponder ssdp = new SsdpResponder(UUID, location, "Android/4.0.4", ssdpPort);
        ssdp.start();

        // 等两个服务真的起来，再告诉驱动可以开始了
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                break;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        // 给 SSDP 线程一点时间完成 bind + joinGroup
        Thread.sleep(300);

        // 驱动需要知道实际绑上的 SSDP 端口（传 0 时由系统分配）
        int actualSsdpPort = ssdp.getBoundPort();
        if (actualSsdpPort <= 0) {
            System.err.println("SSDP 未绑定成功（网卡选择失败？）");
            System.err.flush();
            System.exit(3);
        }

        System.out.println("READY " + port + " " + actualSsdpPort + " " + location);
        System.out.flush();

        Thread.sleep(Long.MAX_VALUE);
    }
}
