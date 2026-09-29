import com.juping.cast.dlna.UpnpHttpServer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 协议测试用的服务端。
 *
 * <p>把真实的 UpnpHttpServer 跑在桌面 JVM 上，配一个「记录型」业务回调 ——
 * 每条指令连同参数都写进日志文件，测试驱动再去核对。
 *
 * <p>这样验证的是完整链路：
 * TCP → HTTP 头解析 → SOAP 解析 → 指令分发 → 业务回调拿到正确参数。
 * 中间任何一环出错都会在日志里露出来。
 *
 * <p>用法：java ProtocolTestServer <端口> <指令日志路径>
 */
public class ProtocolTestServer {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    /** 固定的返回値，测试驱动按这些值来断言 */
    private static final long FAKE_POSITION_MS = 123456L;   // 00:02:03
    private static final long FAKE_DURATION_MS = 7200000L;  // 02:00:00

    private static volatile String transportState = "NO_MEDIA_PRESENT";
    private static volatile int volume = 42;

    public static void main(String[] args) throws Exception {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 49152;
        final String callLog = args.length > 1 ? args[1] : "/tmp/juping-calls.log";

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

        // 等端口真的起来，再告诉驱动可以开始了
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                break;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }

        System.out.println("READY " + port);
        System.out.flush();

        Thread.sleep(Long.MAX_VALUE);
    }
}
