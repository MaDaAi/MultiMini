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
        g.setColor(palette.isLight() ? new Color(214, 219, 227) : Palette.blend(palette.panel, palette.text, 0.16f));
        g.fillRoundRect(left, y, width, 4, 4, 4);
        int filled = Math.max(0, thumbRect.x + thumbRect.width / 2 - left);
        g.setColor(palette.controlAccent());
        g.fillRoundRect(left, y, Math.min(width, filled), 4, 4, 4);
        g.dispose();
    }

    @Override public void paintThumb(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Point pointer = GraphicsEnvironment.isHeadless() ? null : slider.getMousePosition();
        boolean hovered = pointer != null && thumbRect.contains(pointer);
        // Simple solid knob: accent fill, no ring; a soft halo only while hovered or dragged.
        int size = 14;
        int x = thumbRect.x + (thumbRect.width - size) / 2;
        int y = thumbRect.y + (thumbRect.height - size) / 2;
        if (hovered || isDragging()) {
            g.setColor(Theme.withAlpha(palette.accent, 50));
            g.fillOval(x - 5, y - 5, size + 10, size + 10);
        }
        if (slider.hasFocus()) { g.setColor(Theme.withAlpha(palette.controlAccent(), 160)); g.setStroke(new BasicStroke(1.5f)); g.drawOval(x - 3, y - 3, size + 6, size + 6); }
        g.setColor(palette.controlAccent()); g.fillOval(x, y, size, size);
        g.dispose();
    }

    @Override protected Dimension getThumbSize() { return new Dimension(20, 20); }
}
