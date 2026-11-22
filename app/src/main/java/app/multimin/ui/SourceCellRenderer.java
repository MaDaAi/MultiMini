package app.multimin.ui;

import app.multimin.model.Source;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Source row card: live mini thumbnail (or a coloured type tile), name and type,
 * then the visibility and input-cycle toggles. Toggle hit areas match
 * MainWindow's click handling: eye in [width-56, width-30), mouse in [width-30, width].
 */
public final class SourceCellRenderer extends JPanel implements ListCellRenderer<Source> {
    private final Palette palette;
    private final boolean helpers;
    private Source source;
    private boolean selected;

    public SourceCellRenderer(Palette palette) { this(palette, true); }
    public SourceCellRenderer(Palette palette, boolean helpers) {
        this.palette = palette; this.helpers = helpers;
        setOpaque(false);
    }

    static Color kindColor(Source.Kind kind) {
        return switch (kind) {
            case WINDOW -> new Color(96, 148, 255);
            case GAME -> new Color(72, 199, 132);
            case DISPLAY -> new Color(245, 158, 72);
            case IMAGE -> new Color(178, 120, 240);
            default -> new Color(140, 150, 170);
        };
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (source == null) return;
        Graphics2D g = Theme.quality(graphics);
        int w = getWidth(), h = getHeight();
        g.setColor(palette.panel); g.fillRect(0, 0, w, h);
        float x = 2, y = 3, cw = w - 4, ch = h - 6;
        Color card = selected ? palette.accentTint(palette.card(), palette.isLight() ? 0.10f : 0.16f) : palette.card();
        g.setColor(card); g.fill(Theme.round(x, y, cw, ch, Theme.RADIUS));
        g.setColor(selected ? palette.accentTint(palette.hairline(), 0.6f) : palette.hairline());
        g.draw(Theme.round(x + 0.5f, y + 0.5f, cw - 1, ch - 1, Theme.RADIUS));
        if (selected) { g.setColor(palette.accent); g.fill(Theme.round(x + 1, y + 12, 3, ch - 24, 3)); }

        // Thumbnail or type tile.
        int tx = 12, tw = 46, th = 30, ty = (h - th) / 2;
        Shape tile = Theme.round(tx, ty, tw, th, 7);
        BufferedImage frame = source.liveFrame;
        Color kind = kindColor(source.kind);
        if (frame != null && frame.getWidth() > 0) {
            Graphics2D t = (Graphics2D) g.create(); t.clip(tile);
            t.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.max((double) tw / frame.getWidth(), (double) th / frame.getHeight());
            int dw = (int) Math.ceil(frame.getWidth() * scale), dh = (int) Math.ceil(frame.getHeight() * scale);
            t.drawImage(frame, tx + (tw - dw) / 2, ty + (th - dh) / 2, dw, dh, null);
            if (!source.visible) { t.setColor(Theme.withAlpha(palette.panel, 170)); t.fill(tile); }
            t.dispose();
            g.setColor(Theme.withAlpha(Color.WHITE, palette.isLight() ? 0 : 22)); g.draw(tile);
        } else {
            g.setPaint(new GradientPaint(tx, ty, Palette.blend(palette.card(), kind, 0.32f), tx + tw, ty + th, Palette.blend(palette.card(), kind, 0.14f)));
            g.fill(tile);
            new VectorIcon(VectorIcon.forSource(source.kind), 15, Palette.blend(kind, palette.text, palette.isLight() ? 0f : 0.25f))
                .paintIcon(this, g, tx + (tw - 15) / 2, ty + (th - 15) / 2);
        }

        // Name and type.
        int textX = tx + tw + 12, right = w - 62;
        g.setFont(Theme.semibold(13f));
        g.setColor(source.visible ? palette.text : palette.muted);
        String name = ellipsize(g, source.name, right - textX);
        FontMetrics nameMetrics = g.getFontMetrics();
        int nameY = helpers ? h / 2 - 2 : h / 2 + nameMetrics.getAscent() / 2 - 2;
        g.drawString(name, textX, nameY);
        if (helpers) {
            g.setFont(Theme.regular(11f));
            g.setColor(palette.muted);
            g.fillOval(textX, nameY + 9, 6, 6);
            g.setColor(kind); g.fillOval(textX, nameY + 9, 6, 6);
            g.setColor(palette.muted);
            g.drawString(ellipsize(g, source.kind.label + (source.visible ? "" : " · hidden"), right - textX - 12), textX + 11, nameY + 16);
        }

        // Toggles.
        int eyeX = w - 43 - 9, mouseX = w - 17 - 9, iconY = (h - 18) / 2;
        new VectorIcon(source.visible ? VectorIcon.Type.EYE : VectorIcon.Type.EYE_OFF, 18, source.visible ? palette.text : palette.muted).paintIcon(this, g, eyeX, iconY);
        boolean input = source.quickSwapInput && (source.kind == Source.Kind.DISPLAY || source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME);
        if (input) { g.setColor(Theme.withAlpha(source.controlColor, 50)); g.fillOval(mouseX - 4, iconY - 4, 26, 26); }
        new VectorIcon(VectorIcon.Type.MOUSE, 18, input ? source.controlColor : Palette.blend(palette.muted, palette.panel, 0.25f)).paintIcon(this, g, mouseX, iconY);
        g.dispose();
    }

    private static String ellipsize(Graphics2D g, String text, int width) {
        FontMetrics m = g.getFontMetrics();
        if (m.stringWidth(text) <= width) return text;
        String suffix = "…";
        for (int end = text.length(); end > 0; end--) if (m.stringWidth(text.substring(0, end) + suffix) <= width) return text.substring(0, end) + suffix;
        return suffix;
    }

    @Override public Component getListCellRendererComponent(JList<? extends Source> list, Source source, int index, boolean selected, boolean focused) {
        this.source = source; this.selected = selected;
        setToolTipText(source.quickSwapInput ? "Included in the input cycle" : "Click the pointer icon to include in the input cycle");
        getAccessibleContext().setAccessibleName(source.name + ", " + source.kind.label + (source.visible ? ", visible" : ", hidden") + (source.quickSwapInput ? ", included in input switching" : ""));
        return this;
    }
}
