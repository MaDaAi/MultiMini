package app.multimin.ui;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import javax.swing.border.EmptyBorder;
import java.awt.Font;
import java.awt.Color;
public final class LegalConsent {
 private static Palette THEME=new Palette();
 private static final String VERSION="2026-11-05-updates";
 private static String read(String name){try(var in=LegalConsent.class.getResourceAsStream("/legal/"+name)){if(in==null)return "Document unavailable. Obtain the complete release before using capture.";return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){return "Document could not be read.";}}
 private static Color BG=new Color(24,27,33), PANEL=new Color(35,39,48), TEXT=new Color(232,235,240), MUTED=new Color(150,157,169), ACCENT=new Color(103,153,255);
 public static void setPalette(Palette palette){THEME=palette;BG=palette.background;PANEL=palette.controlSurface();TEXT=palette.text;MUTED=palette.muted;ACCENT=palette.accent;}
 private static JTabbedPane documents(){
  JTabbedPane tabs=new JTabbedPane();tabs.setFont(new Font(Font.DIALOG,Font.PLAIN,13));tabs.setBackground(BG);tabs.setForeground(TEXT);
  tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI(){
   @Override protected void installDefaults(){super.installDefaults();tabInsets=new Insets(10,14,10,14);contentBorderInsets=new Insets(10,0,0,0);}
   @Override protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(BG);g.fillRect(x,y,w,h);if(selected){g.setColor(ACCENT);g.fillRect(x+10,y+h-2,w-20,2);}}
   @Override protected void paintTabBorder(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected){}
   @Override protected void paintContentBorder(Graphics g,int placement,int selected){}
   @Override protected void paintFocusIndicator(Graphics g,int p,Rectangle[] rects,int index,Rectangle icon,Rectangle text,boolean selected){}
   @Override protected void paintText(Graphics g,int placement,Font font,FontMetrics metrics,int index,String title,Rectangle bounds,boolean selected){g.setFont(font);g.setColor(selected?TEXT:MUTED);g.drawString(title,bounds.x,bounds.y+metrics.getAscent());}
  });
  for(String name:new String[]{"TERMS.md","PRIVACY.md"}){
   JTextPane text=new JTextPane();text.setEditable(false);text.setBackground(PANEL);text.setForeground(TEXT);text.setSelectionColor(ACCENT);text.setSelectedTextColor(THEME.selectionText());text.setBorder(new EmptyBorder(14,16,14,16));
   var document=text.getStyledDocument();for(String line:read(name).split("\\R",-1)){boolean heading=line.startsWith("#");var style=new javax.swing.text.SimpleAttributeSet();javax.swing.text.StyleConstants.setFontFamily(style,Font.DIALOG);javax.swing.text.StyleConstants.setFontSize(style,heading?16:13);javax.swing.text.StyleConstants.setBold(style,heading);javax.swing.text.StyleConstants.setForeground(style,TEXT);try{document.insertString(document.getLength(),(heading?line.replaceFirst("^#+\\s*",""):line)+"\n",style);}catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}}
   text.setCaretPosition(0);JScrollPane scroll=new JScrollPane(text);scroll.setBorder(BorderFactory.createLineBorder(THEME.line));scroll.getViewport().setBackground(PANEL);scroll.getVerticalScrollBar().setUnitIncrement(20);ModernUi.scroll(scroll.getVerticalScrollBar(),THEME);tabs.add(name.equals("TERMS.md")?"User agreement":"Privacy notice",scroll);
  }return tabs;
 }
 public static void showDocuments(Component parent){Window owner=parent==null?null:parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent);JDialog dialog=new JDialog(owner,"Agreement and privacy",Dialog.ModalityType.APPLICATION_MODAL);JPanel body=new JPanel(new BorderLayout(12,14));body.setBackground(BG);body.setBorder(new EmptyBorder(16,18,14,18));body.add(documents());JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));actions.setOpaque(false);JButton close=new ModernButton("Close",null,THEME,false);close.addActionListener(e->dialog.dispose());actions.add(close);body.add(actions,BorderLayout.SOUTH);dialog.setContentPane(body);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setSize(780,600);dialog.setMinimumSize(new Dimension(600,420));dialog.setLocationRelativeTo(parent);dialog.setVisible(true);}
 public static boolean ensureAccepted(){if(LegalConsent.class.getResource("/legal/TERMS.md")==null || LegalConsent.class.getResource("/legal/PRIVACY.md")==null){AppNotice.show(null,THEME,"Agreement unavailable","Agreement documents are missing. Please obtain the complete release.","Close");return false;}Path path=WorkspaceStore.directory().resolve("agreement.properties");Properties p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);if(VERSION.equals(p.getProperty("acceptedVersion")))return true;}catch(Exception ignored){}
  JDialog dialog=new JDialog((Frame)null,"Welcome to MultiMini",true);JPanel body=new JPanel(new BorderLayout(14,14));body.setBackground(BG);body.setBorder(BorderFactory.createEmptyBorder(20,22,18,22));JLabel intro=new JLabel("<html><b>Welcome to MultiMini</b><br>Review the agreement and privacy notice before continuing.</html>");intro.setFont(new Font(Font.DIALOG,Font.PLAIN,14));intro.setForeground(TEXT);body.add(intro,BorderLayout.NORTH);body.add(documents(),BorderLayout.CENTER);JCheckBox agree=new JCheckBox("I have read and agree to the User Agreement and have read the Privacy Notice.");agree.setOpaque(false);agree.setForeground(TEXT);agree.setFont(new Font(Font.DIALOG,Font.PLAIN,12));agree.setIcon(new AccentCheckIcon(THEME));JButton accept=new ModernButton("Agree and continue",null,THEME,true),decline=new ModernButton("Decline and exit",null,THEME,false);accept.setEnabled(false);agree.addActionListener(e->accept.setEnabled(agree.isSelected()));boolean[] result={false};accept.addActionListener(e->{try{Files.createDirectories(path.getParent());Properties record=new Properties();record.setProperty("acceptedVersion",VERSION);record.setProperty("acceptedAt",java.time.Instant.now().toString());try(var out=Files.newOutputStream(path)){record.store(out,"MultiMini local agreement acceptance");}result[0]=true;dialog.dispose();}catch(Exception error){AppNotice.show(dialog,THEME,"Agreement","Could not save acceptance: "+error.getMessage(),"Close");}});decline.addActionListener(e->dialog.dispose());JPanel bottom=new JPanel(new BorderLayout(8,12));bottom.setBackground(BG);bottom.add(agree,BorderLayout.NORTH);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));buttons.setOpaque(false);buttons.add(decline);buttons.add(accept);bottom.add(buttons,BorderLayout.SOUTH);body.add(bottom,BorderLayout.SOUTH);dialog.setContentPane(body);dialog.setMinimumSize(new Dimension(620,460));dialog.setSize(780,600);dialog.setResizable(true);dialog.setLocationRelativeTo(null);dialog.setVisible(true);return result[0];
 }
}
