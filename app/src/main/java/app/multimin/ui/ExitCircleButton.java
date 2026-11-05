package app.multimin.ui;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
/** Non-rectangular, translucent exit control; does not accept keyboard focus. */
final class ExitCircleButton extends JButton {
    ExitCircleButton(){setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setOpaque(false);setFocusable(false);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setBackground(new Color(103,153,255));setToolTipText("Exit multitask mode");getAccessibleContext().setAccessibleName("Exit multitask mode");}
    @Override public boolean contains(int x,int y){return new Ellipse2D.Double(0,0,getWidth(),getHeight()).contains(x,y);}
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color c=getBackground();g.setColor(new Color(20,24,30,getModel().isRollover()?210:155));g.fillOval(1,1,getWidth()-2,getHeight()-2);g.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),210));g.drawOval(2,2,getWidth()-5,getHeight()-5);g.setColor(new Color(255,255,255,235));g.drawLine(13,13,25,25);g.drawLine(25,13,13,25);g.dispose();}
}
