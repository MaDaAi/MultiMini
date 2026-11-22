import app.multimin.backend.PythonBridge;
import app.multimin.model.Source;
import app.multimin.model.View;
import app.multimin.ui.MainWindow;
import app.multimin.ui.PreviewPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import javax.swing.*;

/**
 * Measurement harness for the multitask-launch path (not a pass/fail test).
 *
 * Modes:
 *   fixture  &lt;title&gt; &lt;x&gt; &lt;y&gt; &lt;w&gt; &lt;h&gt;   animated undecorated window that encodes a nanoTime stamp in its pixels
 *   (default)  --mode native|capture --out file.json [--seconds 8] [--no-robot]
 *
 * One JVM = one mode, so entry #1 really is the first entry after launch in that mode.
 * The runner script (scripts/benchmark-multitask.ps1) sets a disposable APPDATA/LOCALAPPDATA/user.home.
 *
 * Fixture pixel layout (top of the window, left 55% of its width): 48 cells, black/white:
 *   4 sync (1101) | 32 bit stamp (System.nanoTime()/100000, MSB first) | 8 bit check | 4 sync (0110).
 * System.nanoTime on Windows is QueryPerformanceCounter, which is shared by all processes.
 */
public class MultitaskLaunchBenchmark {
 static final int CELLS=48; static final double BAND_W=0.55, BAND_H=0.08;
 static final int FIX_W=980, FIX_H=580, FIX_X=50, FIX_Y=50;

