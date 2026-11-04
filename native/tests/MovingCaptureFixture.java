import java.awt.*;
import javax.swing.*;
import java.util.concurrent.locks.LockSupport;

/** Controlled animated window for WGC pacing tests. */
public class MovingCaptureFixture {
 public static void main(String[] args) throws Exception {
  JFrame frame=new JFrame("MultiMini moving capture fixture"); Canvas canvas=new Canvas();
  SwingUtilities.invokeAndWait(() -> {frame.add(canvas);frame.setSize(980,580);frame.setLocation(50,50);frame.setVisible(true);canvas.createBufferStrategy(2);});
  long start=System.nanoTime(), next=start, sample=start; int rendered=0, sampled=0;
  try {
   while(System.nanoTime()-start<60_000_000_000L && frame.isDisplayable()) {
    var buffers=canvas.getBufferStrategy();var g=buffers.getDrawGraphics();
    g.setColor(new Color((rendered*3)&255,(rendered*7)&255,(rendered*11)&255));g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());
    g.setColor(Color.WHITE);g.drawString("Moving WGC fixture frame "+rendered,30,50);g.dispose();buffers.show();
    rendered++;next+=1_000_000_000L/144;long delay=next-System.nanoTime();if(delay>0)LockSupport.parkNanos(delay);else next=System.nanoTime();
    long now=System.nanoTime();if(now-sample>=1_000_000_000L){System.out.printf("Fixture draw FPS %.1f%n",(rendered-sampled)*1e9/(now-sample));sample=now;sampled=rendered;}
   }
  } finally {System.out.printf("Fixture %.1f rendered FPS%n",rendered*1e9/(System.nanoTime()-start));SwingUtilities.invokeAndWait(frame::dispose);}
 }
}
