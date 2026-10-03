package com.juping.cast.dlna;

/**
 * DLNA 设备描述（device.xml）与三份 SCPD 模板 —— 控制点了解「这台设备是什么、
 * 能调哪些指令」的**唯一来源**。
 *
 * <h3>为什么从 {@link UpnpHttpServer} 里摘出来</h3>
 * 这些内容全是**纯静态数据 + 字符串拼接**，与 HTTP 传输、SOAP 分发毫无关系，
 * 却占了 UpnpHttpServer 近 300 行。更关键的是：原来它们**无法在桌面上被真正校验** ——
 * UpnpHttpServer 依赖 Android（Handler / 线程池 / WebEndpoints），桌面闸门只能拿
 * 正则去"看某个字符串在不在"。摘出来之后本类**零 Android 依赖**，
 * PolicyTest 可以真的用 XML 解析器把三份 SCPD 与 device.xml 读一遍并逐条校验：
 * <ul>
 *   <li>SCPD 的 actionList 与 {@link #knownActions()} **集合相等**
 *       —— 这就是那条红线「声明了就要给得出，给不出就别声明」；</li>
 *   <li>每个 action 的 {@code relatedStateVariable} 都指向本 SCPD 里**真实存在**的
 *       stateVariable —— 历史 bug 正是这里：{@code InstanceID} 与
 *       {@code A_ARG_TYPE_InstanceID} 一字之差，严格的控制点**整份解析失败**；</li>
 *   <li>device.xml 的子元素顺序符合 device-1-0 schema（顺序错同样整份失败）。</li>
 * </ul>
 *
 * <h3>本类不持有任何状态</h3>
 * 描述是 {@code (uuid, 名字, 版本, 端口, IP, 图标)} 的**纯函数** —— 这正是"能断言"
 * 的前提。HTTP 层只负责把它拼进响应。
 *
 * <p>服务清单只有一个来源：{@link #SERVICES}。device.xml 声明的服务、能返回 SCPD 的
 * 服务、SUBSCRIBE 接受的服务名，三处都从它派生 —— 原来手写三遍，改一处漏一处。
 */
public final class DlnaDescription {

    private DlnaDescription() {
    }

    /**
     * device.xml 里声明的三个服务。**顺序即 device.xml 里 serviceList 的顺序**。
     *
     * <p>它同时是：{@link #isKnownService(String)} 的白名单、
     * {@link #scpdFor(String)} 的键集合（配 {@code SCPD_BY_SERVICE} 的下标）、
     * device.xml 里 {@code <service>} 的生成源。
     */
    public static final String[] SERVICES = {
            "AVTransport", "ConnectionManager", "RenderingControl",
    };

    /**
     * MediaRenderer 是所有 DLNA 控制点都会查找的标准设备类型。
     *
     * <p>它有两个消费方，**必须同源**：SSDP 的应答（{@code ST} / {@code USN}）与
     * device.xml 的 {@code <deviceType>}。两边写不一样时，控制点搜到设备却认为
     * "这台设备不是我要的渲染器" —— 表现为搜得到、投不进去。
     *
     * <p>放在本类（而不是 {@link SsdpResponder}）是因为它是**设备描述的一部分**，
     * 而本类零 Android 依赖、可桌面断言；{@link SsdpResponder} 反过来引用它。
     */
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    // ------------------------------------------------------------------ 图标

    /**
     * 设备图标的路径。
     *
     * <p>只服务**一张**图，尺寸以 {@code UpnpHttpServer#setIcon(byte[], int, int)} 传进来的实际像素为准。
     *
     * <p>为什么不按密度声明四档（48/72/96/144）：{@code R.drawable.ic_launcher}
     * 在运行时**只会解析成当前屏幕密度的那一张** —— 四档拿到的是同一个 Bitmap。
     * 声明四个尺寸就是在撒谎，而控制点会照声明去挑，挑中的那张尺寸对不上。
     */
    public static final String ICON_PATH = "/upnp/icon.png";
    /** 项目主页。厂商 URL 与型号 URL 都指向它 —— 这是这台设备真正的"出处" */
    private static final String PROJECT_URL = "https://github.com/yycqyjq/Juping";
    /**
     * DLNA 设备类别声明。
     *
     * <p>{@code DMR-1.50} = Digital Media Renderer，DLNA 1.5 版规范里的渲染器类别。
     *
     * <p>这一条**必须有**。部分控制点（较新的国产投屏 SDK 尤其）先看这个标记，
     * 认不出就不把设备列进投屏列表 —— 表现为「SSDP 明明应答了，列表里却没有」，
     * 而其余字段写得再全也没用。缺了它是最容易被忽略、后果又最彻底的一种缺。
     *
     * <p>只声明 {@code DMR-1.50}，**不声明** {@code M-DMR-1.50}：后者是
     * DLNA Mobile 的类别，声明了会让控制点按移动设备的规则来对待我们
     * （比如假定有触摸屏、假定省电策略不同）。不是移动设备就别领那个标记。
     */
    private static final String DLNA_DOC = "DMR-1.50";
    // ------------------------------------------------- 设备描述（DDD）

