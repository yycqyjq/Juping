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
 * 先把话说准：这里说的是 {@code XmlPullParser}（流式，开销很小），
 * **不是** {@code DocumentBuilderFactory}。后者确实会在 Dalvik 上为一次
 * 几百字节的解析拉起整套 SAX/DOM 基础设施，但流式解析器没有这个问题 ——
 * 所以「0.6GB 设备上性能不够」**不是**本类不用它的理由。
 * （这段注释曾经就是这么写的，理由是错的，2026-10-03 改正。）
 *
 * 真正的理由：换成 {@code XmlPullParser} 会引入 {@code android.*} 依赖。
 * 而本文件被 {@code tools/protocol-test/run.sh} 用 {@code javac} 直接编进
 * 桌面测试，{@code ProtocolTestServer} 里有一组断言挂在协议闸门上
 * （实体只转义一次、{@code dc:title} 与无前缀写法都认、封面多档位择优……）。
 * 一旦绑死 Android，这些断言就只能上真机或模拟器才跑得了 ——
 * 而它们值钱的地方恰恰是「不用真机，{@code javac} 一编就能红」。
 *
 * 元数据的结构是**控制点生成的、高度规整**的（就那么几个元素），
 * 用正则抠足够可靠；畸形 XML 的容错不是本类的目标。
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
     * 取专辑名（{@code upnp:album}）。取不到返回空串。
     *
     * <p>本轮界面**不用**它（信息已经够），但解析器一并补齐 —— 与
     * {@link #title}/{@link #artist} 同构，将来若要在界面上加一行专辑名，
     * 不必再动解析器。
     */
    public static String album(String metadata) {
        return element(metadata, "album");
    }

    /**
     * 封面尺寸档位的择优顺序。
     *
     * <p>DIDL-Lite 里封面地址（{@code upnp:albumArtURI}）常带
     * {@code dlna:profileID} 属性，同一个封面会以多个尺寸各出现一次
     * （{@code JPEG_TN} / {@code JPEG_SM} / {@code JPEG_MED} / {@code JPEG_LRG}）。
     * 界面上的封面框约 220dp，取「中/大」这一档刚好够用。
     *
     * <p><b>为什么不无脑取最大</b>：0.6GB 的设备上，拉一张几 MB 的大图再降采样，
     * 是纯浪费带宽与内存。{@code MED} 优先，{@code LRG} 次之，缩略图排最后。
     */
    private static final String[] ART_PROFILES = {"MED", "LRG", "SM", "TN"};

    /**
     * 取封面地址（{@code upnp:albumArtURI}）。取不到返回空串。
     *
     * <p>多尺寸时按 {@link #ART_PROFILES} 择优；没有档位信息就取**第一个**
     * 非空地址。只认 {@code http://} / {@code https://} 开头的绝对地址 ——
     * 相对地址的基准（控制点主机）我们没有可靠来源，一律**视为不可用**
     * （返回空串，界面据此退回 {@code ic_music} 图标）。
     *
     * <p>返回空串是「没有封面」的唯一信号，界面靠它决定降级 ——
     * 所以这里**不返回 null、不抛异常**。
     */
    public static String albumArtUri(String metadata) {
        if (metadata == null || metadata.length() == 0) {
            return "";
        }
        // 带属性捕获：需要读 dlna:profileID 才能择优
        Pattern p = Pattern.compile(
                "<(?:\\w+:)?albumArtURI(\\s[^>]*)?>(.*?)</(?:\\w+:)?albumArtURI>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(metadata);
        String first = "";
        String best = "";
        int bestRank = ART_PROFILES.length;
        while (m.find()) {
            String attrs = m.group(1) == null ? "" : m.group(1);
            String url = unescape(m.group(2).trim());
            if (!isHttpUrl(url)) {
                continue;
            }
            if (first.length() == 0) {
                first = url;
            }
            int rank = profileRank(attrs);
            if (rank < bestRank) {
                bestRank = rank;
                best = url;
            }
        }
        // 一个档位都没认出来（best 仍为空）时退回第一个可用地址
        return best.length() > 0 ? best : first;
    }

    /** 档位在 {@link #ART_PROFILES} 里的名次；没有档位信息返回「比所有已知档位都差」 */
    private static int profileRank(String attrs) {
        String up = attrs.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < ART_PROFILES.length; i++) {
            if (up.indexOf(ART_PROFILES[i]) >= 0) {
                return i;
            }
        }
        return ART_PROFILES.length;
    }

    /** 只认绝对 HTTP(S) 地址；相对地址的基准我们拿不到，视为不可用 */
    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * 歌词的候选元素，按「可能性」从高到低。
     *
     * <p><b>DLNA 没有标准歌词字段</b>，所以这一路是**机会性**的：控制点若把歌词
     * 塞进某个扩展元素或 {@code dc:description}，我们就显示；否则什么都不显示
     * （不显示 = 现状，对界面零影响）。不引入任何外部歌词服务。
     */
    private static final String[] LYRICS_ELEMENTS = {"lyrics", "description", "longDescription"};

    /**
     * 取歌词（机会性）。依次尝试 {@link #LYRICS_ELEMENTS} 里的候选元素，
     * 返回第一个非空；都取不到返回空串。
     *
     * <p>绝大多数控制点不送歌词 —— 这是**预期内**的结果，不是错误。
     * 返回空串让界面把整行 {@code GONE} 掉即可。
     */
    public static String lyrics(String metadata) {
        for (String name : LYRICS_ELEMENTS) {
            String v = element(metadata, name);
            if (v.length() > 0) {
                return v;
            }
        }
        return "";
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
