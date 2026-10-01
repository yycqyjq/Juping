package com.juping.cast.web;

import android.util.Log;

import com.juping.cast.dlna.UpnpHttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.List;

/**
 * 「外接存储上的安装包」网页端点（批 3.5）。
 *
 * <table>
 *   <tr><td>{@code GET  /apk}</td><td>安装包页（HTML，ES5、零外链）</td></tr>
 *   <tr><td>{@code GET  /apk/list}</td><td>扫描结果（JSON）</td></tr>
 *   <tr><td>{@code POST /apk/refresh}</td><td>强制重扫并返回结果（供页面「刷新」按钮）</td></tr>
 *   <tr><td>{@code POST /apk/install}</td><td>触发系统安装器（本功能**唯一有副作用**的路由）</td></tr>
 * </table>
 *
 * <p>与上传端点共用同一个 HTTP 服务、同一个端口（由 {@link WebRouter} 串起来），
 * 不新起监听 —— 一台 0.6GB 的盒子经不起多一个 HTTP 服务。
 *
 * <p><b>为什么安装是「有副作用但可接受」</b>：网页接口无鉴权（定案：仅限局域网），
 * 一个「任意安装」端点确实是新增攻击面。但系统安装器会弹**确认界面**，
 * 且必须遥控器**物理确认**才真正安装 —— 局域网内最多能让电视弹个框。两道加固：
 * <ol>
 *   <li>{@link #resolveWhitelisted}：只接受**本次扫描结果里出现过**的路径 ——
 *       把攻击面从「装任意文件」收窄到「装本机外接卷上本来就有的 APK」；</li>
 *   <li>{@link LocalStore#isUnder}：只认落在某个已知卷挂载点之下的真实路径 ——
 *       绝不接受 {@code ..}、绝不接受应用私有目录。</li>
 * </ol>
 */
public final class ApkEndpoints implements UpnpHttpServer.WebEndpoints {

    private static final String TAG = "ApkEndpoints";

    /** {@code /apk/install} 这类小请求体的上限 */
    private static final int SMALL_BODY_LIMIT = 8 * 1024;

    /**
     * 安装动作的落点。由上层（{@code DlnaRendererService}）实现 —— 只有它拿得到
     * Service 上下文去 {@code startActivity}。与 {@code WebCastEndpoints.CastTarget}
     * 同一套路：端点不自己碰系统能力，反向拿一个回调接口。
     */
    public interface InstallTarget {
        /** 「未知来源」开关是否已开；未开则系统安装器会拒绝，得先引导用户去打开。 */
        boolean isInstallAllowed();

        /** 唤起系统安装器；返回是否成功发起（真正的安装发生在电视端确认之后）。 */
        boolean installApk(File apk);
    }

    private final ApkScanner scanner;
    private final InstallTarget target;

    public ApkEndpoints(ApkScanner scanner, InstallTarget target) {
        this.scanner = scanner;
        this.target = target;
    }

