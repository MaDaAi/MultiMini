package app.multimin.ui;
import app.multimin.backend.NativeWindowCompositor;
import javax.swing.JFrame;
import java.nio.file.*;
/** Shell relaunch metadata for both the packaged launcher and local JAR. */
public final class WindowBranding {
 private WindowBranding(){}
 public static void apply(JFrame window){try{
  if(!NativeWindowCompositor.available())return;
  Path folder=WorkspaceStore.directory().resolve("branding");Files.createDirectories(folder);Path icon=folder.resolve("multimini.ico");
  try(var in=WindowBranding.class.getResourceAsStream("/app/multimin/multimini.ico")){if(in==null)return;byte[] bytes=in.readAllBytes();if(!Files.isRegularFile(icon)||!java.util.Arrays.equals(bytes,Files.readAllBytes(icon)))Files.write(icon,bytes);}
  String launcher=System.getProperty("jpackage.app-path","");String command;
  if(!launcher.isBlank())command="\""+launcher+"\"";
  else{Path jar=Path.of(WindowBranding.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(!jar.toString().endsWith(".jar"))return;command="\""+Path.of(System.getProperty("java.home"),"bin","javaw.exe")+"\" -jar \""+jar+"\"";}
  var bounds=PreviewPanel.toPhysicalScreenBounds(window.getBounds(),window.getGraphicsConfiguration());long hwnd=NativeWindowCompositor.destination(bounds.x,bounds.y,bounds.width,bounds.height);
  if(hwnd!=0)NativeWindowCompositor.windowIdentity(hwnd,icon.toString()+",0",command);
 }catch(Exception|LinkageError ignored){}}
}
