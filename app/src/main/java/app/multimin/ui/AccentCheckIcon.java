package app.multimin.ui;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.*;

final class AccentCheckIcon implements Icon {
    private final Palette palette;
    AccentCheckIcon(Palette palette) { this.palette = palette; }
    @Override public int getIconWidth() { return 17; }
    @Override public int getIconHeight() { return 17; }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean selected = component instanceof AbstractButton && ((AbstractButton) component).isSelected();
        g.setColor(selected ? palette.accent : palette.controlSurface());
        g.fillRoundRect(x + 1, y + 1, 14, 14, 4, 4);
        g.setColor(selected ? palette.accent : palette.line);
        g.drawRoundRect(x + 1, y + 1, 14, 14, 4, 4);
        if (selected) {
            g.setColor(palette.selectionText()); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 4, y + 8, x + 7, y + 11); g.drawLine(x + 7, y + 11, x + 12, y + 5);
        }
        g.dispose();
    }
}
