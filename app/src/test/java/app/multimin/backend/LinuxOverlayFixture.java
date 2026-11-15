package app.multimin.backend;

import java.awt.*;
import javax.swing.*;

/** Exact Swing overlay shape/type used by MultiMini, with no captured content. */
public final class LinuxOverlayFixture {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            JWindow overlay=new JWindow();
            overlay.setType(Window.Type.POPUP);
            overlay.setFocusableWindowState(false);
            overlay.setAlwaysOnTop(true);
            overlay.setBackground(new Color(0,0,0,0));
            JPanel content=new JPanel(new BorderLayout());
            content.setOpaque(true);content.setBackground(new Color(30,40,60,40));
            JLabel label=new JLabel("MultiMini controlled overlay test",SwingConstants.CENTER);
            label.setForeground(Color.WHITE);content.add(label);
            overlay.setContentPane(content);
            overlay.setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds());
            overlay.setVisible(true);
            System.out.println(ProcessHandle.current().pid()+" "+LinuxWindowIdentity.get(overlay));System.out.flush();
            Timer timeout=new Timer(45000,e->{overlay.dispose();System.exit(0);});timeout.setRepeats(false);timeout.start();
        });
    }
}
