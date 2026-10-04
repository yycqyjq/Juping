package com.juping.cast.player;

/**
 * 从 MP4 容器里读出视频轨的真实显示宽高 —— 纯字节解析，零 Android 依赖。
 *
 * <p><b>为什么需要这个东西</b>：这台电视（海信 MTK5880 / Android 4.0.4）对
 * <b>HLS</b> 走厂商自研的信箱计算链（日志里的 {@code CmpbHttpLiveStreaming::GetRect}
 * → {@code getCmpbAsp} → {@code final_DspH}），比例是对的；而<b>直连 MP4</b>
 * （{@code file format = 11}，抖音、以及腾讯偶尔给的 {@code .f632}）没有这条链，
 * 厂商直接 {@code set overscan!!!} 把画面放大铺满 —— 竖屏视频被撑成全屏。
 * 控制面已穷尽：我们代码里零 overscan 调用、57 个 service 里没有 aspect 类、
 * API 15 没有 {@code settings list}，overscan 是厂商 native 硬编码，关不掉。
 *
 * <p>既然关不掉厂商的，就在<b>我们这一层</b>按真实宽高摆 SurfaceView（软件信箱）。
 *
 * <p><b>为什么是纯静态函数</b>：解析逻辑必须能在桌面上被断言覆盖
 * （{@code tools/policy-test}），而不是只能靠真机试。所以这里不碰任何
 * Android API，输入输出都是字节数组。
 *
 * <p><b>红线：算不出来就如实说算不出来</b> —— 返回 {@code null} 让调用方退回全屏，
 * 绝不返回一个猜的值。（与项目「声明了放不了 = 对控制点撒谎」同一条纪律。）
 */
public final class Mp4Aspect {

    private Mp4Aspect() {
    }

    /**
     * 单次 Range 探测读的窗口。
     *
     * <p><b>不要求装下整个 moov</b> —— 只需要装下 ftyp + moov 的<b>头部</b>。
     * 解析器容忍 moov 被窗口截断（见 {@link #parse}），而 tkhd / stsd / avcC
     * 都在 moov 头 400 余字节内，所以 64KB 对几百 KB 的大 moov 一样够用
     * （实测 B 站 1080P：moov 256807 字节，avcC 在文件偏移 563 处）。
     */
    public static final int WINDOW_BYTES = 64 * 1024;

    /**
     * 把 {@code w×h} 的视频盒子按面板尺寸等比缩到「装得下」，返回
     * {@code {boxW, boxH}}；参数不合法返回 {@code null}（调用方退回全屏）。
     *
     * <p><b>为什么必须缩放而不是按原始像素直接摆</b>：信箱的摆法曾经是
     * {@code new LayoutParams(w, h, CENTER)} —— 拿<b>原始像素</b>当盒子。
     * 竖屏 720×1280 投在 1920×1080 的面板上，高 1280 &gt; 1080，视图直接超出
     * 父容器被裁掉 —— 用户看到的是画面被切头切尾，<b>而不是黑边</b>。这正是
     * 本功能要修的场景，却被实现方式再伤了一遍。
     *
     * <p><b>判方向用交叉相乘，不用除法</b>：{@code w*panelH > h*panelW} 等价于
     * {@code w/h > panelW/panelH}（宽比高更"宽" → 宽贴满），但全程没有除法，
     * 不存在截断误差，也不用给除数为 0 单独开分支。
     *
     * <p><b>溢出</b>：四个入参都是屏幕量级（几千），乘积远在 int 范围内；
     * 负数与 0 在入口就拒掉，不会绕进乘法里。
     *
     * <p>零 Android 依赖 —— 和这个类里所有其它函数一样，判据必须能在桌面上
     * 被 {@code tools/policy-test/PolicyTest} 逐个断言，而不是只能上真机试。
     *
     * @param w       视频真实像素宽（&gt; 0）
     * @param h       视频真实像素高（&gt; 0）
     * @param panelW  面板（父容器）宽（&gt; 0）
     * @param panelH  面板（父容器）高（&gt; 0）
     * @return {@code {boxW, boxH}}（各钳在 {@code 1..panel} 内），或 {@code null}
     */
    public static int[] fitInside(int w, int h, int panelW, int panelH) {
        if (w <= 0 || h <= 0 || panelW <= 0 || panelH <= 0) {
            return null;
        }
        int boxW;
        int boxH;
        if ((long) w * panelH > (long) h * panelW) {
            // 视频比面板更"宽" → 宽贴满，高等等比缩（上下留黑边）
            // +w/2 再整除 = 四舍五入：截断会让 720×1280@1920×1080 摆成
            // 607 宽的盒（半个像素的系统性右偏），信箱居中就歪半个像素。
            boxW = panelW;
            boxH = (int) (((long) h * panelW + (long) w / 2) / w);
        } else {
            // 视频比面板更"高"（或正好）→ 高贴满，宽等比缩（左右留黑边）
            boxH = panelH;
            boxW = (int) (((long) w * panelH + (long) h / 2) / h);
        }
        if (boxW < 1) {
            boxW = 1;
        } else if (boxW > panelW) {
            boxW = panelW;
        }
        if (boxH < 1) {
            boxH = 1;
        } else if (boxH > panelH) {
            boxH = panelH;
        }
        return new int[] {boxW, boxH};
    }

