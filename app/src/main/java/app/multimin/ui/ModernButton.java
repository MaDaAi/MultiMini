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
        setRolloverEnabled(true);
        setHorizontalTextPosition(SwingConstants.RIGHT); setIconTextGap(7);
    }

    @Override public void setToolTipText(String text) {
        super.setToolTipText(text);
        if (getText() == null || getText().isBlank()) getAccessibleContext().setAccessibleName(text);
    }

    @Override public Point getToolTipLocation(java.awt.event.MouseEvent event) { return new Point(0,-34); }
    @Override public JToolTip createToolTip() {
        JToolTip tip=new JToolTip(){@Override public void updateUI(){setUI(new javax.swing.plaf.basic.BasicToolTipUI());}};
        tip.setComponent(this);tip.setBackground(palette.controlSurface());tip.setForeground(palette.text);tip.setFont(getFont().deriveFont(Font.PLAIN,12f));tip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line),new EmptyBorder(5,8,5,8)));return tip;
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ButtonModel model = getModel();
        boolean emphasized = primary;
        Color base = emphasized ? palette.accent : model.isSelected() ? palette.selectionSurface() : palette.controlSurface();
        if (model.isPressed()) base = Palette.blend(base, palette.text, 0.12f);
        else if (model.isRollover()) base = Palette.blend(base, palette.text, 0.06f);
        if (!isEnabled()) base = Palette.blend(palette.panel, base, 0.45f);
        g.setColor(base);
        if (!Boolean.TRUE.equals(getClientProperty("appearance.textOnly"))) g.fill(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth()-2, getHeight()-2, 10, 10));
        if (Boolean.TRUE.equals(getClientProperty("toolbar.outline"))) {
            g.setColor(palette.controlBorder());
            g.draw(new java.awt.geom.RoundRectangle2D.Float(1.5f,1.5f,getWidth()-3,getHeight()-3,9,9));
        }
        if (isFocusOwner()) {
            g.setColor(emphasized ? Palette.contrastText(base) : palette.controlAccent());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(10, getHeight()-4, getWidth()-11, getHeight()-4);
        }
        g.dispose();
        setForeground(!isEnabled() ? palette.muted : emphasized ? palette.selectionText() : palette.text);
        super.paintComponent(graphics);
    }


}
