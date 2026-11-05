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
            pane.addPropertyChangeListener("UI",e -> {if (!(pane.getUI() instanceof Split)) SwingUtilities.invokeLater(()->{if(!(pane.getUI() instanceof Split))split(pane,p);});});
        }
        int location=pane.getDividerLocation();
        pane.setUI(new Split(p)); pane.setBorder(null); pane.setDividerSize(8);
        BasicSplitPaneDivider divider=((BasicSplitPaneUI)pane.getUI()).getDivider();
        // Own the divider gesture; UI delegate refreshes must not leave stale
        // BasicSplitPane mouse handlers attached to the narrow accent strip.
        for(var listener:divider.getMouseListeners())divider.removeMouseListener(listener);
        for(var listener:divider.getMouseMotionListeners())divider.removeMouseMotionListener(listener);
        divider.setBorder(BorderFactory.createEmptyBorder());
        divider.setCursor(Cursor.getPredefinedCursor(pane.getOrientation()==JSplitPane.HORIZONTAL_SPLIT?Cursor.E_RESIZE_CURSOR:Cursor.N_RESIZE_CURSOR));
        javax.swing.event.MouseInputAdapter drag=new javax.swing.event.MouseInputAdapter(){
            int anchor,initial;boolean active;
            private int coordinate(java.awt.event.MouseEvent e){Point point=SwingUtilities.convertPoint(divider,e.getPoint(),pane);return pane.getOrientation()==JSplitPane.HORIZONTAL_SPLIT?point.x:point.y;}
            @Override public void mousePressed(java.awt.event.MouseEvent e){if(!SwingUtilities.isLeftMouseButton(e)||!pane.isEnabled())return;anchor=coordinate(e);initial=pane.getDividerLocation();active=true;e.consume();}
            @Override public void mouseDragged(java.awt.event.MouseEvent e){if(!active)return;int desired=initial+coordinate(e)-anchor;int minimum=pane.getMinimumDividerLocation(),maximum=pane.getMaximumDividerLocation();pane.setDividerLocation(Math.max(minimum,Math.min(maximum,desired)));pane.revalidate();e.consume();}
            @Override public void mouseReleased(java.awt.event.MouseEvent e){active=false;}
        };divider.addMouseListener(drag);divider.addMouseMotionListener(drag);
        if(location>=0) pane.setDividerLocation(location);
    }
    static void popup(JPopupMenu menu,Palette p){
        menu.setOpaque(true);menu.setUI(new BasicPopupMenuUI(){@Override public void paint(Graphics g,JComponent c){g.setColor(p.controlSurface());g.fillRect(0,0,c.getWidth(),c.getHeight());}});
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){menu.setBackground(p.controlSurface());menu.repaint();}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}});
        menu.setBackground(p.controlSurface());menu.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(p.line),BorderFactory.createEmptyBorder(5,5,5,5)));
        for(Component component:menu.getComponents()){
            if(component instanceof JMenuItem item){item.setFont(new Font(Font.DIALOG,Font.PLAIN,13));item.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));item.setUI(new BasicMenuItemUI(){@Override public void paint(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);boolean hover=item.isArmed()||item.isSelected();g.setColor(p.controlSurface());g.fillRect(0,0,c.getWidth(),c.getHeight());if(hover&&item.isEnabled()){g.setColor(p.accent);g.fillRoundRect(0,0,c.getWidth(),c.getHeight(),8,8);}g.setFont(item.getFont());g.setColor(!item.isEnabled()?p.muted:hover?p.selectionText():p.text);FontMetrics metrics=g.getFontMetrics();g.drawString(item.getText(),12,(c.getHeight()-metrics.getHeight())/2+metrics.getAscent());g.dispose();}});}
            else if(component instanceof JSeparator separator){separator.setPreferredSize(new Dimension(1,9));separator.setUI(new javax.swing.plaf.basic.BasicSeparatorUI(){@Override public void paint(Graphics g,JComponent c){g.setColor(p.line);g.drawLine(6,4,c.getWidth()-6,4);}});}
        }
    }
    private static final class Split extends BasicSplitPaneUI {
        final Palette p; Split(Palette p){this.p=p;}
        @Override public BasicSplitPaneDivider createDefaultDivider(){BasicSplitPaneDivider divider=new BasicSplitPaneDivider(this){
            @Override public void paint(Graphics g){g.setColor(p.background);g.fillRect(0,0,getWidth(),getHeight());g.setColor(p.accent);if(getWidth()<getHeight())g.fillRect((getWidth()-2)/2,0,2,getHeight());else g.fillRect(0,(getHeight()-2)/2,getWidth(),2);}
        };divider.setBorder(BorderFactory.createEmptyBorder());return divider;}
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
