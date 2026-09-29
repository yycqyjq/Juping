package com.juping.cast;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.juping.cast.dlna.NetUtil;
import com.juping.cast.dlna.SsdpResponder;
import com.juping.cast.dlna.UpnpHttpServer;
import com.juping.cast.player.MediaPlayerController;

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
        implements UpnpHttpServer.CommandHandler, MediaPlayerController.Listener {

    private static final String TAG = "DlnaRendererService";

    private static final String PREFS = "juping";
    private static final String KEY_UUID = "device_uuid";

    /** DLNA 服务端口。用固定端口方便排查，冲突概率很低。 */
    private static final int HTTP_PORT = 49152;

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

    private String uuid;
    private String friendlyName;
    private String localIp = "0.0.0.0";
    private volatile String transportState = "STOPPED";
    private volatile String lastError = "";
    private volatile String currentUri = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "服务创建");

        uuid = loadOrCreateUuid();
        friendlyName = "聚屏-" + android.os.Build.MODEL;
        player = new MediaPlayerController();
        player.setListener(this);

        acquireLocks();
        startForegroundNotification();

        localIp = NetUtil.pickLocalIp();
        String location = "http://" + localIp + ":" + HTTP_PORT + "/upnp/device.xml";

        httpServer = new UpnpHttpServer(HTTP_PORT, uuid, friendlyName, this);
        httpServer.start();

        ssdp = new SsdpResponder(uuid, location, "Android/" + android.os.Build.VERSION.RELEASE);
        ssdp.start();

        Log.i(TAG, "接收端已就绪：名称=" + friendlyName + " 地址=" + location);
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
        currentUri = uri;
        lastError = "";
        transportState = "TRANSITIONING";
        if (player != null) {
            player.play(uri);
        }
    }

    @Override
    public void onPlay() {
        if (player != null) {
            player.resume();
        }
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
        // 一并清掉上一次的错误：已经停止的传输不该继续挂着旧报错，
        // 否则 describeState() 会优先显示那句陈旧的「出错：…」。
        lastError = "";
    }

    @Override
    public void onSeek(long positionMs) {
        if (player != null) {
            player.seekTo((int) positionMs);
        }
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

    @Override
    public void onSetVolume(int volume0to100) {
        if (player != null) {
            player.setVolume(Math.max(0f, Math.min(1f, volume0to100 / 100f)));
        }
    }

    @Override
    public int getVolume0to100() {
        return 100;
    }

    // ------------------------------------- MediaPlayerController.Listener

    @Override
    public void onStateChanged(String state) {
        Log.i(TAG, "播放状态: " + state);
        if ("PLAYING".equals(state)) {
            transportState = "PLAYING";
        } else if ("PAUSED".equals(state)) {
            transportState = "PAUSED_PLAYBACK";
        } else if ("STOPPED".equals(state)) {
            transportState = "STOPPED";
        } else if ("PREPARING".equals(state)) {
            transportState = "TRANSITIONING";
        }
    }

    @Override
    public void onError(String message) {
        lastError = message;
        Log.w(TAG, "播放错误: " + message);
    }

    @Override
    public void onPrepared(int durationMs) {
        Log.i(TAG, "已就绪，时长 " + durationMs + "ms");
    }

    // ------------------------------------------------------------ 对外查询

    public MediaPlayerController getPlayer() {
        return player;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public String getLocalIp() {
        return localIp;
    }

    /** HTTP 端口。暴露出来是为了让界面显示地址时不必再硬编码一遍端口号。 */
    public int getHttpPort() {
        return HTTP_PORT;
    }

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
     * 组播是否已就绪。
     *
     * <p>没就绪就等于「手机搜不到设备」—— 这是用户最常遇到的故障，
     * 所以单独暴露成一个明确的布尔值，而不是让界面去解析状态字符串。
     */
    public boolean isDiscoveryReady() {
        return ssdp != null && ssdp.isBound();
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

    // ------------------------------------------------------------- 生命周期

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
