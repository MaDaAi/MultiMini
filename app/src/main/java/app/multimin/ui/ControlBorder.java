package app.multimin.ui;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
final class ControlBorder extends AbstractBorder {
    private final Palette palette;
    ControlBorder(Palette palette) { this.palette=palette; }
    @Override public Insets getBorderInsets(Component c) { return new Insets(1,1,1,1); }
    @Override public Insets getBorderInsets(Component c, Insets i) { i.set(1,1,1,1);return i; }
    @Override public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h) {
        Graphics2D g=(Graphics2D)graphics.create();
        g.setColor(c.hasFocus()?palette.text:palette.controlBorder());
        g.setStroke(new BasicStroke(c.hasFocus()?2f:1f));g.drawRect(x,y,w-1,h-1);g.dispose();
    }
}
