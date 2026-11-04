import javax.swing.*;
import app.multimin.backend.PythonBridge;
public class DisplayExclusionCheck {
 public static void main(String[] args) throws Exception {
  SwingUtilities.invokeAndWait(() -> {
   JFrame frame=new JFrame("MultiMini exclusion test"); frame.setSize(300,150); frame.setVisible(true);
   try {
    if(!PythonBridge.prepareNativeDisplayCapture()) throw new AssertionError("Native display exclusion failed");
    System.out.println("PASS: capture exclusion applied from the owning Java process");
   } finally {frame.dispose();}
  });
 }
}
