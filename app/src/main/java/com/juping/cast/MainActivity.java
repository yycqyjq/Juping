package com.juping.cast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.juping.cast.player.PlaybackPolicy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * 主界面。
 *
 * <p>四种形态，靠播放状态自动切换：
 * <ul>
 *   <li><b>等待投屏</b> —— 显示引导 + 设备信息卡片，用户照着做就行</li>
 *   <li><b>视频播放中</b> —— 隐藏面板、全屏出画面，顶部留一条半透明状态条</li>
 *   <li><b>音乐播放中</b> —— 纯音频流在 SurfaceView 上什么都没有，必须换成
 *       音符卡片。不然电视就是**一片黑**：声音明明在放，看着却像投屏坏了</li>
 *   <li><b>图片展示中</b> —— MediaPlayer 解不了静态图，所以图片走的是另一条
 *       通道：BitmapFactory 解码后画在 ImageView 上。见 {@link #loadImage}</li>
 * </ul>
 *
 * <p>界面刻意做得极简：0.6GB 内存的设备上，每一点 UI 开销都是奢侈的。
 * 所以没有列表、没有动画，图形资源只有脚本生成的那几张 PNG。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";

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
     * 界面四态。
     *
     * <p>用四态整数而不是「播放中 / 没播放」两个布尔，是因为音频、视频、图片
     * **互斥**：写成几个布尔就允许出现"既是音频又是视频"这种非法组合，
     * 而它一旦出现，界面会同时显示画面和音符卡片 —— 排查起来非常费劲。
     */
    private static final int MODE_IDLE = 0;
    private static final int MODE_AUDIO = 1;
    private static final int MODE_VIDEO = 2;
    private static final int MODE_IMAGE = 3;

    private SurfaceView surfaceView;
    /**
     * 图片层。投静态图时用它显示画面 —— 与 SurfaceView 互斥。
     *
     * <p>为什么单开一层：SurfaceView 上的内容是 MediaPlayer 解码出来的，
     * 而它解不了静态图，喂进去只会立刻报错、屏幕全黑。所以图片走
     * {@code BitmapFactory} + ImageView 这条完全独立的通道。
     */
    private ImageView imageView;
    private LinearLayout panel;
    private LinearLayout overlay;
    private LinearLayout rowSource;
    private LinearLayout music;
    /** 二维码整块（码 + 小字）。位图生成不出来时整块藏起来，不留一个空白方块。 */
    private LinearLayout qrBox;
    private ImageView qrImage;
    private View statusDot;
    /** 顶部状态条左边那个点。它必须能变颜色 —— 见 applyTopBar 里的说明。 */
    private View overlayDot;

    private TextView infoDevice;
    private TextView infoAddress;
    private TextView infoNetwork;
    private TextView infoState;
    private TextView infoVersion;
    private TextView infoSource;
    private TextView playingText;
    private TextView musicSource;
    private TextView musicProgress;
    private Button btnRestart;

    private DlnaRendererService service;
    private boolean bound;
    private int lastMode = MODE_IDLE;

    /**
     * 当前二维码对应的地址 —— 同时也是「要不要重画」的缓存键。
     *
     * <p>见 {@link #updateQr(String)}：地址没变就一个像素都不重算。
     */
    private String qrPayload;
    private Bitmap qrBitmap;

    /**
     * 当前显示的那张图对应的地址 —— 同时也是「要不要重新解码」的缓存键。
     *
     * <p>同 {@link #updateQr(String)} 的套路：{@link #refresh()} 每 0.5 秒跑一次，
     * 地址没变就一个字节都不重下、不重解。图片往往好几 MB，每个 tick 重来一遍
     * 会直接把 CPU 和内存吃满。
     */
    private String imageUri;
    private Bitmap imageBitmap;
    /**
     * 已经排了队、正在后台下载解码的地址。
     *
     * <p>没有它的话，一张大图解码要几百毫秒，而这期间会经过好几个 tick ——
     * 每 tick 都往后台再排一个下载任务，同一个文件被重复下一堆。
     */
    private String imageLoadingUri;
    /**
     * 已经下载/解码**失败**过的那个地址 —— 同一个地址不再每个 tick 重试。
     *
     * <p>{@link #refresh()} 每 0.5 秒问一次 {@link #loadImage}，而失败的地址既不会
     * 进 {@code imageUri} 也不会进 {@code imageLoadingUri}，于是下一个 tick 又原样
     * 重来一遍。真机实测（地址 404 时）：15 秒内发了 30 次请求、弹了 30 次 Toast ——
     * 在这台 0.6GB 的盒子上是纯粹的浪费。记下来，等地址换了再试。
     */
    private String imageFailedUri;

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
        imageView = (ImageView) findViewById(R.id.image);
        panel = (LinearLayout) findViewById(R.id.panel);
        overlay = (LinearLayout) findViewById(R.id.overlay);
        rowSource = (LinearLayout) findViewById(R.id.row_source);
        music = (LinearLayout) findViewById(R.id.music);
        qrBox = (LinearLayout) findViewById(R.id.qr_box);
        qrImage = (ImageView) findViewById(R.id.qr_image);
        statusDot = findViewById(R.id.status_dot);
        overlayDot = findViewById(R.id.overlay_dot);

        infoDevice = (TextView) findViewById(R.id.info_device);
        infoAddress = (TextView) findViewById(R.id.info_address);
        infoNetwork = (TextView) findViewById(R.id.info_network);
        infoState = (TextView) findViewById(R.id.info_state);
        infoVersion = (TextView) findViewById(R.id.info_version);
        infoSource = (TextView) findViewById(R.id.info_source);
        playingText = (TextView) findViewById(R.id.playing_text);
        musicSource = (TextView) findViewById(R.id.music_source);
        musicProgress = (TextView) findViewById(R.id.music_progress);
        btnRestart = (Button) findViewById(R.id.btn_restart);

        // 版本号只在进程启动时取一次：它是构建产物里的常量（BuildConfig 由
        // build.gradle 的 versionName/versionCode 生成），运行期不会变 ——
        // 放进 refresh() 的 tick 里每 0.5 秒重刷一遍是白烧 CPU。
        //
        // **刻意不写死字面量**：写死就一定会漂。真机排障第一句问的是
        // 「盒子上装的是哪一版」，答错等于白试一轮；而发版时唯一的动作
        // 就是改 build.gradle 那两个数，界面自动跟上。
        //
        // 只显示 versionName（如 0.1.11）：versionCode 是给系统比新旧用的整数，
        // 界面上没有意义 —— 原来写成 "0.1.9（10）"，用户看到括号里的数只会问"这是什么"。
        infoVersion.setText(getString(R.string.fmt_version, BuildConfig.VERSION_NAME));

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

        // 图片形态下，片源地址会在**同一个形态之内**换掉 —— 相册里连投几张就是
        // 这种：每一张都是 MODE_IMAGE，形态从头到尾没变过。而
        // applyModeIfChanged() 只在形态真的变了时才调 applyMode()，
        // 形态不变就一次都不会走到 loadImage()，于是电视永远停在第 1 张
        // （用户报的「切了好几张还是原来那张」）。
        // 所以这里每个 tick 都问一次；loadImage() 自己按地址去重
        // （imageUri / imageLoadingUri），地址没变时立刻返回，不会重复下载。
        if (mode == MODE_IMAGE) {
            loadImage(service.getCurrentUri());
        }

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
            // 二维码编码的就是面板上那行地址（ip:port），点开是上传页。
            //
            // 与设备描述里的 presentationURL 是**同一个地址、同一对来源**
            // （getLocalIp() + 实际监听端口）—— 只是那边在 UpnpHttpServer 里拼
            // （它手上有 getPort()）。两边若各算各的，就会出现「控制点点开的页面
            // 能开、二维码扫出来却连不上」这种最难查的分叉。
            updateQr("http://" + service.getLocalIp() + ":" + service.getHttpPort() + "/");
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
        //
        // 例外是「画面出不来」：那时进度条上的时间对用户毫无意义 ——
        // 屏幕一片黑，他要知道的是"为什么黑"。而这条子正是为这种时刻准备的
        // （见 applyTopBar 的说明），所以让它说原因，优先级高于进度。
        // 真报错时不抢：报错自报错，别拿"编码不支持"去替真正的故障背锅。
        boolean error = (service.getLastErrorKind() != PlaybackPolicy.ERR_NONE);
        if (!error && service.isVideoMissing()) {
            playingText.setText(R.string.hint_video_unsupported);
        } else {
            playingText.setText(dur > 0 ? progress : currentLabel());
        }
    }

    /**
     * 二维码：**只在地址真的变了**时才重画。
     *
     * <p>{@link #refresh()} 每 1.5 秒跑一次（播放中 0.5 秒），而这个位图在地址
     * 不变时永远是同一张。每个 tick 重画一次的话，0.6GB 的盒子上就是白烧
     * CPU：一次重画要跑一遍 QR 编码 + 填几万个像素。所以缓存键就是地址本身 ——
     * 地址没变，直接返回。
     *
     * <p>缓存键里带端口，这一点是**必须**的：端口有 fallback（49152 被厂家自带的
     * DLNA 栈占了就往上移），地址变了缓存自然失效，二维码跟着变 ——
     * 否则电视上会留着一张指着死端口的码，扫出来怎么都打不开。
     */
    private void updateQr(String url) {
        if (qrBox == null || qrImage == null || url.equals(qrPayload)) {
            return;
        }
        qrPayload = url;
        Bitmap bmp = QrRenderer.render(url, qrSizePx());
        // 先拿住旧的，换完新的再回收 —— 顺序反过来的话，ImageView 手上那张
        // 已经被回收，屏幕上会是一块空白（或直接崩）。
        Bitmap old = qrBitmap;
        qrBitmap = bmp;
        if (bmp == null) {
            qrBox.setVisibility(View.GONE);
        } else {
            qrImage.setImageBitmap(bmp);
            qrBox.setVisibility(View.VISIBLE);
        }
        // 这张位图是 ARGB_8888、近 200dp 见方，在电视的分辨率下不算小。
        // 缓存失效时旧的那张没人再引用，就地回收，别留给 GC 去猜。
        if (old != null && old != bmp && !old.isRecycled()) {
            old.recycle();
        }
    }

    /**
     * 二维码位图的边长（像素）。
     *
     * <p>按 View 的实际尺寸生成，而不是写死一个"够大"的数：布局里那 196dp
     * 在 inflate 时就被换算成像素存在 LayoutParams 里了，直接拿来用 ——
     * 既不会因为放大而糊，也不会为了一个 196dp 的方框去分配一张 512×512 的
     * 位图（这台设备的内存得省着用）。布局若哪天改成 wrap_content，
     * 拿到的是负值，这时退回一个够用的下限。
     */
    private int qrSizePx() {
        android.view.ViewGroup.LayoutParams lp = qrImage.getLayoutParams();
        int w = (lp == null) ? 0 : lp.width;
        return w > 0 ? w : 256;
    }

    /**
     * 把图片下载、解码、显示出来。
     *
     * <p><b>为什么整件事必须在后台线程</b>：地址是盒子上那个 HTTP 端口的
     * {@code /media/<名字>}，要真的联网去取。而且图片解码本身也要几百毫秒。
     * 放在主线程上，Android 会直接抛 {@code NetworkOnMainThreadException}；
     * 就算不抛，界面也会卡住 —— 而此刻界面正要显示这张图。
     *
     * <p><b>为什么地址没变就什么都不做</b>：{@link #refresh()} 每 0.5 秒跑一次，
     * 每次都会走到这里。同一张图重复下载 + 重复解码，在这台 0.6GB 的盒子上
     * 是纯粹的浪费，而且每 0.5 秒重新设置一次位图还会让画面闪。
     */
    private void loadImage(String uri) {
        if (uri == null || uri.length() == 0) {
            return;
        }
        if (uri.equals(imageUri) || uri.equals(imageLoadingUri)
                || uri.equals(imageFailedUri)) {
            return;
        }
        imageLoadingUri = uri;
        final String target = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeScaled(target);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!target.equals(imageLoadingUri)) {
                            // 下载期间用户又投了另一张：这次的结果作废，
                            // 但不能留着不回收 —— 位图是 native 内存，
                            // GC 看不见它，只能手动放。
                            if (bmp != null && !bmp.isRecycled()) {
                                bmp.recycle();
                            }
                            return;
                        }
                        imageLoadingUri = null;
                        showImage(target, bmp);
                    }
                });
            }
        }).start();
    }

    /**
     * 下载 + 降采样解码。
     *
     * <p><b>为什么必须降采样</b>：手机随手一张照片就是 4000×3000，直接
     * 解码成 ARGB_8888 要 4000×3000×4 ≈ 48MB —— 而整台设备的可用内存
     * 远没这么多，结果必然是 {@code OutOfMemoryError}，崩的是整个应用，
     * 用户看到的是"投屏把电视搞崩了"。所以先用 {@code inJustDecodeBounds}
     * 只读尺寸，算出 {@code inSampleSize} 再真正解码。
     */
    private Bitmap decodeScaled(String uri) {
        try {
            URLConnection conn = new URL(uri).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            InputStream in = conn.getInputStream();
            byte[] data = readAll(in);
            in.close();
            if (data == null) {
                return null;
            }
            // 先只读尺寸（不解像素）
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                Log.w(TAG, "图片解不出尺寸: " + uri);
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(probe.outWidth, probe.outHeight);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            Log.w(TAG, "图片处理失败: " + uri + "（" + e + "）");
            return null;
        } catch (OutOfMemoryError e) {
            // 位图是 native 内存，OOM 是个 Error 而不是 Exception，
            // 只 catch Exception 的话它会直接掀掉整个进程。
            Log.w(TAG, "图片太大，内存不足: " + uri);
            return null;
        }
    }

    /**
     * 算 {@code inSampleSize}：**必须是 2 的幂**。
     *
     * <p>Android 的文档明写它会向下取整到 2 的幂，非 2 的幂的取值等于白算。
     * 目标是把最长边压到面板分辨率量级以内：压过头画面发糊，
     * 压不够等于没省内存。
     */
    private static int sampleSizeFor(int width, int height) {
        int target = 1600;
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > target) {
            sample *= 2;
        }
        return sample;
    }

    /** 把输入流整个读进内存；失败返回 null（不抛，调用方只需要"成没成"） */
    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把解码好的位图换上屏幕。
     *
     * <p>失败（下载不到 / 解不开 / 内存不够）时**必须给出可见提示**：
     * 电视上没有日志可看，静默失败在用户眼里就是"投屏坏了"，
     * 而真相可能只是"这张图太大了"或"网络断了"。
     */
    private void showImage(String uri, Bitmap bmp) {
        if (bmp == null) {
            // 记下这个坏地址：refresh() 每 0.5 秒就会再问一次 loadImage，
            // 不记的话同一个坏地址会被无限重试（真机实测 15 秒 30 次请求 + 30 次
            // Toast）。等控制点换成别的地址，imageFailedUri 自然不匹配，重试恢复。
            imageFailedUri = uri;
            imageView.setImageBitmap(null);
            Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_LONG).show();
            return;
        }
        // 先拿住旧的，换完新的再回收 —— 顺序反过来的话，ImageView 手上那张
        // 已经被回收，屏幕上会是一块空白（或直接崩）。
        Bitmap old = imageBitmap;
        imageBitmap = bmp;
        imageUri = uri;
        imageView.setImageBitmap(bmp);
        if (old != null && old != bmp && !old.isRecycled()) {
            old.recycle();
        }
    }

    /** 放掉当前那张位图。位图是 native 内存，GC 看不见，只能手动 recycle。 */
    private void releaseImage() {
        Bitmap b = imageBitmap;
        imageBitmap = null;
        imageUri = null;
        imageLoadingUri = null;
        // 「失败过的地址」也要一起清：回到空闲再投同一张图时应当重试一次，
        // 不能因为上次失败（比如当时网还没通）就永远显示不出来。
        imageFailedUri = null;
        if (b != null) {
            imageView.setImageBitmap(null);
            if (!b.isRecycled()) {
                b.recycle();
            }
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

    /**
     * 当前该用哪种形态。
     *
     * <p>判据分三层：先看「有没有内容」（{@link #isPlaying()}），再看
     * 「是不是一张静态图」（{@code service.isImage()}），最后才是
     * 「内容有没有画面」（{@code service.isAudioOnly()}）。
     * 后两层的权威来源在服务里 —— 由元数据里的 {@code upnp:class} 与
     * MediaPlayer 报的真实视频尺寸两个信号合并得出，界面不自己猜。
     */
    private int currentMode() {
        if (!isPlaying()) {
            return MODE_IDLE;
        }
        // 图片要**先于**音频判。它不是音频，但它同样没有 MediaPlayer 画面 ——
        // 落到下面那个 isAudioOnly() 二选一里，只会被判成"视频"，
        // 然后界面去等一个永远不会来的视频帧，屏幕上就是一块黑屏。
        if (service.isImage()) {
            return MODE_IMAGE;
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
        boolean wasPlaying = (lastMode == MODE_AUDIO || lastMode == MODE_VIDEO
                || lastMode == MODE_IMAGE);
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

    /** 切换四种形态 */
    private void applyMode(int mode) {
        boolean idle = (mode == MODE_IDLE);
        boolean image = (mode == MODE_IMAGE);
        panel.setVisibility(idle ? View.VISIBLE : View.GONE);
        // 音乐层与画面层互斥。两者同时可见时，不透明的那层会盖住另一层，
        // 表面看"正常"，但底下还在渲染 —— 0.6GB 的盒子上不该浪费这份开销。
        music.setVisibility(mode == MODE_AUDIO ? View.VISIBLE : View.GONE);
        imageView.setVisibility(image ? View.VISIBLE : View.GONE);
        // 离开图片形态就把那张位图放掉：一张降采样后的图仍有好几 MB，
        // 接下来要放视频时它白占着内存 —— 而这台盒子总共才 0.6GB。
        if (!image) {
            releaseImage();
        }

        // SurfaceView 在空闲态必须**藏起来**。这不是省资源，是修一个真故障：
        //
        // 停止投屏时 MediaPlayer 被释放，视频层随之关闭 —— 而在这类老平台
        // （MTK 尤其明显）上，视频层没有内容时硬件输出的是一屏**蓝色**。
        // SurfaceView 一直 match_parent 可见的话，那层蓝就压在面板底下，
        // 用户看到的是「断开投屏后电视直接蓝屏」，而不是「回到投屏之前的界面」。
        //
        // 藏起来会触发 surfaceDestroyed → Surface 被销毁 → 视频层真正移除，
        // 露出下面的面板。
        //
        // 图片态同理：图片不经过视频层，那个 Surface 上什么都没有，
        // 留着它只会露出一屏蓝底，把刚画上去的照片盖住。
        surfaceView.setVisibility((idle || image) ? View.GONE : View.VISIBLE);
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
        if (image) {
            // 图像内容只有服务知道地址（currentUri 就是 /media/<名字>）。
            // 地址没变时 loadImage 会直接返回，不会重复下载。
            loadImage(service.getCurrentUri());
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
            // 「形态是视频、画面却始终没出来」也算"有话说"：那种情形下屏幕就是
            // **一片纯黑**，而顶条是用户唯一能看到的解释。不写它，他连"是盒子坏了
            // 还是这段视频放不了"都判断不了（真机实证见
            // PlaybackPolicy.INFO_VIDEO_CODEC_NOT_SUPPORT）。
            boolean noPicture = service.isVideoMissing();
            show = error || noPicture || "PAUSED_PLAYBACK".equals(ts) || "TRANSITIONING".equals(ts);
            if (error || noPicture) {
                // 出错、以及「画面出不来」都亮红点。
                // 这个点原来是**写死的绿色**，而顶部条现在恰恰只在
                // 「暂停 / 出错 / 缓冲」时出现 —— 出错时左边一个绿点、
                // 右边写着「出错：…」，自己跟自己打架。
                // 三米外先被看见的是颜色而不是那行小字，所以颜色必须说实话；
                // 「画面出不来」同样是"这条投屏没成功"，绿点会让人以为一切正常。
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

    /**
     * 遥控器返回键 = 结束投屏，回引导面板。
     *
     * <p><b>为什么是返回键</b>：盒子这边没有"关闭投屏"的入口 —— DLNA 只管推流，
     * 控制点（手机 App）退出时不会告诉盒子"我不玩了"。原先试过"播完 60 秒没有
     * 控制指令就自动收尾"，那是在替用户猜他的意图；现在改成用户自己按一下，
     * 盒子立刻回引导面板。
     *
     * <p>走的是控制点 Stop 的同一条路（{@code service.onStop()}）：清 currentUri、
     * 清元数据与下一曲、发一次 AVTransport 事件 —— 与手机端按停止的效果完全一致
     * （包括"回到二维码面板"）。
     *
     * <p><b>空闲态不拦</b>：那时返回键就是正常的"退出应用"，没理由改变它。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && service != null
                && currentMode() != MODE_IDLE) {
            service.onStop();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
