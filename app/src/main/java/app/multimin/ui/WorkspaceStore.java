package app.multimin.ui;
import app.multimin.model.*;
import java.awt.Color;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Versioned user-owned workspace; never stores captured pixels or reusable HWNDs. */
final class WorkspaceStore {
 static Path directory(){String base=System.getenv("APPDATA");return base==null||base.isBlank()?Path.of(System.getProperty("user.home"),".config","multimin"):Path.of(base,"MultiMin");}
 private final Path file; private Properties previous; private boolean recovered;
 record State(List<View> views,int active){}
 WorkspaceStore(){this(directory().resolve("workspace.properties"));}
 WorkspaceStore(Path file){this.file=file;}
 private static final String[] FIELDS={"name","customName","visible","opacity","scale","scaleX","scaleY","freeTransform","x","y","quickSwapInput","inputTarget","windowProcess","displayDeviceIndex","captureMethod","nativeGameTest","captureCursor","imagePath","nativePresentationEnabled"};
 State load()throws IOException {
  if(!Files.exists(file))return new State(new ArrayList<>(),0);
  try{return read(file);}catch(Exception primary){try{State state=read(file.resolveSibling(file.getFileName()+".bak"));recovered=true;return state;}catch(Exception backup){throw new IOException("Workspace could not be read. Original files were preserved: "+file,primary);}}
 }
 private State read(Path path)throws Exception {
  if(Files.size(path)>8_000_000)throw new IOException("Workspace is too large");
  Properties p=new Properties();try(var in=Files.newBufferedReader(path)){p.load(in);}
  if(!"1".equals(p.getProperty("schema")))throw new IOException("Unsupported workspace version");
  int count=number(p,"views",1,100);List<View> views=new ArrayList<>();
  for(int i=0;i<count;i++){
   String key="view."+i+".";View v=new View(p.getProperty(key+"name","View"));int sources=number(p,key+"sources",0,1000);
   for(int j=0;j<sources;j++){
    String s=key+"source."+j+".";Source source=new Source(p.getProperty(s+"name","Source"),Source.Kind.valueOf(p.getProperty(s+"kind")));
    for(String field:FIELDS){String value=p.getProperty(s+field);if(value==null)continue;var f=Source.class.getField(field);if(f.getType()==int.class)f.setInt(source,Integer.parseInt(value));else if(f.getType()==boolean.class)f.setBoolean(source,Boolean.parseBoolean(value));else f.set(source,value);}
    source.opacity=Math.clamp(source.opacity,0,100);source.scale=Math.clamp(source.scale,5,250);source.x=Math.clamp(source.x,0,100);source.y=Math.clamp(source.y,0,100);source.scaleX=Math.clamp(source.scaleX,-1,250);source.scaleY=Math.clamp(source.scaleY,-1,250);source.displayDeviceIndex=Math.max(0,source.displayDeviceIndex);
    source.controlColor=new Color(Integer.parseInt(p.getProperty(s+"color",Integer.toString(source.controlColor.getRGB()))),true);
    v.sources.addElement(source);
   }
   v.controlSource=Math.clamp(Integer.parseInt(p.getProperty(key+"control","0")),0,Math.max(0,v.sources.size()-1));views.add(v);
  }
  return new State(views,Math.clamp(Integer.parseInt(p.getProperty("active","0")),0,views.size()-1));
 }
 private static int number(Properties p,String key,int min,int max)throws IOException{int n=Integer.parseInt(p.getProperty(key,"-1"));if(n<min||n>max)throw new IOException("Invalid workspace count");return n;}
 synchronized void save(List<View> views,View active)throws IOException {
  Properties p=new Properties();p.setProperty("schema","1");p.setProperty("views",Integer.toString(views.size()));p.setProperty("active",Integer.toString(Math.max(0,views.indexOf(active))));
  try{for(int i=0;i<views.size();i++) {View v=views.get(i);String key="view."+i+".";p.setProperty(key+"name",v.name);p.setProperty(key+"control",Integer.toString(v.controlSource));p.setProperty(key+"sources",Integer.toString(v.sources.size()));for(int j=0;j<v.sources.size();j++){Source source=v.sources.get(j);String s=key+"source."+j+".";p.setProperty(s+"kind",source.kind.name());p.setProperty(s+"color",Integer.toString(source.controlColor.getRGB()));for(String field:FIELDS)p.setProperty(s+field,String.valueOf(Source.class.getField(field).get(source)));}}}catch(ReflectiveOperationException error){throw new IOException(error);}
  if(p.equals(previous))return;
  Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"workspace-",".tmp");
  try{try(var out=Files.newBufferedWriter(temp)){p.store(out,"MultiMini workspace; contains window titles and local file paths");}if(Files.exists(file) && !recovered)Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}previous=p;recovered=false;}finally{Files.deleteIfExists(temp);}
 }
}
