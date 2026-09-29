package com.juping.cast.dlna;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 网卡选择的唯一出处。
 *
 * <h3>为什么必须只有一处</h3>
 * 这里有两条链路需要「同一张网卡」：
 * <ul>
 *   <li>SSDP 组播监听绑在哪张网卡（{@link SsdpResponder}）</li>
 *   <li>设备描述 URL（LOCATION）里写哪个 IP（{@link com.juping.cast.DlnaRendererService}）</li>
 * </ul>
 * 如果两边各挑各的，就可能出现「组播从 eth0 收，却告诉手机去 wlan0 取设备描述」——
 * 手机搜得到设备、但拉不到描述，表现为「搜到了却投不了屏」。
 * 把选择逻辑收在一处，两者必然一致。
 *
 * <h3>筛选条件是怎么定下来的（踩过坑）</h3>
 * 最初只认 {@code eth*} / {@code wlan*} / {@code ap*} 三种前缀，认不出就放弃 ——
 * 但 Android 盒子的网卡命名不止这三种，USB 网卡常见 {@code usb0} / {@code rndis0}，
 * 于是加了「任何支持组播的可用网卡」兜底。
 *
 * <p>结果兜底立刻选错了：机器上的 {@code utun3}（VPN 隧道）是 up 的、也支持组播，
 * 排在真实网卡前面，于是被选中 —— 而它**连 IPv4 地址都没有**，
 * {@code joinGroup} 直接抛 {@code EADDRNOTAVAIL}，SSDP 线程静默死掉。
 *
 * <p>所以判据从「网卡类型」改成了「**有没有可用的 IPv4**」，再叠一层类型黑名单。
 * 这一条同时排掉了 Android 上的 {@code rmnet_data0}（蜂窝）、{@code tun0}（VPN）、
 * {@code p2p0}（Wi-Fi Direct —— 正是我们要避开的 Miracast 那条路）。
 */
public final class NetUtil {

    private static final String TAG = "NetUtil";

    /**
     * 明确排除的网卡名前缀。
     *
     * <p>这些都是「看起来能用、实际上不该拿去收 SSDP 组播」的接口：
     * 隧道、VPN、蜂窝数据、Wi-Fi Direct、Apple 的点对点链路。
     * 绑上去要么直接失败，要么收不到局域网里的搜索请求 ——
     * 两种表现都是「手机搜不到设备」，而且日志里看不出所以然。
     */
    private static final String[] EXCLUDED_PREFIXES = {
            // 隧道 / VPN
            "utun", "tun", "tap", "ppp", "ipsec", "gif", "stf", "sit", "gre", "ip6",
            // 蜂窝数据
            "rmnet", "ccmni", "pdp", "wwan", "cdma", "seth",
            // Wi-Fi Direct（正是要避开的 Miracast 那条路）
            "p2p",
            // Apple 的点对点 / 辅助链路
            "awdl", "llw", "anpi",
            // 虚拟与桥接
            "dummy", "bridge", "veth",
    };

    private NetUtil() {
    }

    /**
     * 按优先级列出所有可用于组播的网卡。
     *
     * <p>顺序：有线 &gt; Wi-Fi &gt; 其余。调用方应**依次尝试**，
     * 某一张 {@code joinGroup} 失败就换下一张 —— 比一上来就放弃稳得多。
     *
     * <p>有线优先是因为老盒子插网线时流量走 eth0，组播绑到 wlan0 会一个包都收不到。
     * Wi-Fi 必须排在「其余」之前：否则热点网卡（ap0）排在 wlan0 前面时会被选中，
     * 而手机连的是 wlan0 那一侧，同样收不到搜索请求。
     */
    public static List<NetworkInterface> pickInterfaces() {
        List<NetworkInterface> wired = new ArrayList<NetworkInterface>();
        List<NetworkInterface> wifi = new ArrayList<NetworkInterface>();
        List<NetworkInterface> other = new ArrayList<NetworkInterface>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!isUsable(nif)) {
                    continue;
                }
                // 必须指定 Locale.ROOT。默认 locale 下 "wifi".toUpperCase() 在土耳其语
                // 环境里会变成 "WİFİ"（I -> İ），网卡匹配不上 → 组播绑不上 →
                // 表现为「手机搜不到设备」，而中文环境永远复现不出来。
                String name = nif.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("eth")) {
                    wired.add(nif);
                } else if (name.startsWith("wlan")) {
                    wifi.add(nif);
                } else {
                    other.add(nif);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "枚举网卡失败", e);
        }
        List<NetworkInterface> out = new ArrayList<NetworkInterface>();
        out.addAll(wired);
        out.addAll(wifi);
        out.addAll(other);
        return out;
    }

    /**
     * 挑一张能承载组播的网卡。取 {@link #pickInterfaces()} 的第一个。
     *
     * @return 可用的网卡；实在没有则返回 null
     */
    public static NetworkInterface pickInterface() {
        List<NetworkInterface> list = pickInterfaces();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 这张网卡能不能用来收局域网组播 */
    private static boolean isUsable(NetworkInterface nif) {
        try {
            if (!nif.isUp() || nif.isLoopback()) {
                return false;
            }
            // 不支持组播的网卡绑上去也收不到东西。少了这一句，日志会显示
            // "绑定成功" 却永远收不到包 —— 比直接失败更难查。
            if (!nif.supportsMulticast()) {
                return false;
            }
            // 最关键的一条：必须有可用的 IPv4 地址。
            // 没有地址的接口（VPN 隧道、Apple 的点对点链路）joinGroup 会直接抛
            // EADDRNOTAVAIL，而它往往排在真实网卡前面 —— 这就是 SSDP 静默死掉的原因。
            if (pickIpv4(nif) == null) {
                return false;
            }
            String name = nif.getName().toLowerCase(Locale.ROOT);
            for (int i = 0; i < EXCLUDED_PREFIXES.length; i++) {
                if (name.startsWith(EXCLUDED_PREFIXES[i])) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 取网卡上的第一个 IPv4 地址（优先非链路本地）。没有则返回 null */
    public static String pickIpv4(NetworkInterface nif) {
        if (nif == null) {
            return null;
        }
        String linkLocal = null;
        try {
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (!(addr instanceof Inet4Address)) {
                    continue;
                }
                if (addr.isLoopbackAddress()) {
                    continue;
                }
                // 169.254.x.x 是链路本地地址，只在没拿到 DHCP 时出现。
                // 手机和它不在同一网段，写进 LOCATION 等于给了个死地址 —— 先备着不用。
                if (addr.isLinkLocalAddress()) {
                    if (linkLocal == null) {
                        linkLocal = addr.getHostAddress();
                    }
                    continue;
                }
                return addr.getHostAddress();
            }
        } catch (Exception e) {
            Log.w(TAG, "取网卡地址失败", e);
        }
        return linkLocal;
    }

    /**
     * 本机在局域网里的 IPv4 地址 —— 设备描述 URL 用它。
     *
     * <p>刻意与 {@link #pickInterface()} 走同一套选择逻辑，保证
     * 「组播从哪张网卡收」和「告诉手机去哪取描述」指向同一张网卡。
     */
    public static String pickLocalIp() {
        String ip = pickIpv4(pickInterface());
        return ip == null ? "0.0.0.0" : ip;
    }

    /** 供排障界面显示：当前能用于组播的网卡名单 */
    public static String describeCandidates() {
        List<NetworkInterface> list = pickInterfaces();
        if (list.isEmpty()) {
            return "(无可用于组播的网卡)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i).getName()).append('/').append(pickIpv4(list.get(i)));
        }
        return sb.toString();
    }
}
