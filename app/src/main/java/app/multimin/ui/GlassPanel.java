package app.multimin.ui;

import javax.swing.JPanel;
import java.awt.*;

public class GlassPanel extends JPanel {
    private final Palette palette;
    public GlassPanel(Palette palette) { this.palette = palette; setOpaque(false); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(palette.panel); g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
        super.paintComponent(graphics);
    }
}
