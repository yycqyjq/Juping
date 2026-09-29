package com.juping.cast.dlna;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * GENA 事件通知 —— UPnP 的「服务端主动告诉控制点：状态变了」。
 *
 * <p>没有这一层，控制点只能靠轮询（或者干脆不刷新）：订阅了却收不到事件，
 * 手机上的播放进度条、播放/暂停按钮就会一直是旧的。
 * 原来的实现是「SUBSCRIBE 回个 200 + SID 就完事，从不推送」——
 * 对只发指令不回读的控制点没影响，对依赖事件同步的那些（BubbleUPnP、
 * 各种遥控类 App）就等于坏了一半。
 *
 * <h3>两个必须写进注释的坑</h3>
 *
 * <p><b>① 不能用 HttpURLConnection 发 NOTIFY。</b>
 * {@code setRequestMethod("NOTIFY")} 会抛
 * {@code ProtocolException: Invalid HTTP method: NOTIFY} ——
 * {@code HttpURLConnection} 只认它白名单里的那几个方法。
 * 所以这里用原始 {@link Socket} 手写请求行。
 *
 * <p><b>② 订阅成功后必须立刻推一次「初始事件」，而且必须在 200 响应之后。</b>
 * 规范（UPnP Device Architecture 1.0 §4.3）要求：订阅成功即发送一次包含
 * **当前所有事件变量值**的 NOTIFY，SEQ 从 0 开始。不发的话，控制点会一直
 * 认为这些变量是空的 —— 它不会主动来问，只会等。
 *
 * <p>顺序同样是硬要求：控制点是拿 SUBSCRIBE 响应里的 SID 来认事件的。
 * 如果 NOTIFY 早于响应到达，多数协议栈（Cling 等）看到未知 SID 会**直接丢弃**，
 * 于是初始状态永远补不上。所以这里刻意把两件事拆成
 * {@link #subscribe}（只注册）与 {@link #fireInitial}（推初始事件），
 * 由 HTTP 层在写完 200 之后调用后者。
 *
 * <p>这个类不依赖任何 Android 运行时（只有 android.util.Log），
 * 所以整套订阅/推送逻辑可以在桌面 JVM 上端到端测。
 */
public class EventDispatcher {

    /** 事件源：由业务层提供「某个服务当前所有 sendEvents=yes 的变量」。 */
    public interface EventSource {
        Map<String, String> eventedVars(String service);
    }

    private static final String TAG = "EventDispatcher";

    /** 控制点没指定 TIMEOUT 时的默认值。规范建议 1800 秒。 */
    private static final long DEFAULT_TIMEOUT_SEC = 1800L;
    /** 太短会让控制点频繁续订，反而增加负担。 */
    private static final long MIN_TIMEOUT_SEC = 300L;
    private static final long MAX_TIMEOUT_SEC = 86400L;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    /** 连续失败这么多次就丢弃订阅 —— 控制点可能已经退出了。 */
    private static final int MAX_FAIL = 3;

    /**
     * 订阅表上限。
     *
     * <p>每个订阅占一条 SID + 一个回调地址列表。控制点异常、或者有人拿脚本刷，
     * 都能让它无限增长 —— 而 0.6GB 的盒子上这同样是致命的：
     * 撑爆的是整个进程，SSDP 一起陪葬。
     *
     * <p>32 的依据：真实场景里同一时刻只有一两个控制点在订阅（手机上那个投屏 App），
     * 32 已经远超正常用量。到顶时淘汰**最旧的**一条 —— {@link #subs} 是
     * LinkedHashMap，迭代顺序就是插入顺序；而续订走 {@link #renew}（复用原 SID），
     * 不会插新条目，所以被挤掉的永远是那些本该过期、却因为时钟或实现问题
     * 没被清掉的老订阅。
     */
    private static final int MAX_SUBS = 32;

    private static final class Sub {
        final String sid;
        final String service;
        final List<String> callbacks = new ArrayList<String>();
        volatile long expireAtMs;
        /** 本次授予的超时秒数。SUBSCRIBE 响应里的 TIMEOUT 头要如实回这个值。 */
        volatile long grantedSec;
        volatile int seq;
        volatile int failCount;

        Sub(String sid, String service) {
            this.sid = sid;
            this.service = service;
        }
    }

    private final String uuid;
    private final EventSource source;

    /** SID -> 订阅。所有读写都在 this 上同步。 */
    private final Map<String, Sub> subs = new LinkedHashMap<String, Sub>();
    private int sidCounter;

    /**
     * 单线程池，保证事件**按序**投递：初始事件（SEQ 0）一定先于
     * 之后的状态变化事件（SEQ 1、2…）。用多线程就会乱序，
     * 控制点看到 SEQ 倒退会认为订阅失效。
     */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "upnp-event");
            // 守护线程：绝不能因为它让整个进程退不掉
            t.setDaemon(true);
            return t;
        }
    });

    public EventDispatcher(String uuid, EventSource source) {
        this.uuid = uuid;
        this.source = source;
    }

    // ------------------------------------------------------------ 订阅管理

    /**
     * 新订阅。**只注册，不推送** —— 初始事件由 {@link #fireInitial} 单独触发。
     *
     * <p>为什么不在这一句里顺手推掉：控制点是拿 SUBSCRIBE 响应里的 SID 认事件的，
     * NOTIFY 早于 200 到达会被当成「未知 SID」丢掉（见类注释 ②）。
     * 调用方必须按「写响应 → {@code fireInitial}」的顺序来。
     *
     * @param service        服务短名（AVTransport / RenderingControl …）
     * @param callbackHeader CALLBACK 头原文，形如 {@code <http://ip:port/path>}
     * @return 新 SID；回调地址解析不出来时返回 null（调用方应回 412）
     */
    public String subscribe(String service, String callbackHeader, String timeoutHeader) {
        List<String> callbacks = parseCallbacks(callbackHeader);
        if (callbacks.isEmpty()) {
            return null;
        }
        String sid;
        synchronized (this) {
            pruneLocked();
            // 到顶就淘汰最旧的一条。不设上限的话，异常控制点能把它撑到 OOM ——
            // 而这台盒子只有 0.6GB，撑爆的是整个进程。
            if (subs.size() >= MAX_SUBS) {
                String victim = subs.keySet().iterator().next();
                subs.remove(victim);
                Log.w(TAG, "订阅表已达上限 " + MAX_SUBS + "，淘汰最旧的 SID=" + victim);
            }
            sid = "uuid:" + uuid + "-" + (++sidCounter);
            Sub s = new Sub(sid, service);
            s.callbacks.addAll(callbacks);
            s.grantedSec = clampTimeout(timeoutHeader);
            s.expireAtMs = System.currentTimeMillis() + s.grantedSec * 1000L;
            subs.put(sid, s);
            Log.i(TAG, "新订阅 " + service + " SID=" + sid + " 回调 " + callbacks.size()
                    + " 个，保 " + s.grantedSec + "s");
        }
        return sid;
    }

    /**
     * 推送初始事件（SEQ 0，含该服务当前所有 sendEvents=yes 的变量）。
     *
     * <p>**必须在 SUBSCRIBE 的 200 响应写出之后调用**，理由见 {@link #subscribe}。
     * 订阅已经不存在时静默丢弃，不抛异常。
     */
    public void fireInitial(String sid) {
        if (sid == null) {
            return;
        }
        post(sid);
    }

    /** 该订阅被授予的超时秒数。SUBSCRIBE 响应的 TIMEOUT 头用。 */
    public long timeoutOf(String sid) {
        synchronized (this) {
            Sub s = subs.get(sid);
            return s == null ? DEFAULT_TIMEOUT_SEC : s.grantedSec;
        }
    }

    /**
     * 续订。控制点会带着 SID 再来一次 SUBSCRIBE。
     *
     * @return 新的超时秒数；SID 不认识时返回 -1（调用方应回 412）
     */
    public long renew(String sid, String timeoutHeader) {
        synchronized (this) {
            Sub s = subs.get(sid);
            if (s == null) {
                return -1L;
            }
            long t = clampTimeout(timeoutHeader);
            s.grantedSec = t;
            s.expireAtMs = System.currentTimeMillis() + t * 1000L;
            s.failCount = 0;
            Log.d(TAG, "续订 SID=" + sid + " 再保 " + t + "s");
            return t;
        }
    }

    /** 退订。 */
    public boolean unsubscribe(String sid) {
        synchronized (this) {
            boolean removed = subs.remove(sid) != null;
            if (removed) {
                Log.i(TAG, "退订 SID=" + sid);
            }
            return removed;
        }
    }

    /** 某个服务当前的订阅数。测试与排障用。 */
    public int subscriberCount(String service) {
        synchronized (this) {
            pruneLocked();
            int n = 0;
            for (Sub s : subs.values()) {
                if (s.service.equals(service)) {
                    n++;
                }
            }
            return n;
        }
    }

    public int totalSubscribers() {
        synchronized (this) {
            return subs.size();
        }
    }

    // ------------------------------------------------------------ 推送

    /**
     * 状态变了，推给某个服务的所有订阅者。
     *
     * <p>**非阻塞**：只是往单线程池里排个队，调用方（主线程 / HTTP 线程）
     * 不会被网络 IO 拖住。这一点很重要 —— 这个方法是从
     * {@code onStateChanged} 里调的，卡住就是界面卡住。
     */
    public void notifyAll(String service) {
        List<String> sids = new ArrayList<String>();
        synchronized (this) {
            pruneLocked();
            for (Sub s : subs.values()) {
                if (s.service.equals(service)) {
                    sids.add(s.sid);
                }
            }
        }
        for (int i = 0; i < sids.size(); i++) {
            post(sids.get(i));
        }
    }

    private void post(final String sid) {
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    deliver(sid);
                }
            });
        } catch (Exception e) {
            // 池已关闭（服务正在销毁）。丢掉即可，不必惊动调用方。
            Log.w(TAG, "事件排期失败: " + e);
        }
    }

    private void deliver(String sid) {
        Sub s;
        Map<String, String> vars;
        int seq;
        synchronized (this) {
            s = subs.get(sid);
            if (s == null) {
                return;                       // 已退订 / 已被丢弃
            }
            if (s.expireAtMs < System.currentTimeMillis()) {
                subs.remove(sid);
                Log.d(TAG, "订阅过期，丢弃 SID=" + sid);
                return;
            }
            vars = source == null ? null : source.eventedVars(s.service);
            if (vars == null || vars.isEmpty()) {
                return;                       // SEQ 不能在"什么都没发"时前进
            }
            seq = s.seq++;
        }

        String body = buildBody(vars);
        boolean anyOk = false;
        for (int i = 0; i < s.callbacks.size(); i++) {
            if (sendNotify(s.callbacks.get(i), s.sid, seq, body)) {
                anyOk = true;
            }
        }
        if (anyOk) {
            s.failCount = 0;
            Log.d(TAG, "已推送 " + s.service + " SEQ=" + seq + " 给 " + s.callbacks.size() + " 个回调");
        } else {
            s.failCount++;
            if (s.failCount >= MAX_FAIL) {
                synchronized (this) {
                    subs.remove(sid);
                }
                Log.w(TAG, "回调连续失败 " + MAX_FAIL + " 次，丢弃订阅 SID=" + sid);
            }
        }
    }

    /**
     * 发一条 NOTIFY。用原始 socket，理由见类注释 ①。
     *
     * @return 是否成功送达（能连上并写出请求即算）
     */
    private boolean sendNotify(String callbackUrl, String sid, int seq, String body) {
        Socket sock = null;
        try {
            URL u = new URL(stripBrackets(callbackUrl));
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String file = u.getFile();
            if (file == null || file.length() == 0) {
                file = "/";
            }
            byte[] payload = body.getBytes("UTF-8");

            sock = new Socket();
            sock.connect(new InetSocketAddress(u.getHost(), port), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(READ_TIMEOUT_MS);

            StringBuilder head = new StringBuilder(256);
            head.append("NOTIFY ").append(file).append(" HTTP/1.1\r\n");
            head.append("HOST: ").append(u.getHost()).append(':').append(port).append("\r\n");
            head.append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n");
            head.append("CONTENT-LENGTH: ").append(payload.length).append("\r\n");
            head.append("NT: upnp:event\r\n");
            head.append("NTS: upnp:propchange\r\n");
            head.append("SID: ").append(sid).append("\r\n");
            head.append("SEQ: ").append(seq).append("\r\n");
            head.append("CONNECTION: close\r\n\r\n");

            OutputStream os = sock.getOutputStream();
            os.write(head.toString().getBytes("ISO-8859-1"));
            os.write(payload);
            os.flush();

            // 读一眼状态行。控制点通常回 200 OK；读不到也不致命 ——
            // 有些实现收到事件后直接关连接，这不算失败。
            try {
                InputStream is = sock.getInputStream();
                byte[] buf = new byte[64];
                int n = is.read(buf);
                if (n > 0) {
                    String status = new String(buf, 0, n, "ISO-8859-1").split("\r\n")[0];
                    Log.d(TAG, "NOTIFY 应答: " + status);
                }
            } catch (Exception ignored) {
                // 读不到应答不当失败
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "NOTIFY 失败 " + callbackUrl + " : " + e);
            return false;
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 组装事件体。值必须转义 —— 里面会有带 {@code &} 的媒体 URL。 */
    private static String buildBody(Map<String, String> vars) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\n");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String name = e.getKey();
            sb.append("<e:property><").append(name).append('>')
                    .append(UpnpHttpServer.escapeXml(e.getValue()))
                    .append("</").append(name).append("></e:property>\n");
        }
        sb.append("</e:propertyset>\n");
        return sb.toString();
    }

    public void shutdown() {
        synchronized (this) {
            subs.clear();
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------ 解析助手

    /** CALLBACK 头形如 {@code <http://a/b><http://c/d>}，取出里面所有 URL。 */
    private static List<String> parseCallbacks(String header) {
        List<String> out = new ArrayList<String>();
        if (header == null) {
            return out;
        }
        int i = 0;
        while (true) {
            int lt = header.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int gt = header.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            String url = header.substring(lt + 1, gt).trim();
            if (url.length() > 0) {
                out.add(url);
            }
            i = gt + 1;
        }
        return out;
    }

    private static String stripBrackets(String s) {
        String t = s.trim();
        if (t.startsWith("<")) {
            t = t.substring(1);
        }
        if (t.endsWith(">")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** TIMEOUT 头形如 {@code Second-1800} 或 {@code Second-infinite}。 */
    private static long clampTimeout(String header) {
        if (header == null) {
            return DEFAULT_TIMEOUT_SEC;
        }
        String h = header.trim();
        if (h.toLowerCase(Locale.ROOT).endsWith("infinite")) {
            return MAX_TIMEOUT_SEC;
        }
        long sec = DEFAULT_TIMEOUT_SEC;
        int dash = h.indexOf('-');
        if (dash >= 0) {
            try {
                sec = Long.parseLong(h.substring(dash + 1).trim());
            } catch (NumberFormatException ignored) {
                // 解析不了就用默认值
            }
        }
        if (sec < MIN_TIMEOUT_SEC) {
            sec = MIN_TIMEOUT_SEC;
        }
        if (sec > MAX_TIMEOUT_SEC) {
            sec = MAX_TIMEOUT_SEC;
        }
        return sec;
    }

    /** 顺手清掉过期订阅。每次订阅/推送前调用，不必单开清理线程。 */
    private void pruneLocked() {
        long now = System.currentTimeMillis();
        List<String> dead = null;
        for (Sub s : subs.values()) {
            if (s.expireAtMs < now) {
                if (dead == null) {
                    dead = new ArrayList<String>();
                }
                dead.add(s.sid);
            }
        }
        if (dead != null) {
            for (int i = 0; i < dead.size(); i++) {
                subs.remove(dead.get(i));
            }
        }
    }
}
