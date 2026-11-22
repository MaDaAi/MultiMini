package app.multimin.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Shared button. Variants via client properties:
 * primary (constructor flag): accent fill with a soft glow;
 * default: raised surface with a hairline edge;
 * "appearance.ghost": no surface until hovered or selected (tabs, quiet icons);
 * "appearance.textOnly": text only.
 * Hover and press fade smoothly; keyboard focus draws a ring.
 */
public final class ModernButton extends JButton {
    private final Palette palette;
    private final boolean primary;
    private float hoverLevel;
    private Timer fade;

    public ModernButton(String text, Icon icon, Palette palette, boolean primary) {
        super(text, icon); this.palette = palette; this.primary = primary;
        setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setForeground(Color.WHITE); setBorder(new EmptyBorder(7, 12, 7, 12)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRolloverEnabled(true);
        setFont(primary ? Theme.semibold(13f) : Theme.regular(13f));
        setHorizontalTextPosition(SwingConstants.RIGHT); setIconTextGap(7);
        getModel().addChangeListener(event -> animateTo(getModel().isRollover() || getModel().isPressed() ? 1f : 0f));
    }

    private void animateTo(float target) {
        if (fade == null) {
            fade = new Timer(16, null);
            fade.addActionListener(event -> {
                float goal = getModel().isRollover() || getModel().isPressed() ? 1f : 0f;
                hoverLevel += (goal - hoverLevel) * 0.35f;
                if (Math.abs(goal - hoverLevel) < 0.02f) { hoverLevel = goal; fade.stop(); }
                repaint();
            });
        }
        if (Math.abs(target - hoverLevel) > 0.01f && !fade.isRunning()) fade.start();
    }

    @Override public void setToolTipText(String text) {
        super.setToolTipText(text);
        if (getText() == null || getText().isBlank()) getAccessibleContext().setAccessibleName(text);
    }

    @Override public Point getToolTipLocation(java.awt.event.MouseEvent event) { return new Point(0, -34); }
    @Override public JToolTip createToolTip() {
        JToolTip tip = new JToolTip() { @Override public void updateUI() { setUI(new javax.swing.plaf.basic.BasicToolTipUI()); } };
        tip.setComponent(this); tip.setBackground(palette.card()); tip.setForeground(palette.text); tip.setFont(Theme.regular(12f));
        tip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.hairline()), new EmptyBorder(5, 9, 5, 9)));
        return tip;
    }

    private boolean flag(String name) { return Boolean.TRUE.equals(getClientProperty(name)); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.quality(graphics);
        ButtonModel model = getModel();
        boolean textOnly = flag("appearance.textOnly"), ghost = flag("appearance.ghost");
        boolean selected = model.isSelected();
        float w = getWidth(), h = getHeight(), radius = Math.min(Theme.RADIUS, h / 2f);
        RoundRectangle2D.Float shape = Theme.round(1, 1, w - 2, h - 2, radius);
        if (primary) {
            Color base = palette.accent;
            if (!isEnabled()) base = Palette.blend(palette.panel, base, 0.45f);
            if (isEnabled() && hoverLevel > 0) Theme.shadow(g, 1, 2, w - 2, h - 2, radius, 5, palette.accent, Math.round(90 * hoverLevel));
            Color top = Palette.blend(base, Color.WHITE, 0.10f + 0.06f * hoverLevel), bottom = Palette.blend(base, Color.BLACK, model.isPressed() ? 0.12f : 0.02f);
            g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom)); g.fill(shape);
            g.setColor(Theme.withAlpha(Color.WHITE, 46)); g.draw(Theme.round(1.5f, 1.5f, w - 3, h - 3, radius - 1));
        } else if (!textOnly) {
            Color surface = palette.controlSurface();
            Color fill = selected ? palette.accentTint(palette.panel, palette.isLight() ? 0.14f : 0.22f)
                : ghost ? Theme.withAlpha(palette.hover(palette.panel), Math.round(255 * hoverLevel))
                : Palette.blend(surface, palette.text, (palette.isLight() ? 0.04f : 0.06f) * hoverLevel);
            if (model.isPressed()) fill = Palette.blend(selected ? fill : surface, palette.text, 0.12f);
            if (!isEnabled()) fill = Palette.blend(palette.panel, surface, 0.5f);
            g.setColor(fill); g.fill(shape);
            if (!ghost || selected) {
                Color edge = selected ? palette.accentTint(palette.hairline(), 0.55f) : Palette.blend(palette.hairline(), palette.controlBorder(), 0.25f + 0.35f * hoverLevel);
                g.setColor(edge); g.draw(Theme.round(1.5f, 1.5f, w - 3, h - 3, radius - 1));
            }
        }
        if (isFocusOwner()) {
            g.setColor(Theme.withAlpha(primary ? Palette.contrastText(palette.accent) : palette.controlAccent(), 220));
            g.setStroke(new BasicStroke(2f));
            g.draw(Theme.round(2.5f, 2.5f, w - 5, h - 5, radius - 2));
        }
        g.dispose();
        setForeground(!isEnabled() ? palette.muted : primary ? palette.selectionText() : selected ? palette.text
            : ghost && hoverLevel < 0.5f ? Palette.blend(palette.muted, palette.text, 0.35f + hoverLevel) : palette.text);
        super.paintComponent(graphics);
    }
}
