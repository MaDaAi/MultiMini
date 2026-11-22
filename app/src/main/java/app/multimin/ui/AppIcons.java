package app.multimin.ui;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Program icons for captured windows (Windows only: the backend reports each
 * window's executable path; the shell supplies its icon). Other platforms fall
 * back to the capture-type logo.
 */
final class AppIcons {
    private AppIcons() {}
    private static final Map<Long, BufferedImage> byHandle = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> byPath = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> failed = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    static void onChange(Runnable listener) { listeners.add(listener); }
    static BufferedImage forHandle(long handle) { return handle == 0 ? null : byHandle.get(handle); }

    /** Thread-safe; icon lookup happens off the EDT, listeners run on the EDT. */
    static void register(long handle, String path) {
        if (handle == 0 || path == null || path.isBlank() || failed.containsKey(path)) return;
        BufferedImage icon = byPath.computeIfAbsent(path, AppIcons::load);
        if (icon == null) { byPath.remove(path); failed.put(path, true); return; }
        if (byHandle.put(handle, icon) != icon) SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }

    private static BufferedImage load(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return null;
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(file, 32, 32);
            if (icon == null || icon.getIconWidth() <= 0) return null;
            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (icon instanceof ImageIcon imageIcon) g.drawImage(imageIcon.getImage(), 0, 0, 32, 32, null);
            else icon.paintIcon(null, g, (32 - icon.getIconWidth()) / 2, (32 - icon.getIconHeight()) / 2);
            g.dispose();
            return image;
        } catch (Exception | Error error) {
            return null;
        }
    }

    /** Rounded badge: program icon when known, otherwise the capture-type logo on its colour. */
    static void paintBadge(Graphics2D g, Palette palette, app.multimin.model.Source.Kind kind, BufferedImage icon, int x, int y, int size) {
        g = (Graphics2D) g.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        float radius = size * 0.3f;
        if (icon != null) {
            g.setColor(palette.card()); g.fill(Theme.round(x, y, size, size, radius));
            g.setColor(palette.hairline()); g.draw(Theme.round(x + 0.5f, y + 0.5f, size - 1, size - 1, radius));
            int inner = Math.round(size * 0.62f);
            g.drawImage(icon, x + (size - inner) / 2, y + (size - inner) / 2, inner, inner, null);
        } else {
            Color tint = SourceCellRenderer.kindColor(kind);
            g.setPaint(new GradientPaint(x, y, Palette.blend(tint, Color.WHITE, 0.12f), x, y + size, Palette.blend(tint, Color.BLACK, 0.18f)));
            g.fill(Theme.round(x, y, size, size, radius));
            int inner = Math.round(size * 0.55f);
            new VectorIcon(VectorIcon.forSource(kind), inner, Color.WHITE).paintIcon(null, g, x + (size - inner) / 2, y + (size - inner) / 2);
        }
        g.dispose();
    }
}
