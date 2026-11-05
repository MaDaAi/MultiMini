package app.multimin.ui;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
public class UpdateDialogCheck {
 public static void main(String[] args)throws Exception{
  String notes="# Update\n\n## Plain English\n- The app icon now looks right.\n\n## Detailed\n- Corrected Windows relaunch metadata.";
  if(!UpdateDialog.summary(notes).equals("- The app icon now looks right."))throw new AssertionError("Summary extraction");
  if(!UpdateDialog.detailed(notes).equals("- Corrected Windows relaunch metadata."))throw new AssertionError("Detailed extraction");
  SwingUtilities.invokeAndWait(()->{for(String mode:new String[]{"dark","light"}){Palette p=new Palette();if(mode.equals("light"))p.light();Timer timer=new Timer(350,e->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().equals("MultiMini update")){try{BufferedImage image=new BufferedImage(d.getContentPane().getWidth(),d.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();d.getContentPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",Path.of(args[0],"update-"+mode+".png").toFile());}catch(Exception ex){throw new RuntimeException(ex);}finally{d.dispose();}}});timer.setRepeats(false);timer.start();if(UpdateDialog.offer(null,p,"1.0","1.0.1",notes))throw new AssertionError("Closing dialog must defer");}});
  System.out.println("PASS: separate plain/detailed logs, dark/light dialog rendering, close defers update; no installer executed");
 }
}
