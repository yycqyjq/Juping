package com.juping.cast.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import com.juping.cast.dlna.DlnaDescription;
import com.juping.cast.dlna.UpnpHttpServer;
import com.juping.cast.player.MediaTypes;

import java.io.File;

/**
 * 网页投屏这一整套能力的**宿主**：扫码上传页、外接存储装应用，以及它们各自
 * 需要的那个「落点回调」。
 *
 * <h3>为什么从 {@code DlnaRendererService} 里摘出来</h3>
 * 摘之前，服务里散着 5 个字段（{@code LocalStore} / {@code WebCastEndpoints} /
 * {@code ApkScanner} / {@code ApkEndpoints} / {@code WebRouter}）、5 行装配、
 * 两个回调接口的实现（{@code CastTarget} / {@code InstallTarget}）、
 * 一段 {@code /status} 拼装，外加拼 DIDL 的那一套 —— 可这些事与「当一台 DLNA
 * 渲染端」**没有关系**：它们是「这台盒子上还有一个网页」带来的。
 *
 * <p>摘出来之后，{@code DlnaRendererService} 只剩一个字段和两个薄回调
 * （见 {@link Playback}），不再认识 {@code ApkScanner}、不再自己拼
 * {@code ACTION_VIEW} 的 Intent、也不再知道 {@code /status} 里
 * {@code storage} 那段长什么样。
 *
 * <h3>两个回调接口都由本类实现，而不是由服务实现</h3>
 * {@link WebCastEndpoints} 与 {@link ApkEndpoints} 的设计纪律是「端点不自己碰
 * 系统能力，反向拿一个回调接口」—— 这个纪律没变，只是**回调的实现者**从服务
 * 换成了本类。本类拿得到 {@code Context}（够 {@code startActivity} 与
 * {@code ContentResolver}），而"把东西播出去"这件事仍然只经 {@link Playback}
 * 回到服务 —— 播放状态机只此一处，多一个入口就多一套竞态。
 *
 * <h3>生命周期跟服务走</h3>
 * {@code LocalStore} 只在构造时用一下 {@code getFilesDir()}，端点是无状态的，
 * 两者都不持有 {@code Context} 的长期引用（这里存的 {@code ctx} 是 Service 自己，
 * 与它同生命周期）。所以**网络变化重建 HTTP 服务时不需要重建本类** ——
 * 重建了反而要把上传目录重新探一遍，白做。
 */
