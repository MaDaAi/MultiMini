package app.multimin.ui;

import javax.swing.JPanel;
import java.awt.*;

public final class BackgroundPanel extends JPanel {
    private final Palette palette;
    private boolean transparent;
    public BackgroundPanel(Palette palette) { super(new BorderLayout()); this.palette = palette; setOpaque(false); }
    public void setTransparent(boolean transparent) { this.transparent = transparent; repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        if (transparent) { super.paintComponent(graphics); return; }
        Graphics2D g = (Graphics2D) graphics.create();
        Color glow = new Color(Math.min(255, palette.background.getRed() + 24), Math.min(255, palette.background.getGreen() + 28), Math.min(255, palette.background.getBlue() + 34));
        g.setPaint(new GradientPaint(0, 0, glow, getWidth(), getHeight(), palette.background)); g.fillRect(0, 0, getWidth(), getHeight()); g.dispose();
        super.paintComponent(graphics);
    }
}
