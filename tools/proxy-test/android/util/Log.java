package android.util;

/**
 * android.util.Log 的桌面替身 —— 让 MediaProxy 能在桌面 JVM 上测试。
 * 与 protocol-test 里的同名替身职责相同；不在这里合用是为了让两个
 * 测试目录各自自包含。
 */
public final class Log {

    private Log() {
    }

    public static int i(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("W/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable t) {
        System.out.println("W/" + tag + ": " + msg + " (" + t + ")");
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }
}
