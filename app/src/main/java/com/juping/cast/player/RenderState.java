package com.juping.cast.player;

/**
 * 「当前在放什么」的**唯一快照** —— 不可变值对象，不依赖任何 Android 类。
 *
 * <h3>为什么需要它</h3>
 * 这个项目最大的结构问题是：**同一个事实存了四份**。
 * 「现在在放什么、处于什么状态」同时存在于
 * <ol>
 *   <li>{@link MediaPlayerController}：播放器实例状态 + 视频宽高 + prepare 是否挂着；</li>
 *   <li>{@code DlnaRendererService}：{@code transportState} / {@code audioOnly} /
 *       {@code videoMissing} / {@code kindFromMetadata}；</li>
 *   <li>{@code MainActivity}：{@code currentMode()} 拿 4 个 getter <b>重新推导</b>一遍，
 *       再配上 4 个本地字段（上一次形态 / 上一次时刻 / 冻结态 / 用户主动停止）和宽限计时；</li>
 *   <li>{@code UpnpHttpServer}：另读一遍 {@code transportState} 与 {@code kindOf()}。</li>
 * </ol>
 * 四份副本靠**手工同步**。改一处语义，其余三处都得跟着动，漏一处就表现为
 * 「修好 A、弄坏 B」—— 提交历史里 29% 的提交要同时改「四大文件」中的三个以上，
 * 就是这么来的。
 *
 * <h3>这个类怎么解决它</h3>
 * 把「当前状态」显式化成一个**只读快照**：由 {@code DlnaRendererService} 在唯一一处
 * 组装（它本来就握着全部原料），界面、协议层、事件层都读同一个对象。
 * 从此「改状态语义」只需要改组装点一处。
 *
 * <p>放在 {@code player} 包而不是服务里，是为了**零 Android 依赖** ——
 * 这样判据（{@link PlaybackPolicy#modeOf}）能在桌面上跑断言，
 * 与 {@link PlaybackPolicy} 同一条纪律：语义归桌面断言、形状归源码守卫。
 *
 * <h3>⚠️ 本类只做搬迁，不改语义</h3>
 * 每个字段都对应原来某个具体的 getter / 表达式，取值规则**逐字保持**。
 * 尤其 {@link #hasContent()} 刻意保留原 {@code MainActivity.isPlaying()} 的
 * 「时长已知也算有内容」这条兜底 —— 它本身是个脆弱判据（见该方法注释），
 * 但改它属于**行为变更**，要真机复验，不能混在结构重构里做。
 */
public final class RenderState {

    // ------------------------------------------------------------ 形态（元数据判定）

    /** 元数据没说（或解析不出来）这是什么。 */
    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_AUDIO = 1;
    public static final int KIND_VIDEO = 2;
    public static final int KIND_IMAGE = 3;

    // ------------------------------------------------------------ 字段（全部只读）

    /** 播放器实例是否存在。它不存在时下面所有播放器相关字段都没有意义。 */
    public final boolean hasPlayer;

    /**
     * 是否**有片源**（{@code currentUri} 非空）。
     *
     * <p>这是「有内容在处理」的原始判据。注意它比 {@link #hasContent()} 严格：
     * 收尾（{@code onStop()}）会清掉 {@code currentUri}，而 {@code hasContent()}
     * 还额外接受「时长已知」这一条 —— 两者的差别正是 §7.14
     * 「音乐播完冻在界面不回空闲」那个 bug 的成因。
     */
    public final boolean hasSource;

    /** 元数据判定的形态：{@link #KIND_UNKNOWN} / AUDIO / VIDEO / IMAGE。 */
    public final int kind;

    /**
     * 服务侧定论：**没有画面**（纯音频）。
     *
     * <p>注意它**不等于** {@code kind == KIND_AUDIO} —— 元数据没说时，
     * {@code onPrepared} 会用 MediaPlayer 报的真实视频尺寸定论
     * （{@code audioOnly = !hasVideo}）。两个信号都要用上，只看一个不稳。
     */
    public final boolean audioOnly;

    /** 形态是视频、但厂商说画面编码解不了（电视上是纯黑、声音正常）。 */
    public final boolean videoMissing;

