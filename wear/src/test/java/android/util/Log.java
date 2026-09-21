package android.util;

/**
 * Test-only shadow of the framework {@code android.util.Log}.
 *
 * <p>The unit-test android.jar stubs every Log method to throw
 * "not mocked", so any production code path that logs (e.g.
 * {@code SampleStore.deleteRange}) cannot run on the JVM. This shadow
 * takes precedence on the test runtime classpath and silently discards
 * log output. It lives in the test source set only and never ships.
 */
public final class Log {
    private Log() {}

    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }

    public static String getStackTraceString(Throwable tr) { return ""; }
    public static boolean isLoggable(String tag, int level) { return false; }
}
