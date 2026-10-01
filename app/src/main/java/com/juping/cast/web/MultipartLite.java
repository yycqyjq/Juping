package com.juping.cast.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

/**
 * {@code multipart/form-data} 的**流式**解析器。
 *
 * <p><b>为什么必须流式</b>：这台盒子只有 0.6GB 内存，而用户会从手机传一部 GB 级的电影。
 * 任何"先把 body 读进内存再解析"的写法 —— {@code readAllBytes}、{@code ByteArrayOutputStream}，
 * 甚至只是照着 {@code Content-Length} 分配一个数组 —— 都会当场 {@code OutOfMemoryError}，
 * 而且崩的是**整个进程**，连 SSDP 一起带走（表现为"电视从手机的投屏列表里消失了"）。
 * 所以这里从头到尾只用一块固定大小的缓冲区，**占用与文件大小无关**。
 *
 * <p><b>为什么单独成类、纯 Java、零 Android 依赖</b>：和 {@code PlaybackPolicy} 是同一个道理 ——
 * 埋在 Android 类里的逻辑没法在桌面上验证，抽出来才能进闸门做断言。上传解析是本功能
 * 最该被断言守住的部分（分隔符跨缓冲区、中文文件名、最后一个分段没有尾随 CRLF）。
 *
 * <p>用法：{@link #parse(InputStream, String, Sink)}，由 {@link Sink} 决定每一段往哪里写。
 */
public final class MultipartLite {

    /** 读缓冲。64KB —— 再小则系统调用次数陡增，再大则白占内存（盒子只有 0.6GB）。 */
    private static final int BUF_SIZE = 64 * 1024;

    /** 单个分段头（Content-Disposition 那几行）的长度上限，超过即认定报文畸形。 */
    private static final int HEAD_LIMIT = 8 * 1024;

    /** 普通表单字段（非文件）的长度上限。文件内容不走这里，所以给小值就够。 */
    private static final int FIELD_LIMIT = 8 * 1024;

    /** 丢弃段体时用的出口，等价于 /dev/null */
    private static final OutputStream DEV_NULL = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    /** 每一段的去处，由调用方实现 */
    public interface Sink {
        /**
         * 遇到一个文件段。
         *
         * @return 内容要写进去的流；返回 {@code null} 表示**跳过这一段**
         *         （会被读干净后丢掉，不影响后续分段）
         */
        OutputStream begin(String fieldName, String fileName) throws IOException;

        /**
         * 文件段结束。
         *
         * @param bytes  实际落盘字节数（跳过或失败时为 0）
         * @param failed {@code null} 表示存好了；否则是给用户看的原因
         */
        void end(String fieldName, String fileName, long bytes, String failed);

        /** 普通表单字段（{@code <input name=x>} 之类） */
        void field(String name, String value);
    }

    private MultipartLite() {
    }

