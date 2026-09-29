package com.juping.cast.dlna;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DIDL-Lite 元数据的最小解析器。
 *
 * <h3>它解决什么问题</h3>
 * 控制点推流时会在 {@code CurrentURIMetaData} 里带一段 DIDL-Lite，形如：
 * <pre>{@code
 * <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" ...>
 *   <item id="..." parentID="..." restricted="1">
 *     <dc:title>夜曲</dc:title>
 *     <upnp:artist>周杰伦</upnp:artist>
 *     <upnp:class>object.item.audioItem.musicTrack</upnp:class>
 *   </item>
 * </DIDL-Lite>
 * }</pre>
 * 而在此之前，这份元数据**只被用来猜"音频还是视频"**（一个
 * {@code contains("object.item.audioItem")}），标题和艺术家全丢了。
 * 结果界面上「正在播放」显示的是从 URL 里截出来的文件名 ——
 * 用户看到的是 {@code 6a3f9c2b.mp3} 而不是「夜曲」。
 *
 * <h3>为什么不上 XML 解析器</h3>
 * 目标设备只有 0.6GB 内存，而 {@code DocumentBuilderFactory} 在 Dalvik 上
 * 要为一次几百字节的解析拉起整套 SAX/DOM 基础设施 —— 代价和收益不成比例。
 * 元数据的结构是**控制点生成的、高度规整**的（就那么几个元素），
 * 用正则抠足够可靠。
 *
 * <p>另一个好处：这个类是纯 Java，没有任何 Android 依赖，
 * 所以协议测试可以把它编进去、直接断言解析结果。
 */
public final class DidlLite {

    private DidlLite() {
    }

    /**
     * XML 实体。三种写法都要认：
     * <ul>
     *   <li>命名实体：{@code &amp;} {@code &lt;} {@code &gt;} {@code &quot;} {@code &apos;}</li>
     *   <li>十进制数字实体：{@code &#39;}</li>
     *   <li>十六进制数字实体：{@code &#x27;}</li>
     * </ul>
     * 数字实体不是可有可无的：DIDL 里的单引号**几乎都是** {@code &#39;}，
     * 而歌名带单引号非常常见（{@code Don't Stop}）。
     */
    private static final Pattern ENTITY =
            Pattern.compile("&(#\\d+|#[xX][0-9a-fA-F]+|\\w+);");

    /** 取标题（{@code dc:title}）。取不到返回空串，不返回 null。 */
    public static String title(String metadata) {
        return element(metadata, "title");
    }

    /** 取艺术家（{@code upnp:artist}）。取不到返回空串。 */
    public static String artist(String metadata) {
        return element(metadata, "artist");
    }

    /**
     * 取内容类型（{@code upnp:class}），形如
     * {@code object.item.audioItem.musicTrack}。取不到返回空串。
     */
    public static String upnpClass(String metadata) {
        return element(metadata, "class");
    }

    /**
     * 抠出某个元素的文本内容。
     *
     * <p>命名空间前缀用 {@code (?:\w+:)?} 吃掉 —— 同一个字段，控制点可能写
     * {@code dc:title}、{@code upnp:title}，也可能干脆不带前缀（{@code title}）。
     * 只认一种写法的话，换个手机就解析不出来了，而且**不会报错**，
     * 只是界面上悄悄变回文件名。
     *
     * <p>{@code DOTALL} 是必要的：标题里可能带换行（有些控制点会格式化输出）。
     * 非贪婪匹配是为了取到**第一个**完整的元素 —— 元数据里可能有多个同名元素
     * （比如一张专辑的每首曲子），取第一个即当前曲目。
     */
    static String element(String metadata, String localName) {
        if (metadata == null || metadata.length() == 0) {
            return "";
        }
        Pattern p = Pattern.compile(
                "<(?:\\w+:)?" + localName + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + localName + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(metadata);
        if (!m.find()) {
            return "";
        }
        return unescape(m.group(1).trim());
    }

    /**
     * 还原 XML 实体。
     *
     * <p><b>为什么必须做</b>：元数据是 XML，{@code &} 在 XML 里必须写成
     * {@code &amp;}。不还原的话，界面会显示「Tom &amp; Jerry」这种
     * 明显不对劲的文本 —— 而用户完全没法理解为什么。
     *
     * <p><b>顺序陷阱</b>：{@code &amp;} 不能先替换。先换它的话，
     * 原文里的 {@code &amp;lt;}（表示字面量 "&lt;" 而不是 "<"）
     * 会先变成 {@code &lt;}、再被第二次替换成 {@code <} —— **替换了两次**。
     * 用一次扫描的 {@code Matcher} 就天然避开了这个问题：
     * 每个实体只被处理一次，替换结果不会再进入下一轮扫描。
     */
    static String unescape(String s) {
        if (s == null || s.length() == 0 || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer(s.length());
        while (m.find()) {
            String replacement = expand(m.group(1));
            // quoteReplacement：替换文本里可能含 $ 或 \（歌名里很常见），
            // 不转义的话 appendReplacement 会把它当成组引用而抛异常。
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 单个实体体（分号之间的那一段）→ 真实字符。认不出来就原样保留 */
    private static String expand(String body) {
        if (body.length() == 0) {
            return "";
        }
        char c0 = body.charAt(0);
        if (c0 == '#') {
            int codePoint = -1;
            try {
                if (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X')) {
                    codePoint = Integer.parseInt(body.substring(2), 16);
                } else {
                    codePoint = Integer.parseInt(body.substring(1), 10);
                }
            } catch (NumberFormatException e) {
                return "&" + body + ";";
            }
            try {
                return new String(Character.toChars(codePoint));
            } catch (IllegalArgumentException e) {
                // 非法码点（负数、超出 Unicode 范围）—— 原样保留，
                // 总比抛出去把整条投屏流程掀翻好
                return "&" + body + ";";
            }
        }
        // 只认 XML 规范预定义的这五个。其余（比如 HTML 的 &nbsp;）
        // 在 DIDL 里不合法，原样保留能让问题暴露出来而不是被悄悄吃掉。
        if ("amp".equals(body)) {
            return "&";
        }
        if ("lt".equals(body)) {
            return "<";
        }
        if ("gt".equals(body)) {
            return ">";
        }
        if ("quot".equals(body)) {
            return "\"";
        }
        if ("apos".equals(body)) {
            return "'";
        }
        return "&" + body + ";";
    }
}
