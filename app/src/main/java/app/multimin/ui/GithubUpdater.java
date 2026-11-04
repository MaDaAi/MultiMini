package app.multimin.ui;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
final class GithubUpdater {
 private static boolean busy;
 static void show(JFrame owner,Runnable exit) { if(busy){JOptionPane.showMessageDialog(owner,"An update operation is already in progress.");return;}
  if(!Boolean.getBoolean("multimin.publicUpdates")){JOptionPane.showMessageDialog(owner,"This private beta uses manual updates. Download the new installer through your authorized GitHub account and install it over this installation. Your saved workspace and settings are kept. Public in-app update checks are disabled.","Manual updates",JOptionPane.INFORMATION_MESSAGE);return;}
  Path install;
  try{Path jar=Path.of(GithubUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());install=jar.getParent().getParent();if(!Files.isRegularFile(install.resolve("MultiMini.exe")))throw new IOException();}
  catch(Exception error){busy=false;JOptionPane.showMessageDialog(owner,"In-app updates are available in the installed Windows EXE. Download the installer from github.com/MaDaAi/MultiMini/releases.");return;}
  String current=System.getProperty("jpackage.app-version","0.01");
  if(JOptionPane.showConfirmDialog(owner,"Check GitHub for a newer Windows release? GitHub receives your IP address and request metadata. No captured content or workspace is sent.","Check for updates",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
  busy=true; Path destination=install;
  new SwingWorker<String[],Void>() {
   Path script;
   protected String[] doInBackground()throws Exception{Path cache=Path.of(System.getenv("LOCALAPPDATA"),"MultiMin","updates");Files.createDirectories(cache);script=Files.createTempFile(cache,"updater-",".ps1");try(var in=GithubUpdater.class.getResourceAsStream("/app/multimin/updater.ps1")){if(in==null)throw new IOException("Updater resource missing");Files.copy(in,script,StandardCopyOption.REPLACE_EXISTING);}return GithubUpdater.run(script,"-CurrentVersion",current);}
   protected void done(){try{String[] result=get();if(result.length==0||result[0].equals("NONE")){busy=false;JOptionPane.showMessageDialog(owner,"No newer packaged Windows release was found.");return;}if(JOptionPane.showConfirmDialog(owner,"Version "+result[0]+" is available. Download, save your workspace, and restart to apply it? Your settings and sources stay in your user-data folder.","Update MultiMini",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION){busy=false;return;}download(owner,script,current,destination,exit);}catch(Exception error){busy=false;JOptionPane.showMessageDialog(owner,"Update check failed: "+error.getMessage());}}
  }.execute();
 }
 private static String[] run(Path script,String... args)throws Exception{List<String> command=new ArrayList<>(List.of("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",script.toString()));command.addAll(Arrays.asList(args));Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();if(p.waitFor()!=0)throw new IOException(output);return output.lines().filter(s->!s.isBlank()).toArray(String[]::new);}
 private static void download(JFrame owner,Path script,String current,Path install,Runnable exit){JDialog progress=new JDialog(owner,"Downloading update",false);progress.add(new JLabel("  Downloading and verifying the update. Please wait…  "));progress.pack();progress.setLocationRelativeTo(owner);progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);progress.setVisible(true);new SwingWorker<String[],Void>(){protected String[] doInBackground()throws Exception{return GithubUpdater.run(script,"-Prepare","-CurrentVersion",current);}protected void done(){progress.dispose();try{String[] prepared=get();if(prepared.length!=2)throw new IOException("Release changed; check again");Path log=script.getParent().resolve("update.log");new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",script.toString(),"-Apply","-Prepared",prepared[0],"-ExpectedHash",prepared[1],"-AppPid",Long.toString(ProcessHandle.current().pid()),"-InstallDir",install.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();exit.run();}catch(Exception error){busy=false;JOptionPane.showMessageDialog(owner,"Update failed; current app is unchanged: "+error.getMessage());}}}.execute();}
}
