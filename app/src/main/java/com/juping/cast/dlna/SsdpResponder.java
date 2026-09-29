package com.juping.cast.dlna;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

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
 * 网卡选择见 {@link NetUtil#pickInterfaces()} —— 刻意和 LOCATION 的 IP 用同一套逻辑。
 *
 * <p>而且**要依次尝试**：候选列表里第一张 {@code joinGroup} 失败就换下一张。
 * 只试第一张的话，一旦它在本平台上不可用（比如没有 IPv4 的隧道接口），
 * SSDP 就静默死掉，表现为「手机搜不到设备」而日志里无从定位。
 *
 * <h3>第四个坑：ST 必须原样回给控制点</h3>
 * 控制点是拿 ST（搜索目标）去匹配应答的。如果它搜 {@code upnp:rootdevice}，
 * 而我们回一条 {@code ST: urn:...:device:MediaRenderer:1}，这条应答会被**直接丢弃** ——
 * 在手机看来就是「什么都没搜到」。
 *
 * <p>而且搜 {@code ssdp:all} 时规范要求对**每个**搜索目标各回一条
 * （rootdevice / 自己的 uuid / 设备类型 / 每个服务类型），不是回一条就完事。
 * 原来只回一条且 ST 写死，结果就是有些 App 搜得到、有些搜不到，
 * 同一个 App 有时搜得到有时搜不到 —— 正是「时好时坏」的典型来源。
 */
public class SsdpResponder extends Thread {

    private static final String TAG = "SsdpResponder";

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /** MediaRenderer 是所有 DLNA 控制点都会查找的标准设备类型 */
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    /** 对外暴露的三个服务类型，SSDP 应答与设备描述必须一致 */
    public static final String[] SERVICE_TYPES = {
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
    };

    /**
     * BOOTID 必须在每次设备重启后递增（UPnP 1.1）。用秒级时间戳足够，
     * 且天然满足"重启后更大"。同一个进程内保持不变。
     */
    private static final long BOOT_ID = System.currentTimeMillis() / 1000L;

    private final String uuid;
    private final String location;   // 设备描述 XML 的 URL
    private final String serverName;
    private final int port;

    private volatile boolean running = true;
    private MulticastSocket socket;
    private NetworkInterface boundInterface;
    private volatile int boundPort = -1;

    public SsdpResponder(String uuid, String location, String serverName) {
        this(uuid, location, serverName, SSDP_PORT);
    }

    /**
     * 可指定端口的构造函数。
     *
     * <p>存在的意义是让协议层测试能在桌面 JVM 上把真实的响应器拉起来跑
     * （用临时端口，不去抢 1900）。生产代码走上面那个构造函数，行为不变。
     */
    public SsdpResponder(String uuid, String location, String serverName, int port) {
        super("ssdp-responder");
        setDaemon(true);
        this.uuid = uuid;
        this.location = location;
        this.serverName = serverName;
        this.port = port;
    }

    /** 当前绑定的网卡名，供 UI 显示和排障用 */
    public String getBoundInterfaceName() {
        return boundInterface == null ? "(未绑定)" : boundInterface.getName();
    }

    /** 实际绑上的端口（构造时传 0 则由系统分配）。未绑定成功时为 -1 */
    public int getBoundPort() {
        return boundPort;
    }

    /** 是否已成功加入组播组。没加入成功就等于「手机搜不到设备」 */
    public boolean isBound() {
        return boundPort > 0 && boundInterface != null;
    }

    @Override
    public void run() {
        try {
            List<NetworkInterface> candidates = NetUtil.pickInterfaces();
            if (candidates.isEmpty()) {
                Log.e(TAG, "找不到可用于组播的网卡，SSDP 无法启动");
                return;
            }

            // setReuseAddress 必须在 bind **之前**调用才有效。
            // MulticastSocket(port) 在构造函数里就已经 bind 了，之后再调是空操作 ——
            // 一旦 1900 被占用（盒子自带的投屏服务、或上一次没退干净的自己），
            // 构造函数直接抛 BindException，整个 SSDP 线程静默死掉，
            // 日志里只有一句"SSDP 异常退出"，表现为「设备突然搜不到」。
            // 正确顺序：先建未绑定的 socket → 设置 reuse → 再 bind。
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.setTimeToLive(4);
            socket.bind(new InetSocketAddress(port));
            // 用实际绑上的端口，而不是构造参数 —— 传 0 时由系统分配，
            // 若仍拿 0 去 joinGroup，加入的会是 "239.255.255.250:0" 这个不存在的组。
            int actualPort = socket.getLocalPort();

            // 依次尝试候选网卡：某一张 joinGroup 失败就换下一张。
            // 只试第一张的话，一旦它在本平台上不可用（例如没有 IPv4 的隧道接口），
            // SSDP 就静默死掉 —— 表现为「手机搜不到设备」，且日志里无从定位。
            InetSocketAddress group =
                    new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), actualPort);
            Exception lastError = null;
            for (int i = 0; i < candidates.size(); i++) {
                NetworkInterface nif = candidates.get(i);
                try {
                    socket.joinGroup(group, nif);
                    boundInterface = nif;
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "网卡 " + nif.getName() + " 加入组播失败，换下一张: " + e.getMessage());
                }
            }
            if (boundInterface == null) {
                Log.e(TAG, "所有候选网卡都无法加入组播组，SSDP 无法启动。候选: "
                        + NetUtil.describeCandidates(), lastError);
                return;
            }

            // 只有真正加入成功才对外报告端口。原来在 joinGroup 之前就赋值，
            // 结果是 UI 显示"已就绪"、实际一个搜索请求都收不到 —— 谎报军情比失败更糟。
            boundPort = actualPort;
            Log.i(TAG, "SSDP 已加入组播组 " + SSDP_ADDR + ":" + boundPort
                    + "，网卡=" + boundInterface.getName()
                    + "（候选 " + candidates.size() + " 张）");

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

        String st = headerValue(msg, "ST");
        List<String> targets = searchTargetsFor(st, msg);
        if (targets.isEmpty()) {
            return;
        }

        Log.i(TAG, "收到设备搜索（ST=" + st + "），来自 "
                + packet.getAddress().getHostAddress() + "，将回 " + targets.size() + " 条");
        for (int i = 0; i < targets.size(); i++) {
            sendResponse(targets.get(i), packet);
        }
    }

    /**
     * 算出该对这次搜索回哪些 ST。
     *
     * @param st   请求里的 ST 头（可能为 null）
     * @param msg  完整报文，仅在 ST 头缺失时用于兜底扫描
     */
    private List<String> searchTargetsFor(String st, String msg) {
        List<String> out = new ArrayList<String>();

        if (st == null || st.length() == 0) {
            // 极少数控制点不写 ST 头。兜底：按老办法在全文里扫一遍，
            // 只要能看出它在找我们这类设备，就回一条设备类型的应答。
            if (msg.contains("ssdp:all") || msg.contains(DEVICE_TYPE)
                    || msg.contains("urn:schemas-upnp-org:service:")) {
                out.add(DEVICE_TYPE);
            }
            return out;
        }

        if ("ssdp:all".equals(st)) {
            // 规范要求：对每个搜索目标各回一条，控制点靠 ST 区分它们。
            out.add("upnp:rootdevice");
            out.add("uuid:" + uuid);
            out.add(DEVICE_TYPE);
            for (int i = 0; i < SERVICE_TYPES.length; i++) {
                out.add(SERVICE_TYPES[i]);
            }
            return out;
        }

        if (isOurTarget(st)) {
            out.add(st);
        }
        return out;
    }

    /** 这个搜索目标是不是本设备该应答的 */
    private boolean isOurTarget(String st) {
        if ("upnp:rootdevice".equals(st)) {
            return true;
        }
        if (("uuid:" + uuid).equalsIgnoreCase(st)) {
            return true;
        }
        if (DEVICE_TYPE.equals(st)) {
            return true;
        }
        for (int i = 0; i < SERVICE_TYPES.length; i++) {
            if (SERVICE_TYPES[i].equals(st)) {
                return true;
            }
        }
        return false;
    }

    /**
     * USN 的构造规则（UPnP DA 1.0 §1.2.3）：
     * <ul>
     *   <li>{@code upnp:rootdevice} → {@code uuid:<UDN>::upnp:rootdevice}</li>
     *   <li>{@code uuid:<UDN>} → 就是它自己，不带后缀</li>
     *   <li>其余 → {@code uuid:<UDN>::<ST>}</li>
     * </ul>
     */
    private String usnFor(String st) {
        if ("upnp:rootdevice".equals(st)) {
            return "uuid:" + uuid + "::upnp:rootdevice";
        }
        if (st.startsWith("uuid:")) {
            return st;
        }
        return "uuid:" + uuid + "::" + st;
    }

    private void sendResponse(String st, DatagramPacket request) {
        byte[] payload;
        try {
            // 显式指定 UTF-8。不写的话走平台默认编码 ——
            // 全项目其余部分都严格指定了字符集，这里没有理由例外。
            payload = buildResponse(st).getBytes("UTF-8");
        } catch (IOException e) {
            Log.w(TAG, "编码应答失败", e);
            return;
        }
        try {
            // M-SEARCH 的响应按规范应「单播」回请求源地址 —— 比组播可靠得多，
            // 老设备上这一点尤其明显。
            DatagramPacket reply = new DatagramPacket(
                    payload, payload.length, request.getAddress(), request.getPort());
            socket.send(reply);
        } catch (IOException e) {
            Log.w(TAG, "响应发送失败: " + e.getMessage());
        }
    }

    /** 构造一条 M-SEARCH 应答。ST / USN 必须与本次搜索目标对应 */
    String buildResponse(String st) {
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: " + serverName + " UPnP/1.0 Juping/1.0\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usnFor(st) + "\r\n"
                + "BOOTID.UPNP.ORG: " + BOOT_ID + "\r\n"
                + "OPT: \"http://schemas.upnp.org/upnp/1/0/\"; ns=01\r\n"
                + "01-NLS: " + uuid + "\r\n"
                + "\r\n";
    }

    /**
     * 从报文里取一个头的值（大小写不敏感）。
     *
     * <p>用逐行解析而不是 {@code msg.contains("ST: xxx")}：
     * contains 既会误命中正文里的同名字符串，也没法处理头顺序变化。
     * 行分隔用 {@code \r?\n} —— 少数控制点只发 {@code \n}。
     */
    static String headerValue(String msg, String name) {
        String[] lines = msg.split("\r?\n");
        // 从第 1 行开始：第 0 行是请求行，不含头
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return null;
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
                            new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), boundPort),
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