    /**
     * 解析一段 MP4 字节（头部窗口或尾部窗口），返回视频轨的显示宽高
     * {@code {width, height}}；解析不出返回 {@code null}。
     *
     * <p>入口只认「一段可能含有 moov 的字节」—— 探测器负责决定读头还是读尾，
     * 这里不关心网络。
     *
     * @param buf 读到的字节
     * @param len 有效长度（可能小于 buf.length）
     */
    public static int[] parse(byte[] buf, int len) {
        if (buf == null || len <= 0) {
            return null;
        }
        // 顶层链里找 moov。faststart 的文件 moov 在 ftyp 之后、mdat 之前；
        // 没做 faststart 的文件 moov 在文件尾部 —— 两种都可能落进这个窗口。
        int[] moov = findTopLevel(buf, 0, len, 'm', 'o', 'o', 'v');
        if (moov == null) {
            // 对齐扫描找不到时，多半是**尾部窗口**：窗口开头是 mdat 的载荷
            // （随机字节），不是 box 边界，逐个跳 box 的方式一步都迈不出去。
            // 这时改成在窗口里直接找 'moov' 这四个字节，并用 size 校验它
            // 确实是个真 box（误匹配到 mdat 里的同名 4 字节时，size 对不上）。
            moov = scanForMoov(buf, len);
        }
        if (moov == null) {
            return null;
        }
        // **容忍 moov 被窗口截断**：faststart 的长视频 moov 可达几百 KB，而探测
        // 窗口只有 {@link #WINDOW_BYTES}（64KB）—— 整个 moov 装不下。但 tkhd 在
        // trak 头部、stsd 在 stbl 头部，实测距 moov 头仅 400 余字节，必定落在窗口
        // 里。所以把 end 钳到窗口内继续解析，而不是直接放弃。
        // （这里原先是 `moov[0] + moov[1] > len → return null`，正是它让 B 站
        // 1080P 的预检一条日志都不打：moov 声明 256807 字节 > 64KB 窗口。）
        int end = (int) Math.min((long) moov[0] + moov[1], len);
        if (end <= moov[0] + 8) {
            return null;    // 连 moov 的 box 头都没读全
        }
        // 注意第三个参数是**绝对结束位置**（box 起点 + 长度），不是载荷长度
        return parseMoov(buf, moov[0] + 8, end);
    }

    /**
     * 解析 moov 载荷（已去掉 8 字节 box 头），挑出<b>视频轨</b>的宽高。
     *
     * <p>为什么要「挑」：MP4 里每个 trak 都有 tkhd，音频轨的 tkhd 宽高恒为 0 ——
     * 不挑的话拿到的第一条往往就是音频轨，算出来是 0×0，直接误判成「解析失败」。
     */
    public static int[] parseMoov(byte[] b, int off, int end) {
        int[] trak = findChild(b, off, end, 't', 'r', 'a', 'k');
        // 允许继续找下一个 trak：第一个不是视频就试下一个
        while (trak != null) {
            int[] size = tkhdSize(b, trak[0] + 8, trak[0] + trak[1]);
            if (size != null && size[0] > 0 && size[1] > 0) {
                return size;
            }
            int[] next = findChild(b, trak[0] + trak[1], end, 't', 'r', 'a', 'k');
            if (next == null) {
                break;
            }
            trak = next;
        }
        // 第二条路：tkhd 宽高为 0（有些封装就是不写），退回 stsd 里的编码尺寸。
        // 注意这里重扫一遍，因为视频轨可能排在音频轨后面。
        trak = findChild(b, off, end, 't', 'r', 'a', 'k');
        while (trak != null) {
            int[] s = stsdSize(b, trak[0] + 8, trak[0] + trak[1]);
            if (s != null && s[0] > 0 && s[1] > 0) {
                return s;
            }
            int[] next = findChild(b, trak[0] + trak[1], end, 't', 'r', 'a', 'k');
            if (next == null) {
                break;
            }
            trak = next;
        }
        return null;
    }