    @Override
    public UpnpHttpServer.WebResponse handle(String method, String path, String accept,
                                             String contentType, String range,
                                             int contentLength, InputStream in) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            if ("/apk".equals(path)) {
                // 这个路径不是 DLNA 的（控制点从不请求它），所以不必像 "/" 那样
                // 靠 Accept 分流 —— 直接回安装包页。
                return ok("text/html; charset=\"utf-8\"", PAGE);
            }
            if ("/apk/list".equals(path)) {
                return list();
            }
            return null;
        }
        if ("POST".equals(method)) {
            if ("/apk/install".equals(path)) {
                return install(contentLength, in);
            }
            if ("/apk/refresh".equals(path)) {
                // 为什么单开一条路由、而不是用 GET /apk/list?refresh=1：
                // 查询串在 HTTP 层（normalizePath）就被剥掉了，端点根本看不到它。
                return refresh();
            }
            return null;
        }
        return null;
    }

    /** 未知来源开关是否已开（供 {@code /status} 与页面显示）。 */
    public boolean isInstallAllowed() {
        return target.isInstallAllowed();
    }

    // ------------------------------------------------------------- 列表

    /**
     * 扫描结果。
     *
     * <p><b>为什么不在这里同步扫</b>：扫描可能几秒~几十秒，跑在 HTTP 连接线程上会把
     * 那条连接占死、手机端一直转圈。所以这里是「触发 + 读缓存」：首次进入触发一次
     * 异步扫描、立刻返回 {@code scanning:true}，页面轮询直到 {@code scanning:false}。
     */
    private UpnpHttpServer.WebResponse list() {
        scanner.startScanIfNeeded();
        List<ApkEntry> apps = scanner.cached();
        boolean scanning = scanner.isScanning();
        boolean truncated = scanner.isTruncated();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"apps\":[");
        if (apps != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                entryJson(sb, apps.get(i));
            }
        }
        sb.append("],\"scanning\":").append(scanning);
        sb.append(",\"truncated\":").append(truncated);
        sb.append(",\"installAllowed\":").append(target.isInstallAllowed());
        sb.append(",\"roots\":[");
        List<File> roots = scanner.roots();
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, roots.get(i).getAbsolutePath());
        }
        sb.append("]}");
        return ok("application/json; charset=\"utf-8\"", sb.toString());
    }

    /** 供页面「刷新」按钮用：强制重扫（见 {@code handle} 里的 {@code /apk/refresh}）。 */
    private UpnpHttpServer.WebResponse refresh() {
        scanner.scanAsync(null);
        return list();
    }

    private void entryJson(StringBuilder sb, ApkEntry e) {
        sb.append('{');
        sb.append("\"name\":");
        quote(sb, e.name);
        sb.append(",\"path\":");
        quote(sb, e.file.getAbsolutePath());
        sb.append(",\"size\":").append(e.size);
        sb.append(",\"modified\":").append(e.modified);
        sb.append(",\"packageName\":");
        quote(sb, e.packageName);
        sb.append(",\"label\":");
        quote(sb, e.label);
        sb.append(",\"version\":");
        quote(sb, e.versionName);
        sb.append('}');
    }

    // ------------------------------------------------------------- 安装

    private UpnpHttpServer.WebResponse install(int contentLength, InputStream in) {
        if (contentLength <= 0 || contentLength > SMALL_BODY_LIMIT) {
            return error(400, "Bad Request", "请求体不合法");
        }
        String path = formValue(readSmall(in, contentLength), "name");
        File apk = resolveWhitelisted(path);
        if (apk == null) {
            // 白名单外一律拒绝 —— 这是「装任意文件」收窄到「装本机外接卷上已有的包」的那道闸
            return error(403, "Forbidden", "这个路径不在扫描结果里，拒绝安装");
        }
        if (!target.isInstallAllowed()) {
            // 未知来源关着时系统会拦 —— 不装作已经发起，明确让用户去打开（只给文字指引）
            return message("电视上未开启「未知来源」，请到 设置 → 安全 → 未知来源 打开后重试");
        }
        if (!target.installApk(apk)) {
            return error(500, "Internal Server Error", "唤起安装器失败（电视端可能没有安装器）");
        }
        // 真正装上要等电视端遥控器确认，所以措辞是「已打开安装器」，不是「已安装」
        return message("已在电视上打开安装器，请用遥控器确认");
    }

    /**
     * 把请求方给的路径收窄成一个**可安装的文件**。
     *
     * <p>两道判据缺一不可：① 必须在本次扫描结果里出现过（白名单）；
     * ② 必须在某个已知卷的真实路径之下（{@link LocalStore#isUnder}，防穿越）。
     *
     * @return 通过校验的文件；任一不满足返回 {@code null}
     */
    private File resolveWhitelisted(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        List<ApkEntry> apps = scanner.cached();
        if (apps == null) {
            return null;    // 还没扫过：没有白名单，什么都不许装
        }
        String canon = canonical(new File(path));
        if (canon == null) {
            return null;
        }
        List<File> roots = scanner.roots();
        for (int i = 0; i < apps.size(); i++) {
            ApkEntry e = apps.get(i);
            if (!canon.equals(canonical(e.file))) {
                continue;
            }
            if (!e.file.isFile()) {
                return null;    // 扫完之后被拔盘 / 删掉了
            }
            for (int j = 0; j < roots.size(); j++) {
                if (LocalStore.isUnder(roots.get(j), e.file)) {
                    return e.file;
                }
            }
            return null;        // 在白名单里，却不在任何已知卷之下 —— 拒绝
        }
        return null;
    }

    // ------------------------------------------------------------ 小工具

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static UpnpHttpServer.WebResponse ok(String contentType, String body) {
        return new UpnpHttpServer.WebResponse("200 OK", contentType, body);
    }

    private static UpnpHttpServer.WebResponse message(String text) {
        return ok("application/json; charset=\"utf-8\"", "{\"message\":" + json(text) + "}");
    }

    private static UpnpHttpServer.WebResponse error(int code, String reason, String text) {
        return new UpnpHttpServer.WebResponse(code + " " + reason,
                "application/json; charset=\"utf-8\"",
                "{\"message\":" + json(text) + "}");
    }

    /** 读一个已知很小、且已被长度上限卡住的请求体 */
    private static String readSmall(InputStream in, int contentLength) {
        byte[] buf = new byte[contentLength];
        int read = 0;
        try {
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (IOException e) {
            Log.w(TAG, "读请求体失败", e);
        }
        try {
            return new String(buf, 0, read, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(buf, 0, read);
        }
    }

    /** 从 {@code application/x-www-form-urlencoded} 体里取一个字段 */
    private static String formValue(String body, String key) {
        if (body == null || body.length() == 0) {
            return null;
        }
        String[] pairs = body.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0 || !key.equals(pairs[i].substring(0, eq))) {
                continue;
            }
            try {
                // URLDecoder 会把 '+' 解成空格，这正是表单编码的约定
                return URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return pairs[i].substring(eq + 1);
            }
        }
        return null;
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        quote(sb, s);
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 0x20 || c == 0x7F) {
                    sb.append("\\u00");
                    sb.append(HEX.charAt((c >> 4) & 0xF));
                    sb.append(HEX.charAt(c & 0xF));
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final String HEX = "0123456789abcdef";

    /**
     * 安装包页。**内嵌、无外部资源、ES5** —— 与上传页同一纪律（盒子没有外网，
     * 引一个 CDN 上的框架就整页白屏；老浏览器遇到箭头函数 / fetch 是语法级报错，
     * 同样白屏）。
     *
     * <p>交互：进入即触发一次扫描 → 轮询到扫完 → 每行一个「安装」按钮 →
     * POST {@code /apk/install} → 电视弹出系统安装器（遥控器确认）。
     * 「未知来源」未开时页顶显示一条指引（只给文字，不提供跳转按钮）。
     */
    private static final String PAGE =
            "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\"><head>\n"
            + "<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>聚屏 · 安装包</title>\n"
            + "<style>\n"
            + "*{box-sizing:border-box}\n"
            + "body{margin:0;padding:16px;font:16px/1.5 -apple-system,\"PingFang SC\","
            + "\"Microsoft YaHei\",sans-serif;background:#111;color:#eee}\n"
            + "h1{font-size:20px;margin:0 0 4px}\n"
            + "#sub{color:#9a9a9a;font-size:13px;margin:0 0 12px}\n"
            + "a{color:#7fb0ff;font-size:13px;text-decoration:none}\n"
            + "#warn{display:none;border:1px solid #6a4a1a;background:#2a2010;color:#ffb454;"
            + "border-radius:8px;padding:12px;font-size:14px;margin:0 0 14px}\n"
            + "#msg{margin:12px 0;font-size:14px;min-height:1.5em;color:#ffb454}\n"
            + "button{margin-top:12px;padding:10px 22px;font-size:16px;border:0;"
            + "border-radius:8px;background:#3d7eff;color:#fff}\n"
            + "button:disabled{background:#333;color:#777}\n"
            + "ul{list-style:none;padding:0;margin:0}\n"
            + "li{display:flex;align-items:center;gap:10px;padding:12px 0;"
            + "border-top:1px solid #262626}\n"
            + "li span{flex:1;word-break:break-all;font-size:15px}\n"
            + "li span em{font-style:normal;color:#8a8a8a;font-size:12px}\n"
            + "li button{margin:0;padding:8px 18px;font-size:14px}\n"
            + "</style></head><body>\n"
            + "<h1>安装包</h1>\n"
            + "<p id=\"sub\">读取外接存储（U 盘 / SD 卡）上的 APK</p>\n"
            + "<p id=\"warn\">电视上未开启「未知来源」，无法安装。"
            + "请到 <b>设置 → 安全 → 未知来源</b> 打开后重试。</p>\n"
            + "<p id=\"msg\">正在扫描…</p>\n"
            + "<p><button id=\"refresh\">刷新</button>"
            + " <a href=\"/\">← 返回上传页</a></p>\n"
            + "<ul id=\"list\"></ul>\n"
            + "<script>\n"
            + "var list=document.getElementById('list'),msg=document.getElementById('msg'),"
            + "warn=document.getElementById('warn'),refresh=document.getElementById('refresh');\n"
            + "function fmt(n){if(!(n>0))return '未知';var u=['B','KB','MB','GB'],i=0;"
            + "while(n>=1024&&i<3){n/=1024;i++}return n.toFixed(i?1:0)+u[i]}\n"
            + "function say(t){msg.textContent=t}\n"
            + "function enc(n){return encodeURIComponent(n)}\n"
            + "function render(d){\n"
            + "  warn.style.display=(d.installAllowed===false)?'block':'none';\n"
            + "  var apps=d.apps||[];\n"
            + "  list.innerHTML='';\n"
            + "  for(var i=0;i<apps.length;i++){list.appendChild(row(apps[i]))}\n"
            + "  if(d.scanning){say('正在扫描外接存储…')}\n"
            + "  else if(!apps.length){say('外接存储上没有找到 APK 安装包')}\n"
            + "  else{say('找到 '+apps.length+' 个'+(d.truncated?'（已截断，只显示前 '+apps.length+' 个）':''))}\n"
            + "}\n"
            + "function row(a){\n"
            + "  var li=document.createElement('li');\n"
            + "  var s=document.createElement('span');\n"
            + "  s.appendChild(document.createTextNode((a.label||a.name)+(a.version?(' · v'+a.version):'')));\n"
            + "  var em=document.createElement('em');\n"
            + "  em.textContent=fmt(a.size)+(a.packageName?(' · '+a.packageName):'');\n"
            + "  s.appendChild(document.createElement('br'));s.appendChild(em);li.appendChild(s);\n"
            + "  var b=document.createElement('button');b.textContent='安装';\n"
            + "  b.onclick=function(){install(a.path,b)};li.appendChild(b);\n"
            + "  return li;\n"
            + "}\n"
            + "function load(){\n"
            + "  var x=new XMLHttpRequest();x.open('GET','/apk/list');\n"
            + "  x.onload=function(){\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){say('响应异常');return}\n"
            + "    render(d);\n"
            + "    if(d.scanning){setTimeout(function(){load()},700)}\n"
            + "  };\n"
            + "  x.onerror=function(){say('读取失败，请重试')};\n"
            + "  x.send();\n"
            + "}\n"
            + "function post(url,body,onload,onerror){\n"
            + "  var x=new XMLHttpRequest();x.open('POST',url);\n"
            + "  x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');\n"
            + "  x.onload=onload;x.onerror=onerror;x.send(body);\n"
            + "}\n"
            + "function install(path,b){\n"
            + "  b.disabled=true;b.textContent='…';\n"
            + "  post('/apk/install','name='+enc(path),function(x){\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){d={message:'响应异常'}}\n"
            + "    say(d.message||('HTTP '+x.status));\n"
            + "    b.disabled=false;b.textContent='安装';\n"
            + "  },function(){say('请求发不出去');b.disabled=false;b.textContent='安装'});\n"
            + "}\n"
            + "function rescan(){\n"
            + "  refresh.disabled=true;\n"
            + "  post('/apk/refresh','',function(x){\n"
            + "    refresh.disabled=false;\n"
            + "    var d;try{d=JSON.parse(x.responseText)}catch(e){say('响应异常');return}\n"
            + "    render(d);\n"
            + "    if(d.scanning){setTimeout(function(){load()},700)}\n"
            + "  },function(){refresh.disabled=false;say('刷新请求发不出去')});\n"
            + "}\n"
            + "refresh.onclick=rescan;\n"
            + "load();\n"
            + "</script>\n"
            + "</body></html>\n";
}
