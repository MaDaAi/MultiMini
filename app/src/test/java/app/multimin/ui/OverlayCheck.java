package app.multimin.ui;
import app.multimin.backend.PythonBridge;
import javax.swing.*;
import java.lang.reflect.*;
import java.awt.*;

/** Exercises real exit windows without starting global hooks or a capture service. */
public class OverlayCheck {
    static Object get(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void call(Object owner,String name,Class<?> type,Object argument)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(owner,argument);}
    public static void main(String[] args)throws Exception {
        PythonBridge backend=new PythonBridge();Field ready=PythonBridge.class.getDeclaredField("ready");ready.setAccessible(true);ready.set(backend,true);
        MainWindow[] window={null};
        try {
            SwingUtilities.invokeAndWait(()->{window[0]=new MainWindow(backend);try{call(window[0],"setMultitask",boolean.class,true);}catch(Exception e){throw new RuntimeException(e);}});
            SwingUtilities.invokeAndWait(()->{try{if(!((JWindow)get(window[0],"multitaskControls")).isVisible())throw new AssertionError("exit initially visible");if(!(get(window[0],"multitaskExitButton") instanceof ExitCircleButton))throw new AssertionError("circular exit");}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(3200);
            SwingUtilities.invokeAndWait(()->{try{JWindow controls=(JWindow)get(window[0],"multitaskControls");Rectangle hotspot=(Rectangle)get(window[0],"exitHotspot");if(!hotspot.contains(MouseInfo.getPointerInfo().getLocation())&&controls.isVisible())throw new AssertionError("exit must auto-hide");Method reveal=MainWindow.class.getDeclaredMethod("revealExit");reveal.setAccessible(true);reveal.invoke(window[0]);if(!controls.isVisible())throw new AssertionError("exit reveal");call(window[0],"setMultitask",boolean.class,false);if(get(window[0],"multitaskControls")!=null)throw new AssertionError("exit disposed on stop");System.out.println("Overlay visibility, circular control, reveal and shutdown checks passed.");}catch(Exception e){throw new RuntimeException(e);}});
        }finally{SwingUtilities.invokeAndWait(()->{if(window[0]!=null){try{Method stop=MainWindow.class.getDeclaredMethod("shutdownServices");stop.setAccessible(true);stop.invoke(window[0]);}catch(Exception e){throw new RuntimeException(e);}window[0].dispose();}});}
    }
}
