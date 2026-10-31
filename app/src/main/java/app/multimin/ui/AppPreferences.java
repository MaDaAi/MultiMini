package app.multimin.ui;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;

/** Small per-user settings store that does not depend on registry permissions. */
final class AppPreferences {
    private final List<Path> files = new ArrayList<>();
    private Path activeFile;
    private final Properties values = new Properties();

    AppPreferences() {
        String appData = System.getenv("APPDATA");
        Path directory = appData == null || appData.isBlank()
            ? Path.of(System.getProperty("user.home"), ".config", "multimin")
            : Path.of(appData, "MultiMin");
        files.add(directory.resolve("settings.properties"));
        files.add(Path.of(System.getProperty("user.home"), ".multimin", "settings.properties"));
        files.add(Path.of(System.getProperty("user.dir"), "multimin-settings.properties"));
        for (Path candidate : files) {
            if (!Files.isRegularFile(candidate)) continue;
            try (InputStream input = Files.newInputStream(candidate)) {
                values.load(input); activeFile = candidate; return;
            } catch (Exception ignored) { }
        }
    }

    synchronized int getInt(String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    synchronized boolean getBoolean(String key, boolean fallback) {
        return Boolean.parseBoolean(values.getProperty(key, Boolean.toString(fallback)));
    }

    synchronized void putInt(String key, int value) {
        values.setProperty(key, Integer.toString(value)); save();
    }

    synchronized void putBoolean(String key, boolean value) {
        values.setProperty(key, Boolean.toString(value)); save();
    }

    private void save() {
        List<Path> ordered = new ArrayList<>();
        if (activeFile != null) ordered.add(activeFile);
        for (Path candidate : files) if (!ordered.contains(candidate)) ordered.add(candidate);
        for (Path file : ordered) {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.getParent());
                try (OutputStream output = Files.newOutputStream(temporary)) { values.store(output, "MultiMin settings"); }
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (Exception unsupportedAtomicMove) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                activeFile = file;
                return;
            } catch (Exception denied) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignoredCleanup) { }
            }
        }
    }
}
