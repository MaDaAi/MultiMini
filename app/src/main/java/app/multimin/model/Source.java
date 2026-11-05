package app.multimin.model;

import java.awt.image.BufferedImage;
import java.awt.Color;

public final class Source {
    public enum Kind {
        DISPLAY("Display capture", "▣"), WINDOW("Window capture", "▤"),
        IMAGE("Image", "◇"), VIDEO("Video", "▶"), GAME("Game capture", "◉");

        public final String label;
        public final String glyph;
        Kind(String label, String glyph) { this.label = label; this.glyph = glyph; }
        @Override public String toString() { return label; }
    }

    public String name;
    public boolean customName;
    public final Kind kind;
    public boolean visible = true;
    public int opacity = 100;
    public int scale = 38;
    /** Per-axis scale overrides; -1 follows the proportional scale slider. */
    public int scaleX = -1;
    public int scaleY = -1;
    public boolean freeTransform;
    public int x = 56;
    public int y = 52;
    public boolean quickSwapInput;
    public Color controlColor = new Color(94, 153, 255);
    public String inputTarget = "";
    public String windowProcess = "";
    public long nativeWindowHandle;
    public int displayDeviceIndex;
    /** Capture strategy for window sources: AUTO, WINDOW, or SCREEN. */
    public String captureMethod = "AUTO";
    public boolean nativeGameTest = false;
    public transient boolean nativeDisplayFrames;
    public transient boolean nativeDisplayAttempted;
    public boolean usesWindowTarget() { return kind == Kind.WINDOW || (kind == Kind.GAME && !nativeGameTest); }
    public boolean captureCursor = false;
    public String imagePath = "";
    public transient volatile BufferedImage liveFrame;
    /** Previous WGC image, reused only after the EDT has replaced liveFrame. */
    public transient volatile BufferedImage standbyFrame;
    public transient String captureFramePath;
    public transient long lastCaptureModified;
    public transient long lastCaptureSize;
    public transient long lastMemorySequence;
    public transient String activeCaptureBackend = "Waiting for capture";
    public transient long captureLatencyMillis;
    public transient long capturedFrames;
    public transient long skippedFrames;
    public transient long producedNanos;
    public transient long presentedFrames;
    public transient long lastPresentedCapture;
    public transient double publicationToPaintMillis;
    public boolean nativePresentationEnabled = true;
    public transient boolean nativeCompositionActive;
    public transient int nativeSourceWidth, nativeSourceHeight;

    public Source(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
        if (kind == Kind.DISPLAY) { opacity = 100; scale = 100; x = 0; y = 0; quickSwapInput = true; }
        if (kind == Kind.WINDOW) { scale = 100; x = 0; y = 0; quickSwapInput = true; }
        if (kind == Kind.IMAGE) { scale = 28; x = 8; y = 12; }
    }

    @Override public String toString() { return kind.glyph + "  " + name + "  ·  " + kind.label; }
}
