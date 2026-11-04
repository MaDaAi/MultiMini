package app.multimin.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class ModernButton extends JButton {
    private final Palette palette;
    private final boolean primary;

    public ModernButton(String text, Icon icon, Palette palette, boolean primary) {
        super(text, icon); this.palette = palette; this.primary = primary;
        setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setForeground(Color.WHITE); setBorder(new EmptyBorder(7, 10, 7, 10)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setHorizontalTextPosition(SwingConstants.RIGHT); setIconTextGap(7);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ButtonModel model = getModel(); Color base = primary ? palette.accent : palette.panelRaised;
        if (model.isPressed()) base = base.darker(); else if (model.isSelected()) base = palette.accent; else if (model.isRollover()) base = base.darker();
        g.setColor(base); g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8); g.setColor(isFocusOwner() ? palette.accent : palette.line); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8); g.dispose();
        setForeground(primary || model.isSelected() ? palette.selectionText() : palette.text); super.paintComponent(graphics);
    }

    private static int brightness(Color c) { return (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000; }
}
