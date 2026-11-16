package hev.htproxy;

/**
 * JNI binding for libhev-socks5-tunnel.so.
 * The native library is built from https://github.com/heiher/hev-socks5-tunnel
 * (MIT) and registers its methods on this exact class.
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
