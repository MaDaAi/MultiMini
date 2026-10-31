package app.multimin.ui;

import app.multimin.model.Source;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class SourceCellRenderer extends JPanel implements ListCellRenderer<Source> {
    private final Palette palette;
    private final JLabel glyph = new JLabel();
    private final JLabel name = new JLabel();
    private final JLabel type = new JLabel();
    private final JLabel state = new JLabel();
    private final JLabel quickSwap = new JLabel();

    public SourceCellRenderer(Palette palette) {
        super(new BorderLayout(9, 0));
        this.palette = palette;
        setBorder(new EmptyBorder(5, 8, 5, 8));
        glyph.setHorizontalAlignment(SwingConstants.CENTER);
        glyph.setPreferredSize(new Dimension(26, 26));
        JPanel text = new JPanel(new GridLayout(0, 1)); text.setOpaque(false);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
        type.setFont(type.getFont().deriveFont(10f)); type.setForeground(palette.muted);
        text.add(name); text.add(type);
        JPanel indicators = new JPanel(new GridLayout(1, 2, 5, 0)); indicators.setOpaque(false);
        state.setHorizontalAlignment(SwingConstants.CENTER); state.setPreferredSize(new Dimension(20, 20)); quickSwap.setHorizontalAlignment(SwingConstants.CENTER); quickSwap.setPreferredSize(new Dimension(24, 24));
        indicators.add(state); indicators.add(quickSwap);
        add(glyph, BorderLayout.WEST); add(text, BorderLayout.CENTER); add(indicators, BorderLayout.EAST);
    }

    @Override public Component getListCellRendererComponent(JList<? extends Source> list, Source source, int index, boolean selected, boolean focused) {
        Color foreground = selected ? palette.selectionText() : palette.text;
        glyph.setText(""); glyph.setIcon(new VectorIcon(VectorIcon.forSource(source.kind), 16, foreground)); name.setText(source.name); type.setText(source.kind.label); state.setText(""); state.setIcon(new VectorIcon(source.visible ? VectorIcon.Type.EYE : VectorIcon.Type.EYE_OFF, 16, source.visible ? foreground : palette.muted));
        name.setForeground(foreground); type.setForeground(selected ? foreground : palette.muted);
        state.setForeground(source.visible ? palette.accent : palette.muted);
        quickSwap.setIcon(new VectorIcon(VectorIcon.Type.MOUSE, 17, source.quickSwapInput ? source.controlColor : palette.muted));
        quickSwap.setToolTipText(source.quickSwapInput ? "Included in Right Ctrl input cycle" : "Click to include in Right Ctrl input cycle");
        setBackground(selected ? palette.accent : palette.panel);
        setOpaque(true);
        return this;
    }
}
