import app.multimin.backend.NativeWindowCompositor;
import java.awt.*;
import javax.swing.*;

/** Actual desktop pixels: native DWM update, live refresh, opacity and removal. */
public class DwmSurfaceCheck {
 static JFrame source;
 static Window target;
 static Rectangle physical(Rectangle rectangle) {
  var t=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
  return new Rectangle((int)Math.round(rectangle.x*t.getScaleX()),(int)Math.round(rectangle.y*t.getScaleY()),
    (int)Math.round(rectangle.width*t.getScaleX()),(int)Math.round(rectangle.height*t.getScaleY()));
 }
 static Color sample()throws Exception {Thread.sleep(200);return new Robot().getPixelColor(850,230);}
 public static void main(String[] args)throws Exception {
  for(boolean transparent:new boolean[]{false,true}) {
   SwingUtilities.invokeAndWait(() -> {
    source=new JFrame("DWM test source");source.getContentPane().setBackground(Color.RED);source.setBounds(0,0,200,150);source.setVisible(true);
    if(transparent){JWindow window=new JWindow();window.setBackground(new Color(0,0,0,0));((JPanel)window.getContentPane()).setOpaque(false);target=window;}
    else {JFrame frame=new JFrame("DWM test target");frame.getContentPane().setBackground(Color.BLACK);target=frame;}
    target.setBounds(700,100,400,300);target.setVisible(true);
   });
   long thumbnail=0;
   try {
    Thread.sleep(300);
    Rectangle sr=physical(new Rectangle(20,40,100,60)),tr=physical(new Rectangle(740,160,220,160));
    long origin=NativeWindowCompositor.destination(sr.x,sr.y,sr.width,sr.height);
    long destination=NativeWindowCompositor.destination(tr.x,tr.y,tr.width,tr.height);
    thumbnail=NativeWindowCompositor.register(destination,origin);
    if(thumbnail==0)throw new AssertionError("Cannot register thumbnail");
    long size=NativeWindowCompositor.size(thumbnail);
    if(!NativeWindowCompositor.update(thumbnail,destination,tr.x,tr.y,tr.width,tr.height,0,0,(int)(size>>>32),(int)size,255))throw new AssertionError("Cannot update thumbnail");
    Color red=sample();System.out.println("transparent="+transparent+" red="+red);
    if(red.getRed()<220 || red.getGreen()>20)throw new AssertionError("Native surface did not render red");
    SwingUtilities.invokeAndWait(()->{source.getContentPane().setBackground(Color.GREEN);source.repaint();});
    Color green=sample();System.out.println("live green="+green);
    if(green.getGreen()<220 || green.getRed()>20)throw new AssertionError("Live source update missing");
    NativeWindowCompositor.update(thumbnail,destination,tr.x,tr.y,tr.width,tr.height,0,0,(int)(size>>>32),(int)size,128);
    Color half=sample();System.out.println("half opacity="+half);
    if(half.getGreen()<110 || half.getGreen()>160)throw new AssertionError("Opacity not applied");
    JFrame[] second={null};
    SwingUtilities.invokeAndWait(()->{second[0]=new JFrame("DWM blue source");second[0].getContentPane().setBackground(Color.BLUE);second[0].setBounds(0,500,200,150);second[0].setVisible(true);});
    long upper=0;
    try {
     Thread.sleep(200);Rectangle br=physical(new Rectangle(20,540,100,60));
     long blue=NativeWindowCompositor.destination(br.x,br.y,br.width,br.height);
     upper=NativeWindowCompositor.register(destination,blue);long bs=NativeWindowCompositor.size(upper);
     NativeWindowCompositor.update(upper,destination,tr.x,tr.y,tr.width,tr.height,0,0,(int)(bs>>>32),(int)bs,255);
     Color bluePixel=sample();if(bluePixel.getBlue()<220 || bluePixel.getGreen()>20)throw new AssertionError("New top layer not blue: "+bluePixel);
     NativeWindowCompositor.unregister(thumbnail);thumbnail=NativeWindowCompositor.register(destination,origin);
     NativeWindowCompositor.update(thumbnail,destination,tr.x,tr.y,tr.width,tr.height,0,0,(int)(size>>>32),(int)size,255);
     Color swapped=sample();if(swapped.getGreen()<220 || swapped.getBlue()>20)throw new AssertionError("Layer reorder failed: "+swapped);
     System.out.println("PASS: overlapping native layer order and swap");
    } finally {if(upper!=0)NativeWindowCompositor.unregister(upper);SwingUtilities.invokeAndWait(second[0]::dispose);}
    NativeWindowCompositor.unregister(thumbnail);thumbnail=0;Color cleared=sample();System.out.println("unregistered="+cleared);
    if(cleared.getGreen()>60)throw new AssertionError("Native surface leaked after unregister");
   } finally {
    if(thumbnail!=0)NativeWindowCompositor.unregister(thumbnail);
    SwingUtilities.invokeAndWait(()->{target.dispose();source.dispose();});
   }
  }
 }
}
