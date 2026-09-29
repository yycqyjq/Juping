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

    // ---- 级别常量。取值必须与 android.util.Log 一致 ----
    //
    // 协议层现在会调 Log.isLoggable(TAG, Log.DEBUG) 来开诊断日志
    // （默认不开，真机上由 `adb shell setprop log.tag.<TAG> DEBUG` 控制）。
    // 替身少了这个常量，整个协议测试就**编译不过** —— 而它挂在 dist 的
    // 第四道闸上，会直接把出包拦下来。所以常量必须补齐，不能省。
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    /** 是否打印 VERBOSE/DEBUG —— 协议测试里通常不需要，默认关掉减少噪声 */
    public static boolean verbose = false;

    /**
     * DEBUG 级别是否放行。
     *
     * <p>真机上由 {@code adb shell setprop log.tag.<TAG> DEBUG} 控制；
     * 桌面替身没有那套属性机制，用一个可写的静态开关代替。
     * 默认 false，与真机的默认行为一致（诊断日志不打）。
     */
    public static boolean debugLoggable = false;

    /**
     * 与 Android 语义对齐：**INFO 及以上默认放行**，低于 INFO 的要看开关。
     *
     * <p>「默认级别是 INFO」这条很容易被忽略 —— 如果这里一律返回 false，
     * 生产代码里那些包在 isLoggable 里的正常日志在替身上就全都不见了，
     * 排查协议问题时会对着一份"什么都没有"的日志发呆。
     */
    public static boolean isLoggable(String tag, int level) {
        if (level >= INFO) {
            return true;
        }
        return (level == DEBUG) ? debugLoggable : verbose;
    }

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
