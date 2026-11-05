package app.multimin.ui;

import javax.swing.*;
import javax.swing.plaf.basic.*;
import java.awt.*;

/** Reinstalls owned delegates after Swing look-and-feel refreshes. */
final class ModernUi {
    static void scroll(JScrollBar bar, Palette p) {
        if (bar.getClientProperty("modern.scroll") == null) {
            bar.putClientProperty("modern.scroll", true);
            bar.addPropertyChangeListener("UI", e -> { if (!(bar.getUI() instanceof Scroll)) scroll(bar,p); });
        }
        bar.setUI(new Scroll(p)); bar.setPreferredSize(new Dimension(10,10));
    }
    static void split(JSplitPane pane, Palette p) {
        if (pane.getClientProperty("modern.split") == null) {
            pane.putClientProperty("modern.split",true);
            pane.addPropertyChangeListener("UI",e -> {if (!(pane.getUI() instanceof Split)) split(pane,p);});
        }
        int location=pane.getDividerLocation();
        pane.setUI(new Split(p)); pane.setBorder(null); pane.setDividerSize(4);
        if(location>=0) pane.setDividerLocation(location);
    }
    private static final class Split extends BasicSplitPaneUI {
        final Palette p; Split(Palette p){this.p=p;}
        @Override public BasicSplitPaneDivider createDefaultDivider(){return new BasicSplitPaneDivider(this){
            @Override public void paint(Graphics g){g.setColor(p.accent);g.fillRect(0,0,getWidth(),getHeight());}
        };}
    }
    private static final class Scroll extends BasicScrollBarUI {
        final Palette p; Scroll(Palette p){this.p=p;}
        @Override protected void configureScrollBarColors(){trackColor=p.primary;thumbColor=p.accent;}
        private JButton empty(){JButton b=new JButton();b.setPreferredSize(new Dimension());b.setMinimumSize(new Dimension());b.setMaximumSize(new Dimension());return b;}
        @Override protected JButton createDecreaseButton(int n){return empty();}
        @Override protected JButton createIncreaseButton(int n){return empty();}
        @Override protected void paintTrack(Graphics g,JComponent c,Rectangle r){g.setColor(p.primary);g.fillRect(r.x,r.y,r.width,r.height);}
        @Override protected void paintThumb(Graphics g,JComponent c,Rectangle r){if(r.isEmpty())return;Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(isDragging?p.accent.brighter():p.accent);a.fillRoundRect(r.x+2,r.y+2,Math.max(2,r.width-4),Math.max(2,r.height-4),8,8);a.dispose();}
    }
}