    /**
     * 从 {@code Content-Type} 里取出 boundary。
     *
     * @return boundary 串；不是 multipart 或没带 boundary 时返回 {@code null}
     */
    public static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        int i = lower.indexOf("multipart/");
        if (i < 0) {
            return null;
        }
        int b = lower.indexOf("boundary=", i);
        if (b < 0) {
            return null;
        }
        String v = contentType.substring(b + "boundary=".length()).trim();
        // boundary 可以用双引号括起来（含空格或分号时必须括）—— RFC 2046
        if (v.length() > 0 && v.charAt(0) == '"') {
            int e = v.indexOf('"', 1);
            v = (e < 0) ? v.substring(1) : v.substring(1, e);
        } else {
            int e = v.indexOf(';');
            if (e >= 0) {
                v = v.substring(0, e);
            }
            v = v.trim();
        }
        return v.length() == 0 ? null : v;
    }

    /**
     * 解析整个 multipart 体。
     *
     * <p>返回时 body 已经读完（走到最后的结束分隔符）。中途抛 {@code IOException}
     * 表示报文畸形或连接断了 —— 此时已经落盘的部分**不删**：能存下多少算多少，
     * 总比让用户白等一趟强。
     */
    public static void parse(InputStream in, String boundary, Sink sink) throws IOException {
        if (boundary == null || boundary.length() == 0) {
            throw new IOException("multipart 缺少 boundary");
        }
        // 分隔符是 "\r\n--" + boundary，但**开头的第一道前面没有 CRLF**。
        // 为了让它和后面每一道走完全相同的匹配逻辑，先往缓冲区里塞一个合成的 CRLF ——
        // 否则就得为"第一道分隔符"单独写一套查找代码，那种分支正是漏测的来源。
        PartStream ps = new PartStream(in, ("\r\n--" + boundary).getBytes("ISO-8859-1"));
        ps.seedCrlf();

        String[] disposition = new String[2];
        boolean finished = false;
        while (!finished) {
            ps.expectDelimiter();
            finished = ps.lastDelimiterClosed();
            if (finished) {
                break;
            }
            ps.readPartHeader(disposition);
            String fieldName = disposition[0];
            String fileName = disposition[1];

            if (fileName == null) {
                sink.field(fieldName, ps.readField());
                continue;
            }

            OutputStream os = null;
            String failed = null;
            long written = 0;
            try {
                os = sink.begin(fieldName, fileName);
                written = (os == null) ? drain(ps) : copy(ps, os);
            } catch (IOException e) {
                // 段体**必须读干净**，哪怕这一段存不下来：不读的话分隔符就找不到了，
                // 后面所有分段跟着错位（一个文件写坏会连累它后面的每一个文件）。
                failed = "写入失败：" + e.getMessage();
                drain(ps);
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            sink.end(fieldName, fileName, written, failed);
        }
    }

    /** 把段体原样搬到目标流。缓冲区固定 64KB —— 与文件大小无关。 */
    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[BUF_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(b, 0, b.length)) > 0) {
            out.write(b, 0, n);
            total += n;
        }
        return total;
    }

    /** 读干净但丢掉 */
    private static long drain(InputStream in) throws IOException {
        return copy(in, DEV_NULL);
    }

    /**
     * 段体视图：读到分隔符就"到此为止"（对上层表现为 EOF）。
     *
     * <p>这是整个流式解析的关键 —— {@link Sink} 拿到的是一个普通的
     * {@link InputStream}，它只管往里读到 -1，完全不需要知道分隔符的存在。
     */
    private static final class PartStream extends InputStream {

        private final InputStream in;
        private final byte[] delim;
        private final byte[] buf = new byte[BUF_SIZE];
        /** 待消费数据的区间 [start, end) */
        private int start;
        private int end;
        /** 本段体结束的位置（分隔符的起始下标）；-1 表示还没遇到 */
        private int stop = -1;
        /** 底层流已经读完（还没遇到分隔符就到头 = 报文被截断） */
        private boolean srcEof;
        /** 本段已经读到分隔符 */
        private boolean bodyEof;
        /** 最后一道分隔符以 "--" 结尾 —— 报文到此结束 */
        private boolean closed;

        PartStream(InputStream in, byte[] delim) {
            this.in = in;
            this.delim = delim;
        }

        /** 开头那道分隔符前没有 CRLF，补一个合成前缀（理由见 parse 里的注释） */
        void seedCrlf() {
            buf[0] = '\r';
            buf[1] = '\n';
            end = 2;
        }

        // ---------------------------------------------------------- 段体读取

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] out, int off, int len) throws IOException {
            if (len == 0 || bodyEof) {
                return -1;
            }
            while (true) {
                if (stop < 0) {
                    stop = indexOf(buf, start, end, delim);
                }
                // 分隔符可能正好跨在两次 read 的边界上，所以最后 (delim.length-1) 个字节
                // **不能**当数据交出去 —— 交出去下次就再也匹配不到分隔符，
                // 表现是文件末尾多出一截 "--boundary" 的字节，视频播到最后花屏。
                int safe = (stop >= 0) ? stop : end - (delim.length - 1);
                if (safe > start) {
                    int n = Math.min(safe - start, len);
                    System.arraycopy(buf, start, out, off, n);
                    start += n;
                    return n;
                }
                if (stop >= 0) {
                    bodyEof = true;
                    return -1;
                }
                if (srcEof) {
                    throw new IOException("multipart 段体还没遇到分隔符，请求就断了");
                }
                fill();
            }
        }

        // -------------------------------------------------------- 解析器内部

        /** 前进到下一道分隔符并吃掉它（连同前面的 preamble） */
        void expectDelimiter() throws IOException {
            while (stop < 0) {
                if (srcEof) {
                    throw new IOException("multipart 里找不到分隔符");
                }
                fill();
                stop = indexOf(buf, start, end, delim);
            }
            // 分隔符之前若还有内容，那是 RFC 7578 允许的 preamble（浏览器和 curl 都不发，
            // 但规范允许存在）。它没有任何语义，丢掉即可 —— 这也顺带让"第一道分隔符"
            // 和后面每一道共用同一段代码。
            start = stop + delim.length;
            stop = -1;

            byte[] two = new byte[2];
            if (readRaw(two, 0, 2) != 2) {
                throw new IOException("multipart 分隔符后面请求就断了");
            }
            if (two[0] == '-' && two[1] == '-') {
                closed = true;
            } else if (two[0] == '\r' && two[1] == '\n') {
                closed = false;
            } else {
                throw new IOException("multipart 分隔符后面既不是 -- 也不是 CRLF");
            }
        }

        boolean lastDelimiterClosed() {
            return closed;
        }

        /** 读一段分段头，取出 {@code name} 与 {@code filename}（前者进 out[0]，后者进 out[1]） */
        void readPartHeader(String[] out) throws IOException {
            out[0] = null;
            out[1] = null;
            StringBuilder line = new StringBuilder();
            int total = 0;
            while (true) {
                int c = readRawByte();
                if (c < 0) {
                    throw new IOException("multipart 分段头读到末尾都没结束");
                }
                if (++total > HEAD_LIMIT) {
                    throw new IOException("multipart 分段头超长");
                }
                if (c != '\n') {
                    // 头部按 ISO-8859-1 逐字节累积：一个字节一个字符，无损。
                    // 真正的编码还原留到取 filename 时再做 —— 那里才知道该按 UTF-8
                    // 还是按 RFC 5987 的百分号转义去解。
                    line.append((char) c);
                    continue;
                }
                String s = line.toString();
                line.setLength(0);
                if (s.endsWith("\r")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (s.length() == 0) {
                    return;
                }
                parseDisposition(s, out);
            }
        }

        /** 读一个普通表单字段的值 */
        String readField() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            byte[] b = new byte[1024];
            int n;
            while ((n = read(b, 0, b.length)) > 0) {
                if (bos.size() + n > FIELD_LIMIT) {
                    throw new IOException("multipart 表单字段超长");
                }
                bos.write(b, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        }

        /** 从缓冲里读，不够就直接从底层流读。**只用在分隔符之外**（头部与分隔符本身）。 */
        int readRaw(byte[] dst, int off, int len) throws IOException {
            compact();
            int got = 0;
            int have = end - start;
            if (have > 0) {
                int n = Math.min(have, len);
                System.arraycopy(buf, start, dst, off, n);
                start += n;
                off += n;
                len -= n;
                got += n;
            }
            while (len > 0) {
                int n = in.read(dst, off, len);
                if (n < 0) {
                    break;
                }
                off += n;
                len -= n;
                got += n;
            }
            return got;
        }

        private int readRawByte() throws IOException {
            byte[] one = new byte[1];
            return readRaw(one, 0, 1) == 1 ? (one[0] & 0xFF) : -1;
        }

        /** 把已消费的前缀挪走，腾出空间继续读 */
        private void compact() {
            if (start > 0) {
                int n = end - start;
                System.arraycopy(buf, start, buf, 0, n);
                end = n;
                if (stop >= 0) {
                    stop -= start;
                }
                start = 0;
            }
        }

        /** 再读一段进缓冲。只在还没遇到分隔符时调用。 */
        private void fill() throws IOException {
            compact();
            if (end == buf.length) {
                // 缓冲塞满了都找不到分隔符 —— 只可能是 boundary 比缓冲区还长（畸形请求）
                throw new IOException("multipart 分隔符超出缓冲区");
            }
            int n = in.read(buf, end, buf.length - end);
            if (n > 0) {
                end += n;
            } else {
                srcEof = true;
            }
        }

        private static int indexOf(byte[] hay, int from, int to, byte[] needle) {
            int last = to - needle.length;
            outer:
            for (int i = from; i <= last; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (hay[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        // ------------------------------------------------------------ 头解析

        private static void parseDisposition(String line, String[] out) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("content-disposition:")) {
                return;
            }
            String v = line.substring("content-disposition:".length()).trim();
            // RFC 5987/6266：带非 ASCII 文件名时浏览器发 filename*=UTF-8''%E4%B8%AD.mp4，
            // 但部分客户端（以及部分场景下的同一浏览器）仍然只发原始 UTF-8 的 filename=。
            // **两种都得认** —— 只认一种的话，另一半用户的中文片名会变成乱码或下划线。
            String ext = attr(v, "filename*");
            if (ext != null) {
                out[1] = decodeExtValue(ext);
            } else {
                String raw = attr(v, "filename");
                if (raw != null) {
                    out[1] = decodeRawName(raw);
                }
            }
            String name = attr(v, "name");
            if (name != null) {
                out[0] = decodeRawName(name);
            }
        }

        /** 取 {@code key=value} 里的 value；带引号则去掉引号。找不到返回 null。 */
        private static String attr(String v, String key) {
            String lower = v.toLowerCase(java.util.Locale.ROOT);
            String k = key.toLowerCase(java.util.Locale.ROOT) + "=";
            int i = 0;
            while (true) {
                i = lower.indexOf(k, i);
                if (i < 0) {
                    return null;
                }
                // 前一个字符必须是参数分隔符。否则 "filename=" 会被当成 "name=" 的匹配 ——
                // 那个匹配拿到的值是文件名，会把字段名污染成文件名。
                if (i == 0 || v.charAt(i - 1) == ';' || v.charAt(i - 1) == ' ') {
                    break;
                }
                i += k.length();
            }
            int s = i + k.length();
            if (s < v.length() && v.charAt(s) == '"') {
                int e = v.indexOf('"', s + 1);
                return (e < 0) ? null : v.substring(s + 1, e);
            }
            int e = v.indexOf(';', s);
            return v.substring(s, (e < 0) ? v.length() : e).trim();
        }

        /**
         * 头部是按 ISO-8859-1 逐字节累积的，这里把它还原成字节再按 UTF-8 解 ——
         * 于是"原始 UTF-8 的 filename"能正确还原，纯 ASCII 名字则是恒等变换。
         */
        private static String decodeRawName(String s) {
            try {
                return new String(s.getBytes("ISO-8859-1"), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return s;
            }
        }

        /** 解 RFC 5987 的 {@code UTF-8''%E4%B8%AD.mp4} */
        private static String decodeExtValue(String s) {
            int q1 = s.indexOf('\'');
            if (q1 < 0) {
                return decodeRawName(s);
            }
            int q2 = s.indexOf('\'', q1 + 1);
            if (q2 < 0) {
                return decodeRawName(s);
            }
            String charset = s.substring(0, q1);
            String pct = s.substring(q2 + 1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(pct.length());
            for (int i = 0; i < pct.length(); i++) {
                char c = pct.charAt(i);
                if (c == '%' && i + 2 < pct.length()) {
                    int hi = hex(pct.charAt(i + 1));
                    int lo = hex(pct.charAt(i + 2));
                    if (hi >= 0 && lo >= 0) {
                        bos.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                bos.write(c & 0xFF);
            }
            try {
                return new String(bos.toByteArray(), charset.length() == 0 ? "UTF-8" : charset);
            } catch (UnsupportedEncodingException e) {
                return decodeRawName(s);
            }
        }

        private static int hex(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}