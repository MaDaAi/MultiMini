package app.multimin.backend;

import java.awt.*;
import java.awt.event.*;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;

/** Receives events only on our overlay; never hooks Configure or other apps. */
public final class LinuxOverlayInput {
    private final PythonBridge backend;
    private final JComponent overlay;
    private final BooleanSupplier enabled;
    private final java.util.Map<Integer,Long> heldKeys=new java.util.HashMap<>();
    public LinuxOverlayInput(PythonBridge backend, JComponent overlay, BooleanSupplier enabled) {
        this.backend=backend; this.overlay=overlay; this.enabled=enabled;
        MouseAdapter mouse=new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e){sendMouse(e,"motion",0);}
            @Override public void mouseDragged(MouseEvent e){sendMouse(e,"motion",0);}
            @Override public void mousePressed(MouseEvent e){sendMouse(e,"press",button(e));}
            @Override public void mouseReleased(MouseEvent e){sendMouse(e,"release",button(e));}
            @Override public void mouseWheelMoved(MouseWheelEvent e){
                for(int i=0;i<Math.min(20,Math.abs(e.getWheelRotation()));i++) {
                    sendMouse(e,"press",e.getWheelRotation()<0?4:5);
                    sendMouse(e,"release",e.getWheelRotation()<0?4:5);
                }
            }
        };
        overlay.addMouseListener(mouse); overlay.addMouseMotionListener(mouse); overlay.addMouseWheelListener(mouse);
        KeyboardFocusManager manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.addPropertyChangeListener("focusedWindow",change->{
            if(change.getOldValue()==javax.swing.SwingUtilities.getWindowAncestor(overlay) &&
                "directed".equals(backend.linuxInputMode())) {
                heldKeys.clear();backend.send("{\"command\":\"linux_release_input\"}");
            }
        });
        manager.addKeyEventDispatcher(e->{
            if(!enabled.getAsBoolean() || !"directed".equals(backend.linuxInputMode())) {heldKeys.clear();return false;}
            if(e.isConsumed() || (e.getID()!=KeyEvent.KEY_PRESSED && e.getID()!=KeyEvent.KEY_RELEASED))return false;
            int identity=e.getKeyCode()*8+e.getKeyLocation();
            Long symbol;
            if(e.getID()==KeyEvent.KEY_PRESSED) {
                symbol=heldKeys.get(identity);
                if(symbol==null){symbol=keysym(e);if(symbol!=0)heldKeys.put(identity,symbol);}
            } else symbol=heldKeys.remove(identity);
            if(symbol!=null && symbol!=0)backend.send("{\"command\":\"linux_input\",\"kind\":\""+
                (e.getID()==KeyEvent.KEY_PRESSED?"key_press":"key_release")+"\",\"keysym\":"+symbol+",\"state\":"+state(e)+"}");
            return false;
        });
    }

    private static int button(MouseEvent e){return e.getButton()==MouseEvent.BUTTON2?2:e.getButton()==MouseEvent.BUTTON3?3:1;}
    private static int state(InputEvent e){return(e.isShiftDown()?1:0)|(e.isControlDown()?4:0)|(e.isAltDown()?8:0)|(e.isMetaDown()?64:0);}
    private void sendMouse(MouseEvent e,String kind,int button){
        if(!enabled.getAsBoolean() || !"directed".equals(backend.linuxInputMode()))return;
        Point point=e.getLocationOnScreen();
        var transform=overlay.getGraphicsConfiguration().getDefaultTransform();
        backend.send("{\"command\":\"linux_input\",\"kind\":\""+kind+"\",\"x\":"+
            Math.round(point.x*transform.getScaleX())+",\"y\":"+Math.round(point.y*transform.getScaleY())+
            ",\"button\":"+button+",\"state\":"+state(e)+"}");
    }
    static long keysym(KeyEvent e){
        int code=e.getKeyCode();
        if(code>=KeyEvent.VK_F1 && code<=KeyEvent.VK_F12)return 0xffbe+code-KeyEvent.VK_F1;
        return switch(code){
            case KeyEvent.VK_ENTER->0xff0d; case KeyEvent.VK_BACK_SPACE->0xff08; case KeyEvent.VK_TAB->0xff09;
            case KeyEvent.VK_ESCAPE->0xff1b; case KeyEvent.VK_DELETE->0xffff;
            case KeyEvent.VK_LEFT->0xff51; case KeyEvent.VK_UP->0xff52; case KeyEvent.VK_RIGHT->0xff53; case KeyEvent.VK_DOWN->0xff54;
            case KeyEvent.VK_PAGE_UP->0xff55; case KeyEvent.VK_PAGE_DOWN->0xff56;
            case KeyEvent.VK_HOME->0xff50; case KeyEvent.VK_END->0xff57; case KeyEvent.VK_INSERT->0xff63;
            case KeyEvent.VK_SHIFT->e.getKeyLocation()==KeyEvent.KEY_LOCATION_RIGHT?0xffe2:0xffe1;
            case KeyEvent.VK_CONTROL->e.getKeyLocation()==KeyEvent.KEY_LOCATION_RIGHT?0xffe4:0xffe3;
            case KeyEvent.VK_ALT->e.getKeyLocation()==KeyEvent.KEY_LOCATION_RIGHT?0xffea:0xffe9;
            default->e.getKeyChar()!=KeyEvent.CHAR_UNDEFINED && e.getKeyChar()>=32 ? e.getKeyChar() :
                code>=KeyEvent.VK_A && code<=KeyEvent.VK_Z ? Character.toLowerCase(code) : 0;
        };
    }
}
