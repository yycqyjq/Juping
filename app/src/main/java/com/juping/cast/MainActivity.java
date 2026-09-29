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

/**
 * 主界面。
 *
 * <p>两种形态，靠播放状态自动切换：
 * <ul>
 *   <li><b>等待投屏</b> —— 显示引导 + 设备信息卡片，用户照着做就行</li>
 *   <li><b>播放中</b> —— 隐藏面板、全屏出画面，顶部留一条半透明状态条</li>
 * </ul>
 *
 * <p>界面刻意做得极简：0.6GB 内存的设备上，每一点 UI 开销都是奢侈的。
 * 所以没有列表、没有动画、没有图片资源，全部是纯色 shape + 文字。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final long REFRESH_INTERVAL_MS = 1500L;

    private SurfaceView surfaceView;
    private LinearLayout panel;
    private LinearLayout overlay;
    private LinearLayout rowSource;
    private View statusDot;

    private TextView infoDevice;
    private TextView infoAddress;
    private TextView infoNetwork;
    private TextView infoState;
    private TextView infoSource;
    private TextView playingText;
    private Button btnRestart;

    private DlnaRendererService service;
    private boolean bound;
    private boolean lastPlaying;

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
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
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
        statusDot = findViewById(R.id.status_dot);

        infoDevice = (TextView) findViewById(R.id.info_device);
        infoAddress = (TextView) findViewById(R.id.info_address);
        infoNetwork = (TextView) findViewById(R.id.info_network);
        infoState = (TextView) findViewById(R.id.info_state);
        infoSource = (TextView) findViewById(R.id.info_source);
        playingText = (TextView) findViewById(R.id.playing_text);
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
            return;
        }

        infoDevice.setText(service.getFriendlyName());
        infoAddress.setText(getString(R.string.fmt_address,
                service.getLocalIp(), service.getHttpPort()));
        infoNetwork.setText(service.getBoundInterfaceName());
        infoState.setText(describeState());

        boolean playing = isPlaying();
        applyStatusDot(playing);
        if (playing != lastPlaying) {
            lastPlaying = playing;
            applyMode(playing);
        }

        if (playing && service.getPlayer() != null) {
            int pos = service.getPlayer().getPosition() / 1000;
            int dur = service.getPlayer().getDuration() / 1000;
            playingText.setText(dur > 0
                    ? getString(R.string.fmt_progress, formatClock(pos), formatClock(dur))
                    : shortName(service.getCurrentUri()));
        }
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

    /** 切换「等待」与「播放」两种形态 */
    private void applyMode(boolean playing) {
        panel.setVisibility(playing ? View.GONE : View.VISIBLE);
        overlay.setVisibility(playing ? View.VISIBLE : View.GONE);

        String uri = service == null ? null : service.getCurrentUri();
        boolean hasSource = uri != null && uri.length() > 0;
        // 两个分支都要设可见性。只设 VISIBLE、不设 GONE 的话，
        // 停止播放后「片源」那一行会一直留在面板上，显示上一部片子的地址。
        rowSource.setVisibility(hasSource ? View.VISIBLE : View.GONE);
        if (hasSource) {
            infoSource.setText(uri);
        }
        if (playing) {
            bindSurfaceIfReady();
        }
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
        String err = service == null ? null : service.getLastError();
        if (service == null) {
            dot = R.drawable.dot_error;
        } else if (err != null && err.length() > 0) {
            dot = R.drawable.dot_error;
        } else if (!service.isDiscoveryReady()) {
            // 组播没就绪 —— 手机搜不到设备，这是最该被一眼看见的状态
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
        String err = service.getLastError();
        if (err != null && err.length() > 0) {
            return getString(R.string.fmt_state_error, err);
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
        return getString(R.string.state_waiting);
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
