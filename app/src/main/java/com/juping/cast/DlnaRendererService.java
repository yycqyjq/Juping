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

import com.juping.cast.dlna.EventDispatcher;
import com.juping.cast.dlna.NetUtil;
import com.juping.cast.dlna.SsdpResponder;
import com.juping.cast.dlna.UpnpHttpServer;
import com.juping.cast.player.MediaPlayerController;

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
        EventDispatcher.EventSource {

    private static final String TAG = "DlnaRendererService";

    private static final String PREFS = "juping";
    private static final String KEY_UUID = "device_uuid";

    /** DLNA 服务端口。用固定端口方便排查，冲突概率很低。 */
    private static final int HTTP_PORT = 49152;

    /** 内容类型的三个取值，见 {@link #kindOf} 与 {@link #audioOnly}。 */
    private static final int KIND_UNKNOWN = 0;
    private static final int KIND_AUDIO = 1;
    private static final int KIND_VIDEO = 2;

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

    /**
     * 当前是不是纯音频流（音乐投屏）。界面据此切「音乐卡片 / 视频画面」。
     *
     * <p>为什么必须区分：音频流走同一个 SurfaceView 时画面什么都没有，
     * 电视就是**一片黑**，只显示"正在播放"和进度条 —— 声音明明在放，
     * 看着却像投屏失败了。音乐是主要用途之一，这个误判代价很高。
     */
    private volatile boolean audioOnly = false;

    /** 从 DLNA 元数据里读到的内容类型：0=未知，1=音频，2=视频。 */
    private volatile int kindFromMetadata = 0;

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

        httpServer = new UpnpHttpServer(HTTP_PORT, uuid, friendlyName, this, this);
        httpServer.start();

        // 只把 HTTP 端口交给 SSDP，不传拼好的 LOCATION ——
        // 设备描述地址里的 IP 必须等组播真的绑上某张网卡之后才知道。
        // 提前在外面拼一个，就等于把「组播绑哪张网卡」和「告诉手机去哪取描述」
        // 拆成两次独立选择：第一张候选网卡 joinGroup 失败时，组播会绑到第二张上，
        // 而 LOCATION 还指着第一张 —— 手机搜得到设备、点进去却拉不到描述。
        ssdp = new SsdpResponder(uuid, HTTP_PORT, "Android/" + android.os.Build.VERSION.RELEASE);
        ssdp.start();

        Log.i(TAG, "接收端已就绪：名称=" + friendlyName + " HTTP 端口=" + HTTP_PORT
                + "（设备描述地址等 SSDP 绑上网卡后确定）");
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
        kindFromMetadata = kindOf(metadata);
        // 还没 prepare，先按元数据猜一个；等 onPrepared 拿到真实视频尺寸再定论。
        // 这样从"收到投屏"到"画面出来"这段时间界面形态就是对的，不会先黑一下再跳。
        audioOnly = (kindFromMetadata == KIND_AUDIO);
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
        // 一收到地址就报一次：控制点那边「已收到」的反馈全靠它
        notifyEvent("AVTransport");
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
        return KIND_UNKNOWN;
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

    /** 「当前是否处于出错态」—— 事件与 GetTransportInfo 共用的唯一判据 */
    private boolean hasTransportError() {
        return lastError != null && lastError.length() > 0;
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

    /** 当前是不是纯音频流。界面据此决定显示音乐卡片还是视频画面。 */
    public boolean isAudioOnly() {
        return audioOnly;
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
            vars.put("Mute", "0");
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
        if ("PLAYING".equals(state)) {
            transportState = "PLAYING";
        } else if ("PAUSED".equals(state)) {
            transportState = "PAUSED_PLAYBACK";
        } else if ("STOPPED".equals(state)) {
            transportState = "STOPPED";
        } else if ("PREPARING".equals(state)) {
            transportState = "TRANSITIONING";
        } else if ("RECONNECTING".equals(state)) {
            // 重连中必须报 TRANSITIONING，**不能漏**。
            //
            // 漏掉的话 transportState 会停留在上一次的值（通常就是 PLAYING），
            // 而这段时间播放器已经被释放、位置读不到 —— 控制点看到的是
            // 「状态说正在播放，进度却一直不动」，它的进度条就卡住了。
            // 这是"手机上进度条不跟着走"的来源之一。
            transportState = "TRANSITIONING";
        } else if ("ERROR".equals(state)) {
            // 出错同样不能停留在 PLAYING，否则控制点会一直以为还在播。
            // 注意 TransportState 的合法取值里没有 ERROR ——
            // "出没出错"是 TransportStatus 的事（见 eventedVars）。
            transportState = "STOPPED";
        }
        // 这就是 GENA 存在的理由：播放/暂停一变就告诉控制点，
        // 不然手机上的按钮状态要等用户手动刷新才更新。
        notifyEvent("AVTransport");
    }

    @Override
    public void onError(String message) {
        lastError = message;
        Log.w(TAG, "播放错误: " + message);
    }

    @Override
    public void onPrepared(int durationMs, boolean hasVideo) {
        // 播放已经真的就绪了 —— 把之前那条错误清掉。
        //
        // 不清的话会出一个很别扭的现象：一次**已经自愈**的断流（onError → 重连 →
        // prepare 成功），那句"播放卡死，正在重连"会一直挂在 lastError 里，
        // 而顶部状态条正是靠它判断"要不要出现"—— 于是画面好好的，
        // 屏幕上却一直压着一条报错。
        //
        // 这和「谎报军情比失败更糟」是同一条纪律：状态字段必须反映**现在**，
        // 而不是"曾经出过事"。真要报故障，下一次 onError 会重新写上。
        lastError = "";
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

    public String getLocalIp() {
        // 优先用 SSDP **实际绑上的那张网卡**的地址。
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
