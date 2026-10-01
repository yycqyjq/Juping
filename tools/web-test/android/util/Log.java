package android.util;

/**
 * android.util.Log 的桌面替身 —— 让 LocalStore 能在桌面 JVM 上参与测试。
 * 与 protocol-test / proxy-test 里的同名替身职责相同；不跨目录合用，是为了让
 * 每个测试目录各自自包含（改一处不会影响另一处）。
 */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
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

    public static int e(String tag, String msg) {
        System.out.println("E/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        System.out.println("E/" + tag + ": " + msg + " (" + t + ")");
        return 0;
    }
}