    /**
     * 读一条 trak 里的 tkhd，返回显示宽高（已按旋转矩阵摆正）。
     *
     * <p>读不到 / 宽高为 0 时返回 {@code null}。
     */
    public static int[] tkhdSize(byte[] b, int off, int end) {
        int[] tkhd = findChild(b, off, end, 't', 'k', 'h', 'd');
        if (tkhd == null) {
            return null;
        }
        int p = tkhd[0];
        int size = tkhd[1];
        int limit = p + size;
        // 边界判据要盖住**将要读的那一字节**：下一行读 b[p+8]（version），
        // 所以必须要求 p+9 ≤ b.length —— 写成 p+8 的话，size==8 且 box
        // 恰好顶到窗口尾时，这里放行、下面 b[p+8] 抛 AIOOBE。
        // （虽然被外层 catch 兜住不致崩，但越界就该在源头挡 ——
        // 依赖"反正有人接"是把正确性押在别人的异常处理上。）
        if (p + 9 > b.length || limit > b.length) {
            return null;
        }
        int version = b[p + 8] & 0xFF;
        // 宽高偏移：v0 = 84 / 88，v1（64 位时间戳）= 96 / 100，都从 box 起始算。
        int wOff = (version == 1) ? 96 : 84;
        int hOff = (version == 1) ? 100 : 88;
        if (hOff + 4 > size || p + hOff + 4 > limit) {
            return null;
        }
        // 16.16 定点数：高 16 位才是像素数
        int w = readInt(b, p + wOff) >> 16;
        int h = readInt(b, p + hOff) >> 16;
        if (w <= 0 || h <= 0) {
            return null;
        }
        // 竖屏视频常常不是「写 720×1280」，而是「写 1280×720 + 旋转 90° 的 matrix」。
        // 不摆正的话会把竖屏判成横屏，黑边方向就反了。
        // 旋转矩阵在 v0 里位于偏移 48（layer 之前那一串字段之后）。
        // v1 只比 v0 多 12 字节 —— creation / modification / duration 各自从
        // 32 位变 64 位，+4 +4 +4 —— 所以 v1 的 matrix 在 60，不是 64。
        int mOff = p + 48 + (version == 1 ? 12 : 0);
        if (mOff + 36 <= limit) {
            int a = readInt(b, mOff);
            int bb = readInt(b, mOff + 4);
            int c = readInt(b, mOff + 12);
            int d = readInt(b, mOff + 16);
            // cos=0 即 90°/270°：交换宽高
            if (a == 0 && d == 0 && bb != 0 && c != 0) {
                return new int[] {h, w};
            }
        }
        return new int[] {w, h};
    }

    /**
     * tkhd 拿不到时的兜底：从 stsd → 第一个 sample entry（avc1/hev1/…）读编码尺寸。
     *
     * <p>路径是 trak → mdia → minf → stbl → stsd。
     */
    public static int[] stsdSize(byte[] b, int off, int end) {
        int[] mdia = findChild(b, off, end, 'm', 'd', 'i', 'a');
        if (mdia == null) {
            return null;
        }
        int[] minf = findChild(b, mdia[0] + 8, mdia[0] + mdia[1], 'm', 'i', 'n', 'f');
        if (minf == null) {
            return null;
        }
        int[] stbl = findChild(b, minf[0] + 8, minf[0] + minf[1], 's', 't', 'b', 'l');
        if (stbl == null) {
            return null;
        }
        int[] stsd = findChild(b, stbl[0] + 8, stbl[0] + stbl[1], 's', 't', 's', 'd');
        if (stsd == null || stsd[1] < 16) {
            return null;
        }
        // stsd: size(4) type(4) version/flags(4) entry_count(4) → 第一个 entry 从 +16 起
        int entry = stsd[0] + 16;
        int entryEnd = stsd[0] + stsd[1];
        if (entry + 36 > entryEnd || entry + 36 > b.length) {
            return null;
        }
        // 先验 fourcc：+32/+34 只在 **VisualSampleEntry** 上才是宽高。
        // mp4a 这类音频 entry 的载荷布局完全不同 —— 那两个偏移读出来的是
        // 毫不相干的字节（实测能读成 0x0500/0x02D0 = 1280×720 这种"合法值"），
        // 区间闸（16~8192）根本挡不住"落在区间内的假值"，只能在源头认类型。
        if (!isVisualEntry(typeAt(b, entry + 4))) {
            return null;
        }
        // SampleEntry 载荷 8 字节（reserved[6] + data_ref_index），
        // VisualSampleEntry 再 16 字节（pre_defined/reserved），之后才是 width/height
        // —— 各为 16 位无符号。即从 entry 起始算偏移 32 / 34。
        int w = readU16(b, entry + 32);
        int h = readU16(b, entry + 34);
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[] {w, h};
    }

    // ------------------------------------------------------------------
    // H.264 DPB 容量 —— 「有声无画」的判据
    // ------------------------------------------------------------------

    /**
     * 本机 H.264 解码器的 DPB 上限（单位：宏块）。
     *
     * <p><b>为什么是个写死的常量</b>：这台电视（海信 MT5880）实测只到
     * Level 4.0 —— 1080p 下最多 4 个参考帧。而 {@code MediaCodecInfo} /
     * {@code MediaExtractor} 都要 API 16+，{@code MediaMetadataRetriever}
     * 根本拿不到这个值 —— API 15 上**没有任何 API 能问到**解码器的 DPB 容量，
     * 只能把实测值写死。换设备必须重新实测。
     *
     * <p>实测依据见 {@code .agent/todo.md §7.21} 的对照实验表：同一台盒子，
     * 1080p/6 参考帧的黑屏、1080p/2 参考帧的正常、360p/6 参考帧的正常。
     */
    public static final int DEVICE_MAX_DPB_MBS = 32768;

