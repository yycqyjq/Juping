package com.juping.cast;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.juping.cast.dlna.DidlLite;
import com.juping.cast.dlna.EventDispatcher;
import com.juping.cast.dlna.NetUtil;
import com.juping.cast.dlna.SsdpResponder;
import com.juping.cast.dlna.UpnpHttpServer;
import com.juping.cast.player.MediaPlayerController;
import com.juping.cast.player.PlaybackPolicy;
import com.juping.cast.web.LocalStore;
import com.juping.cast.web.WebCastEndpoints;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 接收端前台服务 —— 整个投屏接收端的心脏。
 *
 * <h3>为什么必须是「前台服务」</h3>
 * Android 4.0 设备的可用内存普遍只有 512MB~1GB。普通后台服务在内存吃紧时
 * 会被 LMK（低内存杀手）直接干掉，表现就是「投着投着突然断了，App 没了」。
 * 挂上前台通知能显著降低被杀的优先级，这是「不断联」的第一道保险。
 *
 * <h3>两把锁，缺一不可</h3>
 * <ul>
 *   <li><b>MulticastLock</b>：不拿就收不到 SSDP 组播 → 手机搜不到设备</li>
 *   <li><b>WifiLock(HIGH_PERF)</b>：不拿，息屏后 Wi-Fi 进省电模式 → 投屏卡顿/断流</li>
 * </ul>
 * 这两把锁必须在服务的整个生命周期内持有，onDestroy 时释放。
 */
