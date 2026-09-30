package com.juping.cast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.juping.cast.player.PlaybackPolicy;

/**
 * 主界面。
 *
 * <p>三种形态，靠播放状态自动切换：
 * <ul>
 *   <li><b>等待投屏</b> —— 显示引导 + 设备信息卡片，用户照着做就行</li>
 *   <li><b>视频播放中</b> —— 隐藏面板、全屏出画面，顶部留一条半透明状态条</li>
 *   <li><b>音乐播放中</b> —— 纯音频流在 SurfaceView 上什么都没有，必须换成
 *       音符卡片。不然电视就是**一片黑**：声音明明在放，看着却像投屏坏了</li>
 * </ul>
 *
 * <p>界面刻意做得极简：0.6GB 内存的设备上，每一点 UI 开销都是奢侈的。
 * 所以没有列表、没有动画，图形资源只有脚本生成的那几张 PNG。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    /**
     * 空闲时的刷新间隔。面板上的信息几乎不变，刷快了纯属浪费 ——
     * 这台设备只有 0.6GB 内存。
     */
    private static final long REFRESH_INTERVAL_IDLE_MS = 1500L;

    /**
     * 播放中的刷新间隔。
     *
     * <p>比空闲时快三倍，是**为了拖动进度条之后的观感**：屏幕上那个
     * {@code 01:23 / 03:45} 是用户唯一能拿来对照手机进度条的东西，
     * 1.5 秒才跳一次的话，拖完总要愣一下才跟上 —— 看起来就像"没同步"。
     * 0.5 秒是「跟得上」与「不浪费」之间的折中：一次 tick 不过是几次 setText。
     */
    private static final long REFRESH_INTERVAL_PLAYING_MS = 500L;

    /**
     * 界面三态。
     *
     * <p>用三态整数而不是「播放中 / 没播放」两个布尔，是因为音频与视频**互斥**：
     * 写成两个布尔就允许出现"既是音频又是视频"这种非法组合，而它一旦出现，
     * 界面会同时显示画面和音符卡片 —— 排查起来非常费劲。
     */
    private static final int MODE_IDLE = 0;
    private static final int MODE_AUDIO = 1;
    private static final int MODE_VIDEO = 2;

    private SurfaceView surfaceView;
    private LinearLayout panel;
    private LinearLayout overlay;
    private LinearLayout rowSource;
    private LinearLayout music;
    private View statusDot;
    /** 顶部状态条左边那个点。它必须能变颜色 —— 见 applyTopBar 里的说明。 */
    private View overlayDot;

    private TextView infoDevice;
    private TextView infoAddress;
    private TextView infoNetwork;
    private TextView infoState;
    private TextView infoSource;
    private TextView playingText;
    private TextView musicSource;
    private TextView musicProgress;
    private Button btnRestart;

    private DlnaRendererService service;
    private boolean bound;
    private int lastMode = MODE_IDLE;

    private final Handler handler = new Handler();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((DlnaRendererService.LocalBinder) binder).getService();
            bound = true;
            bindSurfaceIfReady();
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, lastMode == MODE_IDLE
                    ? REFRESH_INTERVAL_IDLE_MS
                    : REFRESH_INTERVAL_PLAYING_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = (SurfaceView) findViewById(R.id.surface);
        panel = (LinearLayout) findViewById(R.id.panel);
        overlay = (LinearLayout) findViewById(R.id.overlay);
        rowSource = (LinearLayout) findViewById(R.id.row_source);
        music = (LinearLayout) findViewById(R.id.music);
        statusDot = findViewById(R.id.status_dot);
        overlayDot = findViewById(R.id.overlay_dot);

        infoDevice = (TextView) findViewById(R.id.info_device);
        infoAddress = (TextView) findViewById(R.id.info_address);
        infoNetwork = (TextView) findViewById(R.id.info_network);
        infoState = (TextView) findViewById(R.id.info_state);
        infoSource = (TextView) findViewById(R.id.info_source);
        playingText = (TextView) findViewById(R.id.playing_text);
        musicSource = (TextView) findViewById(R.id.music_source);
        musicProgress = (TextView) findViewById(R.id.music_progress);
        btnRestart = (Button) findViewById(R.id.btn_restart);

        surfaceView.getHolder().addCallback(this);

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartService();
            }
        });

        Intent intent = new Intent(this, DlnaRendererService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        // 让遥控器一进来就有落点，否则 D-pad 方向键没有反应
        btnRestart.requestFocus();
    }

    private void restartService() {
        Intent intent = new Intent(this, DlnaRendererService.class);
        stopService(intent);
        startService(intent);
        Toast.makeText(this, R.string.toast_restarted, Toast.LENGTH_SHORT).show();
    }

    /** Surface 就绪时才绑定，避免拿到未初始化的 Surface 导致「黑屏但有声音」 */
    private void bindSurfaceIfReady() {
        if (service == null || service.getPlayer() == null) {
            return;
        }
        SurfaceHolder holder = surfaceView.getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            service.getPlayer().setSurface(holder.getSurface());
        }
    }

    private void refresh() {
        if (service == null) {
            infoDevice.setText("—");
            infoAddress.setText("—");
            infoNetwork.setText("—");
            infoState.setText(R.string.state_starting);
            applyStatusDot(false);
            applyModeIfChanged(MODE_IDLE);
            applyTopBar(MODE_IDLE);
            return;
        }

        int mode = currentMode();
        applyModeIfChanged(mode);
        // 顶部条与「形态」无关：同一个形态里，播放 ↔ 暂停 ↔ 出错随时会变。
        // 所以它必须每个 tick 重算一次，不能只在形态切换时算。
        applyTopBar(mode);

        // 面板（含它那个状态圆点）只有 idle 时才看得见。
        // 播放期间每 0.5 秒重刷一遍这些文字和背景是白费力气 ——
        // 它们在此期间根本不会变，而面板此时本来就是隐藏的。
        // 0.6GB 的设备上，这点开销正好抵掉"播放期提速三倍"那部分。
        if (mode == MODE_IDLE) {
            // idle 就意味着没在播放，所以这里恒传 false
            applyStatusDot(false);
            infoDevice.setText(service.getFriendlyName());
            infoAddress.setText(getString(R.string.fmt_address,
                    service.getLocalIp(), service.getHttpPort()));
            infoNetwork.setText(service.getBoundInterfaceName());
            infoState.setText(describeState());
            return;
        }

        if (service.getPlayer() == null) {
            return;
        }
        int pos = service.getPlayer().getPosition() / 1000;
        int dur = service.getPlayer().getDuration() / 1000;
        // 位置可能短暂越过总时长 —— HTTP 流的分段时长估算是会浮动的，
        // seek 也可能正好落在边界上。不钳的话会显示成 "10:30 / 03:45"
        // 这种越界数字，在电视上看着就像进度"满了"。
        if (dur > 0 && pos > dur) {
            pos = dur;
        }
        // 时长恒为 0 的是 HLS 直播 —— 拿不到总时长就报"正在播放"，
        // 别显示一个 00:00 的总时长把人看懵。
        String progress = dur > 0
                ? getString(R.string.fmt_progress, formatClock(pos), formatClock(dur))
                : getString(R.string.music_live);
        if (mode == MODE_AUDIO) {
            musicSource.setText(currentLabel());
            musicProgress.setText(progress);
        }
        // 顶部条现在只在暂停 / 出错 / 缓冲时出现，那些时刻用户要的正是
        // 「放到哪儿了」，所以两种形态都报进度。原来音频时这里固定写
        // 「音乐投屏」是因为状态条常驻、和音乐卡片的大标题重复了 ——
        // 条子不再常驻，那个理由也就不成立了。
        playingText.setText(dur > 0 ? progress : currentLabel());
    }

    private boolean isPlaying() {
        if (service == null || service.getPlayer() == null) {
            return false;
        }
        if (service.getPlayer().getDuration() > 0) {
            return true;
        }
        String uri = service.getCurrentUri();
        return uri != null && uri.length() > 0;
    }

    /**
     * 当前该用哪种形态。
     *
     * <p>判据分两层：先看「有没有内容」（{@link #isPlaying()}），
     * 再看「内容有没有画面」（{@code service.isAudioOnly()}）。
     * 第二层的权威来源在服务里 —— 由元数据里的 {@code upnp:class} 与
     * MediaPlayer 报的真实视频尺寸两个信号合并得出，界面不自己猜。
     */
    private int currentMode() {
        if (!isPlaying()) {
            return MODE_IDLE;
        }
        return service.isAudioOnly() ? MODE_AUDIO : MODE_VIDEO;
    }

    /** 形态真的变了才动 View —— 每 1.5 秒重设一次 visibility 会触发无谓的重新布局 */
    private void applyModeIfChanged(int mode) {
        if (mode == lastMode) {
            return;
        }
        // 「播放 → 空闲」且这轮界面是投屏自动唤起的：**延迟**退回后台，
        // 电视回到投屏之前的样子（launcher / 上一个应用）。
        //
        // 为什么必须延迟 —— MTK 平台的蓝屏怪癖：视频层刚被销毁时，
        // 显示管线需要一拍才能切回 UI 图层，**同一帧内**把任务切到后台的话，
        // 蓝色视频帧会粘在 launcher 上（真机 Hisense Vision-TV 实测）。
        // 先停 2.5 秒让 SurfaceView 销毁、视频层干净移除（此时显示的是
        // 待机面板），再退后台就看不到蓝屏了。
        //
        // 期间重新投屏（mode 离开 IDLE）会自动取消退避；
        // 手动打开的界面（标志位 false）不动 —— 不能把人踢出他自己开的页面。
        boolean wasPlaying = (lastMode == MODE_AUDIO || lastMode == MODE_VIDEO);
        if (mode == MODE_IDLE && wasPlaying && service != null) {
            if (service.hasAutoFrontFlag()) {
                handler.removeCallbacks(autoBackTask);
                handler.postDelayed(autoBackTask, AUTO_BACK_DELAY_MS);
            }
        } else if (mode != MODE_IDLE) {
            handler.removeCallbacks(autoBackTask);
        }
        lastMode = mode;
        applyMode(mode);
    }

    /**
     * 延迟退回后台的阈值。2.5 秒 = SurfaceView 销毁 + 视频层从硬件
     * 显示管线移除的余量；期间重新投屏会自动取消。
     */
    private static final long AUTO_BACK_DELAY_MS = 2500L;

    private final Runnable autoBackTask = new Runnable() {
        @Override
        public void run() {
            // 双重确认：到点时仍是空闲态、界面仍是自动唤起的，才退回后台。
            // 期间用户重新投屏（mode 离开 IDLE）或手动打开界面（标志被消费）
            // 都会取消或放弃。
            if (service == null || currentMode() != MODE_IDLE) {
                return;
            }
            if (service.takeAutoFrontFlag()) {
                moveTaskToBack(true);
            }
        }
    };

    /** 切换三种形态 */
    private void applyMode(int mode) {
        boolean idle = (mode == MODE_IDLE);
        panel.setVisibility(idle ? View.VISIBLE : View.GONE);
        // 音乐层与画面层互斥。两者同时可见时，不透明的那层会盖住另一层，
        // 表面看"正常"，但底下还在渲染 —— 0.6GB 的盒子上不该浪费这份开销。
        music.setVisibility(mode == MODE_AUDIO ? View.VISIBLE : View.GONE);

        // SurfaceView 在空闲态必须**藏起来**。这不是省资源，是修一个真故障：
        //
        // 停止投屏时 MediaPlayer 被释放，视频层随之关闭 —— 而在这类老平台
        // （MTK 尤其明显）上，视频层没有内容时硬件输出的是一屏**蓝色**。
        // SurfaceView 一直 match_parent 可见的话，那层蓝就压在面板底下，
        // 用户看到的是「断开投屏后电视直接蓝屏」，而不是「回到投屏之前的界面」。
        //
        // 藏起来会触发 surfaceDestroyed → Surface 被销毁 → 视频层真正移除，
        // 露出下面的面板。
        surfaceView.setVisibility(idle ? View.GONE : View.VISIBLE);
        applyTopBar(mode);

        String label = currentLabel();
        boolean hasSource = label.length() > 0;
        // 两个分支都要设可见性。只设 VISIBLE、不设 GONE 的话，
        // 停止播放后「片源」那一行会一直留在面板上，显示上一部片子的地址。
        rowSource.setVisibility(hasSource ? View.VISIBLE : View.GONE);
        if (hasSource) {
            infoSource.setText(label);
        }
        if (!idle) {
            bindSurfaceIfReady();
        }
    }

    /**
     * 顶部状态条的可见性 —— **只在「有话说」的时候出现**。
     *
     * <p>它原来是一进入播放就常显的。于是看视频时画面上永远压着一条半透明黑带，
     * 而用户要的是「全屏就是画面，顶上什么都别挡」。
     *
     * <p>但它也不能直接删掉：暂停和出错的时候，屏幕上恰好没有任何反馈，
     * 那一刻它是唯一的线索 —— 黑屏卡住时要是没有它，就只能去连电脑抓 logcat。
     * 所以规则是：
     * <ul>
     *   <li><b>暂停</b> → 报位置，让用户知道停在哪</li>
     *   <li><b>出错</b> → 报错因，这是电视端唯一的排障出口</li>
     *   <li><b>缓冲 / 重连中</b>（TRANSITIONING）→ 说明画面为什么还没出来</li>
     * </ul>
     * 正常播放中一律隐藏。
     *
     * <p>注意它**不能只写在 {@link #applyMode(int)} 里**：暂停与出错是同一个形态
     * 内部的变化，形态没切换，那些代码根本不会被走到。
     */
    private void applyTopBar(int mode) {
        boolean show = false;
        int dot = R.drawable.dot_online;
        if (mode != MODE_IDLE && service != null) {
            String ts = service.getTransportState();
            // 用分类判断，不用「细节字符串非空」—— 后者是拿"有没有那句话"
            // 当"有没有出错"，一旦哪天细节被清空而分类还在，这里就会漏报。
            boolean error = (service.getLastErrorKind() != PlaybackPolicy.ERR_NONE);
            show = error || "PAUSED_PLAYBACK".equals(ts) || "TRANSITIONING".equals(ts);
            if (error) {
                // 出错就亮红点。
                // 这个点原来是**写死的绿色**，而顶部条现在恰恰只在
                // 「暂停 / 出错 / 缓冲」时出现 —— 出错时左边一个绿点、
                // 右边写着「出错：…」，自己跟自己打架。
                // 三米外先被看见的是颜色而不是那行小字，所以颜色必须说实话。
                dot = R.drawable.dot_error;
            } else if ("TRANSITIONING".equals(ts)) {
                // 缓冲 / 重连中：蓝点表示"还在动"，而不是"已经好了"
                dot = R.drawable.dot_playing;
            }
        }
        overlayDot.setBackgroundResource(dot);
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 状态圆点：绿＝就绪等待，蓝＝播放中，红＝未就绪或出错。
     *
     * <p>这个点原本是**固定的绿色**，等于一直在说「一切正常」——
     * 而组播没绑上（手机根本搜不到设备）时它照样是绿的，
     * 恰好把最需要被看见的那个故障盖住了。电视上圆点比小字好认得多
     * （三米外一眼可辨），所以必须让它说真话。
     */
    private void applyStatusDot(boolean playing) {
        int dot;
        if (service == null) {
            dot = R.drawable.dot_error;
        } else if (service.getLastErrorKind() != PlaybackPolicy.ERR_NONE) {
            dot = R.drawable.dot_error;
        } else if (!service.isDiscoveryReady()) {
            // 组播没就绪 —— 手机搜不到设备，这是最该被一眼看见的状态
            dot = R.drawable.dot_error;
        } else if (!service.isHttpReady()) {
            // 组播活着、HTTP 死了。这个状态比"搜不到设备"**更隐蔽**：
            // 手机能搜到这台设备、能显示它的名字，一点投屏就失败 ——
            // 用户会以为是手机或片源的问题，而电视这边看起来一切正常。
            dot = R.drawable.dot_error;
        } else if (playing) {
            dot = R.drawable.dot_playing;
        } else {
            dot = R.drawable.dot_online;
        }
        statusDot.setBackgroundResource(dot);
    }

    private String describeState() {
        if (service == null || service.getPlayer() == null) {
            return getString(R.string.state_starting);
        }
        int errKind = service.getLastErrorKind();
        if (errKind != PlaybackPolicy.ERR_NONE) {
            // 显示的是「连不上媒体服务器」这类用户能懂的话，而不是
            // "what=1 extra=-1010"。技术细节进日志，不上面。
            return getString(R.string.fmt_state_error, getString(errorTextRes(errKind)));
        }
        // 用 UPnP 传输状态机判断，而不是靠「有没有时长」去猜：
        //   1) 暂停时 getDuration() 照样 > 0，原来那套判断会把「已暂停」显示成「正在播放」
        //   2) HLS 直播的 getDuration() 恒为 0，会被误判成「等待投屏」
        String ts = service.getTransportState();
        if ("PAUSED_PLAYBACK".equals(ts)) {
            return getString(R.string.state_paused);
        }
        if ("PLAYING".equals(ts) || "TRANSITIONING".equals(ts)) {
            return getString(R.string.state_playing);
        }
        // 兜底：地址已下发但播放器还没进入 PLAYING（缓冲/重连中），也算「正在播放」
        String uri = service.getCurrentUri();
        if (uri != null && uri.length() > 0) {
            return getString(R.string.state_playing);
        }
        // HTTP 服务没起来时，手机**搜得到设备却投不上去**。
        // 这时显示"等待投屏"是在误导用户 —— 他会一直等，而设备根本接不了活。
        if (!service.isHttpReady()) {
            return getString(R.string.state_service_down);
        }
        return getString(R.string.state_waiting);
    }

    /**
     * 错误分类 → 文案资源。
     *
     * <p>文案放在 {@code strings.xml} 而不是 Java 里，是为了能本地化 ——
     * 而分类本身在 {@code PlaybackPolicy}（纯逻辑层，可以脱离 Android 跑断言）。
     * 两者分开之后：改一句措辞不会动到判据，改判据也不会动到文案。
     *
     * <p>每条话术都回答「**用户接下来该做什么**」，而不只是"哪里错了"：
     * 网络问题去看路由器、片源问题换一个、格式问题换片源或换设备。
     */
    private int errorTextRes(int kind) {
        switch (kind) {
            case PlaybackPolicy.ERR_CONNECT:
                return R.string.err_connect;
            case PlaybackPolicy.ERR_SERVER:
                return R.string.err_server;
            case PlaybackPolicy.ERR_DECODE:
                return R.string.err_decode;
            case PlaybackPolicy.ERR_STALLED:
                return R.string.err_stalled;
            case PlaybackPolicy.ERR_GIVEUP:
                return R.string.err_giveup;
            default:
                return R.string.err_unknown;
        }
    }

    /**
     * 秒 → 时钟文本。
     *
     * <p>超过一小时要显示成 {@code h:mm:ss}。原来写死 {@code "%02d:%02d"}，
     * 一部两小时的电影会显示成 {@code "120:00"} —— 不难懂，但不像个时长，
     * 而长片恰恰是老盒子最常放的场景。
     */
    private static String formatClock(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    /** 长 URL 在电视上没法看，截成「开头 … 结尾」的形式 */
    private static String shortName(String uri) {
        if (uri == null || uri.length() <= 60) {
            return uri == null ? "" : uri;
        }
        return uri.substring(0, 30) + " … " + uri.substring(uri.length() - 26);
    }

    /**
     * 片源的显示名 —— 界面所有"正在播什么"的地方都走这一个出口。
     *
     * <p><b>优先用元数据里的标题</b>：控制点推流时会带 {@code dc:title}，
     * 那才是用户认得的东西（「夜曲」）。原来直接显示从 URL 截出来的文件名
     * （{@code 6a3f9c2b.mp3}），用户根本不知道那是什么 —— 而这个界面
     * 存在的意义就是让用户确认"电视上放的是不是我要投的那个"。
     *
     * <p><b>取不到标题就回退到文件名</b>，不是显示空白：文件名虽然难认，
     * 但至少能区分"投的是这一个"和"投的是另一个"，比一片空白有用。
     * 而"控制点没给标题"本来就是常见情况（不少 App 的元数据是空壳）。
     *
     * <p>抽成一个方法而不是三处各写一遍：同一个语义写三份，
     * 迟早有一处忘了跟着改，然后界面上同一个片源在不同位置显示成不同的东西。
     */
    private String currentLabel() {
        if (service == null) {
            return "";
        }
        String title = service.getCurrentTitle();
        if (title != null && title.length() > 0) {
            return title;
        }
        return shortName(service.getCurrentUri());
    }

    // ---------------------------------------------- SurfaceHolder.Callback

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        bindSurfaceIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 分辨率切换时 Surface 会重建，必须重新绑定，
        // 否则出现「有声音没画面」—— 老设备上的典型现象
        bindSurfaceIfReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (service != null && service.getPlayer() != null) {
            service.getPlayer().setSurface(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
