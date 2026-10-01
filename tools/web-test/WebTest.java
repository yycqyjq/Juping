import com.juping.cast.web.LocalStore;
import com.juping.cast.web.MultipartLite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传解析与文件名规整的一致性测试。
 *
 * <p>{@link MultipartLite} 与 {@link LocalStore#sanitize} 都是纯逻辑、不依赖 Android
 * 运行时，所以能直接在桌面 JVM 上编译运行（和 {@code PlaybackPolicy} 同一个路子）。
 *
 * <p>这套测试守的是两类**真机上极难排查**的故障：
 * <ul>
 *   <li><b>字节错位</b>：分隔符跨读缓冲区、段体里正好出现分隔符的前缀 —— 这类 bug 的表现
 *       是"文件传上去了、也能播，但末尾多一截乱码 / 中间花屏"，靠肉眼看是看不出来的，
 *       所以 oracle 必须是**逐字节相同**；</li>
 *   <li><b>穿越</b>：文件名完全由请求方提供、接口又没有鉴权，规整一旦失效就能读到
 *       应用私有数据 —— 所以 oracle 是"规整后只可能是本目录内的一个普通名字"。</li>
 * </ul>
 */
public class WebTest {

    private static int passed = 0;
    private static int total = 0;
    private static final List<String> FAILS = new ArrayList<String>();

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        total++;
        if (ok) {
            passed++;
        } else {
            FAILS.add(name + (detail != null && detail.length() > 0 ? "（" + detail + "）" : ""));
        }
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name
                + (detail != null && detail.length() > 0 ? "\n         " + detail : ""));
    }

    // ------------------------------------------------------------ 收件箱

    /** 把解析结果收下来，顺带能模拟"跳过"与"写盘失败"这两种真实路径。 */
    static final class Collect implements MultipartLite.Sink {

        final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        final Map<String, String> fields = new LinkedHashMap<String, String>();
        final List<String> order = new ArrayList<String>();
        /** 这些文件名在 begin 里返回 null（等价于"盒子空间不足"） */
        final List<String> skip = new ArrayList<String>();
        /** 到第几个文件段时抛 IOException（等价于"写盘失败"） */
        int failAtSeq = -1;
        int seq = 0;
        /** 每次 end 的记录：文件名|字节数|结果 */
        final List<String> ends = new ArrayList<String>();
        private ByteArrayOutputStream cur;

        public OutputStream begin(String fieldName, String fileName) throws IOException {
            order.add("file:" + fileName);
            if (skip.contains(fileName)) {
                return null;
            }
            seq++;
            if (seq == failAtSeq) {
                throw new IOException("模拟写盘失败");
            }
            cur = new ByteArrayOutputStream();
            return cur;
        }

        public void end(String fieldName, String fileName, long bytes, String failed) {
            ends.add(fileName + "|" + bytes + "|" + (failed == null ? "ok" : "fail"));
            if (failed == null && cur != null) {
                files.put(fileName, cur.toByteArray());
            }
            cur = null;
        }

        public void field(String name, String value) {
            fields.put(name, value);
            order.add("field:" + name);
        }
    }

    // ------------------------------------------------------------ 工具

    /** 拼 multipart 体。头部按 UTF-8 写 —— 浏览器发中文名就是这么发的。 */
    static final class Body {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final String boundary;

        Body(String boundary) {
            this.boundary = boundary;
        }

        private Body head(String disposition) throws IOException {
            out.write(("--" + boundary + "\r\n").getBytes("ISO-8859-1"));
            out.write(("Content-Disposition: form-data; " + disposition + "\r\n\r\n")
                    .getBytes("UTF-8"));
            return this;
        }

        Body field(String name, String value) throws IOException {
            head("name=\"" + name + "\"").raw(value.getBytes("UTF-8"));
            return this;
        }

        Body file(String name, String filename, byte[] content) throws IOException {
            head("name=\"" + name + "\"; filename=\"" + filename + "\"").raw(content);
            return this;
        }

        Body fileExt(String name, String filenameStar, byte[] content) throws IOException {
            head("name=\"" + name + "\"; filename*=UTF-8''" + filenameStar).raw(content);
            return this;
        }

        Body raw(byte[] b) throws IOException {
            out.write(b);
            out.write("\r\n".getBytes("ISO-8859-1"));
            return this;
        }

        /** 正常收尾：--boundary-- CRLF */
        byte[] done() throws IOException {
            out.write(("--" + boundary + "--\r\n").getBytes("ISO-8859-1"));
            return out.toByteArray();
        }

        /** 收尾不带尾随 CRLF：--boundary-- （RFC 允许，最后一段也合法） */
        byte[] doneNoCrlf() throws IOException {
            out.write(("--" + boundary + "--").getBytes("ISO-8859-1"));
            return out.toByteArray();
        }
    }

    /** 每次只交几个字节出去 —— 逼出"分隔符跨读边界"这条最容易写错的路径。 */
    static final class Trickle extends FilterInputStream {

        private final int chunk;

        Trickle(InputStream in, int chunk) {
            super(in);
            this.chunk = chunk;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, chunk));
        }
    }

    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        int x = seed;
        for (int i = 0; i < n; i++) {
            x = x * 1103515245 + 12345;
            b[i] = (byte) (x >>> 16);
        }
        return b;
    }

    static Collect parse(byte[] body, String boundary) throws IOException {
        Collect c = new Collect();
        MultipartLite.parse(new ByteArrayInputStream(body), boundary, c);
        return c;
    }

    static Collect parseTrickle(byte[] body, String boundary, int chunk) throws IOException {
        Collect c = new Collect();
        MultipartLite.parse(new Trickle(new ByteArrayInputStream(body), chunk), boundary, c);
        return c;
    }

    // ------------------------------------------------------------ 主流程

    public static void main(String[] args) throws Exception {
        final String BD = "----JupingBoundary7xKq";

        System.out.println("\n── 1. Content-Type 里取 boundary ──");

        check("普通 boundary", BD.equals(MultipartLite.boundaryOf("multipart/form-data; boundary=" + BD)));
        check("带引号的 boundary（含空格）",
                "abc def".equals(MultipartLite.boundaryOf("multipart/form-data; boundary=\"abc def\"")));
        check("没有 boundary → null",
                MultipartLite.boundaryOf("multipart/form-data") == null);
        check("不是 multipart / null → null",
                MultipartLite.boundaryOf("application/json") == null
                        && MultipartLite.boundaryOf(null) == null);

        System.out.println("\n── 2. 基本解析：内容必须逐字节一致 ──");

        byte[] a = "hello 聚屏".getBytes("UTF-8");
        Collect c1 = parse(new Body(BD).file("file", "a.txt", a).done(), BD);
        check("单文件：内容字节一致",
                Arrays.equals(a, c1.files.get("a.txt")),
                "拿到 " + (c1.files.containsKey("a.txt") ? c1.files.get("a.txt").length + " 字节" : "无"));
        check("单文件：文件名正确（中文 / 非 ASCII 原样传下去）",
                parse(new Body(BD).file("file", "测试铃声.wav", a).done(), BD)
                        .files.containsKey("测试铃声.wav"));

        Collect c2 = parse(new Body(BD).field("note", "上传后自动播放").done(), BD);
        check("普通字段：值正确且不进 files",
                "上传后自动播放".equals(c2.fields.get("note")) && c2.files.isEmpty(),
                "fields=" + c2.fields + " files=" + c2.files.keySet());

        Collect c3 = parse(new Body(BD).field("n1", "v1").file("file", "b.bin", a)
                .field("n2", "v2").done(), BD);
        check("多段顺序：按出现顺序回调",
                Arrays.asList("field:n1", "file:b.bin", "field:n2").equals(c3.order),
                "实际 " + c3.order);

        System.out.println("\n── 3. 文件名：两种编码都得认（RFC 5987 / 原始 UTF-8）──");

        // 浏览器带非 ASCII 时通常发 filename*=UTF-8''%E6%B5%8B…
        Collect c4 = parse(new Body(BD)
                .fileExt("file", "%E6%B5%8B%E8%AF%95.mp4", a).done(), BD);
        check("filename*=UTF-8''百分号编码 → 还原成中文",
                c4.files.containsKey("测试.mp4"), "拿到 " + c4.files.keySet());

        // 同一段里两者都在时，filename* 优先（RFC 6266 的规定，也是浏览器的意图）
        String dual = "--" + BD + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"???.mp4\"; "
                + "filename*=UTF-8''%E7%8C%AB.mp4\r\n\r\nx\r\n--" + BD + "--\r\n";
        Collect c5 = parse(dual.getBytes("ISO-8859-1"), BD);
        check("filename* 与 filename 同时出现 → 以 filename* 为准",
                c5.files.containsKey("猫.mp4"), "拿到 " + c5.files.keySet());

        // MultipartLite 只管"把名字原样取出来"，取最后一段是 LocalStore 的事 —— 职责边界要钉住
        Collect c6 = parse(new Body(BD).file("file", "sub/dir/深/片子.mp4", a).done(), BD);
        check("解析层不做规整：目录型名字原样交给 Sink",
                c6.files.containsKey("sub/dir/深/片子.mp4"), "拿到 " + c6.files.keySet());

        System.out.println("\n── 4. 流式边界（这类 bug 只表现为花屏 / 末尾多一截垃圾）──");

        Collect c7 = parseTrickle(new Body(BD).file("file", "t.bin", a).done(), BD, 3);
        check("每次只喂 3 字节（分隔符跨读边界）→ 内容字节一致",
                Arrays.equals(a, c7.files.get("t.bin")));

        byte[] big = pattern(200 * 1024, 7);
        byte[] whole = new Body(BD).file("file", "big.bin", big).done();
        Collect c8 = parseTrickle(whole, BD, 3);
        byte[] got8 = c8.files.get("big.bin");
        check("200KB 段体（3 字节一块喂 vs 整块喂）结果一致",
                got8 != null && Arrays.equals(big, got8),
                "拿到 " + (got8 == null ? "无" : got8.length + " 字节，期望 " + big.length));

        // 段体里正好出现分隔符的前缀：少一个字符，绝不能当成分隔符
        ByteArrayOutputStream near = new ByteArrayOutputStream();
        near.write("前".getBytes("UTF-8"));
        near.write(("--" + BD.substring(0, BD.length() - 1)).getBytes("ISO-8859-1"));
        near.write("后".getBytes("UTF-8"));
        byte[] nearBytes = near.toByteArray();
        Collect c9 = parseTrickle(new Body(BD).file("file", "n.bin", nearBytes).done(), BD, 2);
        check("段体里出现分隔符前缀（差一个字符）→ 原样保留",
                Arrays.equals(nearBytes, c9.files.get("n.bin")));

        // 段体末尾正好是部分分隔符 —— 滑动窗口最容易在这里多吐或少吐字节
        byte[] tail = ("数据结尾\r\n--" + BD.substring(0, 3)).getBytes("ISO-8859-1");
        Collect c10 = parseTrickle(new Body(BD).file("file", "t2.bin", tail).done(), BD, 2);
        check("段体末尾就是部分分隔符 → 原样保留（不多吐也不少吐）",
                Arrays.equals(tail, c10.files.get("t2.bin")));

        System.out.println("\n── 5. 收尾与截断 ──");

        Collect c11 = parse(new Body(BD).file("file", "end1.bin", a).doneNoCrlf(), BD);
        check("结束分隔符带 --（没有尾随 CRLF）→ 正常解析",
                Arrays.equals(a, c11.files.get("end1.bin")));
        check("结束分隔符 + CRLF → 正常解析（与上一条成对）",
                Arrays.equals(a, parse(new Body(BD).file("file", "end2.bin", a).done(), BD)
                        .files.get("end2.bin")));

        boolean threw = false;
        try {
            byte[] cut = new Body(BD).file("file", "x.bin", a).done();
            parse(Arrays.copyOf(cut, cut.length - 6), BD);   // 砍掉收尾
        } catch (IOException e) {
            threw = true;
        }
        check("报文被截断（分隔符都找不到）→ 抛 IOException（不能假装成功）", threw);

        boolean threw2 = false;
        try {
            MultipartLite.parse(new ByteArrayInputStream(new byte[0]), null, new Collect());
        } catch (IOException e) {
            threw2 = true;
        }
        check("boundary 为 null → 抛 IOException", threw2);

        System.out.println("\n── 6. Sink 的容错：「空间不足」与「写盘失败」两条真实路径 ──");
        System.out.println("   这两条会真的发生：多文件时第一个把盘写满、或老 eMMC 写坏一截。");
        System.out.println("   关键不是那一个文件怎么样，而是**后面的文件不能跟着遭殃**。");

        Body b12 = new Body(BD).file("file", "skip.bin", big)
                .file("file", "after.bin", a);
        byte[] raw12 = b12.done();
        Collect c12 = new Collect();
        c12.skip.add("skip.bin");
        MultipartLite.parse(new ByteArrayInputStream(raw12), BD, c12);
        check("begin 返回 null（跳过）→ 该文件不落盘", !c12.files.containsKey("skip.bin"));
        check("跳过的段体被读干净 → 后面的文件照样完整",
                Arrays.equals(a, c12.files.get("after.bin")),
                "拿到 " + c12.files.keySet());

        Collect c13 = new Collect();
        c13.failAtSeq = 1;
        MultipartLite.parse(new ByteArrayInputStream(raw12), BD, c13);
        check("begin 抛 IOException（写盘失败）→ end 收到失败原因",
                c13.ends.get(0).endsWith("|fail"), "ends=" + c13.ends);
        check("写坏一个不影响后面 → 后一个文件仍然完整",
                Arrays.equals(a, c13.files.get("after.bin")),
                "拿到 " + c13.files.keySet());

        byte[] empty = new byte[0];
        Collect c14 = parse(new Body(BD).file("file", "empty.bin", empty).done(), BD);
        check("零字节文件 → 成功落盘 0 字节（不是失败）",
                c14.files.containsKey("empty.bin") && c14.files.get("empty.bin").length == 0
                        && c14.ends.get(0).endsWith("|0|ok"),
                "ends=" + c14.ends);

        System.out.println("\n── 7. 文件名规整（这个接口没有鉴权，名字完全由请求方给）──");

        check("路径穿越：../../data/data/…/webview.db → 只留最后一段",
                "webview.db".equals(LocalStore.sanitize(
                        "../../data/data/com.juping.cast/databases/webview.db")));
        check("Windows 反斜杠同样挡住",
                "win.ini".equals(LocalStore.sanitize("..\\..\\windows\\win.ini")));
        check("中文名原样保留（最基本的要求）",
                "春节联欢晚会.mp4".equals(LocalStore.sanitize("春节联欢晚会.mp4")));
        check("目录型名字只取最后一段（浏览器选文件夹时会给这种）",
                "片子.mp4".equals(LocalStore.sanitize("sub/dir/深/片子.mp4")));
        check("开头的点被剥掉（不造隐藏文件）",
                "bashrc".equals(LocalStore.sanitize(".bashrc")));
        check("空 / 全是点 / null → 拒绝（返回 null）",
                LocalStore.sanitize("") == null && LocalStore.sanitize("....") == null
                        && LocalStore.sanitize(null) == null);
        check("文件系统敏感字符换成下划线",
                "a_b_c.mp4".equals(LocalStore.sanitize("a?b*c.mp4")),
                "得到 " + LocalStore.sanitize("a?b*c.mp4"));

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('长');
        }
        longName.append(".mp4");
        String fixed = LocalStore.sanitize(longName.toString());
        check("超长名截中间但保住扩展名（截尾巴会把 .mp4 切掉，文件就放不了）",
                fixed != null && fixed.length() <= 120 && fixed.endsWith(".mp4"),
                "得到 " + (fixed == null ? "null" : fixed.length() + " 字符，结尾 "
                        + fixed.substring(Math.max(0, fixed.length() - 6))));

        System.out.println();
        System.out.println("网页逻辑：" + passed + " / " + total + " 通过");
        if (!FAILS.isEmpty()) {
            System.out.println("失败项：");
            for (String f : FAILS) {
                System.out.println("  · " + f);
            }
            System.exit(1);
        }
    }
}