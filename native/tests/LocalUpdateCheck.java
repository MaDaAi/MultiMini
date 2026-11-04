package app.multimin.ui;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;
public class LocalUpdateCheck {
 interface Attempt{void run()throws Exception;}
 static void reject(Attempt action)throws Exception{try{action.run();throw new AssertionError("Invalid update was accepted");}catch(java.io.IOException expected){}}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("multimini-update-test-");Path cache=root.resolve("cache"),source=root.resolve("MultiMini-Setup-0.04-Windows-x64.exe");byte[] data="Harmless test payload; never executed".getBytes(StandardCharsets.UTF_8);Files.write(source,data);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));Path sum=source.resolveSibling(source.getFileName()+".sha256");Files.writeString(sum,hash+"  "+source.getFileName());var staged=GithubUpdater.stageLocal(source,cache,"0.03");if(!staged.hash().equals(hash)||!Arrays.equals(data,Files.readAllBytes(staged.file())))throw new AssertionError("Staging changed bytes");
  reject(()->GithubUpdater.stageLocal(source,cache,"0.04"));reject(()->GithubUpdater.stageLocal(source,cache,"0.05"));Files.writeString(source,"corrupted");reject(()->GithubUpdater.stageLocal(source,cache,"0.03"));Files.delete(sum);reject(()->GithubUpdater.stageLocal(source,cache,"0.03"));reject(()->GithubUpdater.compareVersions("0..4","0.03"));
  try{GithubUpdater.apply(root.resolve("not-executed.ps1"),staged.file(),hash,root,()->{throw new IllegalStateException("Simulated save failure");},()->{throw new AssertionError("Exited after failed save");});throw new AssertionError("Failed save accepted");}catch(IllegalStateException expected){}if(Files.exists(root.resolve("update.log")))throw new AssertionError("Updater was launched before saving");
  try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}System.out.println("PASS: local update staging, checksum rejection, missing checksum, downgrade/same-version rejection; no installer executed");
 }
}
