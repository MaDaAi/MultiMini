package app.multimin.ui;
import app.multimin.model.*;import java.nio.file.*;import java.util.*;
public class WorkspaceCheck {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("multimini-workspace-test-");Path path=root.resolve("workspace.properties");
  WorkspaceStore store=new WorkspaceStore(path);if(!store.load().views().isEmpty())throw new AssertionError("New workspace must be empty");
  View work=new View("Work"),media=new View("Media");Source window=new Source("Editor",Source.Kind.WINDOW);window.inputTarget="A document";window.windowProcess="editor.exe";window.opacity=42;window.scaleX=75;window.freeTransform=true;window.nativeWindowHandle=12345;window.captureFramePath="temporary.bmp";Source image=new Source("Image",Source.Kind.IMAGE);image.imagePath="C:/a b/image.png";work.sources.addElement(window);work.sources.addElement(image);work.controlSource=1;
  store.save(List.of(work,media),media);var loaded=new WorkspaceStore(path).load();Source restored=loaded.views().get(0).sources.get(0);
  if(loaded.active()!=1||loaded.views().size()!=2||restored.opacity!=42||restored.scaleX!=75||!restored.freeTransform||!restored.windowProcess.equals("editor.exe")||restored.nativeWindowHandle!=0||restored.captureFramePath!=null||loaded.views().get(0).controlSource!=1||!loaded.views().get(0).sources.get(1).imagePath.equals(image.imagePath))throw new AssertionError("Round trip failed");
  String bytes=Files.readString(path);if(bytes.contains("12345")||bytes.contains("temporary.bmp"))throw new AssertionError("Runtime handles leaked");
  window.opacity=67;store.save(List.of(work,media),media);Files.writeString(path,"schema=bad");WorkspaceStore recovery=new WorkspaceStore(path);var recovered=recovery.load();if(recovered.views().get(0).sources.get(0).opacity!=42)throw new AssertionError("Backup recovery failed");recovery.save(recovered.views(),recovered.views().get(0));new WorkspaceStore(path).load();
  Files.writeString(path,"schema=999");Files.delete(path.resolveSibling("workspace.properties.bak"));try{new WorkspaceStore(path).load();throw new AssertionError("Unknown schema accepted");}catch(java.io.IOException expected){}
  try(var files=Files.list(root)){for(Path f:files.toList())Files.delete(f);}Files.delete(root);System.out.println("PASS: workspace roundtrip, empty defaults, no live handles, backup recovery and unsupported schema");
 }
}
