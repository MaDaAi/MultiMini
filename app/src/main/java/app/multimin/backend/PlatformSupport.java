package app.multimin.backend;

import java.nio.file.Path;

/** OS integration choices. UI/model state remains shared across builds. */
public final class PlatformSupport {
    private PlatformSupport() {}
    public static boolean isLinux() { return System.getProperty("os.name", "").equalsIgnoreCase("Linux"); }
    public static boolean isWayland() { return isLinux() && ("wayland".equals(System.getenv("XDG_SESSION_TYPE")) || System.getenv("WAYLAND_DISPLAY") != null); }
    public static boolean isKde() {
        return isWayland() && (System.getenv().getOrDefault("XDG_CURRENT_DESKTOP", "").toLowerCase(java.util.Locale.ROOT).contains("kde")
            || System.getenv().getOrDefault("XDG_SESSION_DESKTOP", "").equalsIgnoreCase("KDE"));
    }
    private static Path base(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? Path.of(System.getProperty("user.home"), fallback) : Path.of(value);
    }
    public static Path configDirectory() {
        if (isLinux()) return base("XDG_CONFIG_HOME", ".config").resolve("multimin");
        String appData = System.getenv("APPDATA");
        return base("APPDATA", ".config").resolve(appData == null || appData.isBlank() ? "multimin" : "MultiMin");
    }
    public static Path screenshotDirectory() {
        return (isLinux() ? base("XDG_DATA_HOME", ".local/share") : base("LOCALAPPDATA", ".local/share"))
            .resolve("MultiMin").resolve("screenshots");
    }
}
