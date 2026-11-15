package app.multimin.ui;
import app.multimin.backend.*;
import app.multimin.model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import javax.swing.*;
/** Disposable-profile fixture driven by the owned-renderer probe. */
public final class LinuxDirectPresentationFixture {
 private static MainWindow window;
 private static PythonBridge backend;
 private static Object get(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private static void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 private static void call(String name,Class<?>[] types,Object...args)throws Exception {Method m=MainWindow.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(window,args);}
 public static void main(String[] args)throws Exception {
  SwingUtilities.invokeAndWait(()->{try{
   backend=new PythonBridge();set(backend,"ready",true);window=new MainWindow(backend);
   View v=(View)get(window,"activeView");v.sources.clear();
   Source s=new Source("Owned renderer",Source.Kind.GAME);s.nativeWindowHandle=Long.parseLong(args[0]);s.scale=100;s.x=s.y=30;
   s.liveFrame=new BufferedImage(320,240,BufferedImage.TYPE_INT_ARGB);v.sources.addElement(s);v.controlSource=0;
   window.setVisible(true);call("setMultitask",new Class<?>[]{boolean.class},true);
   SwingUtilities.invokeLater(()->{try{
    PreviewPanel p=(PreviewPanel)get(window,"preview");JWindow overlay=(JWindow)get(window,"multitaskWindow");
    Rectangle r=p.sourceBoundsOnScreen(0);
    System.out.printf("OWNED %d %d %d %d %d %d%n",ProcessHandle.current().pid(),LinuxWindowIdentity.get(overlay),r.x,r.y,r.width,r.height);System.out.flush();
   }catch(Exception e){throw new RuntimeException(e);}});
  }catch(Exception e){throw new RuntimeException(e);}});
  TimerFailsafe.start();
  var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
  String line;
  while((line=input.readLine())!=null){
   if(line.equals("native"))SwingUtilities.invokeAndWait(()->{try{set(backend,"linuxInputMode","native");call("updateLinuxDirectPresentation",new Class<?>[0]);System.out.println("DIRECT "+(((PreviewPanel)get(window,"preview")).hasLinuxDirectSource()));System.out.flush();}catch(Exception e){throw new RuntimeException(e);}});
   if(line.equals("reset"))SwingUtilities.invokeAndWait(()->{try{call("resetLinuxDirectPresentation",new Class<?>[0]);Toolkit.getDefaultToolkit().sync();System.out.println("RESET");System.out.flush();}catch(Exception e){throw new RuntimeException(e);}});
   if(line.equals("stop"))break;
  }
  SwingUtilities.invokeAndWait(()->{try{call("resetLinuxDirectPresentation",new Class<?>[0]);call("shutdownServices",new Class<?>[0]);for(Window w:Window.getWindows())w.dispose();}catch(Exception e){throw new RuntimeException(e);}});
  System.exit(0);
 }
 private static final class TimerFailsafe {static void start(){Thread t=new Thread(()->{try{Thread.sleep(20000);}catch(InterruptedException ignored){}System.exit(2);});t.setDaemon(true);t.start();}}
}
