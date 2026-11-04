package app.multimin.ui;

import app.multimin.model.Source;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.IdentityHashMap;
import java.util.Map;

/** Reuses Java2D surfaces for composition; the original image remains the fallback. */
final class FrameTextureCache implements AutoCloseable {
    private static final class Entry {
        VolatileImage surface;
        BufferedImage uploaded;
        int width, height;
    }

    private final Map<Source, Entry> entries = new IdentityHashMap<>();
    private final Map<Source, app.multimin.backend.NativeImageScaler> scalers = new IdentityHashMap<>();
    Image scaledImage(Source source,int width,int height) {
        return scalers.computeIfAbsent(source,ignored -> new app.multimin.backend.NativeImageScaler()).resize(source.liveFrame,width,height);
    }
    void release(Source source) {
        Entry entry=entries.remove(source);
        if(entry!=null && entry.surface!=null)entry.surface.flush();
        scalers.remove(source);
    }
    private int acceleratedSources;

    Image imageFor(Source source, GraphicsConfiguration configuration) {
        BufferedImage frame = source.liveFrame;
        if (frame == null || configuration == null) return frame;
        // Captures are physical pixels. HiDPI VolatileImages allocate scaled
        // backing storage and their Graphics scales the upload again. Drawing
        // that texture back at native size resamples text twice. Keep one scale
        // operation by drawing the original raster directly on HiDPI displays.
        var transform = configuration.getDefaultTransform();
        if (transform.getScaleX() != 1.0 || transform.getScaleY() != 1.0) {
            Entry previous = entries.remove(source);
            if (previous != null && previous.surface != null) previous.surface.flush();
            return frame;
        }
        Entry entry = entries.computeIfAbsent(source, ignored -> new Entry());
        if (entry.surface == null || entry.width != frame.getWidth() || entry.height != frame.getHeight()) {
            if (entry.surface != null) entry.surface.flush();
            entry.surface = configuration.createCompatibleVolatileImage(frame.getWidth(), frame.getHeight(), Transparency.TRANSLUCENT);
            entry.width = frame.getWidth();
            entry.height = frame.getHeight();
            entry.uploaded = null;
        }
        int status = entry.surface.validate(configuration);
        if (status == VolatileImage.IMAGE_INCOMPATIBLE) {
            entry.surface.flush();
            entry.surface = configuration.createCompatibleVolatileImage(frame.getWidth(), frame.getHeight(), Transparency.TRANSLUCENT);
            entry.uploaded = null;
        } else if (status == VolatileImage.IMAGE_RESTORED) entry.uploaded = null;
        if (!entry.surface.getCapabilities().isAccelerated()) return frame;
        if (entry.uploaded != frame) {
            Graphics2D upload = entry.surface.createGraphics();
            try {
                upload.setComposite(java.awt.AlphaComposite.Src);
                upload.drawImage(frame, 0, 0, null);
            } finally { upload.dispose(); }
            if (entry.surface.contentsLost()) return frame;
            entry.uploaded = frame;
        }
        return entry.surface;
    }

    void retain(javax.swing.DefaultListModel<Source> sources) {
        scalers.keySet().removeIf(source -> !sources.contains(source));
        entries.entrySet().removeIf(item -> {
            if (sources.contains(item.getKey())) return false;
            if (item.getValue().surface != null) item.getValue().surface.flush();
            return true;
        });
    }

    int countAcceleratedSources() {
        acceleratedSources = 0;
        for (Entry entry : entries.values())
            if (entry.surface != null && entry.surface.getCapabilities().isAccelerated()) acceleratedSources++;
        return acceleratedSources;
    }

    @Override public void close() {
        for (Entry entry : entries.values()) if (entry.surface != null) entry.surface.flush();
        entries.clear();
        scalers.clear();
    }
}