    /**
     * 一次 H.264 SPS 解析的结果 —— DPB 判定的全部输入与派生量。
     *
     * <p>字段要么是 SPS 里**直接读出来的**，要么是由它们**无歧义算出**的，
     * 没有任何猜测成分。（与这个类其它函数同一条纪律：算不出就返回 {@code null}，
     * 绝不返回一个半成品让调用方以为拿到了真值。）
     */
    public static final class Dpb {
        /** profile_idc：66=Baseline / 77=Main / 88=Extended / 100=High。 */
        public final int profileIdc;
        /** level_idc：数值是等级的 10 倍（30 = Level 3.0、40 = Level 4.0）。 */
        public final int levelIdc;
        /** max_num_ref_frames：解码器要同时保有的参考帧数。 */
        public final int maxNumRefFrames;
        /** 一帧的宏块列数 = pic_width_in_mbs_minus1 + 1。 */
        public final int picWidthInMbs;
        /** 一帧的宏块行数（已把场编码的 ×2 算进去）。 */
        public final int frameHeightInMbs;

        Dpb(int profileIdc, int levelIdc, int maxNumRefFrames,
            int picWidthInMbs, int frameHeightInMbs) {
            this.profileIdc = profileIdc;
            this.levelIdc = levelIdc;
            this.maxNumRefFrames = maxNumRefFrames;
            this.picWidthInMbs = picWidthInMbs;
            this.frameHeightInMbs = frameHeightInMbs;
        }

        /** 一帧占多少宏块 = 列 × 行。 */
        public int picSizeInMbs() {
            return picWidthInMbs * frameHeightInMbs;
        }

        /** DPB 需要多少宏块 = 参考帧数 × 每帧宏块数。 */
        public int neededMbs() {
            return maxNumRefFrames * picSizeInMbs();
        }

        /** 流**自己标的** level 允许多少宏块（H.264 Table A-1）；未知等级返回 0。 */
        public int declaredLevelMaxMbs() {
            return levelMaxDpbMbs(levelIdc);
        }

        /**
         * 按**本机硬件**上限判定是否超限 —— 这才是真正的判据。
         *
         * <p>流可以把自己标成 Level 5.0（B 站 1080P 就是这么干的，按 5.0 算
         * 6 个参考帧「合法」），但<b>标称等级骗得过应用，骗不过硬件</b>：
         * 硬件解码器发现 DPB 装不下就放弃视频、音频照放 —— 用户看到黑屏有声，
         * 而且<b>一个错误都不报</b>。
         */
        public boolean exceedsDevice() {
            return neededMbs() > DEVICE_MAX_DPB_MBS;
        }

        /** profile 的可读名；未知返回 {@code "profile <n>"}。 */
        public String profileName() {
            switch (profileIdc) {
                case 66: return "Baseline";
                case 77: return "Main";
                case 88: return "Extended";
                case 100: return "High";
                case 110: return "High 10";
                case 122: return "High 4:2:2";
                case 244: return "High 4:4:4";
                default: return "profile " + profileIdc;
            }
        }

        /** level 的可读名（"3.0" / "4.0" / "1b"）；未知返回 {@code "level <n>"}。 */
        public String levelName() {
            if (levelIdc == 9) {
                return "1b";
            }
            if (levelIdc < 10) {
                return "level " + levelIdc;
            }
            return (levelIdc / 10) + "." + (levelIdc % 10);
        }
    }

    /**
     * H.264 Table A-1 的 MaxDpbMbs（按 level_idc 查）。
     *
     * <p>表里没有的等级返回 0 —— 调用方据此知道「判不了」，而不是拿到一个
     * 看起来像真值的 0 去比较。
     */
    public static int levelMaxDpbMbs(int levelIdc) {
        switch (levelIdc) {
            case 9:  return 396;      // Level 1b
            case 10: return 396;
            case 11: return 900;
            case 12: return 2376;
            case 13: return 2376;
            case 20: return 2376;
            case 21: return 4752;
            case 22: return 8100;
            case 30: return 8100;
            case 31: return 18000;
            case 32: return 20480;
            case 40: return 32768;
            case 41: return 32768;
            case 42: return 34816;
            case 50: return 110400;
            case 51: return 184320;
            case 52: return 184320;
            default: return 0;
        }
    }