 // ------------------------------------------------------------------ fixture
 static void runFixture(String title,int x,int y,int w,int h)throws Exception{
  JFrame frame=new JFrame(title);Canvas canvas=new Canvas();
  frame.setUndecorated(true);frame.setAlwaysOnTop(true);
  int[] clicks={0};canvas.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mousePressed(java.awt.event.MouseEvent e){clicks[0]++;System.out.println("CLICK "+clicks[0]+" button="+e.getButton()+" at "+e.getX()+","+e.getY());System.out.flush();}});
  SwingUtilities.invokeAndWait(()->{frame.add(canvas);frame.setBounds(x,y,w,h);frame.setVisible(true);canvas.createBufferStrategy(2);});
  int refresh=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode().getRefreshRate();
  if(refresh<=0)refresh=60;
  long period=1_000_000_000L/refresh;
  long start=System.nanoTime(),next=start,sample=start;int rendered=0,sampled=0;
  System.out.println("FIXTURE refresh="+refresh);
  Thread bt=new Thread(()->{String last="";while(true){try{Rectangle b=frame.getBounds();String t="BOUNDS "+b.x+" "+b.y+" "+b.width+" "+b.height;if(!t.equals(last)){System.out.println(t);System.out.flush();last=t;}Thread.sleep(50);}catch(Throwable e){return;}}});bt.setDaemon(true);bt.start();
  try{
   while(frame.isDisplayable()){
    long stamp=(System.nanoTime()/100_000L)&0xFFFFFFFFL;
    var buffers=canvas.getBufferStrategy();var g=buffers.getDrawGraphics();
    int cw=canvas.getWidth(),ch=canvas.getHeight();
    g.setColor(new Color((rendered*3)&255,(rendered*7)&255,(rendered*11)&255));g.fillRect(0,0,cw,ch);
    int bandH=(int)Math.ceil(ch*BAND_H);double cell=cw*BAND_W/CELLS;
    boolean[] bits=cells(stamp);
    for(int i=0;i<CELLS;i++){g.setColor(bits[i]?Color.WHITE:Color.BLACK);g.fillRect((int)Math.round(i*cell),0,(int)Math.round((i+1)*cell)-(int)Math.round(i*cell),bandH);}
    int bx=(int)((rendered*5)%(Math.max(1,cw-80)));g.setColor(Color.WHITE);g.fillRect(bx,ch/2,80,80);
    g.setColor(Color.WHITE);g.drawString("Bench fixture frame "+rendered,30,bandH+40);
    g.dispose();buffers.show();rendered++;
    next+=period;long delay=next-System.nanoTime();if(delay>0)LockSupport.parkNanos(delay);else next=System.nanoTime();
    long now=System.nanoTime();if(now-sample>=1_000_000_000L){System.out.printf(Locale.ROOT,"Fixture draw FPS %.1f%n",(rendered-sampled)*1e9/(now-sample));sample=now;sampled=rendered;}
   }
  }finally{System.out.printf(Locale.ROOT,"Fixture %.1f rendered FPS%n",rendered*1e9/Math.max(1,System.nanoTime()-start));SwingUtilities.invokeAndWait(frame::dispose);}
 }
 static boolean[] cells(long stamp){
  boolean[] b=new boolean[CELLS];int[] sync={1,1,0,1};int[] tail={0,1,1,0};
  for(int i=0;i<4;i++){b[i]=sync[i]==1;b[44+i]=tail[i]==1;}
  for(int i=0;i<32;i++)b[4+i]=((stamp>>>(31-i))&1)==1;
  int check=check(stamp);for(int i=0;i<8;i++)b[36+i]=((check>>>(7-i))&1)==1;
  return b;
 }
 static int check(long s){return (int)(((s>>>24)^(s>>>16)^(s>>>8)^s^0xA5)&0xFF);}
 /** Returns stamp (0.1 ms units) or -1 if the strip does not hold a valid pattern. */
 static long decode(BufferedImage strip){
  int w=strip.getWidth(),h=strip.getHeight();if(w<CELLS*2||h<1)return -1;
  double cell=w*BAND_W/CELLS;boolean[] b=new boolean[CELLS];
  for(int i=0;i<CELLS;i++){
   int px=Math.min(w-1,(int)((i+0.5)*cell)),py=h/2;int rgb=strip.getRGB(px,py);
   double lum=0.299*((rgb>>16)&255)+0.587*((rgb>>8)&255)+0.114*(rgb&255);b[i]=lum>128;
  }
  int[] sync={1,1,0,1},tail={0,1,1,0};
  for(int i=0;i<4;i++)if(b[i]!=(sync[i]==1)||b[44+i]!=(tail[i]==1))return -1;
  long stamp=0;for(int i=0;i<32;i++)stamp=(stamp<<1)|(b[4+i]?1:0);
  int check=0;for(int i=0;i<8;i++)check=(check<<1)|(b[36+i]?1:0);
  return check==check(stamp)?stamp:-1;
 }

 // ------------------------------------------------------------------ helpers
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static double pct(List<Double> v,double p){if(v.isEmpty())return Double.NaN;List<Double> s=new ArrayList<>(v);Collections.sort(s);return s.get(Math.min(s.size()-1,(int)Math.ceil(p/100.0*s.size())-1<0?0:(int)Math.ceil(p/100.0*s.size())-1));}
 static double avg(List<Double> v){return v.isEmpty()?Double.NaN:v.stream().mapToDouble(Double::doubleValue).average().getAsDouble();}
 static double max(List<Double> v){return v.isEmpty()?Double.NaN:Collections.max(v);}
 static String lastBounds(List<String> log){synchronized(log){for(int i=log.size()-1;i>=0;i--)if(log.get(i).startsWith("BOUNDS"))return log.get(i).substring(7);}return null;}
 static double r(double v){return Double.isNaN(v)||Double.isInfinite(v)?Double.NaN:Math.round(v*100.0)/100.0;}
 static String json(Object o){
  if(o==null)return "null";
  if(o instanceof Double d)return Double.isNaN(d)||Double.isInfinite(d)?"null":Double.toString(d);
  if(o instanceof Number||o instanceof Boolean)return o.toString();
  if(o instanceof Map<?,?> m){StringBuilder s=new StringBuilder("{");boolean first=true;for(var e:m.entrySet()){if(!first)s.append(",");first=false;s.append(json(String.valueOf(e.getKey()))).append(":").append(json(e.getValue()));}return s.append("}").toString();}
  if(o instanceof Collection<?> c){StringBuilder s=new StringBuilder("[");boolean first=true;for(Object e:c){if(!first)s.append(",");first=false;s.append(json(e));}return s.append("]").toString();}
  String t=o.toString();StringBuilder s=new StringBuilder("\"");
  for(char ch:t.toCharArray()){if(ch=='"'||ch=='\\')s.append('\\').append(ch);else if(ch<32)s.append(String.format("\\u%04x",(int)ch));else s.append(ch);}
  return s.append('"').toString();
 }
 static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
 static double heapMb(){Runtime rt=Runtime.getRuntime();return (rt.totalMemory()-rt.freeMemory())/(1024.0*1024);}
 static long cpuNanos(){return OS.getProcessCpuTime();}
 static long childCpuNanos(ProcessHandle h){try{return h==null?0:h.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);}catch(Exception e){return 0;}}
 static String stamp(){return String.format(Locale.ROOT,"[%.3f] ",System.nanoTime()/1e9);}

 record Robo(long tMid,double delayMs,boolean valid){}
 /** Reads the stamp strip from the screen continuously; fixed screen rectangle in logical coordinates. */
 static final class RobotReader implements Runnable{
  final java.util.function.Supplier<Rectangle> rect;final long periodNanos;final List<Robo> samples=Collections.synchronizedList(new ArrayList<>());
  volatile boolean stop;final Robot robot;Thread thread;volatile long invalidReads;
  RobotReader(java.util.function.Supplier<Rectangle> rect,long periodNanos)throws Exception{this.rect=rect;this.periodNanos=periodNanos;robot=new Robot();}
  void start(){thread=new Thread(this,"bench-robot");thread.setDaemon(true);thread.start();}
  void end()throws InterruptedException{stop=true;if(thread!=null)thread.join(2000);}
  @Override public void run(){
   while(!stop){
    long t0=System.nanoTime();
    try{
     Rectangle src=rect.get();Rectangle strip=new Rectangle(src.x,src.y+(int)(src.height*BAND_H/2)-2,Math.max(1,src.width),4);
     MultiResolutionImage mri=robot.createMultiResolutionScreenCapture(strip);
     var variants=mri.getResolutionVariants();BufferedImage best=null;
     for(var v:variants)if(v instanceof BufferedImage bi&&(best==null||bi.getWidth()>best.getWidth()))best=bi;
     long t1=System.nanoTime();
     long ts=best==null?-1:decode(best);
     if(ts<0){samples.add(new Robo((t0+t1)/2,0,false));}
     else{// grab happens near the start of the call, so use t0; signed 32-bit difference in 0.1 ms units
      long nowUnits=(t0/100_000L)&0xFFFFFFFFL;int diff=(int)((nowUnits-ts)&0xFFFFFFFFL);double delay=diff/10.0;
      samples.add(new Robo(t0,Math.max(0,delay),delay>-20&&delay<5000));}
    }catch(Throwable t){samples.add(new Robo(System.nanoTime(),0,false));}
    long left=periodNanos-(System.nanoTime()-t0);if(left>0)LockSupport.parkNanos(left);
   }
  }
 }

 // ------------------------------------------------------------------ main
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("fixture")){runFixture(args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]));return;}
  String mode="native",out=null;int seconds=8;boolean noRobot=false;String dump=null;int cfgWait=12;boolean verify=false,noStandin=false,diag=false,realflow=false;
  for(int i=0;i<args.length;i++){
   switch(args[i]){case "--mode"->mode=args[++i];case "--out"->out=args[++i];case "--seconds"->seconds=Integer.parseInt(args[++i]);case "--no-robot"->noRobot=true;case "--dump"->dump=args[++i];case "--verify"->verify=true;case "--cfgwait"->cfgWait=Integer.parseInt(args[++i]);case "--no-standin"->noStandin=true;case "--diag"->diag=true;case "--realflow"->realflow=true;default->{}}
  }
  final String dumpDir=dump;final int cfgWaitS=cfgWait;final boolean doVerify=verify,useStandin=!noStandin&&!realflow,doDiag=diag,doReal=realflow;final boolean nativeMode=mode.equals("native");final int runSeconds=seconds;final boolean useRobot=!noRobot;
  // Same switches Main applies; native presentation also needs the build flag Main reads from build.properties.
  System.setProperty("multimin.nativeWindowPresentation","true");
  System.setProperty("multimin.dev.gameCapture","true");
  System.setProperty("sun.java2d.uiScale.enabled","true");
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("mode",nativeMode?"native-presentation":"capture-renderer(native disabled on source)");
  result.put("javaVersion",System.getProperty("java.version"));
  result.put("appdata",System.getenv("APPDATA"));
  long hardDeadline=System.nanoTime()+170_000_000_000L; // runner also enforces a limit
  String title="MMBench fixture "+Long.toHexString(System.nanoTime());
  Process fixture=null;PythonBridge backend=null;MainWindow window=null;RobotReader robot=null;
  List<String> fixtureLog=Collections.synchronizedList(new ArrayList<>());
  List<Map<String,Object>> scenarios=new ArrayList<>();
  try{
   String javaBin=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
   fixture=new ProcessBuilder(javaBin,"-cp",System.getProperty("java.class.path"),"MultitaskLaunchBenchmark","fixture",title,
     ""+FIX_X,""+FIX_Y,""+FIX_W,""+FIX_H).redirectErrorStream(true).start();
   Process fp=fixture;
   Thread t=new Thread(()->{try(var rd=new java.io.BufferedReader(new java.io.InputStreamReader(fp.getInputStream()))){String l;while((l=rd.readLine())!=null)fixtureLog.add(l);}catch(Exception ignored){}});t.setDaemon(true);t.start();
   Thread.sleep(1500);
   Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
   var tx=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
   result.put("screenLogical",screen.width+"x"+screen.height+" scale="+tx.getScaleX());
   result.put("refreshHz",GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode().getRefreshRate());

   // ---- window + backend
   MainWindow[] holder=new MainWindow[1];PythonBridge[] bh=new PythonBridge[1];
   SwingUtilities.invokeAndWait(()->{bh[0]=new PythonBridge();holder[0]=new MainWindow(bh[0]);holder[0].setLocation(screen.x+1100,screen.y+700);holder[0].setVisible(true);bh[0].start(holder[0]::setBackendStatus);});
   backend=bh[0];window=holder[0];final PythonBridge bridge=backend;final MainWindow win=window;
   long startNs=System.nanoTime();long readyBy=System.nanoTime()+40_000_000_000L;
   while(!bridge.isReady()&&System.nanoTime()<readyBy)Thread.sleep(50);
   if(!bridge.isReady())throw new IllegalStateException("Python backend did not become ready (status: "+((JLabel)field(win,"backendStatus")).getText()+")");
   result.put("backendReady",true);result.put("backendReadyMs",r((System.nanoTime()-startNs)/1e6));
   // find fixture HWND via backend list (poll, window may not be listed immediately)
   PythonBridge.WindowTarget target=null;
   for(int attempt=0;attempt<25&&target==null;attempt++){
    CompletableFuture<List<PythonBridge.WindowTarget>> f=new CompletableFuture<>();bridge.requestWindowList(f::complete);
    List<PythonBridge.WindowTarget> list=f.get(10,TimeUnit.SECONDS);
    for(var w:list)if(w.title.equals(title)){target=w;break;}
    if(target==null)Thread.sleep(300);
   }
   if(target==null)throw new IllegalStateException("Fixture window not found in backend list");
   result.put("fixtureHwnd",target.hwnd);result.put("fixtureProcess",target.process);

   // ---- single view, single source
   final PythonBridge.WindowTarget tgt=target;Source[] sh=new Source[1];
   SwingUtilities.invokeAndWait(()->{try{
     View view=(View)field(win,"activeView");view.sources.clear();
     Source s=new Source("Bench fixture",Source.Kind.WINDOW);
     s.nativeWindowHandle=tgt.hwnd;s.windowProcess=tgt.process;s.inputTarget=tgt.title;s.captureMethod="AUTO";
     s.nativePresentationEnabled=nativeMode;
     s.captureFramePath=((Path)field(win,"windowFramesDirectory")).resolve(UUID.randomUUID()+".bmp").toString();
     view.sources.addElement(s);view.controlSource=0;sh[0]=s;
     bridge.registerWindowCapture(s.name,s.inputTarget,s.captureFramePath,s.nativeWindowHandle,s.captureMethod,s.captureCursor);
     ((PreviewPanel)field(win,"preview")).repaint();
   }catch(Exception e){throw new RuntimeException(e);}});
   final Source source=sh[0];final PreviewPanel preview=(PreviewPanel)field(win,"preview");
   // wait for first configure-mode frame (the app requires it before multitask on some platforms)
   long frameBy=System.nanoTime()+cfgWaitS*1_000_000_000L;
   long cfgStart=System.nanoTime();long nextDiag=cfgStart;List<Map<String,Object>> diagRows=new ArrayList<>();
   while(source.liveFrame==null&&System.nanoTime()<frameBy){
    Thread.sleep(50);
    if(doDiag&&System.nanoTime()>=nextDiag){nextDiag+=1_000_000_000L;Map<String,Object> d=new LinkedHashMap<>();
     try{d.put("t",r((System.nanoTime()-cfgStart)/1e9));d.put("mainShowing",win.isShowing());d.put("previewShowing",preview.isShowing());
      d.put("captureSnapshotSize",((List<?>)field(win,"captureSnapshot")).size());d.put("activeCapturePathsSize",((List<?>)field(win,"activeCapturePaths")).size());
      d.put("captureActivitySent",field(win,"captureActivitySent"));d.put("scanQueued",field(win,"windowFrameScanQueued"));d.put("newFramePending",field(win,"newFramePending"));
      d.put("capturedFrames",source.capturedFrames);d.put("lastMemorySequence",source.lastMemorySequence);d.put("hasFrameNotifications",bridge.hasFrameNotifications());d.put("frameStreamPort",bridge.frameStreamPort());}catch(Throwable e){d.put("err",e.toString());}
     diagRows.add(d);}
   }
   long cfgEndNs=System.nanoTime();boolean cfgGot=source.liveFrame!=null;
   if(doDiag&&source.liveFrame==null){
    try{List<String> st=new ArrayList<>();for(var e:Thread.getAllStackTraces().entrySet()){String n=e.getKey().getName();if(n.startsWith("AWT-EventQueue")||n.toLowerCase().contains("frame")||n.startsWith("pool")){StringBuilder sb=new StringBuilder(n+" ["+e.getKey().getState()+"]: ");StackTraceElement[] el=e.getValue();for(int i=0;i<Math.min(6,el.length);i++)sb.append(el[i]).append(" < ");st.add(sb.toString());}}result.put("threadStacksAfterConfigureWait",st);}catch(Throwable ex){result.put("stackErr",ex.toString());}
    // manual read through the app's own reader: tells us whether Python has a frame, and re-arms the notification
    Map<String,Object> d=new LinkedHashMap<>();
    try{Object reader=field(win,"windowFrameReader");var m=reader.getClass().getMethod("readMemory",String.class,long.class,int.class);
     Object fr=m.invoke(reader,source.captureFramePath,0L,bridge.frameStreamPort());
     d.put("manualReadMemoryReturned",fr==null?"null":"Frame");
     try(java.net.Socket sk=new java.net.Socket("127.0.0.1",bridge.frameStreamPort())){sk.setSoTimeout(3000);var dout=new java.io.DataOutputStream(sk.getOutputStream());var din=new java.io.DataInputStream(sk.getInputStream());
      byte[] nm=source.captureFramePath.getBytes(java.nio.charset.StandardCharsets.UTF_8);dout.writeShort(nm.length);dout.write(nm);dout.writeLong(0);dout.flush();
      int w=din.readInt(),h=din.readInt();long seq=din.readLong(),age=din.readLong();int len=din.readInt();d.put("rawSocketReply","w="+w+" h="+h+" seq="+seq+" ageNs="+age+" len="+len+" pathUsed="+source.captureFramePath);}catch(Throwable e){d.put("rawSocketError",e.toString());}
     long c0=source.capturedFrames;Thread.sleep(3000);d.put("capturedFramesGainedWithin3sAfterManualRead",source.capturedFrames-c0);d.put("liveFrameAfter",source.liveFrame!=null);
     long c1=source.capturedFrames;bridge.setCaptureFps(144,true);Thread.sleep(4000);d.put("capturedFramesGainedWithin4sAfter_set_capture_fps_restart",source.capturedFrames-c1);
     long c2=source.capturedFrames;bridge.registerWindowCapture(source.name,source.inputTarget,source.captureFramePath,source.nativeWindowHandle,source.captureMethod,source.captureCursor);Thread.sleep(4000);d.put("capturedFramesGainedWithin4sAfterReRegister",source.capturedFrames-c2);
     long c3=source.capturedFrames;bridge.setActiveWindowCaptures(java.util.List.of(source.captureFramePath));Thread.sleep(3000);d.put("capturedFramesGainedWithin3sAfterReSendActive",source.capturedFrames-c3);
    }catch(Throwable e){d.put("manualReadError",e.toString());}
    diagRows.add(d);
   }
   if(doDiag)result.put("configureDiag",diagRows);if(cfgGot)result.put("configureFirstFrameMs",r((cfgEndNs-cfgStart)/1e6));
   result.put("configureFrameArrived",source.liveFrame!=null);
   if(useStandin&&source.liveFrame==null){source.liveFrame=new BufferedImage(FIX_W,FIX_H,BufferedImage.TYPE_INT_ARGB);result.put("placeholderLiveFrameInstalled","980x580 stand-in for the configure preview frame that never arrived (keeps overlay source bounds 1:1)");}
   result.put("configureBackend",source.activeCaptureBackend);result.put("backendStatusText",((JLabel)field(win,"backendStatus")).getText());
   if(source.liveFrame!=null)result.put("sourceSize",source.liveFrame.getWidth()+"x"+source.liveFrame.getHeight());
   try{result.put("dbgConfigure",Map.of("captureActivitySent",String.valueOf(field(win,"captureActivitySent")),"activeCapturePaths",String.valueOf(field(win,"activeCapturePaths")),"captureSnapshotSize",String.valueOf(((List<?>)field(win,"captureSnapshot")).size()),"previewShowing",String.valueOf(preview.isShowing()),"mainVisible",String.valueOf(win.isVisible())));}catch(Exception e){result.put("dbgConfigure",e.toString());}
   Thread.sleep(1500);

   // ---- robot strip: stamp band of the source as shown in the overlay (source is at canvas 0,0 and 1:1)
   int stripH=4;
   Rectangle fixtureStrip=new Rectangle(FIX_X,FIX_Y+(int)(FIX_H*BAND_H/2)-stripH/2,FIX_W,stripH);
   Rectangle overlayStrip=new Rectangle(screen.x,screen.y+(int)(FIX_H*BAND_H/2)-stripH/2,FIX_W,stripH);
   // baseline: read the fixture directly (no overlay) to calibrate stamp->screen delay
   if(useRobot){
    RobotReader base=new RobotReader(()->new Rectangle(FIX_X,FIX_Y,FIX_W,FIX_H),10_000_000L);base.start();Thread.sleep(1500);base.end();
    List<Double> d=new ArrayList<>();for(Robo s:new ArrayList<>(base.samples))if(s.valid())d.add(s.delayMs());
    Map<String,Object> b=new LinkedHashMap<>();b.put("validReads",d.size());b.put("totalReads",base.samples.size());b.put("avgMs",r(avg(d)));b.put("p95Ms",r(pct(d,95)));b.put("minMs",r(d.isEmpty()?Double.NaN:Collections.min(d)));
    result.put("baselineDirectFixtureRead",b);
   }

   Method setMultitask=MainWindow.class.getDeclaredMethod("setMultitask",boolean.class);setMultitask.setAccessible(true);
   Field multitaskField=MainWindow.class.getDeclaredField("multitask");multitaskField.setAccessible(true);
   Field lastApplied=null;try{lastApplied=MainWindow.class.getDeclaredField("lastAppliedCaptureFps");lastApplied.setAccessible(true);}catch(Exception ignored){}
   if(lastApplied!=null)result.put("captureFpsTarget",lastApplied.get(win));
   Process pyProc=null;try{pyProc=(Process)field(bridge,"process");}catch(Exception ignored){}
   ProcessHandle pyHandle=pyProc==null?null:pyProc.toHandle();

   String[] names=doReal?new String[0]:new String[]{"entry1_first_after_launch","entry2_after_exit_wait_2s","entry3"};
   for(int sc=0;sc<names.length;sc++){
    if(System.nanoTime()>hardDeadline)throw new IllegalStateException("time budget exceeded");
    // ---- scenario
    long[] t0=new long[1];long[] callEnd=new long[1];
    long[] base=new long[8]; // captured, presented, skipped, javaCpu, pyCpu
    double[] heapStart=new double[1];
    Map<String,Object> m=new LinkedHashMap<>();m.put("scenario",names[sc]);final Rectangle[] srcRect={null};
    robot=useRobot?new RobotReader(()->{Rectangle b=srcRect[0];return b!=null&&b.width>50?b:new Rectangle(screen.x,screen.y,FIX_W,FIX_H);},20_000_000L):null;if(robot!=null)robot.start();
    final int idx=sc;boolean[] dumped={false};
    SwingUtilities.invokeLater(()->{
     base[0]=source.capturedFrames;base[1]=source.presentedFrames;base[2]=source.skippedFrames;base[3]=cpuNanos();base[4]=childCpuNanos(pyHandle);heapStart[0]=heapMb();
     t0[0]=System.nanoTime();
     try{setMultitask.invoke(win,true);}catch(Exception e){m.put("enterError",String.valueOf(e.getCause()!=null?e.getCause():e));}
     callEnd[0]=System.nanoTime();
    });
    while(t0[0]==0)Thread.sleep(1);
    long end=t0[0]+runSeconds*1_000_000_000L;
    List<Double> capLat=new ArrayList<>(),pubPaint=new ArrayList<>(),paintMs=new ArrayList<>(),paintFps=new ArrayList<>();
    List<Double> capLatEarly=new ArrayList<>(),paintFpsEarly=new ArrayList<>(),paintMsEarly=new ArrayList<>();
    List<Map<String,Object>> series=new ArrayList<>();
    long firstPresented=0,firstNative=0,firstCaptured=0;
    long lastCap=base[0],lastPres=base[1];long lastSample=t0[0];long nextSample=t0[0]+100_000_000L;
    Set<String> backends=new LinkedHashSet<>();double heapMax=heapStart[0];
    long lastMilli=0;long prevCap=base[0];long capAtEarlyEnd=-1,capAtSteadyStart=-1;
    m.put("fixtureBoundsBefore",lastBounds(fixtureLog));boolean[] b3={false};
    while(System.nanoTime()<end){
     long now=System.nanoTime();
     if(!b3[0]&&now-t0[0]>=3_000_000_000L){b3[0]=true;m.put("fixtureBoundsAt3s",lastBounds(fixtureLog));}
     try{if(preview.isShowing()){Rectangle b=preview.sourceBoundsInPanel(0);Point lo=preview.getLocationOnScreen();b.translate(lo.x,lo.y);srcRect[0]=b;}}catch(Throwable ignored){}
     if(firstPresented==0&&source.presentedFrames>base[1])firstPresented=now;
     if(firstCaptured==0&&source.capturedFrames>base[0])firstCaptured=now;
     if(firstNative==0&&source.nativeCompositionActive)firstNative=now;
     if(dumpDir!=null&&idx==0&&!dumped[0]&&now-t0[0]>=3_000_000_000L){dumped[0]=true;try{BufferedImage shot=new Robot().createScreenCapture(screen);javax.imageio.ImageIO.write(shot,"png",new java.io.File(dumpDir,"overlay-"+(nativeMode?"native":"capture")+".png"));}catch(Throwable ex){result.put("dumpError",ex.toString());}}
     if(capAtSteadyStart<0&&now-t0[0]>=2_000_000_000L)capAtSteadyStart=source.capturedFrames;
     if(now>=nextSample){
      double sec=(now-t0[0])/1e9;
      long cap=source.capturedFrames,pres=source.presentedFrames;
      Map<String,Object> row=new LinkedHashMap<>();
      row.put("t",r(sec));row.put("native",source.nativeCompositionActive);row.put("captured",cap-base[0]);row.put("presented",pres-base[1]);
      row.put("capLatMs",source.captureLatencyMillis);row.put("pubToPaintMs",r(source.publicationToPaintMillis));row.put("paintMs",r(preview.getLastPaintMillis()));row.put("paintFps",r(preview.getMeasuredFps()));
      series.add(row);
      backends.add(source.activeCaptureBackend);
      boolean early=sec<2.0;
      if(cap!=prevCap){(early?capLatEarly:capLat).add((double)source.captureLatencyMillis);prevCap=cap;}
      if(source.publicationToPaintMillis>0&&pres!=lastPres)pubPaint.add(source.publicationToPaintMillis);
      (early?paintMsEarly:paintMs).add(preview.getLastPaintMillis());
      if(preview.getMeasuredFps()>0)(early?paintFpsEarly:paintFps).add(preview.getMeasuredFps());
      lastPres=pres;heapMax=Math.max(heapMax,heapMb());
      nextSample+=100_000_000L;if(nextSample<now)nextSample=now+100_000_000L;
     }
     LockSupport.parkNanos(500_000L);
    }
    long tEnd=System.nanoTime();double dur=(tEnd-t0[0])/1e9;
    long capEnd=source.capturedFrames,presEnd=source.presentedFrames,skipEnd=source.skippedFrames;
    long cpuEnd=cpuNanos(),pyEnd=childCpuNanos(pyHandle);
    boolean stillMultitask=(boolean)multitaskField.get(win);
    if(robot!=null)robot.end();
    // aggregate
    m.put("multitaskActiveAtEnd",stillMultitask);m.put("sourceBoundsOnScreenLogical",String.valueOf(srcRect[0]));BufferedImage lf=source.liveFrame;m.put("liveFrameSize",lf==null?null:lf.getWidth()+"x"+lf.getHeight());m.put("nativeSourceSize",source.nativeSourceWidth+"x"+source.nativeSourceHeight);
    m.put("setMultitaskTrueCallMs",r((callEnd[0]-t0[0])/1e6));
    m.put("firstNewCapturedFrameMs",firstCaptured==0?null:r((firstCaptured-t0[0])/1e6));
    m.put("firstPresentedFrameMs",firstPresented==0?null:r((firstPresented-t0[0])/1e6));
    m.put("nativeCompositionActiveMs",firstNative==0?null:r((firstNative-t0[0])/1e6));
    m.put("firstFrameMs(presented_or_native)",firstPresented!=0||firstNative!=0?r((Math.min(firstPresented==0?Long.MAX_VALUE:firstPresented,firstNative==0?Long.MAX_VALUE:firstNative)-t0[0])/1e6):null);
    m.put("nativeCompositionActiveAtEnd",source.nativeCompositionActive);
    m.put("backendFinal",source.activeCaptureBackend);m.put("backendsSeen",new ArrayList<>(backends));
    m.put("durationS",r(dur));
    m.put("capturedFramesDelta",capEnd-base[0]);m.put("capturedFpsOverall",r((capEnd-base[0])/dur));
    m.put("capturedFpsSteady(after2s)",capAtSteadyStart<0?null:r((capEnd-capAtSteadyStart)/(dur-2.0)));
    m.put("presentedFramesDelta",presEnd-base[1]);m.put("presentedFpsOverall",r((presEnd-base[1])/dur));
    m.put("paintFpsMeasuredAvgSteady",r(avg(paintFps)));m.put("paintFpsMeasuredAvgFirst2s",r(avg(paintFpsEarly)));
    m.put("skippedFramesDelta",skipEnd-base[2]);
    List<Double> allLat=new ArrayList<>(capLatEarly);allLat.addAll(capLat);
    m.put("captureLatencyMs",Map.of("avgAll",r(avg(allLat)),"p95All",r(pct(allLat,95)),"avgFirst2s",r(avg(capLatEarly)),"avgSteady",r(avg(capLat)),"p95Steady",r(pct(capLat,95))));
    m.put("publicationToPaintMs",Map.of("avg",r(avg(pubPaint)),"p95",r(pct(pubPaint,95)),"n",pubPaint.size()));
    List<Double> pm=new ArrayList<>(paintMsEarly);pm.addAll(paintMs);
    m.put("paintMs",Map.of("avg",r(avg(pm)),"p95",r(pct(pm,95)),"max",r(max(pm))));
    m.put("javaCpuMs",r((cpuEnd-base[3])/1e6));m.put("javaCpuPercentOfOneCore",r((cpuEnd-base[3])/1e9/dur*100));
    m.put("pythonBackendCpuMs",pyHandle==null?null:r((pyEnd-base[4])/1e6));
    m.put("pythonBackendCpuPercentOfOneCore",pyHandle==null?null:r((pyEnd-base[4])/1e9/dur*100));
    m.put("heapUsedMb",Map.of("start",r(heapStart[0]),"end",r(heapMb()),"max",r(heapMax)));
    if(robot!=null){
     List<Robo> rs=new ArrayList<>(robot.samples);long firstVisible=0;List<Double> all=new ArrayList<>(),early=new ArrayList<>(),steady=new ArrayList<>();Set<Long> uniq=new HashSet<>();int valid=0;
     for(Robo s:rs){if(s.tMid()<t0[0])continue;if(!s.valid())continue;valid++;
      if(firstVisible==0)firstVisible=s.tMid();
      double sec=(s.tMid()-t0[0])/1e9;all.add(s.delayMs());(sec<2?early:steady).add(s.delayMs());uniq.add(Math.round((s.tMid()/1e6-s.delayMs())*10));}
     int total=0;for(Robo s:rs)if(s.tMid()>=t0[0])total++;
     Map<String,Object> e=new LinkedHashMap<>();
     e.put("firstVisiblePixelsMs(robot, +/-~20ms)",firstVisible==0?null:r((firstVisible-t0[0])/1e6));
     e.put("reads",total);e.put("validReads",valid);
     e.put("delayMsAvgAll",r(avg(all)));e.put("delayMsAvgFirst2s",r(avg(early)));e.put("delayMsAvgSteady",r(avg(steady)));
     e.put("delayMsP50Steady",r(pct(steady,50)));e.put("delayMsP95Steady",r(pct(steady,95)));e.put("delayMsMinSteady",r(steady.isEmpty()?Double.NaN:Collections.min(steady)));e.put("delayMsMaxAll",r(max(all)));
     e.put("distinctFixtureFramesSeenPerSec",r(uniq.size()/dur));
     m.put("endToEnd",e);
    }
    m.put("seriesEvery100ms",series);
    scenarios.add(m);
    System.err.println(stamp()+names[sc]+" done first="+m.get("firstFrameMs(presented_or_native)")+" backend="+source.activeCaptureBackend);
    // ---- exit (always)
    long[] ex=new long[2];
    SwingUtilities.invokeAndWait(()->{ex[0]=System.nanoTime();try{setMultitask.invoke(win,false);}catch(Exception e){m.put("exitError",String.valueOf(e.getCause()!=null?e.getCause():e));}ex[1]=System.nanoTime();});
    m.put("setMultitaskFalseCallMs",r((ex[1]-ex[0])/1e6));
    if(sc<names.length-1)Thread.sleep(2000);
   }

   if(doReal){
    Map<String,Object> v=new LinkedHashMap<>();
    try{
     Robot rb=new Robot();
     v.put("liveFrameBeforeEntry",source.liveFrame==null?"null":source.liveFrame.getWidth()+"x"+source.liveFrame.getHeight());
     v.put("fixtureBoundsBeforeEntry",lastBounds(fixtureLog));
     int clicksBefore=0;synchronized(fixtureLog){for(String l:fixtureLog)if(l.startsWith("CLICK"))clicksBefore++;}
     long[] t0=new long[1];
     SwingUtilities.invokeAndWait(()->{t0[0]=System.nanoTime();try{setMultitask.invoke(win,true);}catch(Exception e){throw new RuntimeException(e);}});
     List<Map<String,Object>> tl=new ArrayList<>();
     long[] probes={50,150,300,600,1000,1500,2500,3500};
     for(long ms:probes){
      long wait=t0[0]+ms*1_000_000L-System.nanoTime();if(wait>0)Thread.sleep(wait/1_000_000L);
      Map<String,Object> row=new LinkedHashMap<>();row.put("tMs",ms);row.put("fixtureWindow",lastBounds(fixtureLog));
      Rectangle[] sr={null};SwingUtilities.invokeAndWait(()->{try{Rectangle b=preview.sourceBoundsInPanel(0);Point lo=preview.getLocationOnScreen();b.translate(lo.x,lo.y);sr[0]=b;}catch(Throwable e){}});
      row.put("overlaySourceBounds",sr[0]==null?null:sr[0].x+" "+sr[0].y+" "+sr[0].width+" "+sr[0].height);
      BufferedImage lf=source.liveFrame;row.put("liveFrame",lf==null?"null":lf.getWidth()+"x"+lf.getHeight());row.put("nativeSource",source.nativeSourceWidth+"x"+source.nativeSourceHeight);row.put("capturedFrames",source.capturedFrames);
      tl.add(row);
     }
     v.put("timeline",tl);
     Rectangle[] sr={null};SwingUtilities.invokeAndWait(()->{Rectangle b=preview.sourceBoundsInPanel(0);Point lo=preview.getLocationOnScreen();b.translate(lo.x,lo.y);sr[0]=b;});
     Rectangle b=sr[0];
     double cell=b.width*BAND_W/CELLS;int cy=b.y+(int)(b.height*BAND_H/2);int decoded=0;
     for(int i=0;i<30;i++){BufferedImage strip=rb.createScreenCapture(new Rectangle(b.x,cy-2,b.width,4));if(decode(strip)>=0)decoded++;Thread.sleep(20);}
     v.put("overlayBandDecodedReads",decoded+"/30 at bounds "+b);
     if(dumpDir!=null)javax.imageio.ImageIO.write(rb.createScreenCapture(screen),"png",new java.io.File(dumpDir,"realflow-"+(nativeMode?"native":"capture")+".png"));
     Point before=MouseInfo.getPointerInfo().getLocation();
     int clickX=b.x+b.width/2,clickY=b.y+b.height*3/4;
     rb.mouseMove(clickX,clickY);Thread.sleep(300);rb.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(80);rb.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(800);
     rb.mouseMove(before.x,before.y);
     List<String> cl=new ArrayList<>();synchronized(fixtureLog){for(String l:fixtureLog)if(l.startsWith("CLICK"))cl.add(l);}
     v.put("clickScreenPoint",clickX+","+clickY);v.put("expectedFixtureLocal(if overlay 1:1)",(clickX-b.x)+","+(clickY-b.y));v.put("fixtureClickLog",cl.subList(Math.min(clicksBefore,cl.size()),cl.size()));
     v.put("fixtureBoundsDuringMultitask",lastBounds(fixtureLog));
    }catch(Throwable e){v.put("realflowError",e.toString());}
    finally{try{SwingUtilities.invokeAndWait(()->{try{setMultitask.invoke(win,false);}catch(Exception ignored){}});}catch(Throwable ignored){}
     try{Thread.sleep(800);}catch(Exception ignored){}v.put("fixtureBoundsAfterExit",lastBounds(fixtureLog));}
    result.put("realflow",v);
   }

   if(doVerify){
    Map<String,Object> v=new LinkedHashMap<>();
    try{
     Robot rb=new Robot();
     SwingUtilities.invokeAndWait(()->{source.opacity=70;});
     Thread.sleep(300);
     int clicksBefore=0;synchronized(fixtureLog){for(String l:fixtureLog)if(l.startsWith("CLICK"))clicksBefore++;}
     SwingUtilities.invokeAndWait(()->{try{setMultitask.invoke(win,true);}catch(Exception e){throw new RuntimeException(e);}});
     Thread.sleep(3000);
     Rectangle[] sr={null};SwingUtilities.invokeAndWait(()->{Rectangle b=preview.sourceBoundsInPanel(0);Point lo=preview.getLocationOnScreen();b.translate(lo.x,lo.y);sr[0]=b;});
     Rectangle b=sr[0];v.put("sourceBoundsOnScreen",String.valueOf(b));
     // fixture's real window rect is not directly readable; compare luminance of a white sync cell
     double cell=b.width*BAND_W/CELLS;int cx=b.x+(int)(0.5*cell),cy=b.y+(int)(b.height*BAND_H/2);
     List<Double> white=new ArrayList<>();int decoded=0,tries=40;
     for(int i=0;i<tries;i++){
      Color c=rb.getPixelColor(cx,cy);white.add(0.299*c.getRed()+0.587*c.getGreen()+0.114*c.getBlue());
      BufferedImage strip=rb.createScreenCapture(new Rectangle(b.x,cy-2,b.width,4));if(decode(strip)>=0)decoded++;
      Thread.sleep(25);
     }
     v.put("bandDecodedReads",decoded+"/"+tries);v.put("whiteCellLuminanceAvg(opacity70 over black ~178; 255 means unblended window on top)",r(avg(white)));
     if(dumpDir!=null)javax.imageio.ImageIO.write(rb.createScreenCapture(screen),"png",new java.io.File(dumpDir,"verify-overlay-"+(nativeMode?"native":"capture")+".png"));
     // click test: strictly inside the overlay area mapped to the fixture (centre of the source rect)
     Point before=MouseInfo.getPointerInfo().getLocation();
     int clickX=b.x+b.width/2,clickY=b.y+b.height*3/4;
     rb.mouseMove(clickX,clickY);Thread.sleep(300);rb.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(80);rb.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(800);
     rb.mouseMove(clickX+40,clickY);Thread.sleep(200);rb.mouseMove(before.x,before.y);
     int clicksAfter=0;List<String> cl=new ArrayList<>();synchronized(fixtureLog){for(String l:fixtureLog)if(l.startsWith("CLICK")){clicksAfter++;cl.add(l);}}
     v.put("clickPoint",clickX+","+clickY);v.put("fixturePressesReceived",clicksAfter-clicksBefore);v.put("fixtureClickLog",cl);
     v.put("multitaskStillActive",(boolean)multitaskField.get(win));
    }catch(Throwable e){v.put("verifyError",e.toString());}
    finally{try{SwingUtilities.invokeAndWait(()->{try{setMultitask.invoke(win,false);}catch(Exception ignored){}});}catch(Throwable ignored){}}
    result.put("verify",v);
   }
   result.put("scenarios",scenarios);
  }catch(Throwable e){
   result.put("error",e.toString());result.put("scenarios",scenarios);e.printStackTrace();
  }finally{
   try{if(robot!=null)robot.end();}catch(Throwable ignored){}
   try{
    final MainWindow w=window;
    if(w!=null)SwingUtilities.invokeAndWait(()->{try{if((boolean)field(w,"multitask")){Method sm=MainWindow.class.getDeclaredMethod("setMultitask",boolean.class);sm.setAccessible(true);sm.invoke(w,false);}}catch(Throwable ignored){}
     try{Method stop=MainWindow.class.getDeclaredMethod("shutdownServices");stop.setAccessible(true);stop.invoke(w);}catch(Throwable ignored){}w.dispose();});
   }catch(Throwable ignored){}
   try{if(backend!=null)backend.close();}catch(Throwable ignored){}
   if(fixture!=null){fixture.destroy();try{if(!fixture.waitFor(3,TimeUnit.SECONDS))fixture.destroyForcibly();}catch(Exception ignored){}}
   List<String> fl;synchronized(fixtureLog){fl=new ArrayList<>(fixtureLog);}
   result.put("fixtureLogTail",fl.subList(Math.max(0,fl.size()-4),fl.size()));
   String js=json(result);System.out.println(js);
   if(out!=null)try{Files.writeString(Path.of(out),js);}catch(Exception ex){System.err.println("cannot write "+out+": "+ex);}
   System.exit(0);
  }
 }
}
