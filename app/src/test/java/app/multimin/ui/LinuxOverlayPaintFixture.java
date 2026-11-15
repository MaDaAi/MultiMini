package app.multimin.ui;
import app.multimin.model.*;
import app.multimin.backend.LinuxWindowIdentity;
import java.awt.*;
import java.awt.image.*;
import javax.swing.*;
public final class LinuxOverlayPaintFixture {
 public static void main(String[] args)throws Exception {SwingUtilities.invokeAndWait(()->{
  View v=new View("Controlled paint");Source s=new Source("Synthetic pixels",Source.Kind.IMAGE);s.scale=80;v.sources.addElement(s);
  BufferedImage[] frames=new BufferedImage[2];for(int i=0;i<2;i++){frames[i]=new BufferedImage(480,270,BufferedImage.TYPE_INT_ARGB);Graphics2D g=frames[i].createGraphics();g.setColor(i==0?Color.RED:Color.GREEN);g.fillRect(0,0,480,270);g.dispose();}s.liveFrame=frames[0];
  PreviewPanel p=new PreviewPanel(new Palette(),v);p.setLocked(true);
  JWindow w=new JWindow();w.setType(Window.Type.POPUP);w.setFocusableWindowState(false);w.setAlwaysOnTop(true);w.setBackground(p.needsTransparentOverlay()?new Color(0,0,0,0):Color.BLACK);w.setContentPane(p);w.setBounds(30,60,480,270);w.setVisible(true);
  System.out.println(ProcessHandle.current().pid()+" "+LinuxWindowIdentity.get(w));System.out.flush();
  int[] index={0};new Timer(16,e->{s.liveFrame=frames[(++index[0])%2];p.repaint();}).start();
  Timer end=new Timer(12000,e->{w.dispose();System.exit(0);});end.setRepeats(false);end.start();
 });}
}
