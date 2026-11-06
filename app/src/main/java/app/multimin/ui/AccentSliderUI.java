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
        int y = trackRect.y + trackRect.height / 2 - 3;
        int left = trackRect.x, width = trackRect.width;
        g.setColor(new Color(palette.line.getRed(), palette.line.getGreen(), palette.line.getBlue(), 115));
        g.fillRoundRect(left, y, width, 6, 6, 6);
        int filled = Math.max(0, thumbRect.x + thumbRect.width / 2 - left);
        g.setColor(palette.controlAccent()); g.fillRoundRect(left, y, Math.min(width, filled), 6, 6, 6);
        g.dispose();
    }

    @Override public void paintThumb(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Point pointer = GraphicsEnvironment.isHeadless() ? null : slider.getMousePosition();
        boolean hovered = pointer != null && thumbRect.contains(pointer);
        int size = hovered || slider.hasFocus() ? 18 : 16;
        int x = thumbRect.x + (thumbRect.width - size) / 2;
        int y = thumbRect.y + (thumbRect.height - size) / 2;
        if (hovered || slider.hasFocus()) {
            g.setColor(Palette.blend(palette.panel, palette.accent, 0.2f));
            g.fillOval(x - 3, y - 3, size + 6, size + 6);
        }
        g.setColor(palette.controlAccent()); g.fillOval(x, y, size, size);
        g.setColor(Palette.contrastText(palette.controlAccent())); g.fillOval(x + size / 2 - 2, y + size / 2 - 2, 4, 4);
        g.dispose();
    }

    @Override protected Dimension getThumbSize() { return new Dimension(18, 18); }
}
