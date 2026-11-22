package app.multimin.ui;

import javax.swing.JSlider;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;

/** A slim track and a high contrast thumb, using the shared accent palette. */
final class AccentSliderUI extends BasicSliderUI {
    private final Palette palette;

    AccentSliderUI(JSlider slider, Palette palette) {
        super(slider); this.palette = palette;
        slider.setOpaque(false);
        slider.setFocusable(true);
        slider.setBackground(palette.background);
    }

    @Override public void paintTrack(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int y = trackRect.y + trackRect.height / 2 - 2;
        int left = trackRect.x, width = trackRect.width;
        g.setColor(palette.isLight() ? new Color(214, 219, 227) : Palette.blend(palette.panel, palette.text, 0.14f));
        g.fillRoundRect(left, y, width, 4, 4, 4);
        int filled = Math.max(0, thumbRect.x + thumbRect.width / 2 - left);
        Color accent = palette.controlAccent();
        g.setPaint(new GradientPaint(left, 0, Palette.blend(accent, palette.panel, 0.25f), left + Math.max(1, filled), 0, accent));
        g.fillRoundRect(left, y, Math.min(width, filled), 4, 4, 4);
        g.dispose();
    }

    @Override public void paintThumb(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Point pointer = GraphicsEnvironment.isHeadless() ? null : slider.getMousePosition();
        boolean hovered = pointer != null && thumbRect.contains(pointer);
        int size = 16;
        int x = thumbRect.x + (thumbRect.width - size) / 2;
        int y = thumbRect.y + (thumbRect.height - size) / 2;
        if (hovered || slider.hasFocus() || isDragging()) {
            g.setColor(Theme.withAlpha(palette.accent, 60));
            g.fillOval(x - 5, y - 5, size + 10, size + 10);
        }
        g.setColor(Theme.withAlpha(Color.BLACK, palette.isLight() ? 40 : 90)); g.fillOval(x, y + 1, size, size);
        g.setColor(palette.isLight() ? Color.WHITE : new Color(244, 246, 250)); g.fillOval(x, y, size, size);
        g.setColor(palette.controlAccent()); g.setStroke(new BasicStroke(2.5f)); g.drawOval(x + 2, y + 2, size - 4, size - 4);
        g.dispose();
    }

    @Override protected Dimension getThumbSize() { return new Dimension(20, 20); }
}