public class DlnaRendererService extends Service
        implements UpnpHttpServer.CommandHandler, MediaPlayerController.Listener,
        EventDispatcher.EventSource,
        // 网页上传的东西要能"投到电视"，而这件事的落点在本类（onSetUri + onPlay
        // 才是真正的播放动作）。让 WebCastEndpoints 反向拿一个回调接口，
        // 而不是自己去碰播放器 —— 播放状态机只此一处，多一个入口就多一套竞态。
        WebCastEndpoints.CastTarget {

    private static final String TAG = "DlnaRendererService";

    static final String PREFS = "juping";
    private static final String KEY_UUID = "device_uuid";

    /**
     * DLNA 服务的**首选**端口。
     *
     * <p>49152 是 UPnP 惯例端口，用固定端口方便排查。但它不再是"写死"的：
     * 被别的进程占了（老电视上厂家自带的 DLNA 栈很可能也占它）会由
     * {@link UpnpHttpServer#getPort()} 回退到相邻端口，LOCATION、界面、
     * {@code /status} 一律显示**实际**端口。这个常量只是绑定的起点。
     */
    private static final int HTTP_PORT = 49152;

    /**
     * 自检间隔（毫秒）。
     *
     * <p>30 秒是个取舍：SSDP 自己已经有 2~30 秒的绑定退避重试，自检只要负责
     * "线程整个死了"这一种情况，不需要更快。太快反而是负担 ——
     * 这台盒子只有 0.6GB 内存，任何周期性动作都要算进预算里。
     */
    private static final long WATCHDOG_INTERVAL_MS = 30000L;

    /**
     * 自检发现故障后，每这么多次才记一条日志。
     *
     * <p>和 SsdpResponder 里那条 RETRY_LOG_EVERY 同一个理由：如果故障是
     * 持续的（比如端口被别的进程永久占着），每 30 秒一条日志挂一整夜
     * 就是近 3000 行，真正有用的信息反而被冲掉。
     */
    private static final int WATCHDOG_LOG_EVERY = 20;

    /**
     * 网络变化后等多久才重建（防抖窗口）。
     *
     * <p><b>为什么要防抖</b>：{@code CONNECTIVITY_ACTION} 在一次网络切换里
     * **会连发好几条**（旧网络断开一条、Wi-Fi 状态变化一条、新网络连上一条）。
     * 每条都立刻重建的话，会在网卡**还没拿到 IPv4** 的时候反复重建 ——
     * 每次都绑不上，反而把 SSDP 自己的退避重试节奏打乱。
     *
     * <p><b>为什么是 3 秒</b>：网卡从"连上"到"拿到 IPv4"通常几百毫秒到 2 秒，
     * 3 秒之后再重建，第一次就能绑上。这个延迟只发生在网络变化时，
     * 不影响稳态。
     */
    private static final long NETWORK_SETTLE_MS = 3000L;

    /** 内容类型的四个取值，见 {@link #kindOf} 与 {@link #audioOnly}。 */
    private static final int KIND_UNKNOWN = 0;
    private static final int KIND_AUDIO = 1;
    private static final int KIND_VIDEO = 2;
    /**
     * 静态图片。
     *
     * <p><b>为什么单列一类，而不是并进 VIDEO</b>：图片根本不经过
     * {@code MediaPlayer} —— 它解不了静态图，喂进去只会立刻报
     * {@code error(1, -2147483648)}，电视上什么都不显示。图片必须由界面
     * 用 {@code BitmapFactory} 解码后画在 ImageView 上。并进 VIDEO 的话，
     * 界面会去等一个永远不会来的视频帧，结果就是**一块黑屏**。
     */
    private static final int KIND_IMAGE = 3;

    public class LocalBinder extends Binder {
        public DlnaRendererService getService() {
            return DlnaRendererService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    private SsdpResponder ssdp;
    private UpnpHttpServer httpServer;
    private MediaPlayerController player;

    /**
     * 上传落盘与网页端点。
     *
     * <p>生命周期跟服务走：{@code LocalStore} 只是包了一个目录路径，
     * 端点是无状态的，两者都不持有 Context（{@code LocalStore} 只在构造时用一下
     * {@code getFilesDir()}），所以网络变化重建 HTTP 服务时**不需要重建它们** ——
     * 重建了反而会把上传目录重新探一遍，白做。
     */
    private LocalStore webStore;
    private WebCastEndpoints webEndpoints;

    private String uuid;
    private String friendlyName;
    private String localIp = "0.0.0.0";
    private volatile String transportState = "STOPPED";

    /**
     * 当前错误分类（{@code PlaybackPolicy.ERR_*}）。{@code ERR_NONE} 表示没出错。
     *
     * <p><b>为什么是分类而不是一个字符串</b>：界面要显示的是「连不上媒体服务器」
     * 这类用户能看懂的话，而播放器给的是 {@code "what=1 extra=-1010"} 这类技术细节 ——
     * 两者必须分开走。分类留在服务层（它是"发生了什么"），文案由界面按分类去
     * {@code strings.xml} 取（那是"怎么说"，还可能要本地化）。
     * 把技术细节直接丢到界面上，用户既看不懂、也没法据此做任何决定。
     */
    private volatile int lastErrorKind = PlaybackPolicy.ERR_NONE;

    /**
     * 错误的技术细节 —— {@code what}/{@code extra}、异常栈里那一行。
     *
     * <p>只进日志。排障时把日志发过来就能定位，而界面上永远不出现它。
     */
    private volatile String lastError = "";

    private volatile String currentUri = "";

    /**
     * 下一曲（SetNextAVTransportURI 预告的续播地址）—— 播放列表/连续播放。
     * 新 SetAVTransportURI 到达即作废；曲目自然播完由播放器回调续播。
     */
    private volatile String nextUri = "";
    private volatile String nextUriMetadata = "";

    /** 本轮播放的起始时刻 —— Auto-Stop 的「本轮播放期间曾有订阅者」判据 */
    private volatile long playStartedAtMs;

    /** 进程内服务启动时刻 —— /status 的 uptimeSec 用 */
    private volatile long startedAtMs;

    /**
     * 控制点推来的原始元数据（DIDL-Lite XML）。
     *
     * <p><b>原样保存、原样回读</b>：控制点会回读 {@code GetMediaInfo} 的
     * {@code CurrentURIMetaData}，和自己刚推的那份比对。回一个"我们重新拼的"
     * 版本，哪怕语义等价，也可能因为元素顺序、命名空间声明的差异被判成不一致。
     * 所以这里不做任何加工，收到什么存什么。
     */
    private volatile String currentMetadata = "";

    /**
     * 从元数据里解析出来的标题。
     *
     * <p>只用于**界面显示**（让用户看到「夜曲」而不是 {@code 6a3f9c2b.mp3}）。
     * 协议回读走 {@link #currentMetadata} 的原文，不用这个 ——
     * 解析出来的标题是给人看的，不是给控制点看的。
     *
     * <p>取不到时为空串，界面自己回退到文件名。**不要在这里编一个
     * "未知曲目"之类的默认值**：那样界面就分不清"控制点没给标题"
     * 和"控制点给的标题就叫未知曲目"，而前者应该显示文件名（那是有用信息）。
     */
    private volatile String currentTitle = "";

    /** 传给 SSDP 的 SERVER 头。抽成字段是因为重建时要用同一个值 */
    private String serverName;

    /**
     * 服务正在销毁。
     *
     * <p>自检看门狗靠它收手。**没有这个标志会出大问题**：{@link #onDestroy()}
     * 会主动 {@code ssdp.shutdown()}，线程随即退出 —— 而看门狗下一轮醒来
     * 看到"线程死了"，就会**把它重新拉起来**，于是服务销毁之后 SSDP
     * 又活了，端口一直被占着，下次启动直接 EADDRINUSE。
     */
    private volatile boolean shuttingDown = false;

    /** 自检看门狗跑在主线程的 Looper 上。见 {@link #watchdogTask} */
    private final Handler watchdog = new Handler();

    /** 连续自检失败的次数。只用于日志节流 */
    private int watchdogFailures = 0;

    /**
     * 网络变化监听。{@code null} 表示还没注册或已经注销。
     *
     * <p><b>为什么必须有这一环</b>：{@link SsdpResponder} 的 LOCATION 是在
     * **绑上网卡那一刻生成一次**的，之后每条应答都用它。而 {@link #checkThreadsAlive()}
     * 的判据是「线程已死**且**未绑定」—— 网络变了但线程活得好好的时候，
     * 看门狗**看不见**：socket 还绑在旧网卡上、LOCATION 还是旧 IP，
     * 用户看到的就是「Wi-Fi 断一下就得重启 App」。
     *
     * <p>路由器重启、IP 换了、从有线切到无线，都会走到这里。
     */
    private BroadcastReceiver connectivityReceiver;

    /**
     * 网络变化后的重建任务。
     *
     * <p>写成字段是为了两件事：① 防抖 —— 新广播到达时先
     * {@code removeCallbacks} 掉上一次；② {@link #onDestroy()} 能把它摘掉，
     * 否则服务销毁后它还会被主线程队列捞起来跑一次。
     */
    private final Runnable rebuildOnNetworkChange = new Runnable() {
        @Override
        public void run() {
            if (!shuttingDown) {
                applyNetworkChange();
            }
        }
    };

    /**
     * 自检任务：活着就继续排下一轮，死了就重建。
     *
     * <p>写成字段而不是匿名类，是为了 {@link #onDestroy()} 能
     * {@code removeCallbacks} 把它摘掉 —— 否则服务销毁后它还会被主线程
     * 的队列捞起来跑一次，那时候字段全是空的。
     */
    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            try {
                checkThreadsAlive();
                checkAutoStop();
            } catch (Throwable t) {
                // 看门狗自己绝不能把主线程掀翻 —— 它是"最后一道保险"，
                // 它崩了就真的没有任何东西能发现故障了。
                Log.w(TAG, "自检本身出错（忽略）", t);
            }
            if (!shuttingDown) {
                watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    /**
     * 当前是不是纯音频流（音乐投屏）。界面据此切「音乐卡片 / 视频画面」。
     *
     * <p>为什么必须区分：音频流走同一个 SurfaceView 时画面什么都没有，
     * 电视就是**一片黑**，只显示"正在播放"和进度条 —— 声音明明在放，
     * 看着却像投屏失败了。音乐是主要用途之一，这个误判代价很高。
     */
    private volatile boolean audioOnly = false;

    /**
     * 界面是否由「投屏到达」自动唤起且尚未消费。
     * 置位在 {@link #bringPlayerToFront()}，消费在 MainActivity 的
     * 「播放→空闲」变迁（退回后台）。volatile：写在前台唤起路径，
     * 读在界面刷新线程。
     */
    private volatile boolean autoFront = false;

    /** 从 DLNA 元数据里读到的内容类型：0=未知，1=音频，2=视频，3=图片。 */
    private volatile int kindFromMetadata = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startedAtMs = System.currentTimeMillis();
        Log.i(TAG, "服务创建");

        uuid = loadOrCreateUuid();
        friendlyName = loadFriendlyName();
        serverName = "Android/" + android.os.Build.VERSION.RELEASE;
        // 必须把 this 传进去：播放器要用它调 MediaPlayer.setWakeMode()，
        // 那一步是"息屏后音乐还能继续放"的唯一保障（理由见
        // MediaPlayerController.startInternal）。Service 就是最合适的 Context ——
        // 它的生命周期与播放器完全一致，不会泄漏。
        player = new MediaPlayerController(this);
        player.setListener(this);

        acquireLocks();
        startForegroundNotification();

        localIp = NetUtil.pickLocalIp();

        // 网页上传端点。建在 HTTP 服务之前 —— 构造要把它传进去。
        // 落盘根是 getFilesDir()/uploads（为什么不是外部存储，见 LocalStore 的类注释：
        // 这台盒子上"外部存储"就是插着的那支 U 盘）。
        webStore = new LocalStore(this);
        webEndpoints = new WebCastEndpoints(webStore, this);

        httpServer = new UpnpHttpServer(HTTP_PORT, uuid, friendlyName, BuildConfig.VERSION_NAME, this, this,
                webEndpoints);
        // 图标要在 start() 之前给 —— 设备描述是随请求现生成的，
        // 但早点给上可以让"第一台来搜的控制点"就看到图标。
        provideDeviceIcon();
        // start() 里同步完成绑定（含端口回退），返回时端口就是确定值。
        httpServer.start();

        // 只把 **HTTP 实际监听的端口** 交给 SSDP，不传拼好的 LOCATION ——
        // 设备描述地址里的 IP 必须等组播真的绑上某张网卡之后才知道。
        // 提前在外面拼一个，就等于把「组播绑哪张网卡」和「告诉手机去哪取描述」
        // 拆成两次独立选择：第一张候选网卡 joinGroup 失败时，组播会绑到第二张上，
        // 而 LOCATION 还指着第一张 —— 手机搜得到设备、点进去却拉不到描述。
        //
        // 端口同理必须用 getPort() 而不是首选常量：49152 被占时 HTTP 会回退到
        // 别的端口，这里要是还报首选端口，手机就会去一个没人听的地址取描述 ——
        // 又是「搜得到但投不了」。
        ssdp = new SsdpResponder(uuid, httpServer.getPort(), serverName, BuildConfig.VERSION_NAME);
        ssdp.start();

        // 网络变化监听。放在两条链路都起来之后注册 —— 注册本身不做事，
        // 但万一注册失败抛异常，前面该起来的已经起来了。
        registerConnectivityWatch();

        // 自检看门狗。第一轮不用等 30 秒 —— 立刻排一次，让"启动就失败"
        // 这种情况能早点进日志。
        watchdog.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);

        Log.i(TAG, "接收端已就绪：名称=" + friendlyName + " HTTP 端口=" + httpServer.getPort()
                + "（设备描述地址等 SSDP 绑上网卡后确定）");
    }

    /**
     * 给设备描述准备图标 —— 控制点的设备列表里显示的就是它。
     *
     * <p>为什么要"解码成 Bitmap 再压回 PNG"，而不是直接读资源字节：
     * APK 里 drawable 的文件路径会被 aapt / R8 改写（{@code res/drawable-xhdpi/
     * ic_launcher.png} 可能变成 {@code res/xx.png}），按路径去 zip 里取是不可靠的。
     * 而 {@code BitmapFactory} 是稳定接口。
     *
     * <p>为什么只做一张、尺寸运行时读：{@code R.drawable.ic_launcher} 在运行时
     * **只会解析成当前屏幕密度的那一张**（四档拿到的是同一个 Bitmap）。
     * 声明四个尺寸就是撒谎，所以按实际像素声明，多大就是多大。
     *
     * <p>整个过程包在 catch(Throwable) 里，失败就**不声明 iconList**：
     * 图标是纯装饰，绝不能因为它让设备描述出不来 ——
     * device.xml 拉不到等于整个设备在控制点眼里不存在。
     * 用 Throwable 而不是 Exception 是有意的：老设备的 BitmapFactory
     * 在内存吃紧时抛的是 {@code OutOfMemoryError}（一个 Error，不是 Exception），
     * 漏掉它的话这一句会把整个 onCreate 掀翻，投屏功能全没了。
     */
    private void provideDeviceIcon() {
        UpnpHttpServer s = httpServer;
        if (s == null) {
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
            if (bmp == null) {
                Log.w(TAG, "图标解码失败，设备描述将不声明 iconList");
                return;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            // PNG 是无损格式，quality 参数对它没有意义，100 只是惯例写法
            bmp.compress(Bitmap.CompressFormat.PNG, 100, buf);
            // 老设备上 Bitmap 占的是 native 堆，越早释放越好
            bmp.recycle();
            byte[] png = buf.toByteArray();
            s.setIcon(png, w, h);
            Log.i(TAG, "设备图标已就绪：" + w + "x" + h + "，" + png.length + " 字节");
        } catch (Throwable t) {
            Log.w(TAG, "准备设备图标失败，设备描述将不声明 iconList", t);
        }
    }

    /**
     * 设备 UUID 必须持久化。
     * 每次启动都换 UUID 的话，手机端会看到一堆重复设备，投屏列表会变得一团糟。
     */
    private String loadOrCreateUuid() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_UUID, null);
        if (saved != null && saved.length() > 0) {
            return saved;
        }
        String generated = UUID.randomUUID().toString();
        //noinspection ApplySharedPref
        // 这里刻意用 commit() 而不是 apply()：
        // apply() 是异步落盘，而这个服务随时可能被低内存杀手（LMK）干掉。
        // 一旦 UUID 没写进去，下次启动就会换一个新的 —— 手机端会看到一堆重复设备。
        // 一次同步写盘的开销（几毫秒，且只发生一次）远比 UUID 丢失划算。
        sp.edit().putString(KEY_UUID, generated).commit();
        return generated;
    }

    /**
     * 设备名：改名过的读改名，没改过的用默认「聚屏-&lt;型号&gt;」。
     *
     * <p>存盘侧在 {@link RenameReceiver}（adb 广播通道）—— 电视上没有
     * 可靠的输入法，弹 EditText 是给用户添堵。改名不影响 UUID，
     * 控制点对这台设备的记忆（订阅、缓存）不会断。
     */
    private String loadFriendlyName() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString(RenameReceiver.KEY_FRIENDLY_NAME, "");
        if (saved != null && saved.trim().length() > 0) {
            return saved.trim();
        }
        return "聚屏-" + android.os.Build.MODEL;
    }

    /**
     * 应用改名：重读名字，变了就重建两条链路。
     *
     * <p>由 {@code onStartCommand} 在收到 {@link RenameReceiver#ACTION_APPLY_RENAME}
     * 时调。重建走的是与网络自愈同一条 restartHttp / restartSsdp 路径 ——
     * 那边已经处理好了「先关旧的再开新的」「图标重给」「SSDP 重播 alive」，
     * 这里不许另写一份。名字没变（重复广播）就什么都不动。
     */
    private void applyRename() {
        String old = friendlyName;
        friendlyName = loadFriendlyName();
        if (friendlyName.equals(old)) {
            Log.i(TAG, "设备名未变化（" + friendlyName + "），无需重建");
            return;
        }
        restartHttp("设备改名");
        restartSsdp("设备改名");
        Log.i(TAG, "设备已改名：" + old + " → " + friendlyName);
    }

    private void acquireLocks() {
        // 必须用 getApplicationContext()，不能用 Service 自己的 getSystemService()。
        // Android N 之前，从 Service 取 WIFI_SERVICE 拿到的 WifiManager 会持有该 Service 的
        // Context 引用，而 Service 又被 WifiLock/MulticastLock 反向持有 —— 形成引用环，
        // Service 实例永远回收不掉。这台盒子只有 0.6GB 内存，漏一个 Service 就是致命的。
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.e(TAG, "拿不到 WifiManager，组播锁无法获取");
            return;
        }
        multicastLock = wm.createMulticastLock("juping-multicast");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();
        Log.i(TAG, "MulticastLock 已获取");

        // WIFI_MODE_FULL_HIGH_PERF 在部分老设备上不可用，降级到 FULL
        try {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "juping-wifi");
        } catch (Exception e) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "juping-wifi");
        }
        wifiLock.setReferenceCounted(true);
        wifiLock.acquire();
        Log.i(TAG, "WifiLock 已获取");
    }

    /**
     * 注册网络变化监听。
     *
     * <p>用**动态注册**而不是在 manifest 里静态声明：{@code CONNECTIVITY_ACTION}
     * 从 Android 7.0 起静态注册就收不到了，动态注册才是能长期工作的写法；
     * 而且我们只在服务活着的时候关心它，动态注册天然对得上这个生命周期。
     */
    private void registerConnectivityWatch() {
        connectivityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 先记一笔 —— 这条日志是「设备为什么突然重新广播了」的第一现场。
                Log.i(TAG, "网络发生变化（" + intent.getAction() + "），"
                        + (NETWORK_SETTLE_MS / 1000) + " 秒后重建监听");
                // 防抖：新广播到达就把上一次的排程推后。
                watchdog.removeCallbacks(rebuildOnNetworkChange);
                watchdog.postDelayed(rebuildOnNetworkChange, NETWORK_SETTLE_MS);
            }
        };
        try {
            registerReceiver(connectivityReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            Log.i(TAG, "已注册网络变化监听");
        } catch (Exception e) {
            // 注册不上只是少了「网络变了自动重建」这一层保险，
            // 看门狗和 SSDP 自己的退避重试还在。绝不能因此让服务起不来。
            connectivityReceiver = null;
            Log.w(TAG, "注册网络变化监听失败（继续运行）", e);
        }
    }

    /**
     * 网络真的变了：重新选网卡、重建两条链路。
     *
     * <p>顺序有讲究：**先刷新锁，再重建链路**。锁是「能收到组播包」的前提，
     * 而 {@link SsdpResponder} 一绑上就开始收包 —— 顺序反了的话，
     * 重建出来的 socket 会有一段收不到包的空窗。
     */
    private void applyNetworkChange() {
        if (shuttingDown) {
            return;
        }
        // 重新选 IP：NetUtil 每次都重新枚举网卡、不缓存，
        // 所以这里拿到的就是变化之后的真实情况。
        localIp = NetUtil.pickLocalIp();
        Log.i(TAG, "按网络变化重建：候选网卡 " + NetUtil.describeCandidates());
        refreshLocks();
        restartSsdp("网络变化");
        restartHttp("网络变化");
    }

    /**
     * 重新获取两把锁。
     *
     * <p>Wi-Fi 断开重连会把系统层面的锁清掉，而 {@code isHeld()} 在老平台上
     * 不一定如实反映这件事 —— 所以这里**无条件** release + acquire，
     * 不去赌那个布尔值。release 之前仍然判一次 {@code isHeld()}，
     * 因为对没持有的锁调 release 会抛 {@code RuntimeException}。
     */
    private void refreshLocks() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
            if (multicastLock != null) {
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "重新获取 MulticastLock 失败", e);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (wifiLock != null) {
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "重新获取 WifiLock 失败", e);
        }
    }

    /**
     * 重建 SSDP 接收线程。
     *
     * <p>抽成方法是因为它有**两个**调用方：自检看门狗（线程死了）和网络变化
     * （网卡换了）。同一段重建逻辑写两遍的话，迟早有一处忘了改 ——
     * 而「重建漏了一步」的后果是老盒子上慢慢漏 fd，最后报错的是别的模块。
     */
    private void restartSsdp(String reason) {
        SsdpResponder s = ssdp;
        if (s != null) {
            // 先 shutdown：网络变化时线程**还活着**，
            // 不关就重建等于漏一个 socket + 一个调度器。
            s.shutdown();
        }
        // 用 HTTP **实际在听**的端口，而不是首选常量 —— 回退过一次之后
        // 首选常量就再也对不上了（见 currentHttpPort）。
        int httpPort = currentHttpPort();
        ssdp = new SsdpResponder(uuid, httpPort, serverName, BuildConfig.VERSION_NAME);
        ssdp.start();
        Log.i(TAG, "已重建 SSDP（" + reason + "，LOCATION 端口 " + httpPort + "）");
    }

    /**
     * 重建 HTTP 服务。
     *
     * <p>图标必须重新给一次：设备描述是随请求现生成的，而新实例里
     * {@code iconPng} 是空的 —— 不重给的话，网络变化之后控制点就再也
     * 拿不到图标了（设备列表里变成一个默认方块）。
     */
    private void restartHttp(String reason) {
        UpnpHttpServer h = httpServer;
        int before = h != null ? h.getPort() : -1;
        if (h != null) {
            h.shutdown();
        }
        httpServer = new UpnpHttpServer(HTTP_PORT, uuid, friendlyName, BuildConfig.VERSION_NAME, this, this,
                webEndpoints);
        provideDeviceIcon();
        httpServer.start();
        int after = httpServer.getPort();
        Log.i(TAG, "已重建 HTTP 服务（" + reason + "，端口 " + after + "）");
        // 端口若变了，SSDP 正在广播的 LOCATION 就指向旧端口 —— 必须让它重算 LOCATION。
        // applyNetworkChange 的顺序是「先 SSDP 后 HTTP」（守卫钉着这个顺序），
        // 所以这里重建 HTTP 时 SSDP 已经拿着旧端口起来了，这是唯一能补上的一步：
        // 少了它，回退过端口的设备在网络变化之后就变成「搜得到但投不了」。
        if (httpServer.isBound() && after != before) {
            restartSsdp(reason + "：HTTP 端口变为 " + after);
        }
    }

    private void startForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        //noinspection UnspecifiedImmutableFlag
        // 这里刻意不加 FLAG_IMMUTABLE / FLAG_MUTABLE：
        // 那两个常量是 API 23 (Android 6.0) 才引入的，本机是 API 15。
        // 加了会在真机上直接 NoSuchFieldError 崩溃 —— 这就是 lint 警告必须无视的原因。
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this)
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(getString(R.string.notify_text))
                // 通知栏小图标必须是「纯白剪影」，不能用带颜色的应用图标：
                // 从 Android 5.0 起系统会把非白色部分全部涂掉，彩色图标会显示成一个白方块。
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(pi)
                .setOngoing(true)
                .getNotification();
        startForeground(1, notification);
    }

    // ------------------------------------------- UpnpHttpServer.CommandHandler

    @Override
    public void onSetUri(String uri, String metadata) {
        Log.i(TAG, "收到投屏地址: " + uri);
        bringPlayerToFront();
        currentUri = uri;
        // 新片源到达，之前预告的「下一曲」作废（新歌单会重新 SetNext）——
        // 服务层字段和播放器侧队列要一起清
        nextUri = "";
        nextUriMetadata = "";
        if (player != null) {
            player.setNext(null, null);
        }
        playStartedAtMs = System.currentTimeMillis();
        if (startedAtMs == 0) {
            startedAtMs = System.currentTimeMillis();
        }
        clearError();
        kindFromMetadata = kindOf(metadata);
        // 元数据原样留着（协议回读要用），另外解析一份标题给界面。
        // 解析失败不抛异常 —— DidlLite 取不到就返回空串，界面回退到文件名。
        currentMetadata = metadata == null ? "" : metadata;
        currentTitle = DidlLite.title(currentMetadata);
        if (currentTitle.length() > 0) {
            Log.i(TAG, "片源标题: " + currentTitle);
        }
        // 还没 prepare，先按元数据猜一个；等 onPrepared 拿到真实视频尺寸再定论。
        // 这样从"收到投屏"到"画面出来"这段时间界面形态就是对的，不会先黑一下再跳。
        audioOnly = (kindFromMetadata == KIND_AUDIO);
        if (kindFromMetadata == KIND_IMAGE) {
            // 图片**不走 MediaPlayer** —— 它解不了静态图，喂进去只会立刻报错，
            // 电视上什么都不显示（实测就是一块黑屏）。真正的画面由
            // MainActivity 用 BitmapFactory 解码后画在 ImageView 上。
            //
            // 这里只负责两件事：把可能还在播的上一个片源停掉（否则图片会盖在
            // 上一部片子的画面上），以及把传输状态对齐 —— 对控制点来说，
            // 一张图"正在展示"与一段视频"正在播放"是同一种事（PLAYING），
            // 报 STOPPED 会让它的界面显示成"已停止"。
            if (player != null) {
                player.stop();
            }
            // ⚠️ 这一句必须在 player.stop() **之后**：stop() 会同步回调
            // onStateChanged("STOPPED") 把 transportState 打回 STOPPED，
            // 写在前面就会被那次回调覆盖掉，控制点看到的是"收到地址却没播"。
            transportState = "PLAYING";
        } else {
            boolean restarted = (player != null) && player.play(uri);
            if (restarted) {
                transportState = "TRANSITIONING";
            } else {
                // 同一个地址又下发了一遍（控制点拖进度条时的常见行为）。
                // 播放**没有**被打断，状态就不该假装成"正在切换"——
                // 报 TRANSITIONING 会让顶部条闪一下，控制点也可能据此重画进度条，
                // 而画面其实一秒都没断。
                Log.i(TAG, "同一地址重复下发，播放未中断，状态保持不变");
            }
        }
        // 一收到地址就报一次：控制点那边「已收到」的反馈全靠它
        notifyEvent("AVTransport");
    }

    @Override
    public void onSetNextUri(String uri, String metadata) {
        Log.i(TAG, "收到下一曲预告: " + uri);
        nextUri = uri == null ? "" : uri;
        nextUriMetadata = metadata == null ? "" : metadata;
        // 关键：把下一曲交给播放器 —— 它才是 onCompletion 时自动续播的执行者。
        // 只存字段不给播放器的话，续播永远不触发（真机踩过）。
        if (player != null) {
            player.setNext(nextUri, nextUriMetadata);
        }
        // 事件里带上 Next 变更，依赖事件的控制点不用轮询就能刷新队列显示
        notifyEvent("AVTransport");
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
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        jsonPut(sb, "friendlyName", friendlyName);
        jsonPut(sb, "state", getTransportState());
        jsonPut(sb, "transportStatus", getTransportStatus());
        sb.append("\"positionMs\":").append(getPositionMs()).append(',');
        sb.append("\"durationMs\":").append(getDurationMs()).append(',');
        // 打码后再输出 —— 带签名的 CDN URL 里签名本身就是播放凭证，
        // 而 /status 是无鉴权端点（详见 maskUri 的注释）。
        jsonPut(sb, "currentUri", maskUri(currentUri));
        jsonPut(sb, "ip", getLocalIp());
        sb.append("\"httpPort\":").append(getHttpPort()).append(',');
        jsonPut(sb, "version", BuildConfig.VERSION_NAME);
        if (httpServer != null) {
            sb.append("\"subscribers\":").append(httpServer.aliveSubscriberCount()).append(',');
            sb.append("\"msSinceLastControl\":").append(httpServer.millisSinceLastControl()).append(',');
        }
        // 上传目录与存储版图。排障时不开 adb 也能看到"文件到底存哪了、还剩多少"——
        // 这台盒子上"外部存储"就是那支 U 盘，纸面上推不出来，只能看实机。
        if (webEndpoints != null) {
            sb.append("\"storage\":").append(webEndpoints.storageJson()).append(',');
        }
        sb.append("\"uptimeSec\":").append(
                (System.currentTimeMillis() - startedAtMs) / 1000);
        sb.append('}');
        return sb.toString();
    }

    /** JSON 字符串值的最小转义：反斜杠与双引号（URL 与状态串的实际字符集都安全） */
    private static void jsonPut(StringBuilder sb, String key, String val) {
        if (val == null) {
            val = "";
        }
        sb.append('"').append(key).append("\":\"")
          .append(val.replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\",");
    }

    /**
     * 把媒体 URL 打码后再放进 {@code /status} —— 只抹掉排障不需要的那部分。
     *
     * <p><b>为什么必须打码：</b>DLNA 控制点推来的地址常常是**带签名的 CDN URL**
     * （{@code ?token=xxx&expire=...&uid=...}），而**签名本身就是播放凭证** ——
     * 谁拿到这个 URL 谁就能在别处播。可 {@code /status} 是**无鉴权**的 HTTP 端点
     * （{@code http://<电视IP>:49152/status}），局域网内任何人都能取走它。
     * 家庭网络风险低，但访客连过 Wi-Fi、或酒店/公司网络下就是真泄漏；
     * 而且本仓库开源、README 公开写了这个端点。
     *
     * <p><b>为什么保留 host/port/path：</b>排障时要判断「是不是 CDN、端口对不对、
     * 是哪个文件」—— 这些不敏感且是定位问题的关键。所以只把 {@code ?} 之后的
     * query（签名/token 所在）整段替换成 {@code ?***}，host/port/path 原样保留。
     *
     * <p>没有 {@code ?} 的 URL 只做 userinfo 打码（见 {@link #maskUserInfo}）后返回；
     * 空串原样返回；{@code null} 也原样返回，交给 {@link #jsonPut} 现有的空串兜底。
     */
    private static String maskUri(String uri) {
        if (uri == null || uri.length() == 0) {
            return uri;
        }
        // ① userinfo（user:pass@）打码 —— 私有 NAS 的 basic-auth 地址会泄漏凭证。
        String masked = maskUserInfo(uri);
        // ② query（? 之后）整段抹掉：签名/token 参数值不可复原（不留任何前缀字符）。
        int q = masked.indexOf('?');
        if (q < 0) {
            // 没有 query 就没有签名可泄，不打 query 码（但 userinfo 已抹）。
            return masked;
        }
        return masked.substring(0, q) + "?***";
    }

    /**
     * 抹掉 URL 里的 userinfo（{@code user:pass@}）—— 私有 NAS 的 basic-auth 地址。
     *
     * <p><b>为什么也要打码：</b>CDN 用 query 签名（{@link #maskUri} 已覆盖），但
     * {@code http://user:pass@192.168.1.5/video.mp4} 这种把凭据写在 userinfo 里的
     * 地址真实存在（私有 NAS / 简单鉴权服务器）。凭证同样是「谁拿到谁能播」，
     * 一样不能出现在无鉴权的 {@code /status} 上。
     *
     * <p>只在 scheme 之后的 authority 段里找 {@code @}：authority 到第一个
     * {@code / ? #} 为止。这样不会误伤 path 里的 {@code @}（如 {@code /a@b.mp4}）。
     * host/port/path 全部保留 —— 排障信息不丢。
     */
    private static String maskUserInfo(String uri) {
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return uri;  // 不是带 scheme 的绝对 URL，没有 userinfo 可言
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = uri.length();
        for (int i = authorityStart; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        int at = uri.indexOf('@', authorityStart);
        if (at < 0 || at >= authorityEnd) {
            return uri;  // authority 里没有 @（或 @ 在 path 里），不是 userinfo
        }
        // user:pass 换成 ***，保留 @ 与 host/port/path。
        return uri.substring(0, authorityStart) + "***" + uri.substring(at);
    }

    /**
     * 投屏到达时把界面带到前台。
     *
     * <p>为什么必须做：服务是开机自启的，电视重启后**只有服务在跑、
     * 界面从未打开** —— 这时手机一投，播放器没有可渲染的 SurfaceView，
     * 结果是「电视屏幕停在桌面/上一个应用，只有声音」——
     * 用户看到的就是「后台投屏」「投了没反应」。
     * （真机 Hisense Vision-TV 实测确认过的现象。）
     *
     * <p>Android 4.x 允许 Service 启动 Activity（后台启动的限制是
     * Android 10 才引入的，本机 targetSdk 19 不受影响）。
     * MainActivity 是 singleTask：已在前台时只是无操作，在后台/没开时
     * 把任务带回来并复用实例 —— 不会堆积多个界面。
     *
     * <p>每次 SetAVTransportURI 都调（不只第一次）：控制点换片时界面可能
     * 又被用户按 Home 退到后台了，换片就应该再次把画面带回来。
     */
    private void bringPlayerToFront() {
        try {
            autoFront = true;
            Intent intent = new Intent(this, MainActivity.class);
            // Service 里 startActivity 必须带 NEW_TASK（当前不在任何任务栈里）。
            // 这里刻意不加 FLAG_IMMUTABLE 等 —— 与 startForegroundNotification
            // 同一条注释：那些常量是 API 23 才有的，本机 API 15 会 NoSuchFieldError。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // 唤起失败绝不能打断投屏 —— 大不了继续后台播（声音还在）。
            // 用 Throwable 的话会连 OutOfMemoryError 一起吞，这里 Exception 够了。
            Log.w(TAG, "唤起界面到前台失败（继续后台播放）", e);
        }
    }

    /**
     * 界面是不是由「投屏到达」自动唤起的（且还没被消费）。
     *
     * <p>对称设计的一半：投屏到达 → 唤起到前台；播放结束 → 退回后台，
     * 电视回到投屏之前的样子。由界面在「播放→空闲」变迁时取走并执行
     * {@code moveTaskToBack}。用户手动打开的界面（标志位为 false）不动 ——
     * 不能把正在看面板的人踢回桌面。
     */
    public boolean takeAutoFrontFlag() {
        boolean v = autoFront;
        autoFront = false;
        return v;
    }

    /** 只看不消费 —— 界面的延迟退回任务用来决定「到点后要不要退」 */
    public boolean hasAutoFrontFlag() {
        return autoFront;
    }

    /**
     * 从 DLNA 元数据里判内容类型。
     *
     * <p>控制点会在 {@code CurrentURIMetaData} 里带一段 DIDL-Lite，
     * 其中的 {@code upnp:class} 是权威判据：
     * {@code object.item.audioItem.musicTrack} / {@code object.item.videoItem} 等。
     *
     * <p>但**不能只靠它** —— 不少控制点根本不传元数据，或者传个空壳。
     * 所以这里只当"提示"，拿不到时由 {@link #onPrepared} 用真实视频尺寸定论。
     */
    private static int kindOf(String metadata) {
        if (metadata == null || metadata.length() == 0) {
            return KIND_UNKNOWN;
        }
        if (metadata.contains("object.item.audioItem")) {
            return KIND_AUDIO;
        }
        if (metadata.contains("object.item.videoItem")) {
            return KIND_VIDEO;
        }
        // 图片要**排在视频之后**判：{@code object.item.imageItem.photo} 里
        // 不含 "videoItem"，两者不会互相误伤；这里排在后面只是因为
        // 视频/音频是主用途，先判更常见的。
        if (metadata.contains("object.item.imageItem")) {
            return KIND_IMAGE;
        }
        return KIND_UNKNOWN;
    }

    @Override
    public void onPlay() {
        if (player != null) {
            player.resume();
        }
    }

    // ------------------------------------------- WebCastEndpoints.CastTarget

    /**
     * 把盒子上已上传的本地文件当投屏源播出去（网页上点「投到电视」的落点）。
     *
     * <p><b>为什么复用 onSetUri 而不是直接 player.play()</b>：onSetUri 里还有一整套
     * 状态对齐 —— currentUri / 元数据 / 标题 / kindFromMetadata / 清空下一曲队列 /
     * 推 AVTransport 事件。绕开它直接播，控制点回读到的 CurrentURI 还是上一部片子，
     * 界面形态也会停在旧类型上，等于电视机和手机显示的是两个不同的东西。
     *
     * <p><b>为什么地址是 {@code http://<本机IP>:<端口>/media/<名字>}，而不是 {@code file://}</b>：
     * 上传的文件在应用私有目录里（{@code files/uploads} 真机实测是 {@code drwx------}），
     * 而真正去 open 它的是**另一个进程** mediaserver —— 它穿不过这个目录，
     * 实测只会拿到 {@code error (1, -2147483648)}，电视上什么都不放。
     * 改由我们自己进程以 HTTP 提供，权限问题就不存在了。
     *
     * <p>顺带两个好处：① Content-Type 探测要用 http 地址，而这台 MTK 盒子
     * {@code getVideoWidth()} 恒返回 0 —— 少了探测，上传的视频会被判成纯音频，
     * 画面在放、界面却弹音乐卡片；② {@code currentUri} 成了一个真正的 URL，
     * 控制点回读/续播/拖拽都按既有那条（http 媒体的）路径走，不必为本地文件
     * 再开一套语义。
     */
    @Override
    public void castLocalFile(File file) {
        if (file == null) {
            return;
        }
        String url = "http://" + getLocalIp() + ":" + getHttpPort()
                + "/media/" + Uri.encode(file.getName());
        onSetUri(url, didlFor(file));
        onPlay();
    }

    /**
     * 给本地文件拼一段最小 DIDL-Lite。
     *
     * <p><b>为什么必须自己拼</b>：{@link DidlLite} 只有读取方法、没有构造器。
     * 而 {@code <upnp:class>} 这一项不能省 —— {@link #kindOf} 靠它分音频/视频/图片，
     * 少了它 mp3 会被判成 {@code KIND_UNKNOWN}，界面按视频形态渲染出**一块黑屏**，
     * 而声音其实正常（用户会以为投屏坏了）。
     */
    private static String didlFor(File file) {
        String name = file.getName();
        String title = escapeXml(name);
        String upnpClass;
        // 图片必须**先判**：它若掉进下面那个"其他一律当视频"的兜底分支，
        // {@link #kindOf} 就会把 kindFromMetadata 判成 KIND_VIDEO，
        // 界面于是走视频通道去等一个永远不来的视频帧 —— 又是黑屏。
        if (isImageName(name)) {
            upnpClass = "object.item.imageItem.photo";
        } else if (isAudioName(name)) {
            upnpClass = "object.item.audioItem.musicTrack";
        } else {
            upnpClass = "object.item.videoItem";
        }
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                + " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                + "<item id=\"" + title + "\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + title + "</dc:title>"
                + "<upnp:class>" + upnpClass + "</upnp:class>"
                + "</item></DIDL-Lite>";
    }

    /**
     * 按扩展名判是不是图片。
     *
     * <p>只认浏览器与相册最常产出的这几种。判错方向的代价不对称：
     * 漏判成图片会退回"视频"通道（黑屏），误判成图片则一张真视频变成一张
     * 显示不出来的图 —— 所以宁可只认确定的扩展名。
     */
    private static boolean isImageName(String name) {
        String ext = extensionOf(name);
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext) || "webp".equals(ext);
    }

    /** 小写扩展名（不含点）；没有点则返回空串 */
    private static String extensionOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    /**
     * 按扩展名判是不是音频。
     *
     * <p>判不出来一律当视频：视频是"有画面"的默认预期，猜错的代价也最小 ——
     * 猜成视频而实际是音频，画面是黑的但声音在放；猜成音频而实际是视频，
     * 界面会切到音乐形态、把画面藏起来，那才是真的丢了东西。
     */
    private static boolean isAudioName(String name) {
        String ext = extensionOf(name);
        return "mp3".equals(ext) || "m4a".equals(ext) || "aac".equals(ext)
                || "wav".equals(ext) || "flac".equals(ext) || "ogg".equals(ext)
                || "wma".equals(ext) || "ape".equals(ext);
    }

    /** DIDL-Lite 是 XML，文件名里的 {@code & < > " '} 必须转义 */
    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    @Override
    public void onPause() {
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onStop() {
        if (player != null) {
            player.stop();
        }
        transportState = "STOPPED";
        // 必须把当前片源清掉。
        // MainActivity.isPlaying() 在时长归零后会退化成「有没有 URI」来判断，
        // 而这个字段不清就永远非空 —— 结果是用户按了停止，电视上却还停在
        // 「正在播放」形态（面板隐藏、只剩播放条）。describeState() 同理，
        // 它也会因为 URI 非空而继续报「正在播放」。
        currentUri = "";
        // 元数据和标题一起清。留着的话，下一次投屏在解析出新标题之前，
        // 界面会短暂显示**上一首**的名字 —— 旧标题配新地址，自相矛盾。
        currentMetadata = "";
        currentTitle = "";
        // 下一曲队列一并作废：Stop 是控制点明确结束，歌单不跨 Stop 存活
        // （DLNA 语义：新的 SetAVTransportURI/Stop 都会清掉 Next）。
        nextUri = "";
        nextUriMetadata = "";
        // 一并清掉上一次的错误：已经停止的传输不该继续挂着旧报错，
        // 否则 describeState() 会优先显示那句陈旧的「出错：…」。
        clearError();
        // 报一次：控制点要知道"设备上已经没内容了"
        notifyEvent("AVTransport");
    }

    @Override
    public void onSeek(long positionMs) {
        if (player == null) {
            return;
        }
        long duration = player.getDuration();
        // 越界保护：目标超出总时长时**忽略，不要下发**。
        //
        // 为什么必须挡：MediaPlayer.seekTo() 一旦越过末尾，位置会直接落到结尾
        // 并触发播放完成 —— 用户看到的就是「拖了一下进度条，电视上进度直接满了、
        // 声音也没了」。而越界值往往不是控制点算错，是**我们把 Target 解析错了**
        // （单位、格式、小数位）：错得越大，越像"跳到了结尾"。
        //
        // 留 1 秒容差：控制点按百分比算目标时会有取整误差，
        // 而"正好拖到末尾"本身是合法操作，不该被拦。
        if (duration > 0 && positionMs > duration + 1000L) {
            Log.w(TAG, "Seek 目标越界，已忽略：target=" + positionMs
                    + "ms, duration=" + duration + "ms（超出 "
                    + (positionMs - duration) + "ms）");
            return;
        }
        // 两个数一起打。这一行是「进度满了」这类问题的第一现场 ——
        // 只打 target 看不出它是不是被解析错了，必须和 duration 对着看。
        Log.i(TAG, "Seek：target=" + positionMs + "ms, duration=" + duration + "ms");
        player.seekTo((int) Math.max(0L, positionMs));
    }

    @Override
    public long getPositionMs() {
        return player == null ? 0 : player.getPosition();
    }

    @Override
    public long getDurationMs() {
        return player == null ? 0 : player.getDuration();
    }

    @Override
    public String getTransportState() {
        return transportState;
    }

    /**
     * GetTransportInfo 的 CurrentTransportStatus —— 与事件里的
     * {@code TransportStatus} 共用同一个判据。
     *
     * <p>抽成 {@link #hasTransportError()} 而不是两处各写一遍：
     * 同一个语义写两份，迟早会有一份忘了跟着改，而"两个接口对同一台设备
     * 给出相反答案"这种不一致，恰恰是最难排查的一类问题 —— 控制点自己都
     * 不知道该信哪个。
     */
    @Override
    public String getTransportStatus() {
        return hasTransportError() ? "ERROR_OCCURRED" : "OK";
    }

    /** 「当前是否处于出错态」—— 事件、GetTransportInfo、界面共用的唯一判据 */
    private boolean hasTransportError() {
        return lastErrorKind != PlaybackPolicy.ERR_NONE;
    }

    /**
     * 清掉当前错误。
     *
     * <p><b>为什么抽成一个方法</b>：要清的是**两个**字段（分类 + 细节），
     * 三处各写一遍迟早会漏掉一个 —— 而漏掉的后果是「分类说没错、
     * 细节里还留着上次的报错」这种自相矛盾的状态。顶部状态条正是靠分类
     * 判断要不要出现，于是会出现画面好好的、屏幕上却压着一条旧报错。
     */
    private void clearError() {
        lastErrorKind = PlaybackPolicy.ERR_NONE;
        lastError = "";
    }

    @Override
    public void onSourceChanged(String uri, String metadata) {
        // 播放列表自动续播：URI/元数据状态与播放器对齐（不走 SOAP）。
        currentUri = uri;
        currentMetadata = metadata == null ? "" : metadata;
        currentTitle = DidlLite.title(currentMetadata);
        kindFromMetadata = kindOf(currentMetadata);
        audioOnly = (kindFromMetadata == KIND_AUDIO);
        Log.i(TAG, "自动续播已切换片源: " + currentUri);
        notifyEvent("AVTransport");
    }

    @Override
    public void onSetVolume(int volume0to100) {
        if (player != null) {
            player.setVolume(Math.max(0f, Math.min(1f, volume0to100 / 100f)));
        }
        // 音量事件：控制点上同时可能有好几个遥控器（手机、平板），
        // 不推的话另一个界面上的音量条会一直停在旧值。
        notifyEvent("RenderingControl");
    }

    @Override
    public int getVolume0to100() {
        // 回读真实音量。原来这里硬编码 return 100 —— 控制点拖完音量条
        // 再读一次会看到跳回 100，等于对着控制点撒谎。
        return player == null ? 100 : player.getVolume0to100();
    }

    @Override
    public void onSetMute(boolean mute) {
        if (player != null) {
            player.setMute(mute);
        }
        // 静音也是 RenderingControl 的状态，同样要推事件。
        // 不推的话：用户手机上按了静音，同一个网络的另一台遥控设备
        // （平板、另一个人的手机）上的静音开关会一直停在旧状态。
        notifyEvent("RenderingControl");
    }

    @Override
    public boolean getMute() {
        // 如实回读。写死 false 的话，控制点按完静音回读一次看到"没静音"，
        // 会把开关又画回去 —— 和已经修过的 GetVolume 恒回 100 是同一个 bug。
        return player != null && player.isMuted();
    }

    /** 当前是不是纯音频流。界面据此决定显示音乐卡片还是视频画面。 */
    public boolean isAudioOnly() {
        return audioOnly;
    }

    /**
     * 当前投的是不是一张静态图。界面据此走 {@code BitmapFactory} 通道
     * 而不是等 MediaPlayer 出画面。
     *
     * <p>刻意**不动** {@link #isAudioOnly()}：图片既不是音频也不是视频，
     * 硬塞进那个二值里，总有一边要错。三态各问各的，判据才不会打架。
     */
    public boolean isImage() {
        return kindFromMetadata == KIND_IMAGE;
    }

    // ------------------------------------------- EventDispatcher.EventSource

    /**
     * 某个服务当前所有 {@code sendEvents="yes"} 的变量值 —— 事件体就由它组装。
     *
     * <p>这里有两条铁律：
     * <ol>
     *   <li><b>只给 SCPD 里声明过事件化的变量。</b>多塞字段控制点会忽略，
     *       少给字段它就会一直认为那个值是空的 —— 而它不会来问，只会等。</li>
     *   <li><b>值必须是真的。</b>事件是"主动汇报"，控制点没法核对。
     *       在这里填个好看的常量（比如音量恒 100），就是对着用户撒谎。</li>
     * </ol>
     *
     * <p>返回空 Map 表示"这个服务没有可报的变量"。{@link EventDispatcher}
     * 会据此**不推进 SEQ、不发空事件** —— 这是有意的，发一条什么都没有的
     * NOTIFY 只会让控制点白忙一场。
     */
    @Override
    public Map<String, String> eventedVars(String service) {
        Map<String, String> vars = new HashMap<String, String>();
        if ("AVTransport".equals(service)) {
            vars.put("TransportState", transportState);
            // 如实报"出没出错"。原来这里写死 "OK" ——
            // 而「状态字段必须反映现在」是这个项目一以贯之的纪律（见 onPrepared 里
            // 清 lastError 的那段说明）。控制点拿到 OK 就不会提示用户，
            // 于是一个正在反复重连的设备在它眼里是"一切正常"。
            // 判据与 GetTransportInfo 的 CurrentTransportStatus 共用，
            // 见 hasTransportError()。
            vars.put("TransportStatus", getTransportStatus());
            vars.put("CurrentTrackURI", currentUri == null ? "" : currentUri);
            vars.put("CurrentTrackDuration",
                    UpnpHttpServer.formatTime(getDurationMs()));
            // 当前位置也进事件。
            //
            // 为什么必须带：一部分控制点（国产投屏 SDK 居多）**不轮询
            // GetPositionInfo**，而是靠事件里的 RelativeTimePosition 更新进度条。
            // 不给这个字段，它的进度条就从头到尾不动 —— 而设备这边看起来一切正常，
            // 日志里也全是 200，排查时完全没有线索。
            //
            // 事件只在状态变化时发，所以它不会退化成每秒一次的位置流
            // （那是轮询该干的事）；它的作用是让控制点在**状态切换的那一刻**
            // 拿到一个正确的位置基准，而不是从 0 开始重新推。
            vars.put("RelativeTimePosition",
                    UpnpHttpServer.formatTime(getPositionMs()));
            return vars;
        }
        if ("RenderingControl".equals(service)) {
            vars.put("Volume", String.valueOf(getVolume0to100()));
            // 必须报真实静音状态。原来这里写死 "0"（未静音）——
            // 而 SCPD 里 Mute 是声明了可事件化的，控制点会拿这个值去画开关。
            // 写死就等于"用户按了静音，另一个遥控器上的开关又自己弹回来了"。
            vars.put("Mute", getMute() ? "1" : "0");
            return vars;
        }
        if ("ConnectionManager".equals(service)) {
            vars.put("SourceProtocolInfo", "");
            // 和 GetProtocolInfo 共用同一份常量，避免两处漂移
            vars.put("SinkProtocolInfo", UpnpHttpServer.SINK_PROTOCOL_INFO);
            vars.put("CurrentConnectionIDs", "0");
            return vars;
        }
        return vars;
    }

    /**
     * 状态变了，推事件给订阅了的控制点。**非阻塞**（只是往队列里排），
     * 所以可以放心从 onStateChanged 这种高频回调里调。
     */
    private void notifyEvent(String service) {
        UpnpHttpServer s = httpServer;
        if (s != null) {
            s.notifyEvent(service);
        }
    }

    // ------------------------------------- MediaPlayerController.Listener

    @Override
    public void onStateChanged(String state) {
        Log.i(TAG, "播放状态: " + state);
        String mapped = transportState;
        if ("PLAYING".equals(state)) {
            mapped = "PLAYING";
        } else if ("PAUSED".equals(state)) {
            mapped = "PAUSED_PLAYBACK";
        } else if ("STOPPED".equals(state)) {
            mapped = "STOPPED";
        } else if ("PREPARING".equals(state)) {
            mapped = "TRANSITIONING";
        } else if ("RECONNECTING".equals(state)) {
            // 重连中必须报 TRANSITIONING，**不能漏**。
            //
            // 漏掉的话 transportState 会停留在上一次的值（通常就是 PLAYING），
            // 而这段时间播放器已经被释放、位置读不到 —— 控制点看到的是
            // 「状态说正在播放，进度却一直不动」，它的进度条就卡住了。
            // 这是"手机上进度条不跟着走"的来源之一。
            mapped = "TRANSITIONING";
        } else if ("ERROR".equals(state)) {
            // 出错同样不能停留在 PLAYING，否则控制点会一直以为还在播。
            // 注意 TransportState 的合法取值里没有 ERROR ——
            // "出没出错"是 TransportStatus 的事（见 eventedVars）。
            mapped = "STOPPED";
        }
        // 状态去重：没变化就不推事件。
        //
        // 不是省流量 —— 是掐断一个真死循环：播放器放弃重连进入错误态后，
        // 事件推送要读播放器，读一下触发一次 -38 错误 → onError → 又推
        // ERROR → 又要读…… 25 次/秒刷屏（网易云切歌真机实测）。
        // LastChange 的语义本来就是「变了才推」。
        if (mapped.equals(transportState)) {
            return;
        }
        transportState = mapped;
        // 这就是 GENA 存在的理由：播放/暂停一变就告诉控制点，
        // 不然手机上的按钮状态要等用户手动刷新才更新。
        notifyEvent("AVTransport");
    }

    @Override
    public void onError(int kind, String detail) {
        lastErrorKind = kind;
        lastError = detail;
        // 分类名进日志（"播放错误[DECODE]"），细节也进日志。
        // 两者都要有：分类让日志能一眼扫出"哪类错多"，细节用来定位具体那一次。
        Log.w(TAG, "播放错误[" + PlaybackPolicy.errorKindName(kind) + "]: " + detail);
    }

    @Override
    public void onPrepared(int durationMs, boolean hasVideo) {
        // 注意：这个方法可能对同一次播放被调用两次 —— 老芯片（MTK 5880 实测）
        // onPrepared 时视频尺寸未就绪，播放器先按纯音频回调，延迟复查确认
        // 有视频后再以 hasVideo=true 重调。本方法幂等：audioOnly 由
        // 元数据 / hasVideo 重新定夺，界面轮询刷新自然跟上。
        // 播放已经真的就绪了 —— 把之前那条错误清掉。
        //
        // 不清的话会出一个很别扭的现象：一次**已经自愈**的断流（onError → 重连 →
        // prepare 成功），那句"播放卡死，正在重连"会一直挂在 lastError 里，
        // 而顶部状态条正是靠它判断"要不要出现"—— 于是画面好好的，
        // 屏幕上却一直压着一条报错。
        //
        // 这和「谎报军情比失败更糟」是同一条纪律：状态字段必须反映**现在**，
        // 而不是"曾经出过事"。真要报故障，下一次 onError 会重新写上。
        clearError();
        // 元数据说了算的时候听元数据的；元数据没说，就用 MediaPlayer 报的
        // 真实视频尺寸定论。两个信号都用上，比只看一个稳。
        if (kindFromMetadata == KIND_AUDIO) {
            audioOnly = true;
        } else if (kindFromMetadata == KIND_VIDEO) {
            audioOnly = false;
        } else {
            audioOnly = !hasVideo;
        }
        Log.i(TAG, "已就绪，时长 " + durationMs + "ms，"
                + (audioOnly ? "纯音频（音乐投屏）" : "含视频画面"));
        // 时长/片源到这一步才真正确定，补一次事件让控制点的进度条能算比例
        notifyEvent("AVTransport");
    }

    // ------------------------------------------------------------ 对外查询

    public MediaPlayerController getPlayer() {
        return player;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    @Override
    public String getLocalIp() {
        // 优先用 SSDP **实际绑上的那张网卡**的地址。
        //
        // 三个用途共用这一个出处：界面上的地址、/status、以及设备描述里的
        // presentationURL（批 2 起）。谁自己算一遍，都会在「第一张候选网卡
        // joinGroup 失败、换到第二张」时分叉。
        //
        // 不能只返回 localIp：那是 onCreate 时按「候选列表第一张网卡」猜的，
        // 而 joinGroup 有可能换到第二张（第一张没有 IPv4、或是隧道接口）。
        // 两者一旦分叉，界面显示的地址和控制点拿到的 LOCATION 就是两个 IP ——
        // 排障时照着界面上的地址去 curl，怎么都复现不了用户的问题。
        SsdpResponder s = ssdp;
        if (s != null) {
            String ip = s.getBoundIp();
            if (ip != null) {
                return ip;
            }
        }
        // 还没绑上（开机 Wi-Fi 未就绪、正在退避重试）才退回猜测值。
        return localIp;
    }

    /**
     * HTTP **实际监听**的端口。暴露出来是为了让界面/`/status` 显示地址时
     * 不必再硬编码一遍端口号，也不会在端口回退后显示一个错的。
     */
    public int getHttpPort() {
        return currentHttpPort();
    }

    /**
     * 取当前 HTTP 服务的实际端口，还没建起来时退回首选常量。
     *
     * <p>单一出口：LOCATION、界面、{@code /status} 都从这里拿端口，
     * 免得有哪一处还在用 {@link #HTTP_PORT} 这个首选值 —— 一旦发生回退，
     * 那一处就会给出一个没人监听的地址。
     */
    private int currentHttpPort() {
        UpnpHttpServer h = httpServer;
        return h != null ? h.getPort() : HTTP_PORT;
    }

    /**
     * 当前错误的**分类** —— 界面据此显示用户能懂的话。
     *
     * <p>取值是 {@link PlaybackPolicy} 里的 {@code ERR_*}，
     * {@code ERR_NONE} 表示当前没出错。
     */
    public int getLastErrorKind() {
        return lastErrorKind;
    }

    /**
     * 当前错误的**技术细节**。
     *
     * <p>给排障用的（写进日志、贴给人看）。**界面不该直接显示它** ——
     * {@code "what=1 extra=-1010"} 这种字符串用户看不懂，也没法据此做任何决定。
     * 界面上要显示的是 {@link #getLastErrorKind()} 对应的那句话。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 当前媒体 URL。
     *
     * <p>这个方法身兼两职：界面拿它显示"正在播放什么"，协议层拿它回答
     * GetMediaInfo / GetPositionInfo 的 CurrentURI、TrackURI。**必须是同一个
     * 出处** —— 否则界面显示的和报给控制点的会不一致，排障时极难发现。
     */
    @Override
    public String getCurrentUri() {
        return currentUri;
    }

    /**
     * GetMediaInfo / GetPositionInfo 用 —— 当前媒体的元数据原文。
     *
     * <p>回的是**控制点自己推来的那一份**，一个字节都不改。
     */
    @Override
    public String getCurrentMetadata() {
        return currentMetadata;
    }

    /**
     * 从元数据里解析出的标题，给界面用。
     *
     * <p>可能为空串（控制点没给标题，或者给的是空壳元数据）——
     * 界面此时应当回退到从 URL 截出来的文件名，而不是显示空白。
     */
    public String getCurrentTitle() {
        return currentTitle;
    }

    /**
     * 组播是否已就绪。
     *
     * <p>没就绪就等于「手机搜不到设备」—— 这是用户最常遇到的故障，
     * 所以单独暴露成一个明确的布尔值，而不是让界面去解析状态字符串。
     */
    public boolean isDiscoveryReady() {
        return ssdp != null && ssdp.isBound();
    }

    /**
     * HTTP 服务是否真的在监听。
     *
     * <p>和 {@link #isDiscoveryReady()} 是**两件独立的事，必须分开报**：
     * SSDP 与 HTTP 是两条链路，死一条另一条照活。搜得到设备（SSDP 正常）
     * 但 HTTP 没起来时，症状是「手机能看到这台设备、一点投屏就失败」——
     * 只看组播状态的话，界面会显示一切正常，等于谎报军情。
     */
    public boolean isHttpReady() {
        return httpServer != null && httpServer.isBound();
    }

    public String getBoundInterfaceName() {
        if (ssdp == null) {
            return "(未启动)";
        }
        if (isDiscoveryReady()) {
            return ssdp.getBoundInterfaceName();
        }
        // 没绑上时把候选网卡列出来 —— 这才是排障真正需要的信息。
        // 只显示"未绑定"等于什么都没说：用户既不知道有没有网卡，也不知道为什么没绑上。
        return "(未绑定) 候选: " + NetUtil.describeCandidates();
    }

    // ------------------------------------------------------------- 自检

    /**
     * 自检：两条链路是不是还活着；死了就重建。
     *
     * <p><b>为什么必须有"发现坏了就修"这一环。</b>这个项目里所有其它机制
     * ——绑定退避重试、并发上限、body 上限、异常兜底——全都是**预防**。
     * 而线程可能因为谁也预料不到的原因退出：系统把组播组踢掉、receive 抛出
     * 一个没被 catch 的异常、socket 被底层关掉、Wi-Fi 驱动重启……
     * 一旦退出，{@code isBound()} 就一直返回 false，
     * 而**没有任何东西会去把它拉起来**。
     *
     * <p>用户看到的是「昨天还好好的，今天搜不到了」，重启 App 才好 ——
     * 而日志里什么都看不出来，因为故障发生时根本没人记录它。
     *
     * <p>两条链路分开判断：SSDP 死了只影响"搜得到"，HTTP 死了只影响"投得上去"。
     * 任何一个死了都单独重建，不一起推倒 —— 重建有成本（端口重绑、
     * 控制点要重新拉设备描述）。
     *
     * <p>判据是 {@code !isAlive() && !isBound()}，两个条件缺一不可：
     * 线程还活着但只是**还没绑上**（开机 Wi-Fi 未就绪、正在退避重试）是正常状态，
     * 绝不能重建 —— 那会把正在进行的退避循环打断，变成"每 30 秒重启一次、
     * 永远等不到网"。
     */
    /**
     * Auto-Stop（借鉴 gmrender-resurrect 的 --auto-stop）：控制点离开后
     * 自动停止投屏 —— 「手机退出了，电视还在播」的行业解法。
     *
     * <p>判据（全部满足才停）：<br>
     * ① 正在播放；<br>
     * ② 存活订阅数为 0 —— 订阅由控制点周期续订，续订停止 = 控制点真的走了
     *    （阈值授予 300s 起，所以本检查的响应下限是订阅超时，不是 30 秒）；<br>
     * ③ 本轮播放期间曾有订阅者 —— 从未订阅过的控制点（纯投放型）不受
     *    此机制影响，绝不误停；<br>
     * ④ 距最后一次控制指令超过 {@link PlaybackPolicy#AUTO_STOP_AFTER_MS}。
     */
    private void checkAutoStop() {
        if (player == null || httpServer == null) {
            return;
        }
        if (!"PLAYING".equals(transportState)) {
            return;
        }
        if (httpServer.aliveSubscriberCount() > 0) {
            return;
        }
        if (httpServer.lastSubscribeAt() < playStartedAtMs) {
            return;
        }
        long idleMs = httpServer.millisSinceLastControl();
        if (idleMs < PlaybackPolicy.AUTO_STOP_AFTER_MS) {
            return;
        }
        Log.w(TAG, "控制点已离开（无订阅者，最后指令 " + (idleMs / 1000)
                + "s 前），自动停止投屏");
        player.stop();
    }

    private void checkThreadsAlive() {
        if (shuttingDown) {
            return;
        }
        boolean bad = false;

        SsdpResponder s = ssdp;
        if (s != null && !s.isAlive() && !s.isBound()) {
            bad = true;
            logWatchdog("SSDP 线程已死且未绑定，重建");
            restartSsdp("线程已死");
        }

        UpnpHttpServer h = httpServer;
        if (h != null && !h.isAlive() && !h.isBound()) {
            bad = true;
            logWatchdog("HTTP 线程已死且未监听，重建");
            restartHttp("线程已死");
        }

        if (!bad) {
            // 恢复正常了就把计数清零 —— 否则"偶发一次"会累积成
            // 触发日志节流的次数，下一次真出问题时反而不打日志。
            watchdogFailures = 0;
        }
    }

    /** 自检日志的节流出口。前几次每次都记（那是最需要看的窗口），之后每 N 次一条 */
    private void logWatchdog(String reason) {
        watchdogFailures++;
        if (watchdogFailures <= 3 || watchdogFailures % WATCHDOG_LOG_EVERY == 0) {
            Log.w(TAG, "自检：" + reason + "（连续第 " + watchdogFailures + " 次）");
        }
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 改名广播：重读名字并重建两条链路，让新名字立即出现在
        // device.xml 与 SSDP 应答里，不用等重启。
        if (intent != null && RenameReceiver.ACTION_APPLY_RENAME.equals(intent.getAction())) {
            applyRename();
        }
        // 被系统杀掉后自动重启，这是「不断联」的第二道保险
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务销毁，释放资源");
        // 第一件事：告诉自检看门狗"别再重建了"。
        //
        // 必须在 shutdown 之前设上。反过来的话，看门狗可能正好在
        // "我们刚 shutdown、还没设标志"的窗口里醒来，看到线程死了
        // 就把它重新拉起来 —— 于是服务都销毁了，SSDP 还活着占着端口，
        // 下次启动直接 EADDRINUSE。
        shuttingDown = true;
        watchdog.removeCallbacks(watchdogTask);
        // 网络变化的重建任务也要摘掉 —— 它跑在同一个主线程队列上，
        // 服务销毁后醒来会对着一堆已经清空的字段动手。
        watchdog.removeCallbacks(rebuildOnNetworkChange);
        // 注销广播：不注销的话，系统会一直持有这个 Receiver，
        // 而它内部持有 Service 实例 —— 老盒子上这就是一个漏掉的 Service。
        if (connectivityReceiver != null) {
            try {
                unregisterReceiver(connectivityReceiver);
            } catch (Exception e) {
                Log.w(TAG, "注销网络监听失败（继续销毁）", e);
            }
            connectivityReceiver = null;
        }

        // 先撤掉前台通知，再拆服务。
        //
        // 不撤的话：startForeground 挂上去的那条常驻通知**不会**跟着服务一起消失，
        // 它会一直留在通知栏里，点一下还会去拉起一个已经死掉的服务 ——
        // 用户看到的是「投屏早断了，通知栏里却还说正在投屏」。
        // 老设备上通知栏本来就不宽裕，一条僵尸通知很显眼。
        //
        // 顺序放在最前：后面几行会关 socket、释放播放器，那些都可能抛异常，
        // 万一抛在中间，通知就永远撤不掉了。
        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.w(TAG, "撤前台通知失败（继续销毁）", e);
        }

        if (ssdp != null) {
            ssdp.shutdown();
        }
        if (httpServer != null) {
            httpServer.shutdown();
        }
        if (player != null) {
            player.release();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
    }
}
