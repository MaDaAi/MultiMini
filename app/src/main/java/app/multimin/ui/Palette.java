package app.multimin.ui;

import java.awt.Color;

public final class Palette {
    // Layered neutrals with a faint cool tint: app chrome < panels < raised controls.
    public Color primary = new Color(20, 23, 28);
    public Color accent = new Color(103, 153, 255);
    public Color background = new Color(13, 15, 19);
    public Color panel = new Color(20, 23, 28);
    public Color panelRaised = new Color(29, 33, 41);
    public Color line = new Color(37, 42, 51);
    public Color text = new Color(236, 239, 244);
    public Color muted = new Color(146, 154, 168);
    private boolean lightMode;

    public Color controlSurface() { return new Color(panelRaised.getRed(), panelRaised.getGreen(), panelRaised.getBlue()); }
    public boolean isLight() { return lightMode; }
    /** Deepest surface: the canvas well behind the composition preview. */
    public Color canvas() { return lightMode ? blend(background, new Color(210, 215, 224), 0.35f) : blend(background, Color.BLACK, 0.35f); }
    /** Card surface one step above panels (rows, tiles, grouped settings). */
    public Color card() { return lightMode ? Color.WHITE : blend(panel, panelRaised, 0.55f); }
    /** Hover wash for interactive surfaces. */
    public Color hover(Color base) { return blend(base, text, lightMode ? 0.05f : 0.07f); }
    /** Hairline used for decorative card edges (controls use controlBorder). */
    public Color hairline() { return lightMode ? new Color(222, 226, 233) : blend(panel, text, 0.10f); }
    /** Accent at a given strength over a surface, for soft tints and glows. */
    public Color accentTint(Color base, float amount) { return blend(base, accent, amount); }

    /** Opaque, low-emphasis selection for navigation and list rows. */
    public Color selectionSurface() { return blend(controlSurface(), accent, 0.16f); }

    public static Color blend(Color base, Color tint, float amount) {
        return new Color(Math.round(base.getRed() * (1 - amount) + tint.getRed() * amount),
            Math.round(base.getGreen() * (1 - amount) + tint.getGreen() * amount),
            Math.round(base.getBlue() * (1 - amount) + tint.getBlue() * amount));
    }


    /** A visible control boundary, distinct from decorative panel dividers. */
    public Color controlBorder() { return readable(blend(controlSurface(), text, 0.48f), controlSurface(), 3.0); }
    public Color controlAccent() { return readable(accent, panel, 3.0); }
    private Color readable(Color color, Color surface, double minimum) {
        for (int step = 0; step <= 20; step++) {
            Color candidate = blend(color, contrastText(surface), step / 20f);
            if (contrastRatio(candidate, surface) >= minimum) return candidate;
        }
        return contrastText(surface);
    }
    public static double contrastRatio(Color a, Color b) {
        double x = 0.2126 * channel(a.getRed()) + 0.7152 * channel(a.getGreen()) + 0.0722 * channel(a.getBlue());
        double y = 0.2126 * channel(b.getRed()) + 0.7152 * channel(b.getGreen()) + 0.0722 * channel(b.getBlue());
        return (Math.max(x,y) + 0.05) / (Math.min(x,y) + 0.05);
    }

    public Color selectionText() {
        return contrastText(accent);
    }

    public static Color contrastText(Color background) {
        double r = channel(background.getRed()), g = channel(background.getGreen()), b = channel(background.getBlue());
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return (1.05 / (luminance + 0.05)) >= ((luminance + 0.05) / 0.05) ? Color.WHITE : Color.BLACK;
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    public void dark() {
        lightMode = false;
        primary = new Color(20, 23, 28); background = new Color(13, 15, 19);
        panel = new Color(20, 23, 28); panelRaised = new Color(29, 33, 41);
        line = new Color(37, 42, 51); text = new Color(236, 239, 244); muted = new Color(146, 154, 168);
    }

    public void light() {
        lightMode = true;
        primary = new Color(248, 249, 251); background = new Color(236, 239, 243);
        panel = new Color(248, 249, 251); panelRaised = new Color(240, 243, 247);
        line = new Color(221, 225, 232); text = new Color(22, 26, 33); muted = new Color(92, 100, 114);
    }

    public void customBase(Color base) {
        background = base;
        int brightness = (base.getRed() * 299 + base.getGreen() * 587 + base.getBlue() * 114) / 1000;
        if (brightness < 145) {
            primary = base.brighter(); panel = withAlpha(base.brighter(), 220); panelRaised = withAlpha(base.brighter().brighter(), 225);
            text = new Color(238, 241, 244); muted = new Color(171, 180, 186); line = new Color(125, 137, 144, 145);
        } else {
            primary = base.darker(); panel = withAlpha(base.brighter(), 210); panelRaised = withAlpha(base, 230);
            text = new Color(28, 34, 39); muted = new Color(84, 96, 103); line = new Color(95, 108, 116, 130);
        }
    }

    private static Color withAlpha(Color color, int alpha) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha); }
}
