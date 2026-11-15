package app.multimin.ui;
import app.multimin.model.*;
import java.awt.*;
import java.awt.image.*;
/** Regression for stale alpha in the Linux transparent multitask surface. */
public final class LinuxOverlayRepaintCheck {
 public static void main(String[] args)throws Exception {
  if(!app.multimin.backend.PlatformSupport.isLinux()){System.out.println("SKIP: Linux-only overlay raster path");return;}
  javax.swing.SwingUtilities.invokeAndWait(()->{
   View v=new View("test");Source s=new Source("test",Source.Kind.DISPLAY);
   s.scale=50;s.opacity=50;s.liveFrame=new BufferedImage(320,180,BufferedImage.TYPE_INT_ARGB);
   Graphics2D q=s.liveFrame.createGraphics();q.setColor(Color.RED);q.fillRect(0,0,320,180);q.dispose();v.sources.addElement(s);
   PreviewPanel p=new PreviewPanel(new Palette(),v);
   if(!p.needsTransparentOverlay())throw new AssertionError("Display overlay must retain transparency");
   p.setSize(320,180);p.setLocked(true);
   BufferedImage out=new BufferedImage(320,180,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
   p.paint(g);int a=out.getRGB(20,20);p.paint(g);int b=out.getRGB(20,20);

   s.x=100;p.paint(g);int old=out.getRGB(20,20);
   g.dispose();p.removeNotify();
   if(a==0 || a!=b || old!=0)throw new AssertionError("Overlay retains old pixels/accumulates alpha");
   v.sources.clear();v.sources.addElement(new Source("window",Source.Kind.WINDOW));
   if(p.needsTransparentOverlay())throw new AssertionError("Window-only overlay must use opaque presentation");
   System.out.println("PASS: repeated translucent frames remain stable; moved layers erase old pixels");
  });System.exit(0);
 }
}
