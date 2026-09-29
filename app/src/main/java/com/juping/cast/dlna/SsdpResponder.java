package com.juping.cast.dlna;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * SSDP 响应器 —— 让手机端投屏 App「搜得到」这台设备。
 *
 * <p>这是整个接收端最要命的一环。收不到 M-SEARCH，设备就永远不会出现在
 * 腾讯视频 / B站 / 爱奇艺 / QQ音乐 的投屏设备列表里。
 *
 * <h3>Android 上的头号坑</h3>
 * 系统默认会过滤发往组播地址的数据包。没有 {@code WifiManager.MulticastLock}，
 * 这个 socket 收不到任何东西 —— 表现为「设备搜不到」，而代码看起来完全正确。
 * 这个锁必须在整个服务生命周期内持有，释放时机见 {@link com.juping.cast.DlnaRendererService}。
 *
 * <h3>第二个坑</h3>
 * Android 4.0 老设备的 Wi-Fi 芯片在息屏后会进省电模式，导致组播丢包甚至断流。
 * 所以必须配合 WifiLock（WIFI_MODE_FULL_HIGH_PERF），两者缺一不可。
 *
 * <h3>第三个坑</h3>
 * 组播必须绑定到真正承载流量的网卡。老盒子如果插着网线，
 * 流量走的是 eth0 而不是 wlan0，绑错了网卡同样收不到。
 * {@link #findUsableInterface()} 按优先级挑网卡就是这个原因。
 */
public class SsdpResponder extends Thread {

    private static final String TAG = "SsdpResponder";

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /** MediaRenderer 是所有 DLNA 控制点都会查找的标准设备类型 */
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private final String uuid;
    private final String location;   // 设备描述 XML 的 URL
    private final String serverName;

    private volatile boolean running = true;
    private MulticastSocket socket;
    private NetworkInterface boundInterface;

    public SsdpResponder(String uuid, String location, String serverName) {
        super("ssdp-responder");
        setDaemon(true);
        this.uuid = uuid;
        this.location = location;
        this.serverName = serverName;
    }

    /** 当前绑定的网卡名，供 UI 显示和排障用 */
    public String getBoundInterfaceName() {
        return boundInterface == null ? "(未绑定)" : boundInterface.getName();
    }

    @Override
    public void run() {
        try {
            boundInterface = findUsableInterface();
            if (boundInterface == null) {
                Log.e(TAG, "找不到可用网卡，SSDP 无法启动");
                return;
            }

            socket = new MulticastSocket(SSDP_PORT);
            socket.setReuseAddress(true);
            socket.setTimeToLive(4);
            socket.joinGroup(
                    new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT),
                    boundInterface);
            Log.i(TAG, "SSDP 已加入组播组 " + SSDP_ADDR + "，网卡=" + boundInterface.getName());

            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    if (!running) {
                        break;
                    }
                    Log.w(TAG, "接收异常（继续监听）: " + e.getMessage());
                    continue;
                }
                try {
                    handleMessage(new String(packet.getData(), 0, packet.getLength(), "UTF-8"), packet);
                } catch (Exception e) {
                    Log.w(TAG, "处理报文异常", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SSDP 异常退出", e);
        } finally {
            closeQuietly();
        }
    }

    private void handleMessage(String msg, DatagramPacket packet) {
        // 只处理 M-SEARCH（设备搜索）；NOTIFY 是别的设备在上线广播，与我们无关
        if (msg == null || !msg.startsWith("M-SEARCH")) {
            return;
        }

        // 只响应「找所有设备」或「找 MediaRenderer / AVTransport」的请求，
        // 避免对无关搜索做无谓应答，减少局域网噪声。
        boolean wanted = msg.contains("ssdp:all")
                || msg.contains(DEVICE_TYPE)
                || msg.contains("urn:schemas-upnp-org:service:AVTransport:1");
        if (!wanted) {
            return;
        }

        Log.i(TAG, "收到设备搜索，来自 " + packet.getAddress().getHostAddress());
        byte[] payload = buildResponse().getBytes();
        try {
            // M-SEARCH 的响应按规范应「单播」回请求源地址 —— 比组播可靠得多，
            // 老设备上这一点尤其明显。
            DatagramPacket reply = new DatagramPacket(
                    payload, payload.length, packet.getAddress(), packet.getPort());
            socket.send(reply);
        } catch (IOException e) {
            Log.w(TAG, "响应发送失败: " + e.getMessage());
        }
    }

    private String buildResponse() {
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: " + serverName + " UPnP/1.0 Juping/1.0\r\n"
                + "ST: " + DEVICE_TYPE + "\r\n"
                + "USN: uuid:" + uuid + "::" + DEVICE_TYPE + "\r\n"
                + "OPT: \"http://schemas.upnp.org/upnp/1/0/\"; ns=01\r\n"
                + "01-NLS: " + uuid + "\r\n"
                + "\r\n";
    }

    /**
     * 挑一个可用于组播的网卡。
     *
     * <p>优先级：有线 &gt; Wi-Fi。老盒子插网线时，流量走 eth0，
     * 组播也必须绑到 eth0，绑到 wlan0 会收不到任何搜索请求。
     */
    private NetworkInterface findUsableInterface() {
        NetworkInterface wifi = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                // 必须指定 Locale.ROOT。默认 locale 下 "eth0".toUpperCase() 在土耳其语环境里
                // 会得到 "ETH0" 没问题，但 'i'/'I' 的映射会翻转（"wifi" -> "wİFİ"），
                // 结果就是网卡匹配不到、组播绑不上 —— 表现为「手机搜不到设备」。
                // 这类 bug 在中文环境永远复现不出来，所以必须提前掐掉。
                String name = nif.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("eth")) {
                    return nif;             // 有线优先
                }
                if (wifi == null && (name.startsWith("wlan") || name.startsWith("ap"))) {
                    wifi = nif;             // Wi-Fi 备选
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "枚举网卡失败", e);
        }
        return wifi;
    }

    public void shutdown() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        if (socket == null) {
            return;
        }
        try {
            if (!socket.isClosed()) {
                try {
                    socket.leaveGroup(
                            new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT),
                            boundInterface);
                } catch (Exception ignored) {
                    // 退出组播失败不影响关闭
                }
                socket.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭 SSDP socket 出错", e);
        }
    }
}
