package com.juping.cast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 接收端界面。
 *
 * <p>老盒子上的界面只有两个作用：
 * <ol>
 *   <li>提供一块 {@link SurfaceView} 给解码器输出画面</li>
 *   <li>显示设备名、IP、绑定的网卡、当前状态 —— 这些是排障时唯一能看到的东西</li>
 * </ol>
 * 所以刻意不做花哨 UI：布局用代码拼，零资源文件，减少出错面。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceView surfaceView;
    private TextView statusView;

    private DlnaRendererService service;
    private boolean bound;

    private final Handler handler = new Handler();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((DlnaRendererService.LocalBinder) binder).getService();
            bound = true;
            // Surface 可能还没创建完成。只有 isValid 时才绑定，
            // 否则交给 surfaceCreated / surfaceChanged 回调去做。
            SurfaceHolder holder = surfaceView.getHolder();
            if (service.getPlayer() != null && holder.getSurface() != null
                    && holder.getSurface().isValid()) {
                service.getPlayer().setSurface(holder.getSurface());
            }
            refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setPadding(24, 16, 24, 16);
        statusView.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        root.addView(statusView, lp);

        setContentView(root);

        Intent intent = new Intent(this, DlnaRendererService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    /** 状态面板：排障时全靠它 */
    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        if (service == null) {
            sb.append("服务启动中…\n");
        } else {
            sb.append("设备名  : ").append(service.getFriendlyName()).append('\n');
            sb.append("地址    : ").append(service.getLocalIp()).append(":49152\n");
            sb.append("组播网卡: ").append(service.getBoundInterfaceName()).append('\n');
            sb.append("状态    : ").append(currentState()).append('\n');
            String uri = service.getCurrentUri();
            if (uri != null && uri.length() > 0) {
                sb.append("片源    : ").append(uri).append('\n');
            }
            String err = service.getLastError();
            if (err != null && err.length() > 0) {
                sb.append("最近错误: ").append(err).append('\n');
            }
        }
        sb.append("\n在手机的腾讯视频 / B站 里点「投屏」，选择上面的设备名。");
        statusView.setText(sb.toString());
    }

    private String currentState() {
        if (service == null || service.getPlayer() == null) {
            return "未就绪";
        }
        return service.getPlayer().getDuration() > 0
                ? "已连接（" + service.getPlayer().getPosition() / 1000 + "s / "
                    + service.getPlayer().getDuration() / 1000 + "s）"
                : "等待投屏";
    }

    // ---------------------------------------------- SurfaceHolder.Callback

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (service != null && service.getPlayer() != null) {
            service.getPlayer().setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Surface 尺寸变化（分辨率切换）时必须重新绑定，
        // 否则会出现「有声音没画面」—— 老设备上非常典型的一个坑。
        if (service != null && service.getPlayer() != null) {
            service.getPlayer().setSurface(holder.getSurface());
        }
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
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
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