public final class WebCastHost
        implements WebCastEndpoints.CastTarget, ApkEndpoints.InstallTarget {

    private static final String TAG = "WebCastHost";

    /**
     * 本类需要上层提供的三件东西 —— 只有「服务」知道答案。
     *
     * <p>刻意做成接口而不是直接传 {@code DlnaRendererService}：传服务的话，
     * 本类就能顺手调到服务的任何一个方法，摘出来的意义立刻打折。
     * 三个方法就是全部耦合面。
     */
    public interface Playback {
        /**
         * 本机地址。用**实际**那个（{@code NetUtil.pickLocalIp()} 的结果），
         * 不是自己再猜一个 —— 地址与 LOCATION 必须同源。
         */
        String localIp();

        /**
         * HTTP 服务**实际监听**的端口。
         *
         * <p>不能用首选端口常量：端口有 fallback（49152 被厂家自带的 DLNA 栈
         * 占了就往上移），写死首选端口的话，「投到电视」会把播放器指向一个
         * 没人监听的端口 —— 点下去什么都没发生。
         */
        int httpPort();

        /**
         * 把一个地址当投屏源播出去。
         *
         * <p>实现方**必须**复用既有那条 DLNA 渲染路径（{@code onSetUri} + 播放），
         * 不要绕开它直接调播放器：绕开的话控制点回读到的 {@code CurrentURI}
         * 还是上一部片子，电视机和手机显示的是两个不同的东西。
         */
        void cast(String url, String didl);
    }

    private final Context ctx;
    private final Playback playback;

    private final LocalStore store;
    private final WebCastEndpoints endpoints;
    private final ApkScanner apkScanner;
    private final ApkEndpoints apkEndpoints;
    /** 复合路由：上传端点 + 安装包端点，串成 UpnpHttpServer 要的那一个 WebEndpoints。 */
    private final UpnpHttpServer.WebEndpoints router;

    public WebCastHost(Context ctx, Playback playback) {
        this.ctx = ctx;
        this.playback = playback;
        // 落盘根是 getFilesDir()/uploads（为什么不是外部存储，见 LocalStore 的类注释：
        // 这台盒子上"外部存储"就是插着的那支 U 盘）。
        this.store = new LocalStore(ctx);
        this.endpoints = new WebCastEndpoints(store, this);
        // 外接存储 APK 扫描 / 安装（批 3.5）。与上传端点共用同一个 HTTP 服务，
        // 由 WebRouter 串起来 —— HTTP 层只认一个 WebEndpoints。
        this.apkScanner = new ApkScanner(ctx);
        this.apkEndpoints = new ApkEndpoints(apkScanner, this);
        this.router = new WebRouter(endpoints, apkEndpoints);
    }

    /** 给 {@code UpnpHttpServer} 的那一个 WebEndpoints。 */
    public UpnpHttpServer.WebEndpoints endpoints() {
        return router;
    }

    /**
     * {@code /status} 里属于网页这一块的那一段（含尾随逗号）。
     *
     * <p>拼装留在本类，是因为「哪些字段属于网页子系统」这件事只有本类知道 ——
     * 服务那边若还要逐项取，等于把 5 个字段换个地方又摆一遍。
     */
    public String statusJson() {
        return "\"storage\":" + endpoints.storageJson() + ","
                + "\"installAllowed\":" + apkEndpoints.isInstallAllowed() + ",";
    }

    // ------------------------------------------- WebCastEndpoints.CastTarget

    /**
     * 把盒子上已上传的本地文件当投屏源播出去（网页上点「投到电视」的落点）。
     *
     * <p><b>为什么地址是 {@code http://<本机IP>:<端口>/media/<名字>}，而不是 {@code file://}</b>：
     * 上传的文件在应用私有目录里（{@code files/uploads} 真机实测是 {@code drwx------}），
     * 而真正去 open 它的是**另一个进程** mediaserver —— 它穿不过这个目录，
     * 实测只会拿到 {@code error (1, -2147483648)}，电视上什么都不放。
     * 改由我们自己进程以 HTTP 提供，权限问题就不存在了。
     *
     * <p>顺带两个好处：① Content-Type 探测要用 http 地址，而这台 MTK 盒子
     * {@code getVideoWidth()} 恒返回 0 —— 少了探测，上传的视频会被判成纯音频，
     * 画面在放、界面却弹音乐卡片；② 地址成了一个真正的 URL，控制点回读/续播/拖拽
     * 都按既有那条（http 媒体的）路径走，不必为本地文件再开一套语义。
     */
    @Override
    public void castLocalFile(File file) {
        if (file == null) {
            return;
        }
        String url = "http://" + playback.localIp() + ":" + playback.httpPort()
                + "/media/" + Uri.encode(file.getName());
        playback.cast(url, didlFor(file));
    }

    // ------------------------------------------- ApkEndpoints.InstallTarget

    /**
     * 「未知来源」开关是否已开（批 3.5）。
     *
     * <p>Android 4.0 上是**全局开关**：读 {@code INSTALL_NON_MARKET_APPS}
     * （API 3 起可用；API 17 起 deprecated 但仍可读），1 = 已开。关着时系统安装器会拒绝，
     * 所以先查它、把「去打开」这条指引提前给出来，而不是让用户点了没反应。
     *
     * <p>读不出来（个别 ROM 没这个键）时返回 true —— 让系统安装器自己判断，
     * 好过我们误判成「未开」把用户挡在门外。
     */
    @Override
    public boolean isInstallAllowed() {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1;
        } catch (Throwable t) {
            Log.w(TAG, "读「未知来源」开关失败，按已开处理", t);
            return true;
        }
    }

    /**
     * 唤起系统安装器安装外接卷上的一个 APK（批 3.5）。
     *
     * <p>用 {@code ACTION_VIEW} + {@code application/vnd.android.package-archive}
     * 而不是 {@code ACTION_INSTALL_PACKAGE}：前者是 Android 4.0 上最通用的安装唤起方式，
     * 系统安装器必然注册了它。地址用 {@code file://}（{@code Uri.fromFile}）——
     * 本项目 {@code targetSdk 19 < 24}，{@code FileUriExposedException} 不生效，
     * 无需 FileProvider。必须带 {@code FLAG_ACTIVITY_NEW_TASK}（从 Service 上下文启动）。
     *
     * <p><b>若装的是聚屏自己</b>：安装过程中系统会杀掉本进程 → 前台服务随之中断 →
     * 装完需重新打开聚屏。这是系统行为，无解（方案 §6.2），已在 README 写明。
     *
     * @return 是否成功发起；真正装上要等电视端遥控器确认
     */
    @Override
    public boolean installApk(File apk) {
        if (apk == null) {
            return false;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            Log.i(TAG, "已唤起安装器：" + apk.getName());
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "唤起安装器失败：" + apk.getName(), e);
            return false;
        }
    }

    /**
     * 给本地文件拼一段最小 DIDL-Lite。
     *
     * <p><b>为什么必须自己拼</b>：{@code DidlLite} 只有读取方法、没有构造器。
     * 而 {@code <upnp:class>} 这一项不能省 —— 服务侧的形态判定靠它分音频/视频/图片，
     * 少了它 mp3 会被判成「未知」，界面按视频形态渲染出**一块黑屏**，
     * 而声音其实正常（用户会以为投屏坏了）。
     *
     * <p>类型判定走 {@link MediaTypes}（纯逻辑、零 Android 依赖、桌面可断言），
     * 本方法只负责把它拼成 XML。转义走 {@link DlnaDescription#escapeXml} ——
     * 全项目**只有这一处** XML 转义规则，SOAP 响应、事件体、这里的 DIDL 三处
     * 必须逐字一致，各写一份迟早分叉。
     */
    private static String didlFor(File file) {
        String name = file.getName();
        String title = DlnaDescription.escapeXml(name);
        String upnpClass;
        // 图片必须**先判**：它若掉进下面那个"其他一律当视频"的兜底分支，
        // 服务侧就会把形态判成视频，界面于是走视频通道去等一个永远不来的视频帧
        // —— 又是黑屏。
        if (MediaTypes.isImageName(name)) {
            upnpClass = "object.item.imageItem.photo";
        } else if (MediaTypes.isAudioName(name)) {
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
}
