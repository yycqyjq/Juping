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

    /** 单次 Range 探测读的窗口：足够装下 ftyp + 常见的 moov。 */
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
        // moov 必须完整落在窗口内，否则字段会读到窗口外的垃圾。
        if (moov[0] + moov[1] > len) {
            return null;
        }
        // 注意第三个参数是**绝对结束位置**（box 起点 + 长度），不是载荷长度
        return parseMoov(buf, moov[0] + 8, moov[0] + moov[1]);
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
            if (next <= p || next > end) {
                return null;
            }
            if (type == want) {
                return new int[] {p, (int) Math.min(size, Integer.MAX_VALUE - 8)};
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

    /** 在 [off,end) 里顺序找第一个子 box，返回 {offset, size}。 */
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
                    return null;
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
}