    /** {@code prepareAsync} 已发出、回调还没到。 */
    public final boolean preparing;

    /** 当前处于出错态（分类不为 {@code ERR_NONE}）。 */
    public final boolean hasError;

    /** 位置（毫秒）。读的是采样缓存，不是 native 直调。 */
    public final long positionMs;

    /** 时长（毫秒）。0 表示还不知道。 */
    public final long durationMs;

    /** 软件信箱要摆的盒子宽高（已按面板缩放）。0 表示未知。 */
    public final int aspectW;
    public final int aspectH;

    // ------------------------------------------------------------ 构造

    public RenderState(boolean hasPlayer, boolean hasSource, int kind, boolean audioOnly,
                       boolean videoMissing, boolean preparing, boolean hasError,
                       long positionMs, long durationMs, int aspectW, int aspectH) {
        this.hasPlayer = hasPlayer;
        this.hasSource = hasSource;
        this.kind = kind;
        this.audioOnly = audioOnly;
        this.videoMissing = videoMissing;
        this.preparing = preparing;
        this.hasError = hasError;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.aspectW = aspectW;
        this.aspectH = aspectH;
    }

    /** 播放器不存在时的空快照 —— 界面在服务未就绪时读它，语义等价于"什么都没有"。 */
    public static RenderState empty() {
        return new RenderState(false, false, KIND_UNKNOWN, false, false, false, false,
                0L, 0L, 0, 0);
    }

    // ------------------------------------------------------------ 派生（唯一一处）

    /** 当前投的是不是一张静态图。图片**既不进 MediaPlayer、也不算音频**。 */
    public boolean isImage() {
        return kind == KIND_IMAGE;
    }

    /**
     * 界面「有内容在处理」的判据 —— 逐字等价于旧 {@code MainActivity.isPlaying()}。
     *
     * <p><b>为什么保留「时长已知也算」这条兜底</b>：有控制点只发
     * {@code SetAVTransportURI}、时长已经读到、但 {@code currentUri} 一时为空
     * （收尾与重新投屏之间的窗口）。只看 URI 的话这段时间会闪一下空闲面板。
     *
     * <p><b>但这条兜底本身是脆的</b>：时长归零后它会退化成「有没有 URI」，
     * 于是收尾只停播放器、不清片源时，界面会**永远非空闲** ——
     * 这正是 §7.14「一首歌播完，电视冻在音乐界面不动」的根因，
     * 当时的修法是在 {@code onStop()} 里补清 {@code currentUri}（治标）。
     * 治本要改成读播放器的真实状态，但那是**行为变更**、要真机复验，
     * 因此本轮只把它显式写在这里，不做改动。
     */
    public boolean hasContent() {
        return hasPlayer && (durationMs > 0 || hasSource);
    }

    /**
     * 「投的是视频，但画面还没准备好」—— 逐字等价于旧
     * {@code DlnaRendererService.isVideoPending()}。
     *
     * <p>判据用「prepare 还挂着」而**不是**「时长为 0」：HLS 直播的时长恒为 0，
     * 拿它当判据会把正常播放的直播永远判成准备中，占位层再也撤不掉
     * （铁律：判据必须会终结）。
     *
     * <p>这里要求 {@link #hasSource}（而不是 {@link #hasContent()}）——
     * 与旧实现里的 {@code currentUri != null && length() > 0} 逐字对应。
     */
    public boolean isVideoPending() {
        return hasPlayer && kind == KIND_VIDEO && !audioOnly && preparing && hasSource;
    }

    /**
     * 「在放」时的形态 —— 图片优先于音频、音频优先于视频。
     *
     * <p>图片必须**先于**音频判：它不是音频，却同样没有 MediaPlayer 画面，
     * 落到「音频 / 视频」二选一里只会被判成"视频"，界面去等一个永远不会来的
     * 视频帧，屏幕就是一块黑屏。
     */
    public int playingMode() {
        if (isImage()) {
            return PlaybackPolicy.MODE_IMAGE;
        }
        return audioOnly ? PlaybackPolicy.MODE_AUDIO : PlaybackPolicy.MODE_VIDEO;
    }
}
