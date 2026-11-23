package app.multimin.ui;

/** Release JSON parsing and error text for GithubUpdater; no network access. */
public class UpdateParseCheck {
 public static void main(String[] args)throws Exception{
  // Raw notes as a user would read them: quotes, backslashes and literal \n sequences.
  String unit="- Say \"hi\" from C:\\tmp\\new and literal \\n token\n";
  StringBuilder raw=new StringBuilder();
  while(raw.length()<300_000)raw.append(unit);
  String notes=raw.toString();
  String escapedBody=jsonEscape(notes);
  if(escapedBody.length()<290_000)throw new AssertionError("Body should be about 300 KB, was "+escapedBody.length());
  String version="9.8.7";
  String asset="MultiMini-"+version+"-Linux-x86_64.flatpak";
  String base="https://github.com/MaDaAi/MultiMini/releases/download/v"+version+"/";
  String json="{\"html_url\":\"https://github.com/MaDaAi/MultiMini/releases/tag/v"+version+"\","
   +"\"tag_name\":\"v"+version+"\",\"body\":\""+escapedBody+"\",\"assets\":["
   +"{\"name\":\""+asset+"\",\"browser_download_url\":\""+base+asset+"\"},"
   +"{\"name\":\""+asset+".sha256\",\"browser_download_url\":\""+base+asset+".sha256\"}]}";
  String pattern="MultiMini-([0-9]+(?:\\.[0-9]+){0,3})-Linux-x86_64\\.flatpak";
  GithubUpdater.PortableRelease release=GithubUpdater.parseLatest(json,pattern);
  if(release==null)throw new AssertionError("Latest release with a matching asset was not parsed");
  check(release.version().equals(version),"version: "+release.version());
  check(release.assetName().equals(asset),"asset name: "+release.assetName());
  check(release.assetUrl().equals(base+asset),"asset url: "+release.assetUrl());
  check(release.sumUrl().equals(base+asset+".sha256"),"checksum url: "+release.sumUrl());
  check(release.page().equals("https://github.com/MaDaAi/MultiMini/releases/tag/v"+version),"release page: "+release.page());
  check(release.notes().equals(notes),"notes not fully unescaped (length "+release.notes().length()+" vs "+notes.length()+")");

  // jsonString: missing key, and an escaped quote or backslash right before the closing quote.
  check(GithubUpdater.jsonString("{\"a\":\"b\"}","missing").equals(""),"missing key must return empty string");
  String tricky="{\"body\":\"ends with quote \\\"\",\"x\":\"y\"}";
  check(GithubUpdater.jsonString(tricky,"body").equals("ends with quote \\\""),"escaped quote before closing quote");
  String backslash="{\"body\":\"path C:\\\\\",\"x\":\"y\"}";
  check(GithubUpdater.jsonString(backslash,"body").equals("path C:\\\\"),"escaped backslash before closing quote");

  // describe: never null or "null", and known network causes read clearly.
  String stack=GithubUpdater.describe(new java.util.concurrent.ExecutionException(new StackOverflowError()));
  check(stack!=null && !stack.equals("null") && !stack.isBlank(),"describe(StackOverflowError) gave: "+stack);
  check(GithubUpdater.describe(new java.net.ConnectException()).equals("could not connect to GitHub"),"ConnectException text");

  System.out.println("PASS: release JSON with a 300 KB escaped body parses fully; jsonString edge cases; update error text readable");
 }
 private static String jsonEscape(String text){
  StringBuilder out=new StringBuilder(text.length()+text.length()/8);
  for(int i=0;i<text.length();i++){
   char c=text.charAt(i);
   switch(c){
    case '\\'->out.append("\\\\");
    case '"'->out.append("\\\"");
    case '\n'->out.append("\\n");
    default->out.append(c);
   }
  }
  return out.toString();
 }
 private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
}
