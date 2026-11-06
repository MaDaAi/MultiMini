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
        g.setColor(palette.background); g.fillRect(0, 0, getWidth(), getHeight()); g.dispose();
        super.paintComponent(graphics);
    }
}
