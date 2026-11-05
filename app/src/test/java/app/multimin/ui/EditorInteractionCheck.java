package app.multimin.ui;
import app.multimin.model.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
public class EditorInteractionCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(1);});
  JSplitPane[] panes={null};JFrame[] frames={null};
  SwingUtilities.invokeAndWait(()->{JPanel left=new JPanel(),right=new JPanel();left.setMinimumSize(new Dimension(70,0));right.setMinimumSize(new Dimension(70,0));JSplitPane pane=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,right);ModernUi.split(pane,new Palette());pane.setContinuousLayout(true);JFrame f=new JFrame();f.setContentPane(pane);f.setSize(800,500);f.addNotify();f.validate();pane.setDividerLocation(260);panes[0]=pane;frames[0]=f;pane.updateUI();});
  SwingUtilities.invokeAndWait(()->{JSplitPane pane=panes[0];frames[0].validate();pane.doLayout();Component divider=((BasicSplitPaneUI)pane.getUI()).getDivider();int before=pane.getDividerLocation();System.out.println("UI="+pane.getUI().getClass()+" Divider="+divider.getClass()+" "+divider.getBounds()+" insets="+((Container)divider).getInsets()+" listeners="+divider.getMouseListeners().length+" before="+before);long t=System.currentTimeMillis();int x=divider.getWidth()/2,y=100;divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_PRESSED,t,MouseEvent.BUTTON1_DOWN_MASK,x,y,1,false,MouseEvent.BUTTON1));divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_DRAGGED,t+1,MouseEvent.BUTTON1_DOWN_MASK,x-100,y,0,false,MouseEvent.NOBUTTON));divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_RELEASED,t+2,0,x-100,y,1,false,MouseEvent.BUTTON1));check(pane.getDividerLocation()<before,"divider must respond to mouse dragging after UI refresh");frames[0].dispose();});
  SwingUtilities.invokeAndWait(()->{MainWindow window=null;try{
   window=new MainWindow(new app.multimin.backend.PythonBridge());window.setExtendedState(JFrame.NORMAL);window.setSize(1180,740);window.addNotify();window.validate();
   Field config=MainWindow.class.getDeclaredField("configuration");config.setAccessible(true);JSplitPane outer=(JSplitPane)((JPanel)config.get(window)).getComponent(0);JSplitPane inner=(JSplitPane)outer.getLeftComponent();inner.doLayout();
   int before=inner.getDividerLocation();Component divider=((BasicSplitPaneUI)inner.getUI()).getDivider();long now=System.currentTimeMillis();int x=divider.getWidth()/2;
   divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_PRESSED,now,MouseEvent.BUTTON1_DOWN_MASK,x,100,1,false,MouseEvent.BUTTON1));divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_DRAGGED,now+1,MouseEvent.BUTTON1_DOWN_MASK,x-120,100,0,false,MouseEvent.NOBUTTON));divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_RELEASED,now+2,0,x,100,1,false,MouseEvent.BUTTON1));
   check(inner.getDividerLocation()<before,"main window nested left divider must shrink");check(inner.getDividerLocation()<160,"left panel can shrink below half default width");
   Method theme=MainWindow.class.getDeclaredMethod("refreshTheme");theme.setAccessible(true);theme.invoke(window);check(((BasicSplitPaneUI)inner.getUI()).getDivider()==divider,"theme must preserve live divider handlers");System.out.println("PASS: actual main-window left divider shrinks below half width and survives theme refresh");
  }catch(Exception e){throw new RuntimeException(e);}finally{if(window!=null){try{Method stop=MainWindow.class.getDeclaredMethod("shutdownServices");stop.setAccessible(true);stop.invoke(window);}catch(Exception ignored){}window.dispose();}}});
  SwingUtilities.invokeAndWait(()->{try{Palette p=new Palette();View view=new View("test");Source s=new Source("sample",Source.Kind.IMAGE);s.scale=30;view.sources.addElement(s);PreviewPanel panel=new PreviewPanel(p,view);panel.setSize(800,500);Rectangle bounds=panel.sourceBoundsInPanel(0);Field field=PreviewPanel.class.getDeclaredField("canvas");field.setAccessible(true);Rectangle canvas=(Rectangle)field.get(panel);Method snap=PreviewPanel.class.getDeclaredMethod("snapPosition",int.class,int.class,boolean.class,Source.class);snap.setAccessible(true);int centered=canvas.x+canvas.width/2-bounds.width/2;check((int)snap.invoke(panel,centered+3,bounds.width,true,s)==centered,"center snapping");check((int)snap.invoke(panel,canvas.x+4,bounds.width,true,s)==canvas.x,"edge snapping");check((int)snap.invoke(panel,canvas.x+31,bounds.width,true,s)==canvas.x+31,"free movement outside tolerance");panel.removeNotify();System.out.println("PASS: divider mouse dragging after UI refresh, center/edge snapping, free movement");}catch(Exception e){throw new RuntimeException(e);}});
 }
}
