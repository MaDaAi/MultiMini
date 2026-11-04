package app.multimin.backend;

import java.io.InputStream;
import java.nio.file.*;

/** Optional Windows JNI copy from page-file backed shared memory into Java ARGB storage. */
final class SharedFrameBridge {
    static final boolean AVAILABLE = load();
    private static boolean load() {
        if (!System.getProperty("os.name", "").startsWith("Windows") ||
            Boolean.getBoolean("multimin.disableSharedFrames")) return false;
        try (InputStream input = SharedFrameBridge.class.getResourceAsStream("/native/multimin-frame-bridge.dll")) {
            if (input == null) return false;
            Path library = Files.createTempFile("multimin-frame-bridge-", ".dll");
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString());
            return true;
        } catch (Exception | LinkageError error) { return false; }
    }
    static native long open(String name, int bytes);
    static native int excludeOwnWindows();
    static native void copy(long address, int[] output);
    static native void release(long address);
    static native boolean resize(int[] input, int width, int height, int[] output, int targetWidth, int targetHeight);
}
