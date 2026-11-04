package app.multimin.ui;
import app.multimin.backend.PythonBridge;
import app.multimin.model.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.Path;

/** Exercises the actual MainWindow capture, EDT handoff, and preview paint path. */
public class CapturePipelineCheck {
 static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 static double optional(Object object,String name){try{Object value=field(object,name);return value instanceof Boolean b?(b?1:0):((Number)value).doubleValue();}catch(Exception ignored){return -1;}}
 public static void main(String[] args)throws Exception {
  SwingUtilities.invokeAndWait(() -> {
   try {
    if(args.length>1 && args[1].equals("dwm"))System.setProperty("multimin.nativeWindowPresentation","true");
    PythonBridge backend=new PythonBridge();MainWindow window=new MainWindow(backend);
    View view=(View)field(window,"activeView");view.sources.clear();
    Source source=new Source("WGC pacing fixture",Source.Kind.WINDOW);source.nativeWindowHandle=Long.parseLong(args[0]);
    source.inputTarget="MultiMini moving capture fixture";source.captureFramePath=Path.of("build/internal/fixture.bmp").toAbsolutePath().toString();source.captureMethod="WGC";
    view.sources.addElement(source);
    PreviewPanel preview=(PreviewPanel)field(window,"preview");
    if(args.length>1 && (args[1].equals("native") || args[1].equals("dwm"))) {
     window.setUndecorated(true);window.setContentPane(preview);preview.setLocked(true);
     window.setBounds(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds());
    }
    window.setVisible(true);backend.start(window::setBackendStatus);
    backend.registerWindowCapture(source.name,source.inputTarget,source.captureFramePath,source.nativeWindowHandle,"WGC",false);
    long[] last={System.nanoTime(),0,0,0};int[] tick={0};
    var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    long initialCpu=os instanceof com.sun.management.OperatingSystemMXBean system?system.getProcessCpuTime():0;
    new Timer(1000,event -> {
     long now=System.nanoTime();double seconds=(now-last[0])/1e9;
     System.out.printf(java.util.Locale.ROOT,"sample=%d delivered=%.1f painted=%.1f publication_to_paint_ms=%.2f paint_ms=%.2f frame_age_ms=%d capture_fps=%.1f%n",
      tick[0],(source.capturedFrames-last[1])/seconds,(optional(source,"presentedFrames")-last[2])/seconds,
      optional(source,"publicationToPaintMillis"),preview.getLastPaintMillis(),source.captureLatencyMillis,(source.lastMemorySequence-last[3])/seconds);
     last[0]=now;last[1]=source.capturedFrames;last[2]=(long)optional(source,"presentedFrames");
     last[3]=source.lastMemorySequence;
     if(source.capturedFrames==0)try{System.out.println("BACKEND "+((JLabel)field(window,"backendStatus")).getText());}catch(Exception ignored){}
     if(args.length>1 && args[1].equals("dwm"))System.out.println("Native DWM active="+optional(source,"nativeCompositionActive")+" backend="+source.activeCaptureBackend);
     if(tick[0]==10 && os instanceof com.sun.management.OperatingSystemMXBean system)
      System.out.printf("Consumer Java CPU seconds=%.3f heap_used_MiB=%.1f%n",(system.getProcessCpuTime()-initialCpu)/1e9,
       (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/(1024.0*1024));
     if(++tick[0]>=12){((Timer)event.getSource()).stop();try{var close=MainWindow.class.getDeclaredMethod("shutdownServices");close.setAccessible(true);close.invoke(window);}catch(Exception error){error.printStackTrace();}window.dispose();}
    }).start();
   } catch(Exception error){throw new RuntimeException(error);}
  });
 }
}
