package app.multimin.ui;

import javax.swing.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.io.*;

/** Public checks are optional; private builds accept verified user-downloaded updates. */
final class GithubUpdater {
    private static boolean busy;
    record Prepared(Path file, String hash, String version) {}
    static void checkOnLaunch(JFrame owner,Runnable save,Runnable exit){if(Boolean.getBoolean("multimin.publicUpdates"))check(owner,save,exit,true);}
    static void show(JFrame owner,Runnable save,Runnable exit){check(owner,save,exit,false);}
    private static void check(JFrame owner, Runnable save, Runnable exit, boolean startup) {
        if (busy && startup)return;
        if (busy) { message(owner,"An update operation is already in progress."); return; }
        Path install;
        try {
            Path jar=Path.of(GithubUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            install=jar.getParent().getParent();
            if(!Files.isRegularFile(install.resolve("MultiMini.exe")))throw new IOException();
        } catch(Exception error) { if(!startup)message(owner,"Installable updates work in the Windows EXE build. Download the installer from GitHub Releases.");return; }
        if(!Boolean.getBoolean("multimin.publicUpdates")){manual(owner,install,save,exit);return;}
        busy=true;
        new SwingWorker<String[],Void>() {
            Path script;
            protected String[] doInBackground()throws Exception{script=extractScript();return runScript(script,"-CurrentVersion",current(),"-Details");}
            protected void done(){try{String[] result=get();if(startup&&(!owner.isShowing()||!owner.isDisplayable())){busy=false;return;}if(result.length>0&&result[0].equals("PRIVATE")){busy=false;if(!startup)manual(owner,install,save,exit);return;}if(result.length==0||result[0].equals("NONE")){busy=false;if(!startup)message(owner,"You're up to date.");return;}String notes=result.length>1?new String(Base64.getDecoder().decode(result[1]),StandardCharsets.UTF_8):"";Palette palette=owner instanceof MainWindow main?main.uiPalette():new Palette();if(!UpdateDialog.offer(owner,palette,current(),result[0],notes)){busy=false;return;}download(owner,script,install,save,exit,result[0]);}catch(Exception error){busy=false;if(!startup)message(owner,"Update check failed: "+cause(error));}}
        }.execute();
    }
    private static String current(){return System.getProperty("jpackage.app-version","0.03");}
    private static void manual(JFrame owner,Path install,Runnable save,Runnable exit){
        Object[] choices={"Open releases","Close"};
        int choice=AppNotice.show(owner,owner instanceof MainWindow main?main.uiPalette():new Palette(),"MultiMini updates","Updates are not publicly accessible yet. Sign in to an authorized GitHub account,\ndownload the newer installer and run it over the existing installation.\nYour saved views, sources, settings and screenshots are preserved.\nOnce releases are public, MultiMini can download and apply updates for you.","Open releases","Close");
        if(choice==0)try{java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://github.com/MaDaAi/MultiMini/releases"));}catch(Exception error){message(owner,"Open https://github.com/MaDaAi/MultiMini/releases in your browser.");}
    }
    static Prepared stageLocal(Path selected,Path cache,String current)throws Exception{
        String name=selected.getFileName().toString();var match=Pattern.compile("^MultiMini-Setup-([0-9.]+)-Windows-x64\\.exe$").matcher(name);
        if(!match.matches())throw new IOException("Select an original MultiMini installer filename; remove any browser-added (1) suffix.");
        String version=match.group(1);if(compareVersions(version,current)<=0)throw new IOException("Select a newer version than "+current+". Downgrades are not applied.");
        Path sidecar=selected.resolveSibling(name+".sha256");if(!Files.isRegularFile(sidecar)||Files.size(sidecar)>4096)throw new IOException("Place the matching "+name+".sha256 file beside the installer.");
        var sum=Pattern.compile("(?i)^([0-9a-f]{64})\\s+\\*?"+Pattern.quote(name)+"$").matcher(Files.readString(sidecar,StandardCharsets.UTF_8).trim());
        if(!sum.matches())throw new IOException("The checksum file has an invalid filename or format.");
        String expected=sum.group(1).toLowerCase(Locale.ROOT);Files.createDirectories(cache);Path stage=Files.createTempDirectory(cache,"local-");Path file=stage.resolve(name);
        try{Files.copy(selected,file);String actual=digest(file);if(!actual.equals(expected))throw new IOException("Checksum mismatch. Download both files again from your authorized GitHub release.");return new Prepared(file,actual,version);}
        catch(Exception error){Files.deleteIfExists(file);Files.deleteIfExists(stage);throw error;}
    }
    static int compareVersions(String a,String b)throws IOException{try{String[] x=a.split("\\.",-1),y=b.split("\\.",-1);if(x.length>4||y.length>4)throw new NumberFormatException();for(int i=0;i<Math.max(x.length,y.length);i++){long left=i<x.length?Long.parseLong(x[i]):0,right=i<y.length?Long.parseLong(y[i]):0;if(left<0||right<0)throw new NumberFormatException();if(left!=right)return Long.compare(left,right);}return 0;}catch(NumberFormatException error){throw new IOException("Invalid update version",error);}}
    private static String digest(Path path)throws Exception{MessageDigest sum=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] bytes=new byte[1024*1024];for(int n;(n=in.read(bytes))>=0;)sum.update(bytes,0,n);}return HexFormat.of().formatHex(sum.digest());}
    private static Path cache()throws IOException{String base=System.getenv("LOCALAPPDATA");if(base==null)throw new IOException("Local application data folder unavailable");Path path=Path.of(base,"MultiMin","updates");Files.createDirectories(path);return path;}
    private static Path extractScript()throws Exception{Path file=Files.createTempFile(cache(),"updater-",".ps1");try(var in=GithubUpdater.class.getResourceAsStream("/app/multimin/updater.ps1")){if(in==null)throw new IOException("Updater resource missing");Files.copy(in,file,StandardCopyOption.REPLACE_EXISTING);}return file;}
    private static String[] runScript(Path script,String... args)throws Exception{List<String> command=new ArrayList<>(List.of("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",script.toString()));command.addAll(Arrays.asList(args));Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(p.waitFor()!=0)throw new IOException(output);return output.lines().filter(s->!s.isBlank()).toArray(String[]::new);}
    static void apply(Path script,Path file,String hash,Path install,Runnable save,Runnable exit)throws Exception{save.run();new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",script.toString(),"-Apply","-Prepared",file.toString(),"-ExpectedHash",hash,"-AppPid",Long.toString(ProcessHandle.current().pid()),"-InstallDir",install.toString()).redirectErrorStream(true).redirectOutput(script.getParent().resolve("update.log").toFile()).start();exit.run();}
    private static void download(JFrame owner,Path script,Path install,Runnable save,Runnable exit,String version){JDialog progress=progress(owner,"Downloading and verifying update…");new SwingWorker<String[],Void>(){protected String[] doInBackground()throws Exception{return runScript(script,"-Prepare","-CurrentVersion",current(),"-TargetVersion",version);}protected void done(){progress.dispose();try{String[] files=get();if(files.length!=2)throw new IOException("Release changed; check again");apply(script,Path.of(files[0]),files[1],install,save,exit);}catch(Exception error){busy=false;message(owner,"Update was not applied: "+cause(error));}}}.execute();}
    private static JDialog progress(JFrame owner,String text){Palette palette=owner instanceof MainWindow main?main.uiPalette():new Palette();JDialog dialog=new JDialog(owner,"Updating MultiMini",false);JPanel body=new JPanel(new java.awt.BorderLayout(12,16));body.setBackground(palette.background);body.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));JLabel label=new JLabel(text);label.setForeground(palette.text);label.setFont(new java.awt.Font(java.awt.Font.DIALOG,java.awt.Font.PLAIN,14));JProgressBar bar=new JProgressBar();bar.setIndeterminate(true);bar.setForeground(palette.accent);bar.setBackground(palette.controlSurface());bar.setBorderPainted(false);body.add(label,java.awt.BorderLayout.NORTH);body.add(bar);dialog.setContentPane(body);dialog.setSize(440,140);dialog.setLocationRelativeTo(owner);dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);dialog.setVisible(true);return dialog;}
    private static String cause(Exception error){Throwable cause=error.getCause();return cause==null?error.getMessage():cause.getMessage();}
    private static void message(JFrame owner,String message){AppNotice.show(owner,owner instanceof MainWindow main?main.uiPalette():new Palette(),"MultiMini updates",message,"Close");}
}
