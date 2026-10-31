package app.multimin.ui;

import javax.swing.JPanel;
import java.awt.*;

public class GlassPanel extends JPanel {
    private final Palette palette;
    public GlassPanel(Palette palette) { this.palette = palette; setOpaque(false); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        GradientPaint frost = new GradientPaint(0, 0, new Color(palette.panel.getRed(), palette.panel.getGreen(), palette.panel.getBlue(), Math.min(245, palette.panel.getAlpha() + 18)), getWidth(), getHeight(), palette.panel);
        g.setPaint(frost); g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(255, 255, 255, 18)); g.drawLine(0, 0, getWidth(), 0);
        g.setColor(palette.line);
        g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
        g.dispose();
        super.paintComponent(graphics);
    }
}