    /**
     * 从一段含 moov 的字节里解析视频轨的 H.264 SPS，给出 DPB 判定结果；
     * 解析不出返回 {@code null}。
     *
     * <p><b>为什么不需要额外的网络请求</b>：{@code avcC} 在 {@code stsd} 里、
     * {@code stsd} 在 {@code moov} 里 —— 和 {@link #parse} 读 tkhd 用的是
     * <b>同一个窗口</b>。所以只要这个窗口已经拿到了，SPS 就是顺手多走两层的事，
     * 零额外流量。这是本功能敢做「播放前预检」的前提。
     *
     * <p>只认 H.264（{@code avc1} / {@code avc3}）。H.265 的 SPS 在 {@code hvcC}
     * 里、结构完全不同，一律返回 {@code null}（不猜）。
     *
     * @param buf 读到的字节（与 {@link #parse} 同一份窗口即可）
     * @param len 有效长度
     */
    public static Dpb dpb(byte[] buf, int len) {
        if (buf == null || len <= 0) {
            return null;
        }
        int[] moov = findTopLevel(buf, 0, len, 'm', 'o', 'o', 'v');
        if (moov == null) {
            moov = scanForMoov(buf, len);
        }
        if (moov == null) {
            return null;
        }
        // 与 {@link #parse} 同一处理：容忍 moov 被窗口截断 —— avcC 也在 moov
        // 头部，整个 moov 装不下不影响判定。
        int end = (int) Math.min((long) moov[0] + moov[1], len);
        if (end <= moov[0] + 8) {
            return null;
        }
        return dpbFromMoov(buf, moov[0] + 8, end);
    }

    /** 在 moov 载荷里逐个 trak 找带 avcC 的那条。 */
    static Dpb dpbFromMoov(byte[] b, int off, int end) {
        int[] trak = findChild(b, off, end, 't', 'r', 'a', 'k');
        while (trak != null) {
            Dpb d = dpbFromTrak(b, trak[0] + 8, trak[0] + trak[1]);
            if (d != null) {
                return d;
            }
            int[] next = findChild(b, trak[0] + trak[1], end, 't', 'r', 'a', 'k');
            if (next == null) {
                break;
            }
            trak = next;
        }
        return null;
    }

    /** trak → mdia → minf → stbl → stsd → avc1/avc3 → avcC。 */
    static Dpb dpbFromTrak(byte[] b, int off, int end) {
        int[] mdia = findChild(b, off, end, 'm', 'd', 'i', 'a');
        if (mdia == null) {
            return null;
        }
        int[] minf = findChild(b, mdia[0] + 8, mdia[0] + mdia[1], 'm', 'i', 'n', 'f');
        if (minf == null) {
            return null;
        }
        int[] stbl = findChild(b, minf[0] + 8, minf[0] + minf[1], 's', 't', 'b', 'l');
        if (stbl == null) {
            return null;
        }
        int[] stsd = findChild(b, stbl[0] + 8, stbl[0] + stbl[1], 's', 't', 's', 'd');
        if (stsd == null || stsd[1] < 16) {
            return null;
        }
        int entry = stsd[0] + 16;
        int entryEnd = stsd[0] + stsd[1];
        // VisualSampleEntry 的固定头是 78 字节（从 entry 的 box 起点算到
        // compressorname 之后的 depth/pre_defined 结束），子 box 从 +86 起。
        // 算错这个偏移会一路读到别的东西 —— 而且多半还是"合法字节"，
        // 所以下面必须再验 fourcc，不能只靠偏移对得上。
        if (entry + 86 > entryEnd || entry + 86 > b.length) {
            return null;
        }
        int entryType = typeAt(b, entry + 4);
        // 只有 avc1 / avc3 带 avcC。hvc1/hev1 的 SPS 在 hvcC 里，
        // 结构完全不同 —— 不认就是「算不出」，走 null 红线。
        if (entryType != fourcc('a', 'v', 'c', '1')
                && entryType != fourcc('a', 'v', 'c', '3')) {
            return null;
        }
        int[] avcc = findChild(b, entry + 86, entryEnd, 'a', 'v', 'c', 'C');
        if (avcc == null) {
            return null;
        }
        return parseAvcC(b, avcc[0] + 8, avcc[0] + avcc[1]);
    }

    /**
     * 解 AVCDecoderConfigurationRecord，取第一个 SPS。
     *
     * <pre>
     * configurationVersion         u(8)   = 1
     * AVCProfileIndication         u(8)
     * profile_compatibility        u(8)
     * AVCLevelIndication           u(8)
     * 6 bits reserved + lengthSizeMinusOne      u(8)
     * 3 bits reserved + numOfSequenceParameterSets  u(8)
     * for each SPS:  u(16) 长度 + 该长度的 NAL
     * </pre>
     *
     * <p>只解第一个 SPS：多 SPS 属于可伸缩/多视图编码，本机解不了也不需要。
     */
    static Dpb parseAvcC(byte[] b, int off, int end) {
        if (off + 7 > end || off + 7 > b.length) {
            return null;
        }
        if ((b[off] & 0xFF) != 1) {
            return null;    // 只认第 1 版配置记录
        }
        int numSps = b[off + 5] & 0x1F;
        if (numSps < 1) {
            return null;
        }
        int p = off + 6;
        int spsLen = readU16(b, p);
        p += 2;
        // 长度必须是个能装下 NAL header + 几个字段的合理值，且不能越界
        if (spsLen < 4 || p + spsLen > end || p + spsLen > b.length) {
            return null;
        }
        return parseSps(b, p, spsLen);
    }

