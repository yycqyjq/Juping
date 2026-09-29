package android.util;

/**
 * 桌面测试用的 android.util.Log 替身。
 *
 * <p>存在的理由：整个 dlna 包对 Android 的依赖**只有这一个类**。
 * 补上它之后，UpnpHttpServer / SsdpResponder 就能用普通 javac 编译、
 * 在桌面 JVM 上真实跑起来 —— 于是「手写 UPnP 协议对不对」这件事
 * 可以在没有真机的情况下被完整地端到端验证。
 *
 * <p>输出走 stderr，前缀与 Android 的 logcat 一致，方便肉眼对照。
 */
public final class Log {

    private Log() {
    }

    /** 是否打印 VERBOSE/DEBUG —— 协议测试里通常不需要，默认关掉减少噪声 */
    public static boolean verbose = false;

    private static int print(String level, String tag, String msg, Throwable t) {
        if ("V".equals(level) && !verbose) {
            return 0;
        }
        String line = level + "/" + tag + ": " + msg;
        if (t != null) {
            line = line + " -- " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.err.println(line);
        return line.length();
    }

    public static int v(String tag, String msg) {
        return print("V", tag, msg, null);
    }

    public static int v(String tag, String msg, Throwable t) {
        return print("V", tag, msg, t);
    }

    public static int d(String tag, String msg) {
        return print("D", tag, msg, null);
    }

    public static int d(String tag, String msg, Throwable t) {
        return print("D", tag, msg, t);
    }

    public static int i(String tag, String msg) {
        return print("I", tag, msg, null);
    }

    public static int i(String tag, String msg, Throwable t) {
        return print("I", tag, msg, t);
    }

    public static int w(String tag, String msg) {
        return print("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable t) {
        return print("W", tag, msg, t);
    }

    public static int w(String tag, Throwable t) {
        return print("W", tag, String.valueOf(t), t);
    }

    public static int e(String tag, String msg) {
        return print("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable t) {
        return print("E", tag, msg, t);
    }
}
