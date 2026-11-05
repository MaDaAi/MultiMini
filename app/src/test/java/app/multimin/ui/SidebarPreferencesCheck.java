package app.multimin.ui;
import app.multimin.backend.PythonBridge;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
public class SidebarPreferencesCheck {
 static MainWindow window;
 static Object field(String name)throws Exception{Field f=MainWindow.class.getDeclaredField(name);f.setAccessible(true);return f.get(window);}
 static void call(String name)throws Exception{Method m=MainWindow.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(window);}
 static void create(){window=new MainWindow(new PythonBridge());window.setExtendedState(JFrame.NORMAL);window.setSize(1180,740);window.setVisible(true);}
 static void close()throws Exception{call("shutdownServices");window.dispose();}
 public static void main(String[] args)throws Exception{Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(1);});
  SwingUtilities.invokeAndWait(SidebarPreferencesCheck::create);
  SwingUtilities.invokeAndWait(()->{try{JSplitPane outer=(JSplitPane)field("sidebarOuter"),inner=(JSplitPane)field("sidebarInner");outer.setDividerLocation(outer.getWidth()-220-outer.getDividerSize());outer.doLayout();inner.setDividerLocation(150);call("saveSidebarWidths");close();}catch(Exception e){throw new RuntimeException(e);}});
  SwingUtilities.invokeAndWait(SidebarPreferencesCheck::create);
  SwingUtilities.invokeAndWait(()->{try{JSplitPane outer=(JSplitPane)field("sidebarOuter"),inner=(JSplitPane)field("sidebarInner");if(inner.getDividerLocation()!=150)throw new AssertionError("Left width did not persist: "+inner.getDividerLocation());int right=outer.getWidth()-outer.getDividerLocation()-outer.getDividerSize();if(right!=220)throw new AssertionError("Right width did not persist: "+right);System.out.println("PASS: both sidebar widths restored across separate app instances");close();}catch(Exception e){throw new RuntimeException(e);}});
 }
}