    /**
     * stsd 的第一个 sample entry 是不是**视觉轨**（视频编解码）。
     *
     * <p>白名单认这几类常见视频 fourcc：H.264（avc1/avc3）、H.265（hvc1/hev1）、
     * AV1（av01）、VP9（vp09）、MPEG-4 视觉（mp4v）。不在名单上一律当非视频
     * （音频 mp4a、文本 tx3g、元数据 …）→ 返 {@code null}，走"算不出就不猜"红线。
     *
     * <p>为什么白名单而不是黑名单：新编解码格式会不断出现，白名单最多是
     * "遇到新格式退回全屏"（无害）；黑名单漏一个，就是把音频 entry 的垃圾
     * 字节当尺寸摆上去（有害）。
     */
    private static boolean isVisualEntry(int fourcc) {
        return fourcc == fourcc('a', 'v', 'c', '1')
                || fourcc == fourcc('a', 'v', 'c', '3')
                || fourcc == fourcc('h', 'v', 'c', '1')
                || fourcc == fourcc('h', 'e', 'v', '1')
                || fourcc == fourcc('a', 'v', '0', '1')
                || fourcc == fourcc('v', 'p', '0', '9')
                || fourcc == fourcc('m', 'p', '4', 'v');
    }

    // ------------------------------------------------------------------
    // box 遍历
    // ------------------------------------------------------------------

    /** 在 [off,end) 里顺序找第一个顶层 box（逐个跳过），返回 {offset, size}。 */
    private static int[] findTopLevel(byte[] b, int off, int end, char t0, char t1,
                                      char t2, char t3) {
        int p = off;
        int want = fourcc(t0, t1, t2, t3);
        while (p + 8 <= end && p + 8 <= b.length) {
            long size = readUInt(b, p);
            int type = typeAt(b, p + 4);
            if (size == 1) {
                // 64 位 largesize：真实长度在后面 8 字节。
                if (p + 16 > end || p + 16 > b.length) {
                    return null;
                }
                long hi = readUInt(b, p + 8);
                long lo = readUInt(b, p + 12);
                size = (hi << 32) | lo;
            } else if (size < 8) {
                // 头部不合法（不是 MP4，或窗口从中间截断了）
                return null;
            }
            // 步进必须先校验再跳：尾部窗口的开头落在 mdat 载荷**中间**，头 4 字节
            // 是随机载荷 —— 可能是 0xABABABAB 这种高位为 1 的值，`(int) size`
            // 会变成负数，p 变负后循环条件仍成立，下一轮 readUInt 就越界崩了。
            // 不合法就当作「这里不是 box 边界」返回 null，交给 scanForMoov 回退。
            long next = (long) p + size;
            // **命中优先于越界检查**：faststart 的长视频 moov 可达几百 KB，而
            // 探测窗口只有 {@link #WINDOW_BYTES}（64KB）—— 整个 moov 装不下。
            // 这时仍要把 moov 的位置和**声明**大小交出去：调用方会把 end 钳到
            // 窗口内继续解析，而 tkhd / stsd / avcC 都在 moov 头部（实测距 moov
            // 头 400 余字节），必定落在窗口里。
            // （曾经这里先查越界再比类型，于是「moov 声明 256807 > 窗口 65536」
            // 直接返回 null —— B 站 1080P 的预检一条日志都不打，根因在此。）
            if (type == want) {
                return new int[] {p, (int) Math.min(size, Integer.MAX_VALUE - 8)};
            }
            if (next <= p || next > end) {
                return null;
            }
            p = (int) next;
        }
        return null;
    }

    /**
     * 尾部窗口回退：窗口开头落在 mdat 载荷中间，逐个跳 box 一步都迈不出去，
     * 于是直接在字节流里找 'moov' 这四个字节。
     *
     * <p>必须做 size 校验，否则 mdat 里随机出现的 'm''o''o''v' 会被误认成 box ——
     * 真 box 的 size 一定 ≥ 8 且 box 一定在窗口内收尾。
     *
     * @return {offset, size}，找不到返回 {@code null}
     */
    private static int[] scanForMoov(byte[] b, int len) {
        if (len < 16) {
            return null;
        }
        for (int i = 4; i + 4 <= len; i++) {
            if (b[i] != 'm' || b[i + 1] != 'o' || b[i + 2] != 'o' || b[i + 3] != 'v') {
                continue;
            }
            int start = i - 4;
            long size = readUInt(b, start);
            // box 头占 8 字节，size 至少这么长；且必须完整落在窗口里
            if (size < 8 || start + size > len) {
                continue;
            }
            // 大盒子（moov 一般几十 KB）不可能只占几个字节，
            // 再卡一道：至少要装得下 tkhd 所需的子结构
            if (size < 64) {
                continue;
            }
            return new int[] {start, (int) size};
        }
        return null;
    }

