package app.multimin.backend;

import java.nio.file.Path;

/** OS integration choices. UI/model state remains shared across builds. */
public final class PlatformSupport {
    private PlatformSupport() {}
    private static String os() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT); }
    public static boolean isWindows() { return os().startsWith("windows"); }
    public static boolean isLinux() { return os().equals("linux"); }
    public static boolean isMac() { return os().startsWith("mac"); }
    /**
     * Linux and macOS share the portable path: a Python backend speaking the
     * Linux JSON protocol, Java-side composition and overlay input forwarding.
     * Windows keeps its own WGC/JNI path.
     */
    public static boolean isPortable() { return isLinux() || isMac(); }
    public static boolean isWayland() { return isLinux() && ("wayland".equals(System.getenv("XDG_SESSION_TYPE")) || System.getenv("WAYLAND_DISPLAY") != null); }
    public static boolean isKde() {
        return isWayland() && (System.getenv().getOrDefault("XDG_CURRENT_DESKTOP", "").toLowerCase(java.util.Locale.ROOT).contains("kde")
            || System.getenv().getOrDefault("XDG_SESSION_DESKTOP", "").equalsIgnoreCase("KDE"));
    }
    /** Short OS label shown in Settings ("Windows Build", "Linux Build", "Mac Build"). */
    public static String osLabel() { return isMac() ? "Mac" : isLinux() ? "Linux" : "Windows"; }
    /** Release channel note for this OS build. */
    public static String osStatus() { return isMac() ? "Untested preview" : isLinux() ? "Beta" : "Stable"; }
    private static Path base(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? Path.of(System.getProperty("user.home"), fallback) : Path.of(value);
    }
    private static Path macSupport() { return Path.of(System.getProperty("user.home"), "Library", "Application Support", "MultiMini"); }
    public static Path configDirectory() {
        if (isLinux()) return base("XDG_CONFIG_HOME", ".config").resolve("multimin");
        if (isMac()) return macSupport();
        String appData = System.getenv("APPDATA");
        return base("APPDATA", ".config").resolve(appData == null || appData.isBlank() ? "multimin" : "MultiMin");
    }
    public static Path screenshotDirectory() {
        if (isMac()) return macSupport().resolve("screenshots");
        return (isLinux() ? base("XDG_DATA_HOME", ".local/share") : base("LOCALAPPDATA", ".local/share"))
            .resolve("MultiMin").resolve("screenshots");
    }
}
