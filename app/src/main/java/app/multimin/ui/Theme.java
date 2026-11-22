package app.multimin.ui;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

/** Typography, radii and soft-shadow helpers shared by the configure-mode UI. */
final class Theme {
    private Theme() {}
    static final int RADIUS = 10, RADIUS_LARGE = 14, RADIUS_SMALL = 7;
    static final String FAMILY;
    static final String SEMIBOLD;
    static {
        String[] families;
        try { families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(); }
        catch (Exception error) { families = new String[0]; }
        java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(families));
        String chosen = Font.DIALOG;
        for (String candidate : new String[]{"Segoe UI", "SF Pro Text", ".AppleSystemUIFont", "Helvetica Neue", "Inter", "Cantarell", "Noto Sans", "Ubuntu", "DejaVu Sans"})
            if (available.contains(candidate)) { chosen = candidate; break; }
        FAMILY = chosen;
        SEMIBOLD = available.contains("Segoe UI Semibold") && chosen.equals("Segoe UI") ? "Segoe UI Semibold" : null;
    }
    static boolean isThemeFont(Font font) { return font != null && (font.getFamily().equals(FAMILY) || font.getName().equals(FAMILY) || (SEMIBOLD != null && (font.getName().equals(SEMIBOLD) || font.getFamily().equals(SEMIBOLD)))); }
    static Font regular(float size) { return new Font(FAMILY, Font.PLAIN, Math.round(size)).deriveFont(size); }
    /** Semibold where the platform has it, otherwise bold. */
    static Font semibold(float size) { return SEMIBOLD != null ? new Font(SEMIBOLD, Font.PLAIN, Math.round(size)).deriveFont(size) : new Font(FAMILY, Font.BOLD, Math.round(size)).deriveFont(size); }
    /** Small uppercase section label with a little letter spacing. */
    static Font eyebrow(float size) { return semibold(size).deriveFont(Map.of(TextAttribute.TRACKING, 0.08f)); }
    /** Headings: semibold at the font's own spacing (tightened tracking rendered unevenly). */
    static Font title(float size) { return semibold(size); }

    static Graphics2D quality(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }
    static RoundRectangle2D.Float round(float x, float y, float w, float h, float radius) { return new RoundRectangle2D.Float(x, y, w, h, radius, radius); }

    /** Soft drop shadow under a rounded rectangle (layered translucent rings). */
    static void shadow(Graphics2D g, float x, float y, float w, float h, float radius, int spread, Color color, int strength) {
        Composite previous = g.getComposite();
        for (int i = spread; i >= 1; i--) {
            float alpha = strength / 255f * (1f - (float) i / (spread + 1)) / spread * 2.2f;
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(255, Math.max(0, Math.round(alpha * 255)))));
            g.fill(round(x - i, y - i + i * 0.45f, w + i * 2, h + i * 2, radius + i * 2));
        }
        g.setComposite(previous);
    }
    static Color withAlpha(Color color, int alpha) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))); }
}
