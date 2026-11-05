package app.multimin.ui;

import java.awt.Color;

public final class Palette {
    public Color primary = new Color(38, 43, 52);
    public Color accent = new Color(103, 153, 255);
    public Color background = new Color(24, 27, 33);
    public Color panel = new Color(35, 39, 48, 218);
    public Color panelRaised = new Color(48, 54, 65, 225);
    public Color line = new Color(82, 90, 105, 150);
    public Color text = new Color(232, 235, 240);
    public Color muted = new Color(150, 157, 169);

    public Color controlSurface() { return new Color(panelRaised.getRed(), panelRaised.getGreen(), panelRaised.getBlue()); }

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
        primary = new Color(38, 43, 52); background = new Color(24, 27, 33);
        panel = new Color(35, 39, 48, 218); panelRaised = new Color(48, 54, 65, 225);
        line = new Color(82, 90, 105, 150); text = new Color(232, 235, 240); muted = new Color(150, 157, 169);
    }

    public void light() {
        primary = new Color(230, 235, 240); background = new Color(205, 214, 220);
        panel = new Color(245, 248, 250, 205); panelRaised = new Color(225, 231, 236, 230);
        line = new Color(134, 146, 154, 130); text = new Color(31, 37, 43); muted = new Color(91, 103, 111);
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