    /**
     * 设备描述（DDD）—— 控制点了解"这台设备是什么"的唯一来源。
     *
     * <p><b>元素顺序不是随便排的。</b>UPnP 的 device-1-0 schema 对
     * {@code <device>} 的子元素定死了顺序，严格按 schema 校验的控制点
     * （部分嵌入式协议栈）会因为顺序错而**整份描述解析失败** —— 不是
     * "少读一个字段"，是这台设备在它眼里不存在。顺序取自 UDA 1.0 的
     * device-1-0 schema：
     * <pre>
     *   deviceType, friendlyName, manufacturer, manufacturerURL?, modelDescription?,
     *   modelName, modelNumber?, modelURL?, serialNumber?, UDN, UPC?,
     *   iconList?, serviceList?, deviceList?, presentationURL?, (其它命名空间)*
     * </pre>
     * {@code dlna:X_DLNADOC} 属于最后那类"其它命名空间"，所以放最后 ——
     * MiniDLNA 与多数商用 DMR 的实际排法也是如此。
     */
    public static String deviceDescription(String uuid, String friendlyName,
                                           String versionName, int port, String localIp,
                                           byte[] iconPng, int iconWidth, int iconHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n")
                .append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
                .append("  <device>\n")
                .append("    <deviceType>").append(DEVICE_TYPE).append("</deviceType>\n")
                // friendlyName 是用户自己可能改过的（将来若要支持改名），
                // 所以必须转义 —— 一个 & 就能让整份描述变成非法 XML。
                .append("    <friendlyName>").append(escapeXml(friendlyName))
                .append("</friendlyName>\n")
                .append("    <manufacturer>Juping</manufacturer>\n")
                .append("    <manufacturerURL>").append(PROJECT_URL).append("</manufacturerURL>\n")
                .append("    <modelDescription>DLNA/UPnP 投屏接收端</modelDescription>\n")
                .append("    <modelName>Juping Receiver</modelName>\n")
                .append("    <modelNumber>").append(escapeXml(versionName))
                .append("</modelNumber>\n")
                .append("    <modelURL>").append(PROJECT_URL).append("</modelURL>\n")
                // serialNumber 用设备自己的 UDN 值。它本来就是"这台设备在这个
                // 网络里的唯一编号"，而且跨重启稳定（UUID 持久化在 SharedPreferences 里）。
                // 编一个假的流水号没有任何好处 —— 排障时能对上号才有意义。
                .append("    <serialNumber>").append(escapeXml(uuid)).append("</serialNumber>\n")
                .append("    <UDN>uuid:").append(uuid).append("</UDN>\n");
        // UPC：我们不是零售商品，没有 UPC 码。**刻意不写** ——
        // 编一个假码没有任何好处，而 schema 里它是可选的。
        appendIconList(sb, iconPng, iconWidth, iconHeight);
        sb.append("    <serviceList>\n");
        // 服务清单与 SERVICES **同源** —— device.xml 声明了哪些服务、能返回哪些
        // SCPD、SUBSCRIBE 接受哪些服务名，三处永远是同一份列表。
        // 原来这三行手写、isKnownService 里再手写一遍、scpdFor 里第三遍，
        // 改一处漏一处就是"声明了却给不出"。
        for (int i = 0; i < SERVICES.length; i++) {
            sb.append(serviceEntry(SERVICES[i]));
        }
        sb.append("    </serviceList>\n");
        // presentationURL 必须排在 serviceList 之后、dlna 扩展之前（schema 顺序）。
        appendPresentationUrl(sb, localIp, port);
        sb.append("    <dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
                .append(DLNA_DOC).append("</dlna:X_DLNADOC>\n")
                .append("  </device>\n")
                .append("</root>\n");
        return sb.toString();
    }
    /**
     * 有图标才写 iconList。
     *
     * <p>{@code <icon>} 的子元素顺序同样是 schema 定死的：
     * mimetype, width, height, depth, url。
     *
     * <p>{@code depth} 报 32：图标是 PNG RGBA（8 位/通道 × 4 通道），
     * 这是**实测值**，不是照抄别人的 24。控制点一般不校验它，
     * 但既然写了就写真的 —— 这个项目里没有"随手填一个看着合理"的字段。
     */
    private static void appendIconList(StringBuilder sb, byte[] iconPng,
                                       int iconWidth, int iconHeight) {
        if (iconPng == null) {
            return;
        }
        sb.append("    <iconList>\n")
                .append("      <icon>\n")
                .append("        <mimetype>image/png</mimetype>\n")
                .append("        <width>").append(iconWidth).append("</width>\n")
                .append("        <height>").append(iconHeight).append("</height>\n")
                .append("        <depth>32</depth>\n")
                .append("        <url>").append(ICON_PATH).append("</url>\n")
                .append("      </icon>\n")
                .append("    </iconList>\n");
    }
    /**
     * presentationURL：声明设备自带的 Web 界面 —— 就是那张「扫码传文件」的上传页。
     *
     * <p>规范里这个字段只有一个含义：「用浏览器打开这里看设备信息」。批 2 起我们
     * **真的有** Web 界面了，所以按规范声明出来：控制点的设备列表里会多一个
     * 「打开设备页面」按钮，点开就是上传页。
     *
     * <p>原来这里写的是「刻意不声明」，那时是对的 —— {@code "/"} 当时只会把
     * device.xml 本身喂给浏览器（一屏原始 XML），画一个点开是乱码的按钮比没有按钮糟。
     * 现在 {@code "/"} 真的是一张页面了，那条理由随之消失。
     *
     * <p><b>端口必须走传进来的 {@code port}</b>：端口有 fallback（49152 被厂家自带的
     * DLNA 栈占了就往上移），写死首选端口的话，这个按钮会把浏览器指到一个没人
     * 监听的端口上。主机走传进来的 {@code localIp}（与 LOCATION 同源）。
     *
     * <p>拿不到可用地址时**不声明**，同 {@link #appendIconList(StringBuilder, byte[], int, int)} 的纪律：
     * 声明了控制点就会真的去打开它，而 {@code http://0.0.0.0/} 谁也打不开 ——
     * 那比没有按钮糟。
     */
    private static void appendPresentationUrl(StringBuilder sb, String localIp, int port) {
        String ip = localIp;
        if (ip == null || ip.length() == 0 || "0.0.0.0".equals(ip)) {
            return;
        }
        sb.append("    <presentationURL>http://").append(escapeXml(ip))
                .append(':').append(port).append("/</presentationURL>\n");
    }
    /**
     * 一条 service 记录。
     *
     * <p><b>子元素顺序也是 schema 定死的</b>：serviceType → serviceId →
     * SCPDURL → controlURL → eventSubURL。已按 UDA 1.0 的 device-1-0 schema
     * 核对，并与 gmrender-resurrect（成熟 DMR 实现）的实际输出一致。
     *
     * <p>MiniDLNA 用的是 controlURL → eventSubURL → SCPDURL，那是它的历史写法，
     * 多数控制点宽容接受，但没有理由跟着走。
     */
    private static String serviceEntry(String shortName) {
        String type = "urn:schemas-upnp-org:service:" + shortName + ":1";
        return "      <service>\n"
                + "        <serviceType>" + type + "</serviceType>\n"
                + "        <serviceId>urn:upnp-org:serviceId:" + shortName + "</serviceId>\n"
                + "        <SCPDURL>/upnp/" + shortName + ".xml</SCPDURL>\n"
                + "        <controlURL>/upnp/control/" + shortName + "</controlURL>\n"
                + "        <eventSubURL>/upnp/event/" + shortName + "</eventSubURL>\n"
                + "      </service>\n";
    }
    // ------------------------------------------------------------ 服务名

    /**
     * 我们在 device.xml 里声明了这三个服务，SUBSCRIBE 只能指向它们。
     *
     * <p>不校验的话，控制点拼错一个服务名也能拿到 SID，然后永远收不到事件 ——
     * 它只会觉得"设备事件坏了"，而日志里一切正常。宁可当场回 404。
     */
    public static boolean isKnownService(String s) {
        for (int i = 0; i < SERVICES.length; i++) {
            if (SERVICES[i].equals(s)) {
                return true;
            }
        }
        return false;
    }
    /**
     * 某个服务的 SCPD；{@code shortName} 不在 {@link #SERVICES} 里时返回 {@code null}
     * （调用方据此回 404，而不是把一份空 SCPD 发出去）。
     */
    public static String scpdFor(String shortName) {
        for (int i = 0; i < SERVICES.length; i++) {
            if (SERVICES[i].equals(shortName)) {
                return SCPD_BY_SERVICE[i];
            }
        }
        return null;
    }

    // -------------------------------------------------- action 白名单

    /**
     * 能应答的 action 白名单。不在这张表里的按 UPnP 规范回 401 Fault。
     *
     * <p>为什么要有这张表：原来对未知 action 是「回 200 + 空参数」。
     * 后果是控制点发来一个我们根本不认识的指令，却收到一个语法上合法、
     * 语义上毫无意义的响应 —— 手机端只能显示一个笼统的失败，问题无从定位。
     * 规范要求回 401 Invalid Action，这样控制点至少能给出准确的原因。
     *
     * <p>表里除了真正实现的指令，还刻意收了一批「无副作用的探测指令」
     * （GetTransportSettings / GetDeviceCapabilities / ListPresets 等）——
     * 它们不需要真正做什么，但控制点经常会先问一遍。对这些回 401 会让
     * 手机端误判成「这台设备有问题」，所以给它们一个合法的空响应。
     */
    private static final String[] KNOWN_ACTIONS = {
            // AVTransport —— 与 SCPD_AV_TRANSPORT 的 actionList 一一对应。
            // 两边必须同步：SCPD 里没有的 action 控制点不会发（写了也是死的），
            // 而 SCPD 里有、这张表里没有的会被回 401（明明声明支持却做不到）。
            "SetAVTransportURI", "SetNextAVTransportURI", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions",
            "Stop", "Play", "Pause", "Seek", "Next", "Previous", "SetPlayMode",
            // ConnectionManager
            "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo",
            // RenderingControl
            "GetVolume", "SetVolume", "GetMute", "SetMute", "ListPresets", "SelectPreset",
    };

    public static boolean isKnownAction(String action) {
        for (int i = 0; i < KNOWN_ACTIONS.length; i++) {
            if (KNOWN_ACTIONS[i].equals(action)) {
                return true;
            }
        }
        return false;
    }
    /**
     * action 白名单的副本 —— 给桌面断言用（比对 SCPD 的 actionList 是否与它相等）。
     *
     * <p>返回副本而不是数组本身：断言代码不该有办法改到这张表。
     */
    public static String[] knownActions() {
        return KNOWN_ACTIONS.clone();
    }

    // ----------------------------------------------------------- SCPD 模板
    //
    // 这三份 XML 是**控制点了解"能调哪些指令"的唯一依据**。写错的后果不是
    // "少一个功能"，而是控制点整份解析失败 —— 严格按 SCPD 校验的协议栈
    // （Cling / jUPnP 系、BubbleUPnP 等）会直接判定这个服务不可用，于是设备
    // 在列表里是灰的、点不动。宽松的（多数国产 SDK）不看这些也能用，
    // 所以这类 bug 只在部分控制点上暴露，最容易被误判成"那台手机的问题"。
    //
    // 本轮改掉的两个硬伤：
    //
    //  1. relatedStateVariable **必须**指向 serviceStateTable 里真实存在的变量名。
    //     原来直接拿参数名当变量名，生成的是
    //         <relatedStateVariable>InstanceID</relatedStateVariable>
    //     而表里没有叫 InstanceID 的变量（正确名是 A_ARG_TYPE_InstanceID）。
    //     这是上面说的那种"整份解析失败"。
    //
    //  2. **out 参数原来一个都没声明。** 规范要求每个 action 的出参都列出来，
    //     控制点靠它知道响应里该有哪些字段、以及去哪个变量取值。
    //     缺了它，控制点拿到响应也不知道怎么读。
    //
    // 另外补上了原来漏在 SCPD 外面、但代码里已经在处理的 action
    // （GetDeviceCapabilities / GetTransportSettings / GetCurrentTransportActions /
    //  Next / Previous / SetPlayMode，以及 RenderingControl 的 ListPresets /
    //  SelectPreset）—— 控制点**不会发 SCPD 里没写的 action**，
    // 所以这些代码原本是死的。
    //
    // action 清单与变量表照 UPnP 官方的 AVTransport:1 / RenderingControl:1 /
    // ConnectionManager:1 SCPD 模板来，并与 Platinum UPnP SDK
    // （Source/Devices/MediaRenderer/AVTransportSCPD.xml 等）逐条核对过。
    // 刻意**没有**收录 SetPlaySpeed —— 它不是 AVTransport:1 的标准 action，
    // 写进 :1 的 SCPD 本身就是错的（原来它还同时出现在 KNOWN_ACTIONS 里）。

    private static final String SCPD_AV_TRANSPORT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("SetAVTransportURI",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:CurrentURI:AVTransportURI",
                            "in:CurrentURIMetaData:AVTransportURIMetaData")
                    + action("SetNextAVTransportURI",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:NextURI:AVTransportURI",
                            "in:NextURIMetaData:AVTransportURIMetaData")
                    + action("GetMediaInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:NrTracks:NumberOfTracks",
                            "out:MediaDuration:CurrentMediaDuration",
                            "out:CurrentURI:AVTransportURI",
                            "out:CurrentURIMetaData:AVTransportURIMetaData",
                            "out:NextURI:NextAVTransportURI",
                            "out:NextURIMetaData:NextAVTransportURIMetaData",
                            "out:PlayMedium:PlaybackStorageMedium",
                            "out:RecordMedium:RecordStorageMedium",
                            "out:WriteStatus:RecordMediumWriteStatus")
                    + action("GetTransportInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:CurrentTransportState:TransportState",
                            "out:CurrentTransportStatus:TransportStatus",
                            "out:CurrentSpeed:TransportPlaySpeed")
                    + action("GetPositionInfo",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:Track:CurrentTrack",
                            "out:TrackDuration:CurrentTrackDuration",
                            "out:TrackMetaData:CurrentTrackMetaData",
                            "out:TrackURI:CurrentTrackURI",
                            "out:RelTime:RelativeTimePosition",
                            "out:AbsTime:AbsoluteTimePosition",
                            "out:RelCount:RelativeCounterPosition",
                            "out:AbsCount:AbsoluteCounterPosition")
                    + action("GetDeviceCapabilities",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:PlayMedia:PossiblePlaybackStorageMedia",
                            "out:RecMedia:PossibleRecordStorageMedia",
                            "out:RecQualityModes:PossibleRecordQualityModes")
                    + action("GetTransportSettings",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:PlayMode:CurrentPlayMode",
                            "out:RecQualityMode:CurrentRecordQualityMode")
                    + action("GetCurrentTransportActions",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:Actions:CurrentTransportActions")
                    + action("Stop", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    // Play 的 in:Speed **必须声明** —— 这条是踩出来的，别再删。
                    //
                    // Cling 系控制点（芒果 TV 的 UA 就是 `Cling/2.0`）发 Play 用的是
                    // 编译期生成的桩，桩里固定会 `setInput("Speed", …)`；而 Cling 在
                    // setInput 时拿**运行时取回的 SCPD** 校验参数存不存在，不存在就抛
                    // IllegalArgumentException。结果是 Play **根本发不出去** ——
                    // 盒子侧连一条 POST 都收不到（真机取证：暂停后 28 秒只有
                    // Get* 轮询，零 Play）。Pause 没有 Speed 参数，所以暂停一直正常，
                    // 现象就是「能暂停、不能继续播放」。
                    //
                    // 规范里 Speed 是**可选**参数、AllowedValue 只有 1 和 1/2。
                    // 我们不支持变速：值非 1 时如实拒绝（见 dispatch），
                    // 1 或缺省按 1x 播，CurrentSpeed 如实回 1。
                    + action("Play",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Speed:A_ARG_TYPE_Speed")
                    + action("Pause", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("Seek",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Unit:A_ARG_TYPE_SeekMode",
                            "in:Target:A_ARG_TYPE_SeekTarget")
                    // Next / Previous 在规范里是**必选** action（可以回 701，
                    // 但不能不存在）—— 所以它们必须写在 SCPD 里。
                    // 代码侧由 notApplicableReason() 回 701。
                    + action("Next", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("Previous", "in:InstanceID:A_ARG_TYPE_InstanceID")
                    + action("SetPlayMode",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:NewPlayMode:CurrentPlayMode")
                    + " </actionList>\n"
                    + " <serviceStateTable>\n"
                    // ---- AVTransport 的事件承载是**规范形态**：整张表里只有
                    //      LastChange 一个变量声明为可事件化，变化的内容以一段
                    //      XML 文档塞在它的值里（见 avtLastChange()）。
                    //      其余变量一律 sendEvents="no" —— 规范本就如此，而且
                    //      Cling 系控制点（芒果 TV 实测）是按 SCPD 生成桩的：
                    //      事件体里出现未声明的变量会被**整条忽略**。逐变量推送时
                    //      它根本收不到 TransportState。
                    //      可事件化的集合**必须**与 DlnaRendererService
                    //      .eventedVars("AVTransport") 给出的键完全一致：
                    //      多一个：控制点会一直等一个永远不来的值；
                    //      少一个：事件体里带了它，控制点按 SCPD 直接忽略。
                    //      两边各写一份，靠 tools/policy-test 的守卫钉住。
                    + stateVar("LastChange", "string", true)
                    // 下面这些既是事件文档的内容（见 avtLastChange），也被 out
                    // 参数引用 —— 必须在表里，但**不再单独事件化**，值随
                    // LastChange 一起走。
                    + stateVar("TransportState", "string", false)
                    + stateVar("TransportStatus", "string", false)
                    + stateVar("CurrentTrackURI", "string", false)
                    + stateVar("CurrentTrackDuration", "string", false)
                    // 当前位置也进事件文档：一部分控制点（国产投屏 SDK 居多）
                    // 不轮询 GetPositionInfo，而是靠事件里的 RelativeTimePosition
                    // 更新进度条。少给这个字段，它的进度条就从头到尾不动。
                    + stateVar("RelativeTimePosition", "string", false)
                    // ---- 下面这些是"被 out 参数引用到"的变量，规范要求它们
                    //      必须出现在表里，但不需要事件化（sendEvents="no"）。----
                    + stateVar("PlaybackStorageMedium", "string", false)
                    + stateVar("RecordStorageMedium", "string", false)
                    + stateVar("PossiblePlaybackStorageMedia", "string", false)
                    + stateVar("PossibleRecordStorageMedia", "string", false)
                    + stateVar("CurrentPlayMode", "string", false)
                    + stateVar("TransportPlaySpeed", "string", false)
                    + stateVar("RecordMediumWriteStatus", "string", false)
                    + stateVar("CurrentRecordQualityMode", "string", false)
                    + stateVar("PossibleRecordQualityModes", "string", false)
                    + stateVar("NumberOfTracks", "ui4", false)
                    + stateVar("CurrentTrack", "ui4", false)
                    + stateVar("CurrentMediaDuration", "string", false)
                    + stateVar("CurrentTrackMetaData", "string", false)
                    + stateVar("AVTransportURI", "string", false)
                    + stateVar("AVTransportURIMetaData", "string", false)
                    + stateVar("NextAVTransportURI", "string", false)
                    + stateVar("NextAVTransportURIMetaData", "string", false)
                    + stateVar("AbsoluteTimePosition", "string", false)
                    + stateVar("RelativeCounterPosition", "i4", false)
                    + stateVar("AbsoluteCounterPosition", "i4", false)
                    + stateVar("CurrentTransportActions", "string", false)
                    // A_ARG_TYPE_* 是"参数类型"变量，规范里统一用这个前缀。
                    + stateVar("A_ARG_TYPE_InstanceID", "ui4", false)
                    + stateVar("A_ARG_TYPE_SeekMode", "string", false)
                    + stateVar("A_ARG_TYPE_SeekTarget", "string", false)
                    // Play 的 Speed 参数类型。**不声明它的话，Play 里的
                    // relatedStateVariable 就是悬空的**，严格的 SCPD 校验器
                    // （以及我们自己的策略守卫）会判整份 SCPD 非法。
                    + stateVar("A_ARG_TYPE_Speed", "string", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_CONNECTION_MANAGER =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    + action("GetProtocolInfo",
                            "out:Source:SourceProtocolInfo",
                            "out:Sink:SinkProtocolInfo")
                    + action("GetCurrentConnectionIDs",
                            "out:ConnectionIDs:CurrentConnectionIDs")
                    + action("GetCurrentConnectionInfo",
                            "in:ConnectionID:A_ARG_TYPE_ConnectionID",
                            "out:RcsID:A_ARG_TYPE_RcsID",
                            "out:AVTransportID:A_ARG_TYPE_AVTransportID",
                            "out:ProtocolInfo:A_ARG_TYPE_ProtocolInfo",
                            "out:PeerConnectionManager:A_ARG_TYPE_ConnectionManager",
                            "out:PeerConnectionID:A_ARG_TYPE_ConnectionID",
                            "out:Direction:A_ARG_TYPE_Direction",
                            "out:Status:A_ARG_TYPE_ConnectionStatus")
                    + " </actionList>\n"
                    // ConnectionManager 在标准里也有可事件化变量，控制点常订阅它。
                    // 声明了就必须在 eventedVars 里如实给值，否则控制点收到的是一份
                    // 缺字段的事件体 —— 比不订阅更糟。
                    + " <serviceStateTable>\n"
                    + stateVar("SourceProtocolInfo", "string", true)
                    + stateVar("SinkProtocolInfo", "string", true)
                    + stateVar("CurrentConnectionIDs", "string", true)
                    + stateVar("A_ARG_TYPE_ConnectionStatus", "string", false)
                    + stateVar("A_ARG_TYPE_ConnectionManager", "string", false)
                    + stateVar("A_ARG_TYPE_Direction", "string", false)
                    + stateVar("A_ARG_TYPE_ProtocolInfo", "string", false)
                    + stateVar("A_ARG_TYPE_ConnectionID", "i4", false)
                    + stateVar("A_ARG_TYPE_AVTransportID", "i4", false)
                    + stateVar("A_ARG_TYPE_RcsID", "i4", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    private static final String SCPD_RENDERING_CONTROL =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
                    + " <specVersion><major>1</major><minor>0</minor></specVersion>\n"
                    + " <actionList>\n"
                    // ListPresets / SelectPreset 属于 RenderingControl:1（不在 AVTransport 里）。
                    // 原来 KNOWN_ACTIONS 和 responseArgs 都处理了它们，SCPD 里却没声明 ——
                    // 而控制点不会发 SCPD 里没有的 action，所以那两段代码是死的。
                    + action("ListPresets",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "out:CurrentPresetNameList:PresetNameList")
                    + action("SelectPreset",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:PresetName:A_ARG_TYPE_PresetName")
                    + action("GetVolume",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "out:CurrentVolume:Volume")
                    + action("SetVolume",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "in:DesiredVolume:Volume")
                    + action("GetMute",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "out:CurrentMute:Mute")
                    + action("SetMute",
                            "in:InstanceID:A_ARG_TYPE_InstanceID",
                            "in:Channel:A_ARG_TYPE_Channel",
                            "in:DesiredMute:Mute")
                    + " </actionList>\n"
                    // 声明 Volume / Mute 是「可事件化」的 —— 控制点订阅后，
                    // 音量/静音一变就能收到 NOTIFY。原来这里一张 stateVariable 表都没有，
                    // 于是控制点订阅 RenderingControl 拿到的是空事件集。
                    + " <serviceStateTable>\n"
                    + stateVar("Volume", "ui2", true)
                    + stateVar("Mute", "boolean", true)
                    + stateVar("PresetNameList", "string", false)
                    + stateVar("A_ARG_TYPE_InstanceID", "ui4", false)
                    + stateVar("A_ARG_TYPE_Channel", "string", false)
                    + stateVar("A_ARG_TYPE_PresetName", "string", false)
                    + " </serviceStateTable>\n"
                    + "</scpd>\n";

    /**
     * 三份 SCPD，**下标与 {@link #SERVICES} 一一对应**。
     *
     * <p>声明位置有讲究：静态初始化按书写顺序执行，而三份 SCPD 都是
     * {@code action(...)} 拼出来的（不是编译期常量），所以本数组必须写在它们**之后**
     * —— 写在前面会拿到三个 {@code null}，而且是静默的。
     *
     * <p>有了它，{@code SERVICES} 就成了真正的单一来源：{@link #scpdFor(String)}
     * 按名字在 {@code SERVICES} 里找下标、再取本数组同下标的 SCPD，
     * 不再是一串 {@code if ("AVTransport".equals(...))}。加一个服务只需要
     * 往 {@code SERVICES} 和本数组各加一格，而且下标错位会在 PolicyTest 里当场红
     * （它会断言每份 SCPD 的 {@code serviceType} 与 {@code SERVICES[i]} 一致）。
     */
    private static final String[] SCPD_BY_SERVICE = {
            SCPD_AV_TRANSPORT, SCPD_CONNECTION_MANAGER, SCPD_RENDERING_CONTROL,
    };

    /**
     * 生成一条 {@code <action>}。
     *
     * <p>参数按 {@code "方向:参数名:关联状态变量"} 写，例如
     * {@code "out:CurrentVolume:Volume"}、{@code "in:InstanceID:A_ARG_TYPE_InstanceID"}。
     *
     * <p>为什么把第三个字段做成**必填**而不是从参数名推：规范要求
     * {@code relatedStateVariable} 指向 serviceStateTable 里真实存在的变量，
     * 而参数名与变量名**经常不一样**（{@code CurrentVolume} → {@code Volume}、
     * {@code InstanceID} → {@code A_ARG_TYPE_InstanceID}）。
     * 按参数名推就是本类原来那个 bug 的成因。写成三段之后，
     * 少写一段会当场抛异常（而不是静默生成一份畸形 SCPD）。
     */
    private static String action(String name, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <action><name>").append(name).append("</name>");
        if (args.length > 0) {
            sb.append("<argumentList>");
            for (int i = 0; i < args.length; i++) {
                String[] p = args[i].split(":");
                if (p.length != 3) {
                    // 宁可当场炸掉。静态初始化失败会让 UpnpHttpServer 类加载不出来，
                    // 服务一起起不来 —— 声音很大，但**好过静默生成一份畸形 SCPD**：
                    // 后者只在部分控制点上表现为"设备是灰的"，极难定位。
                    // 而它一定会在 tools/protocol-test 里被抓到（那一轮会 GET 三份 SCPD）。
                    throw new IllegalArgumentException(
                            "SCPD 参数必须写成 方向:参数名:状态变量，收到: " + args[i]);
                }
                sb.append("<argument><name>").append(p[1]).append("</name>")
                        .append("<direction>").append(p[0]).append("</direction>")
                        .append("<relatedStateVariable>").append(p[2])
                        .append("</relatedStateVariable></argument>");
            }
            sb.append("</argumentList>");
        }
        sb.append("</action>\n");
        return sb.toString();
    }

    private static String stateVar(String name, String type, boolean events) {
        return "  <stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\">"
                + "<name>" + name + "</name><dataType>" + type + "</dataType></stateVariable>\n";
    }
    // ------------------------------------------------------------- XML 转义

    /**
     * XML 转义。**嵌 URL 时必须过这一道**。
     *
     * <p>视频 CDN 的地址几乎必然带查询串，例如
     * {@code http://cdn/x.mp4?token=abc&expire=123}。这个 {@code &} 直接写进
     * {@code <CurrentURI>} 会让整份 SOAP 响应变成非法 XML —— 控制点那边不是
     * "这一项读不到"，而是**整条报文解析失败**，表现为投屏后立刻报错。
     * 同理 {@code <} {@code >} 出现在带签名的 URL 里也不罕见。
     *
     * <p>{@code &} 必须最先替换，否则会把后面刚生成的实体再转一遍
     * （{@code &lt;} → {@code &amp;lt;}）。
     *
     * <p>公开出来是给 {@link EventDispatcher} 用的：事件体里同样嵌着带
     * {@code &} 的媒体 URL，转义规则**必须和 SOAP 响应完全一致**。
     */
    public static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
