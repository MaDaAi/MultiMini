package app.multimin.backend;

import java.awt.Window;
import java.nio.file.*;

/** Identity of our own realized X11 Swing peer; no target-window discovery. */
public final class LinuxWindowIdentity {
    private static final boolean AVAILABLE=load();
    private LinuxWindowIdentity() {}
    private static boolean load(){
        if(!PlatformSupport.isLinux() || !java.awt.Toolkit.getDefaultToolkit().getClass().getName().contains("X11"))return false;
        try {
            System.load(Path.of(System.getProperty("java.home"),"lib","libjawt.so").toString());
            try(var input=LinuxWindowIdentity.class.getResourceAsStream("/native/libmultimini-linux.so")) {
                if(input==null)return false;
                Path library=Files.createTempFile("multimini-linux-window-",".so");
                Files.copy(input,library,StandardCopyOption.REPLACE_EXISTING);
                library.toFile().deleteOnExit();System.load(library.toString());return true;
            }
        }catch(Exception|LinkageError error){return false;}
    }
    public static long get(Window window){
        try{return AVAILABLE && window!=null && window.isDisplayable()?windowId(window):0;}
        catch(RuntimeException|LinkageError error){return 0;}
    }
    private static native long windowId(Window window);
}
