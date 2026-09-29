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
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * 网卡选择见 {@link NetUtil#pickInterfaces()}。
 *
 * <p>而且**要依次尝试**：候选列表里第一张 {@code joinGroup} 失败就换下一张。
 * 只试第一张的话，一旦它在本平台上不可用（比如没有 IPv4 的隧道接口），
 * SSDP 就静默死掉，表现为「手机搜不到设备」而日志里无从定位。
 *
 * <p>由此还引出一条：LOCATION 必须由**实际绑上的那张网卡**算出来
 * （见 {@link #tryBindOnce()}），不能由外部传一个 IP 进来。
 * 外部那个 IP 是按「第一张候选网卡」算的，而组播完全可能绑在第二张上 ——
 * 两边一分叉就成了「从 eth0 收搜索请求、却告诉手机去 wlan0 取设备描述」，
 * 手机搜得到设备、点进去却拉不到描述。
 *
 * <h3>第五个坑：绑定失败不能一次就放弃</h3>
 * 这个服务是**开机自启**的（见 {@code BootReceiver}）。开机广播到达时
 * Wi-Fi 往往还没连上，那一刻 {@link NetUtil#pickInterfaces()} 返回空 ——
 * 原来直接 {@code return}，SSDP 线程就**永久**结束了。而
 * {@code startService()} 对一个**已经在跑**的服务不会再触发 {@code onCreate}，
 * 所以用户后来手动打开 App 也救不回来，只能重启服务。
 *
 * <p>表现为「开机后怎么都搜不到设备，重启一下 App 就好了」——
 * 而日志里只有一句「找不到可用于组播的网卡」，看不出是时序问题。
 * 所以绑定必须**带退避地重试**，见 {@link #bindUntilReady()}。
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

    /**
     * 设备描述的有效期（秒）。控制点在这个时间内不会再重新拉描述。
     *
     * <p>它和 {@link #ANNOUNCE_INTERVAL_SEC} 是一对：规范要求主动广播的间隔
     * **不得超过 max-age 的一半**，否则控制点会在两次广播之间把设备判为过期。
     * 两个数字必须一起看，所以放得这么近。
     */
    private static final long CACHE_MAX_AGE_SEC = 1800L;

    /**
     * 主动广播（{@code ssdp:alive}）的重播间隔（秒）。
     *
     * <p>为什么必须**定期**重播，而不是启动时发一轮就完事：
     * 被动发现类控制点（网易云音乐等）是**监听广播**来构建设备列表的，
     * 而 UDP 广播本身会丢包、控制点的缓存也会过期。只发一轮的话，
     * 「设备明明在线却搜不到」会在每次丢包之后重新出现。
     *
     * <p>为什么取 120 秒而不是「max-age 的一半」（900 秒）：
     * 规范（UPnP DA 1.0 §1.2.2）只要求间隔**不超过** max-age/2，往下没有下限。
     * 而实测里网易云那类控制点丢过一轮广播之后，要等到**下一次**广播才会
     * 重新把设备列出来 —— 间隔取 900 秒意味着「搜不到」可以持续 15 分钟，
     * 用户早就以为设备坏了。开销这边：8 个 NT × 3 轮 / 2 分钟
     * = 每分钟 12 个小 UDP 包，局域网上完全可以忽略。
     *
     * <p>硬约束：{@code ANNOUNCE_INTERVAL_SEC * 2 <= CACHE_MAX_AGE_SEC}
     * （否则控制点会在两次广播之间把设备判为过期）。这条由核验脚本守着。
     */
    private static final long ANNOUNCE_INTERVAL_SEC = 120L;

    /** 绑定失败后第一次重试前等多久（毫秒） */
    private static final long RETRY_BASE_MS = 2000L;

    /**
     * 退避上限（毫秒）。不能无限拉长 —— 网络就绪（网线插上、Wi-Fi 连上）之后
     * 用户最多等这么久就能被搜到。30 秒是「够快」与「不折腾」之间的取舍。
     */
    private static final long RETRY_MAX_MS = 30000L;

    /**
     * 退避到达上限之后，每这么多次才记一条日志。
     *
     * <p>设备在没网的状态下挂一整夜就是几千次失败。不节流的话 logcat 会被刷满，
     * 真正有用的日志反而被冲掉 —— 而排障恰恰要靠那些日志。
     * 按 30 秒一次算，20 次约等于 10 分钟一条。
     */
    private static final int RETRY_LOG_EVERY = 20;

    /**
     * MX 头的上限（秒）。
     *
     * <p>规范里 MX 表示"设备最多等这么多秒再应答"，取值 1~5。
     * 这里夹一道上限不是为了合规，是为了**挡住畸形报文**：
     * 一个写着 {@code MX: 99999} 的包会让应答排在 27 小时之后 ——
     * 那和不支持没有区别，而且它还会占着一个待发位置。
     */
    private static final int MAX_MX_SEC = 5;

    /**
     * 待发应答的批数上限。
     *
     * <p>这台盒子只有 0.6GB 内存。一个每秒发几十条 M-SEARCH 的异常控制点
     * （或一个端口扫描器）能排出一条无限长的队列 —— 那是内存泄漏，
     * 而且它排在最前面堵着，正常搜索的应答反而排在后面。
     *
     * <p>到上限就**直接丢弃**：UDP 本来就会丢包，控制点收不到会重发，
     * 丢几条远比把进程撑爆好。32 批 × 最多 6 条 = 192 个包，够用了。
     */
    private static final int MAX_PENDING_REPLIES = 32;

    /**
     * 随机延迟用的发生器。
     *
     * <p>用 {@link Random} 而不是 {@code Math.random()}：这里要的是一个
     * **有明确边界**的整数（0 到 MX 毫秒之间，含两端），
     * {@code nextInt(n)} 正好表达这个语义，不必再处理取整方向。
     */
    private static final Random RANDOM = new Random();

    private final String uuid;
    private final int httpPort;      // 设备描述 URL 里的端口（LOCATION 用）
    private final String serverName;

    /** 应用版本名（如 "0.1.4"）—— SERVER 头产品段 "Juping/<版本>" 的来源 */
    private final String versionName;
    private final int port;

    /**
     * 设备描述 XML 的 URL。
     *
     * <p><b>绑上组播之后才确定</b> —— 它由实际绑上的那张网卡的 IPv4 拼出来
     * （见 {@link #tryBindOnce()}），而不是构造时由外部传进来的。
     * 在那之前是 null，所以 {@link #buildResponse} / {@link #buildNotify}
     * 只允许在绑定成功之后调用。
     */
    private volatile String location;

    private volatile boolean running = true;
    private MulticastSocket socket;
    private NetworkInterface boundInterface;
    private volatile int boundPort = -1;

    /**
     * 延迟应答用的调度器。
     *
     * <p>为什么必须延迟：UPnP DA 1.0 §1.3.2 要求设备在 **0 ~ MX 秒之间随机**
     * 之后才回应 M-SEARCH，目的是把多台设备、多个搜索目标的应答在时间上错开，
     * 减少 UDP 碰撞。立即连发的话（原来的做法），一次搜索就要连发最多 6 条，
     * 几台设备同网时碰撞概率相当高 —— 表现就是「有时搜得到、有时搜不到」，
     * 且完全看不出规律，最容易被当成"手机的问题"。
     *
     * <p>为什么用调度器而不是在收包线程里 sleep：收包线程必须一直待在
     * {@code receive()} 上。睡一秒就意味着这一秒内其它设备的搜索
     * （以及控制点因为没收到应答而重发的搜索）全被丢掉。
     */
    private volatile ScheduledExecutorService replyScheduler;

    /** 已经排进调度器、还没发出去的批数。见 {@link #MAX_PENDING_REPLIES} */
    private final AtomicInteger pendingReplies = new AtomicInteger();

    /** 定期重播线程。持有引用是为了能在 {@link #shutdown()} 里 interrupt 掉。 */
    private volatile Thread announcer;

    public SsdpResponder(String uuid, int httpPort, String serverName) {
        this(uuid, httpPort, serverName, SSDP_PORT);
    }

    /** 生产入口：版本号随构造注入（SERVER 头产品段的来源），SSDP 端口用默认 1900。 */
    public SsdpResponder(String uuid, int httpPort, String serverName, String versionName) {
        this(uuid, httpPort, serverName, SSDP_PORT, versionName);
    }

    /**
     * 可指定端口的构造函数。
     *
     * <p>存在的意义是让协议层测试能在桌面 JVM 上把真实的响应器拉起来跑
     * （用临时端口，不去抢 1900）。生产代码走上面那个构造函数，行为不变。
     *
     * <p>注意这里收的是 <b>HTTP 端口</b>而不是拼好的 LOCATION 字符串：
     * LOCATION 里的 IP 必须等组播真的绑上某张网卡之后才知道，
     * 提前从外面传进来就等于把「组播绑哪张网卡」和「告诉手机去哪取描述」
     * 拆成了两次独立选择，它们随时可能分叉。
     */
    public SsdpResponder(String uuid, int httpPort, String serverName, int port) {
        this(uuid, httpPort, serverName, port, "0.0.0");
    }

    /** 带版本号的构造函数 —— SERVER 头的产品段来自它，与 device.xml 的 modelNumber 同源。 */
    public SsdpResponder(String uuid, int httpPort, String serverName, int port,
                         String versionName) {
        super("ssdp-responder");
        setDaemon(true);
        this.uuid = uuid;
        this.httpPort = httpPort;
        this.serverName = serverName;
        this.port = port;
        this.versionName = versionName;
    }

    /** 当前绑定的网卡名，供 UI 显示和排障用 */
    public String getBoundInterfaceName() {
        return boundInterface == null ? "(未绑定)" : boundInterface.getName();
    }

    /** 实际绑上的端口（构造时传 0 则由系统分配）。未绑定成功时为 -1 */
    public int getBoundPort() {
        return boundPort;
    }

    /**
     * 实际绑定的那张网卡上的 IPv4。
     *
     * <p>界面显示设备地址、以及 {@code DlnaRendererService.getLocalIp()}
     * 都以它为准 —— 这才是控制点真正能访问到的地址。
     *
     * @return 未绑定时返回 null
     */
    public String getBoundIp() {
        return boundInterface == null ? null : NetUtil.pickIpv4(boundInterface);
    }

    /** 设备描述 URL。绑定成功之前是 null */
    public String getLocation() {
        return location;
    }

    /** 是否已成功加入组播组。没加入成功就等于「手机搜不到设备」 */
    public boolean isBound() {
        return boundPort > 0 && boundInterface != null;
    }

    @Override
    public void run() {
        try {
            if (!bindUntilReady()) {
                // 只有「还没绑上就被要求关闭」才会走到这里
                return;
            }

            // 延迟应答的调度器。单线程就够（应答只是"到点发几个 UDP 包"），
            // 而且单线程天然把并发量压在 1 —— 0.6GB 的盒子上越简单越好。
            replyScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ssdp-reply");
                    // daemon：桌面协议测试跑完要能直接退出，
                    // 不能被这个线程吊着不放。
                    t.setDaemon(true);
                    return t;
                }
            });

            // 加入组播成功之后**立刻主动广播一轮 ssdp:alive**。
            //
            // 这一步是「被动发现」类控制点（网易云音乐等）唯一的入口：
            // 它们不主动发 M-SEARCH，只监听广播来构建设备列表 ——
            // 只应答不广播的话，在它们眼里这台设备根本不存在。
            // 而腾讯视频 / B站 会主动搜索，所以只有前者受影响，
            // 表现为「有些 App 搜得到、有些搜不到」，极易被误判成 App 的兼容性问题。
            announceAlive();
            startAnnouncer();

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

    /**
     * 带退避地反复尝试绑定，直到成功或收到关闭指令。
     *
     * <p>为什么必须重试，而不是失败一次就退出：这个服务是**开机自启**的。
     * 开机广播到达时 Wi-Fi 往往还没连上（拿不到 IP），于是
     * {@link NetUtil#pickInterfaces()} 返回空 → 线程直接结束。
     * 而 {@code startService()} 对一个**已经在跑**的服务不会再触发
     * {@code onCreate}，所以用户后来手动打开 App 也救不回来 ——
     * 设备就永远搜不到了，直到重启服务。
     *
     * <p>退避从 2 秒起步、上限 30 秒：网络就绪后最多 30 秒就能被发现，
     * 同时不会在没网时把 CPU 和日志刷爆。
     *
     * @return 绑定成功返回 true；还没绑上就被要求关闭则返回 false
     */
    private boolean bindUntilReady() {
        long wait = RETRY_BASE_MS;
        int attempt = 0;
        while (running) {
            attempt++;
            String reason = tryBindOnce();
            if (reason == null) {
                if (attempt > 1) {
                    // 这条日志是排障的关键：它证明「曾经失败过、后来自己好了」。
                    // 只有它能把「开机后一段时间搜不到」和「一直搜不到」区分开。
                    Log.i(TAG, "第 " + attempt + " 次尝试后绑定成功（网卡终于就绪）");
                }
                return true;
            }
            if (!running) {
                return false;
            }
            // 日志节流：退避到达上限之后每 RETRY_LOG_EVERY 次才记一条。
            // 前几次（还在快速退避）每次都记 —— 那正是最需要看的窗口。
            if (wait < RETRY_MAX_MS || attempt % RETRY_LOG_EVERY == 1) {
                Log.w(TAG, "第 " + attempt + " 次绑定 SSDP 失败：" + reason
                        + "；" + (wait / 1000L) + " 秒后重试");
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // shutdown() 会 interrupt 这个 sleep，别把 30 秒白等完
                Thread.currentThread().interrupt();
                return false;
            }
            wait = Math.min(wait * 2, RETRY_MAX_MS);
        }
        return false;
    }

    /**
     * 尝试绑定一次。
     *
     * <p>成功时把 {@link #socket} / {@link #boundInterface} / {@link #boundPort} /
     * {@link #location} 一并置好；失败时**把自己建的 socket 关掉**再返回。
     * 失败路径不关 socket 的话，每次重试都漏一个 fd ——
     * 老盒子上几十次之后就是 {@code Too many open files}，
     * 而那时报错的是**别的**模块，根本联想不到 SSDP 重试。
     *
     * @return 成功返回 null；失败返回原因（给日志和重试循环用）
     */
    private String tryBindOnce() {
        List<NetworkInterface> candidates = NetUtil.pickInterfaces();
        if (candidates.isEmpty()) {
            return "找不到可用于组播的网卡（候选: " + NetUtil.describeCandidates() + "）";
        }

        MulticastSocket s = null;
        NetworkInterface bound = null;
        int actualPort = -1;
        String reason = null;
        try {
            // setReuseAddress 必须在 bind **之前**调用才有效。
            // MulticastSocket(port) 在构造函数里就已经 bind 了，之后再调是空操作 ——
            // 一旦 1900 被占用（盒子自带的投屏服务、或上一次没退干净的自己），
            // 构造函数直接抛 BindException，整个 SSDP 线程静默死掉，
            // 日志里只有一句"SSDP 异常退出"，表现为「设备突然搜不到」。
            // 正确顺序：先建未绑定的 socket → 设置 reuse → 再 bind。
            s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.setTimeToLive(4);
            s.bind(new InetSocketAddress(port));
            // 用实际绑上的端口，而不是构造参数 —— 传 0 时由系统分配，
            // 若仍拿 0 去 joinGroup，加入的会是 "239.255.255.250:0" 这个不存在的组。
            actualPort = s.getLocalPort();

            // 依次尝试候选网卡：某一张 joinGroup 失败就换下一张。
            // 只试第一张的话，一旦它在本平台上不可用（例如没有 IPv4 的隧道接口），
            // SSDP 就静默死掉 —— 表现为「手机搜不到设备」，且日志里无从定位。
            InetSocketAddress group =
                    new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), actualPort);
            Exception lastError = null;
            for (int i = 0; i < candidates.size(); i++) {
                NetworkInterface nif = candidates.get(i);
                try {
                    s.joinGroup(group, nif);
                    bound = nif;
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "网卡 " + nif.getName() + " 加入组播失败，换下一张: " + e.getMessage());
                }
            }
            if (bound == null) {
                reason = "所有候选网卡都无法加入组播组（候选 "
                        + NetUtil.describeCandidates() + "）: " + lastError;
            }
        } catch (Exception e) {
            reason = e.toString();
        }

        if (reason != null) {
            closeSocket(s);
            return reason;
        }

        // 只有真正加入成功才对外报告端口。原来在 joinGroup 之前就赋值，
        // 结果是 UI 显示"已就绪"、实际一个搜索请求都收不到 —— 谎报军情比失败更糟。
        socket = s;
        boundInterface = bound;

        // LOCATION 由**实际绑上的那张网卡**算出来，不再由外部传入。
        //
        // 外部传进来的 IP 是按「第一张候选网卡」算的，而组播可能绑在第二张上
        // （第一张 joinGroup 失败时）。两边一分叉，就成了「组播从 eth0 收搜索请求、
        // 却告诉手机去 wlan0 取设备描述」—— 手机搜得到设备、点进去却拉不到描述，
        // 表现是「搜到了却投不了屏」。由 bound 直接算，两条链路必然指向同一张网卡。
        String ip = NetUtil.pickIpv4(bound);
        location = "http://" + (ip == null ? "0.0.0.0" : ip) + ":" + httpPort
                + "/upnp/device.xml";

        // boundPort 必须**最后**赋值：isBound() 判的就是它。
        // 先赋它的话，会出现「isBound() 已经是 true、location 还是 null」的窗口 ——
        // 而调用方（界面、协议测试）拿到 isBound() 就会立刻去读 LOCATION。
        // 顺序定死成「绑上 ⇒ 地址已经可用」，别让调用方去处理中间态。
        boundPort = actualPort;

        Log.i(TAG, "SSDP 已加入组播组 " + SSDP_ADDR + ":" + boundPort
                + "，网卡=" + bound.getName()
                + "（候选 " + candidates.size() + " 张），LOCATION=" + location);
        return null;
    }

    private static void closeSocket(MulticastSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Exception ignored) {
            // 关不上就算了，重试路径上不值得再抛
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

        long delayMs = randomDelayMs(headerValue(msg, "MX"));
        Log.i(TAG, "收到设备搜索（ST=" + st + "），来自 "
                + packet.getAddress().getHostAddress() + "，将回 " + targets.size()
                + " 条，延迟 " + delayMs + "ms");
        scheduleResponse(targets, packet, delayMs);
    }

    /**
     * 按 MX 头算出延迟多少毫秒再应答。
     *
     * <p>规范要求「0 ~ MX 秒之间的**随机**延迟」，而不是立即回、也不是固定睡满。
     *
     * <p>MX 缺失时按 1 秒处理。规范说 MX 是必选头，但缺失时用一个保守的小值
     * 比不应答好：应答晚一点不影响正确性，**不应答才是真的搜不到**。
     *
     * <p>上限夹在 {@link #MAX_MX_SEC}：一个畸形报文里写 {@code MX: 99999}
     * 会让应答排在一天之后 —— 那和不支持没有区别，而且白占一个待发位置。
     *
     * <p>返回值是**纯函数**（只依赖入参和随机源），所以能单独核验：
     * {@code MX=0} 必须恰好得 0（这是可判定的，测试就靠它把"真的按 MX 算了"
     * 和"其实没算、只是碰巧很快"区分开）。
     */
    static long randomDelayMs(String mx) {
        int seconds = 1;
        if (mx != null && mx.length() > 0) {
            try {
                seconds = Integer.parseInt(mx.trim());
            } catch (NumberFormatException ignored) {
                // 解析不出来就按默认的 1 秒走。**不要**因为一个畸形头不应答。
                seconds = 1;
            }
        }
        if (seconds < 0) {
            seconds = 0;
        }
        if (seconds > MAX_MX_SEC) {
            seconds = MAX_MX_SEC;
        }
        if (seconds == 0) {
            return 0L;
        }
        // [0, seconds*1000] 闭区间：MX=1 时可能是 0ms（立刻），也可能是 1000ms。
        // nextInt(n) 的取值范围是 [0, n)，所以上界要 +1 才把 1000 包含进来。
        return (long) RANDOM.nextInt(seconds * 1000 + 1);
    }

    /**
     * 把这一轮应答排进延迟队列。
     *
     * <p>延迟时长对整个 targets 列表是**同一个** —— 规范说的是"对这次搜索的
     * 应答延迟一个随机时间"，不是每条各自随机。同一个值还能保证控制点在极短的
     * 窗口内收齐所有 ST 的应答，不会出现"搜到了设备类型、却没搜到它的服务"
     * 这种半截状态（那会让设备列表里出现一个点不开的条目）。
     */
    private void scheduleResponse(final List<String> targets, final DatagramPacket request,
                                  long delayMs) {
        final ScheduledExecutorService s = replyScheduler;
        if (s == null || s.isShutdown()) {
            return;
        }
        if (pendingReplies.get() >= MAX_PENDING_REPLIES) {
            // 丢弃而不是排队。控制点收不到会重发，而排队排到最后的结果
            // 是"内存被撑爆 + 正常搜索的应答被堵在后面"。
            Log.w(TAG, "待发应答已达上限 " + MAX_PENDING_REPLIES
                    + "，丢弃这次搜索（控制点会重发）");
            return;
        }
        pendingReplies.incrementAndGet();
        try {
            s.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < targets.size(); i++) {
                            sendResponse(targets.get(i), request);
                        }
                    } finally {
                        pendingReplies.decrementAndGet();
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 调度器正好在关闭。计数必须还回去 —— 不还的话它会一直顶在上限，
            // 从此所有搜索都被判成"队列满"而丢弃，表现为「设备突然搜不到了」。
            pendingReplies.decrementAndGet();
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

    // ------------------------------------------------ 主动广播（NOTIFY）

    /**
     * 广播一轮 {@code ssdp:alive}。
     *
     * <p>按 UPnP DA 1.0 §1.2.2，设备入网时要对**每个** NT 各发一次；
     * 又因为 UDP 不可靠，每条要重复发 3 次（后两次前各等 100ms / 200ms）。
     *
     * <p>「每个 NT 都要发」这条和 M-SEARCH 的应答规则是同一个道理：
     * 控制点靠 NT / USN 建索引，少一个 NT 就等于少一种被发现的方式。
     */
    private void announceAlive() {
        if (socket == null || socket.isClosed()) {
            return;
        }
        // 显式指定从哪张网卡发出去。多网卡设备（盒子同时插着网线和 Wi-Fi）上
        // 不指定的话，广播可能从另一张网卡发走 —— 手机在 wlan 那一侧就收不到。
        try {
            socket.setNetworkInterface(boundInterface);
        } catch (Exception e) {
            Log.w(TAG, "设置组播出口网卡失败（继续）: " + e.getMessage());
        }
        List<String> targets = announceTargets();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < targets.size(); i++) {
                sendNotify(targets.get(i), "ssdp:alive");
            }
            if (round < 2) {
                try {
                    Thread.sleep(100L * (round + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Log.i(TAG, "已广播 ssdp:alive（" + targets.size() + " 个 NT × 3 轮）");
    }

    /**
     * 广播 {@code ssdp:byebye} —— 告诉控制点「我走了」。
     *
     * <p>不发的话，控制点会一直把设备留在列表里，直到 max-age 过期
     * （最长 30 分钟）。用户看到的是「App 里挂着个设备，点进去投不了」——
     * 而这时设备其实早就关掉了，只能靠人去猜。
     */
    private void announceByeBye() {
        if (socket == null || socket.isClosed() || boundInterface == null) {
            return;
        }
        try {
            socket.setNetworkInterface(boundInterface);
        } catch (Exception ignored) {
            // 发得出去就行，指定网卡失败不影响
        }
        List<String> targets = announceTargets();
        for (int i = 0; i < targets.size(); i++) {
            sendNotify(targets.get(i), "ssdp:byebye");
        }
        Log.i(TAG, "已广播 ssdp:byebye（" + targets.size() + " 个 NT）");
    }

    /** 主动广播要覆盖的 NT 集合 —— 与 M-SEARCH 里 {@code ssdp:all} 的应答集合保持一致 */
    private List<String> announceTargets() {
        List<String> out = new ArrayList<String>();
        out.add("upnp:rootdevice");
        out.add("uuid:" + uuid);
        out.add(DEVICE_TYPE);
        for (int i = 0; i < SERVICE_TYPES.length; i++) {
            out.add(SERVICE_TYPES[i]);
        }
        return out;
    }

    /**
     * 定期重播 alive 的守护线程。
     *
     * <p>它必须和接收循环分开：接收循环会阻塞在 {@code socket.receive()} 上，
     * 塞进同一个线程就永远轮不到发送。
     *
     * <p>线程引用要存下来：{@link #shutdown()} 得能把它从
     * {@code sleep(ANNOUNCE_INTERVAL_SEC)} 里叫醒。不存的话，
     * 服务销毁后这个线程还要挂着睡满一整个间隔才退出。
     */
    private void startAnnouncer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(ANNOUNCE_INTERVAL_SEC * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    announceAlive();
                }
            }
        }, "ssdp-announcer");
        t.setDaemon(true);
        announcer = t;
        t.start();
    }

    private void sendNotify(String nt, String nts) {
        try {
            byte[] payload = buildNotify(nt, nts).getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length, InetAddress.getByName(SSDP_ADDR), port);
            socket.send(packet);
        } catch (Exception e) {
            Log.w(TAG, "发送 NOTIFY(" + nts + ") 失败: " + e.getMessage());
        }
    }

    /**
     * 构造一条 NOTIFY 报文。
     *
     * <p>和 M-SEARCH 应答有三个关键差异，写错任何一个都会让整条广播被控制点丢弃：
     * <ol>
     *   <li>请求行是 {@code NOTIFY * HTTP/1.1} —— 目标写 {@code *}，不是路径</li>
     *   <li>用 {@code NT} + {@code NTS} 两个头，而应答用的是单个 {@code ST}</li>
     *   <li>{@code HOST} 头**必须**有（应答里反而没有这个头）</li>
     * </ol>
     *
     * <p>{@code ssdp:byebye} 按规范不带 LOCATION / CACHE-CONTROL / SERVER ——
     * 设备都要走了，报地址没有意义。
     */
    String buildNotify(String nt, String nts) {
        boolean alive = "ssdp:alive".equals(nts);
        StringBuilder sb = new StringBuilder();
        sb.append("NOTIFY * HTTP/1.1\r\n");
        sb.append("HOST: ").append(SSDP_ADDR).append(":").append(port).append("\r\n");
        if (alive) {
            sb.append("CACHE-CONTROL: max-age=").append(CACHE_MAX_AGE_SEC).append("\r\n");
            sb.append("LOCATION: ").append(location).append("\r\n");
            sb.append("SERVER: ").append(serverName).append(" UPnP/1.0 Juping/" + versionName + "\r\n");
        }
        sb.append("NT: ").append(nt).append("\r\n");
        sb.append("NTS: ").append(nts).append("\r\n");
        sb.append("USN: ").append(usnFor(nt)).append("\r\n");
        sb.append("BOOTID.UPNP.ORG: ").append(BOOT_ID).append("\r\n");
        sb.append("CONFIGID.UPNP.ORG: 1\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /** 构造一条 M-SEARCH 应答。ST / USN 必须与本次搜索目标对应 */
    String buildResponse(String st) {
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=" + CACHE_MAX_AGE_SEC + "\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: " + serverName + " UPnP/1.0 Juping/" + versionName + "\r\n"
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
        // 必须在关 socket **之前**发 byebye —— 关了就没法发出去了。
        announceByeBye();
        closeQuietly();
        // 唤醒卡在退避 sleep 里的绑定重试（最长 30 秒）—— 不然服务都销毁了，
        // 这个线程还要挂着睡满一轮才退出。
        // 注意 receive() 不响应 interrupt，它是靠上面的 close 解开的，
        // 所以这一句只对「还没绑上、正在退避」那个状态有意义。
        // 写成 this.interrupt() 而不是裸的 interrupt()：这个类本身就是 Thread，
        // 而下面还要 interrupt 另一个线程（announcer），不写 this 容易看串。
        this.interrupt();
        // 重播线程同理：它睡的是 120 秒。
        Thread t = announcer;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 关掉 socket，并把「已绑定」的状态**一并复位**。
     *
     * <p>复位这一步是本类里最容易漏、后果又最隐蔽的一句。原来这里只 close，
     * {@code boundPort} / {@code boundInterface} / {@code location} 全留着 ——
     * 于是线程因为任何原因退出之后（被系统踢出组播组、receive 抛出没 catch 的异常、
     * 上层 shutdown 之后又被重建……），{@code isBound()} **仍然返回 true**。
     *
     * <p>后果不是"少一个状态"这么轻：界面据此显示"设备已就绪"，而实际上
     * 一个搜索请求都收不到 —— 手机搜不到设备，界面却说一切正常。
     * 用户唯一能做的就是重启盒子，而重启之后"看起来"又好了，
     * 于是永远定位不到这个故障。
     *
     * <p>顺序：**先清状态，再关 socket**。反过来的话，close 与清状态之间
     * 有一个瞬间是"端口已经没了、状态却说在监听"，调用方会读到假信息。
     */
    private void closeQuietly() {
        MulticastSocket s = socket;
        NetworkInterface nif = boundInterface;
        int boundPortBefore = boundPort;

        boundPort = -1;
        boundInterface = null;
        location = null;
        socket = null;

        // 调度器也一并收掉。不关的话它会挂着一条 daemon 线程，
        // 而里面排着的延迟应答还持有着 socket 的引用 —— 服务都销毁了，
        // 30 秒后还想往一个已关闭的 socket 上发东西。
        ScheduledExecutorService sc = replyScheduler;
        replyScheduler = null;
        if (sc != null) {
            sc.shutdownNow();
        }

        if (s == null) {
            return;
        }
        try {
            if (!s.isClosed()) {
                // leaveGroup 用的是**关掉之前**的值 —— 状态已经清空了，
                // 这里必须用上面存下来的局部变量，否则退组退到的是 null 组。
                try {
                    if (nif != null && boundPortBefore > 0) {
                        s.leaveGroup(new InetSocketAddress(
                                InetAddress.getByName(SSDP_ADDR), boundPortBefore), nif);
                    }
                } catch (Exception ignored) {
                    // 退出组播失败不影响关闭
                }
                s.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭 SSDP socket 出错", e);
        }
    }
}
