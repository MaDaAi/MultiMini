package app.multimin.ui;
import javax.swing.border.AbstractBorder;
import java.awt.*;
/** Rounded input/dropdown edge: readable 3:1 boundary, accent ring when focused. */
final class ControlBorder extends AbstractBorder {
    private final Palette palette;
    ControlBorder(Palette palette) { this.palette=palette; }
    @Override public Insets getBorderInsets(Component c) { return new Insets(2,4,2,2); }
    @Override public Insets getBorderInsets(Component c, Insets i) { i.set(2,4,2,2);return i; }
    @Override public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h) {
        Graphics2D g=Theme.quality(graphics);
        boolean focus=c.hasFocus()||(c instanceof javax.swing.JComboBox<?> combo&&combo.isPopupVisible());
        if(focus){g.setColor(Theme.withAlpha(palette.accent,70));g.setStroke(new BasicStroke(3f));g.draw(Theme.round(x+1.5f,y+1.5f,w-3,h-3,8));}
        g.setColor(focus?palette.controlAccent():palette.controlBorder());
        g.setStroke(new BasicStroke(1f));g.draw(Theme.round(x+0.5f,y+0.5f,w-1,h-1,8));g.dispose();
    }
}
