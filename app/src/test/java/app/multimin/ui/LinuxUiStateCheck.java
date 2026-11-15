package app.multimin.ui;

import app.multimin.backend.PythonBridge;
import app.multimin.model.Source;
import app.multimin.model.View;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import javax.swing.*;

/** Controlled GUI state test. Does not capture/control any external application. */
public final class LinuxUiStateCheck {
    static Object field(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static Object call(Object object,String name)throws Exception {
        Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);
    }
    static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            MainWindow window=null;
            try {
                window=new MainWindow(new PythonBridge());
                View view=(View)field(window,"activeView");
                check(view.sources.isEmpty(),"New workspace has sources");
                Source first=new Source("Controlled red",Source.Kind.WINDOW);
                Source second=new Source("Controlled blue",Source.Kind.WINDOW);
                first.opacity=37;second.opacity=71;
                first.nativeWindowHandle=11;second.nativeWindowHandle=22;
                first.liveFrame=new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB);
                second.liveFrame=new BufferedImage(320,180,BufferedImage.TYPE_INT_RGB);
                view.sources.addElement(first);view.sources.addElement(second);view.controlSource=0;
                call(window,"cycleQuickSwapTarget");
                check(view.controlSource==1,"Input did not cycle");
                check(view.sources.get(0)==first,"Input changed visual order");
                call(window,"swapVisibleLayers");
                check(view.sources.get(0)==second,"Visual order did not switch");
                check(view.sources.get(view.controlSource)==second,"Visual order changed input target");
                check(first.opacity==37 && second.opacity==71,"Switching changed opacity");
                window.setVisible(true);window.validate();
                BufferedImage composition=(BufferedImage)call(window,"linuxComposition");
                check(composition.getWidth()>100 && composition.getHeight()>100,"Composition is empty");
                ScreenshotStore gallery=new ScreenshotStore();
                Path path=gallery.save(composition);
                check(javax.imageio.ImageIO.read(path.toFile()).getWidth()==composition.getWidth(),"PNG dimensions changed");
                WorkspaceStore store=new WorkspaceStore();store.save(List.of(view),view);
                View restored=store.load().views().get(0);
                check(restored.sources.size()==2,"Source count not restored");
                check(restored.sources.get(0).opacity==71,"Order/opacity not restored");
                check(restored.sources.get(0).nativeWindowHandle==0,"Stale native handle persisted");
                System.out.println("PASS: empty workspace, independent input/layer switching, opacity preserved, composition PNG and workspace reload");
            } catch(Exception e){throw new RuntimeException(e);}
            finally {if(window!=null){try{call(window,"shutdownServices");}catch(Exception e){throw new RuntimeException(e);}window.dispose();}}
        });
        System.exit(0);
    }
}