    /**
     * 在 [off,end) 里顺序找第一个子 box，返回 {offset, size}。
     *
     * <p><b>目标 box 越界 = 被窗口截断，不是「找不到」</b>：返回<b>钳断到窗口
     * 边界</b>的长度（{@code end - p}），让调用方按「窗口边界即硬边界」尽力读
     * 它的头部字段。这是大 moov 场景能工作的关键 —— trak 的声明大小往往几十万
     * 字节（它装着整个 stbl 的表），而我们要的 tkhd / stsd 就在 trak 头部。
     *
     * <p>钳断是安全的：调用方读字段时同时受 {@code size} 与 {@code b.length}
     * 约束（如 {@link #tkhdSize} 的 {@code hOff + 4 > size} 闸），读不全就返回
     * {@code null}，绝不会把窗口外的字节当成字段值。
     *
     * <p>非目标 box 越界则仍返回 {@code null}：跳过它需要它的真实长度，而那个
     * 长度已被截断 —— 无法定位它后面的兄弟 box，只能老实说找不到。
     */
    private static int[] findChild(byte[] b, int off, int end, char t0, char t1,
                                   char t2, char t3) {
        if (off < 0 || end > b.length) {
            return null;
        }
        int p = off;
        int want = fourcc(t0, t1, t2, t3);
        while (p + 8 <= end) {
            long size = readUInt(b, p);
            int type = typeAt(b, p + 4);
            if (size < 8) {
                return null;
            }
            if (type == want) {
                if (p + size > end) {
                    // 被窗口截断 —— 交出可见部分，由调用方决定读不读得全。
                    return new int[] {p, end - p};
                }
                return new int[] {p, (int) Math.min(size, Integer.MAX_VALUE - 8)};
            }
            long next = p + size;
            if (next <= p || next > end) {
                return null;
            }
            p = (int) next;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 字节读取
    // ------------------------------------------------------------------

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static long readUInt(byte[] b, int p) {
        return ((long) readInt(b, p)) & 0xFFFFFFFFL;
    }

    private static int readU16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int typeAt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static int fourcc(char a, char b, char c, char d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    // ------------------------------------------------------------------
    // H.264 SPS 解析
    // ------------------------------------------------------------------

    /**
     * 解一条 SPS NAL，给出 DPB 判定结果；任何一步不合规都返回 {@code null}。
     *
     * <p>字段顺序见 H.264 7.3.2.1.1。这里只读到 {@code frame_mbs_only_flag}
     * 为止 —— 后面的 {@code frame_cropping} / VUI 与 DPB 容量无关
     * （DPB 用宏块数算，不用裁剪后的像素数）。
     */
    static Dpb parseSps(byte[] src, int off, int len) {
        if (len < 4) {
            return null;
        }
        // **NAL header 必须先跳过**：首字节是 forbidden_zero_bit(1) +
        // nal_ref_idc(2) + nal_unit_type(5)。不跳的话会把 0x67 当成
        // profile_idc，解出 103 这种根本不存在的 profile、level 0.0、
        // 参考帧数还是个"看着合理"的值 —— 这个坑实际踩过。
        if ((src[off] & 0x1F) != 7) {
            return null;    // 不是 SPS
        }
        byte[] rbsp = unescapeRbsp(src, off + 1, len - 1);
        BitReader r = new BitReader(rbsp);
        try {
            int profileIdc = r.u(8);
            r.u(8);                     // constraint_setN_flags + reserved
            int levelIdc = r.u(8);
            r.ue();                     // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122
                    || profileIdc == 244 || profileIdc == 44 || profileIdc == 83
                    || profileIdc == 86 || profileIdc == 118 || profileIdc == 128
                    || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                    || profileIdc == 135) {
                // High 系列比 Baseline/Main 多这一段，**必须跟着读完**，
                // 否则后面所有字段整体错位。
                int chromaFormatIdc = r.ue();
                if (chromaFormatIdc == 3) {
                    r.u(1);             // separate_colour_plane_flag
                }
                r.ue();                 // bit_depth_luma_minus8
                r.ue();                 // bit_depth_chroma_minus8
                r.u(1);                 // qpprime_y_zero_transform_bypass_flag
                if (r.u(1) != 0) {      // seq_scaling_matrix_present_flag
                    int lists = (chromaFormatIdc == 3) ? 12 : 8;
                    for (int i = 0; i < lists; i++) {
                        if (r.u(1) != 0) {      // seq_scaling_list_present_flag
                            skipScalingList(r, (i < 6) ? 16 : 64);
                        }
                    }
                }
            }

            r.ue();                     // log2_max_frame_num_minus4
            int pocType = r.ue();       // pic_order_cnt_type
            if (pocType == 0) {
                r.ue();                 // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                r.u(1);                 // delta_pic_order_always_zero_flag
                r.se();                 // offset_for_non_ref_pic
                r.se();                 // offset_for_top_to_bottom_field
                int cycle = r.ue();     // num_ref_frames_in_pic_order_cnt_cycle
                if (cycle < 0 || cycle > 255) {
                    return null;
                }
                for (int i = 0; i < cycle; i++) {
                    r.se();             // offset_for_ref_frame[i]
                }
            }

            int maxNumRefFrames = r.ue();
            r.u(1);                     // gaps_in_frame_num_value_allowed_flag
            int picWidthInMbs = r.ue() + 1;
            int picHeightInMapUnits = r.ue() + 1;
            int frameMbsOnlyFlag = r.u(1);
            if (frameMbsOnlyFlag == 0) {
                r.u(1);                 // mb_adaptive_frame_field_flag
            }
            r.u(1);                     // direct_8x8_inference_flag

            // 场编码（frame_mbs_only_flag = 0）时一帧占两倍的行数 ——
            // 漏掉这个 ×2 会把隔行扫描的 1080p 少算一半宏块，
            // 于是「本该报警的超限」被判成正常。
            int frameHeightInMbs = (2 - frameMbsOnlyFlag) * picHeightInMapUnits;

            // ---- 合理性闸：位流错位时会解出"看着合法"的错值 ----
            // H.264 规范里 max_num_ref_frames 上限是 16；分辨率上限按
            // 本项目的 MAX_DIM（8192 像素 = 512 宏块）取宽裕值。
            // 没有这几道闸，「错位」会被当成「一个奇怪的正常流」放过去。
            if (maxNumRefFrames < 0 || maxNumRefFrames > 16) {
                return null;
            }
            if (picWidthInMbs < 1 || picWidthInMbs > 512) {
                return null;
            }
            if (frameHeightInMbs < 1 || frameHeightInMbs > 1024) {
                return null;
            }
            return new Dpb(profileIdc, levelIdc, maxNumRefFrames,
                    picWidthInMbs, frameHeightInMbs);
        } catch (RuntimeException e) {
            // 位流读越界 / 前导 0 过多：一律当"解不出"，不抛给调用方。
            return null;
        }
    }

    /**
     * 跳过一条 scaling list（默认矩阵用的差分编码）。
     *
     * <p>不跳过的话，下面所有字段都会错位 —— 而错位解出来的
     * {@code max_num_ref_frames} 往往仍是个 0~16 的"合理值"，
     * 于是这条流被安静地判成正常。
     */
    private static void skipScalingList(BitReader r, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int delta = r.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    /**
     * 剥掉 SPS 里的 emulation prevention 字节。
     *
     * <p>H.264 规定 NAL 载荷里不许出现 {@code 00 00 00/01/02/03}（那会被
     * 误当成起始码），所以编码器在 {@code 00 00} 之后插入一个 {@code 03} 打断它。
     * <b>不剥掉这些 03，位流会整体错位</b>，解出来的参考帧数是个看似合理的错值。
     *
     * <p>做法：单次扫描，只在「已经连着两个 00 且当前字节是 03」时丢弃它，
     * 丢弃后把 00 计数归零 —— 03 本来就是来打断这个序列的。
     */
    private static byte[] unescapeRbsp(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        int n = 0;
        int zeros = 0;
        for (int i = 0; i < len; i++) {
            int v = src[off + i] & 0xFF;
            if (zeros >= 2 && v == 0x03) {
                zeros = 0;
                continue;
            }
            out[n++] = (byte) v;
            zeros = (v == 0) ? zeros + 1 : 0;
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /**
     * 按位读的游标。越界一律抛异常，由 {@link #parseSps} 统一转成 {@code null}。
     *
     * <p>为什么自己写而不是用现成的：这是**位**流不是字节流，且
     * {@code java.util.BitSet} 只按位存取、不提供 Exp-Golomb。
     */
    private static final class BitReader {
        private final byte[] b;
        private final int bitEnd;
        private int bitPos;

        BitReader(byte[] b) {
            this.b = b;
            this.bitPos = 0;
            this.bitEnd = b.length * 8;
        }

        /** 读 {@code n} 位无符号（{@code n} ≤ 32）。 */
        int u(int n) {
            if (n <= 0 || n > 32 || bitPos + n > bitEnd) {
                throw new IllegalStateException("SPS 位流越界");
            }
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | ((b[bitPos >> 3] >> (7 - (bitPos & 7))) & 1);
                bitPos++;
            }
            return v;
        }

        /** 无符号 Exp-Golomb（ue(v)）：先数前导 0 的个数 k，再读 k 位。 */
        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                zeros++;
                // 上限保护：SPS 里不可能有 31 个前导 0。没有这道闸，
                // 一段垃圾位流会把循环跑到位流尽头（甚至死循环）。
                if (zeros > 31) {
                    throw new IllegalStateException("SPS Exp-Golomb 前导 0 过多");
                }
            }
            if (zeros == 0) {
                return 0;
            }
            return (1 << zeros) - 1 + u(zeros);
        }

        /** 有符号 Exp-Golomb（se(v)）：0→0、1→1、2→-1、3→2、4→-2 … */
        int se() {
            int k = ue();
            return ((k & 1) == 1) ? ((k + 1) / 2) : -(k / 2);
        }
    }
}
