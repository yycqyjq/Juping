package com.juping.cast.player;

import java.util.Locale;

/**
 * 「这个地址（或文件名）是什么媒体形态」—— 只看扩展名的纯判定。
 *
 * <h3>为什么独立成一个类</h3>
 * 这四条判定原来住在 {@code DlnaRendererService} 里（私有静态方法），可是它们
 * **一个 Android API 都不碰** —— 就是字符串处理。放在那个 2000 行的服务里，
 * 它们既没法在桌面上被断言（闸门只能拿正则看"某个字符串在不在源码里"），
 * 又有**两个互不相干的消费方**：
 * <ul>
 *   <li>投屏形态判定（{@code kindOfUrl}）：控制点给的元数据没说清形态时，
 *       退回按地址扩展名猜；</li>
 *   <li>网页上传后投本地文件（{@code didlFor}）：拼 DIDL 时要决定
 *       {@code <upnp:class>} 写 imageItem 还是 audioItem。</li>
 * </ul>
 * 抽出来之后，{@link #extensionOf(String)} 那套「切查询串 / 切锚点 / 限字形 / 限长度」
 * 的细节第一次能被穷举（PolicyTest §18），而不是靠一条「方法体里出现过
 * {@code indexOf('?')}」的源码守卫。
 *
 * <h3>判错方向的代价不对称（这是所有表都"只认确定的那几个"的原因）</h3>
 * <ul>
 *   <li>视频被判成音频 → 音乐卡片把画面整个盖住，用户**什么都看不到**；</li>
 *   <li>音频被判成视频 → 画面黑着但声音在放，至少还有声音。</li>
 * </ul>
 * 所以拿不准的一律不猜（{@code m3u8} 就是最典型的一个：HLS 既可能是视频、
 * 也可能是纯音频网络电台，留给 {@code onPrepared} 与 Content-Type 探测去定论）。
 *
 * <p><b>本类不持有任何状态、不依赖任何 Android 类</b> —— 这是它能被桌面闸门
 * 直接编译运行的前提（与 {@link PlaybackPolicy} 同一套做法）。
 */
public final class MediaTypes {

    private MediaTypes() {
    }

    /**
     * 小写扩展名（不含点）；没有点、或尾巴不像扩展名则返回空串。
     *
     * <p><b>既能吃文件名，也能吃 URL</b>：URL 上的查询串/锚点会污染扩展名
     * （{@code …/a.mp4?token=xx} 直接取最后一个点的后半段会得到 {@code mp4?token=xx}），
     * 所以先把它们切掉。
     *
     * <p>还要挡住「路径里有句点却没有扩展名」的情况：{@code http://h/1.2/video}
     * 取出来的是 {@code 2/video} —— 里面带 {@code /}，不是扩展名，返回空串。
     * 所以只认纯字母数字、不超过 5 位的尾巴。
     *
     * <p><b>但纯数字的尾巴会照原样返回</b>：{@code http://h/video.2019} 会得到
     * {@code "2019"}。这是**刻意**的 —— 不能要求尾巴以字母开头，因为 {@code 3gp}
     * 就是数字开头、而且真在视频表里。代价只是这类地址被归成"认不出来"
     * （{@code "2019"} 不在任何一张表里，形态仍是未知），不是误判成某一类。
     * 判错方向的代价不对称，所以这里宁可"认不出"，不可"猜一个"。
     */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase(Locale.ROOT);
        int cut = n.indexOf('?');
        if (cut >= 0) {
            n = n.substring(0, cut);
        }
        cut = n.indexOf('#');
        if (cut >= 0) {
            n = n.substring(0, cut);
        }
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = n.substring(dot + 1);
        if (ext.length() == 0 || ext.length() > 5) {
            return "";
        }
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return "";
            }
        }
        return ext;
    }

    /**
     * 按扩展名判是不是图片。
     *
     * <p>只认浏览器与相册最常产出的这几种。判错方向的代价不对称：
     * 漏判成图片会退回"视频"通道（黑屏），误判成图片则一张真视频变成一张
     * 显示不出来的图 —— 所以宁可只认确定的扩展名。
     */
    public static boolean isImageName(String name) {
        String ext = extensionOf(name);
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext) || "webp".equals(ext);
    }

    /**
     * 按扩展名判是不是视频。
     *
     * <p>列进来的都是明确"有画面"的容器。{@code m3u8}（HLS）**刻意不收** ——
     * 它既可能是视频、也可能是纯音频网络电台，见类注释里那条"拿不准就不猜"。
     */
    public static boolean isVideoName(String name) {
        String ext = extensionOf(name);
        return "mp4".equals(ext) || "m4v".equals(ext) || "mkv".equals(ext)
                || "avi".equals(ext) || "mov".equals(ext) || "webm".equals(ext)
                || "flv".equals(ext) || "ts".equals(ext) || "m2ts".equals(ext)
                || "wmv".equals(ext) || "mpg".equals(ext) || "mpeg".equals(ext)
                || "3gp".equals(ext) || "rmvb".equals(ext) || "asf".equals(ext)
                || "ogv".equals(ext) || "vob".equals(ext) || "mts".equals(ext);
    }

    /**
     * 按扩展名判是不是音频。
     *
     * <p>判不出来一律当视频：视频是"有画面"的默认预期，猜错的代价也最小 ——
     * 猜成视频而实际是音频，画面是黑的但声音在放；猜成音频而实际是视频，
     * 界面会切到音乐形态、把画面藏起来，那才是真的丢了东西。
     */
    public static boolean isAudioName(String name) {
        String ext = extensionOf(name);
        return "mp3".equals(ext) || "m4a".equals(ext) || "aac".equals(ext)
                || "wav".equals(ext) || "flac".equals(ext) || "ogg".equals(ext)
                || "wma".equals(ext) || "ape".equals(ext);
    }
}
