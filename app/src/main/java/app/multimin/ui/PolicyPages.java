package app.multimin.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;

/** Bundled text only: opening Help or a policy never loads remote content. */
final class PolicyPages {
    static JTabbedPane create(Palette palette, boolean includeHelp) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setBackground(palette.panel); tabs.setForeground(palette.text);
        tabs.getAccessibleContext().setAccessibleName("Help and policies");
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults(); tabInsets = new Insets(9, 10, 9, 10);
                contentBorderInsets = new Insets(10, 0, 0, 0);
            }
            @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
                g.setColor(palette.panel); g.fillRect(x,y,w,h);
                if(selected){g.setColor(palette.controlSurface());g.fillRoundRect(x+2,y+2,w-4,h-5,10,10);}
                if (selected) { g.setColor(palette.controlAccent()); g.fillRect(x+8,y+h-3,w-16,3); }
            }
            @Override protected void paintTabBorder(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected) {}
            @Override protected void paintContentBorder(Graphics g,int p,int s) {}
            @Override protected void paintText(Graphics g,int p,Font f,FontMetrics m,int i,String title,Rectangle bounds,boolean selected) {
                g.setFont(f);g.setColor(palette.text);g.drawString(title,bounds.x,bounds.y+m.getAscent());
            }
            @Override protected void paintFocusIndicator(Graphics g,int p,Rectangle[] r,int i,Rectangle icon,Rectangle text,boolean selected) {
                if(tabs.hasFocus() && selected) {g.setColor(palette.controlAccent());g.fillRect(r[i].x+8,r[i].y+r[i].height-3,r[i].width-16,3);}
            }
        });
        if (includeHelp) add(tabs,palette,"Guide","HELP.md");
        add(tabs,palette,"Privacy","PRIVACY.md");
        add(tabs,palette,"EULA & terms","TERMS.md");
        add(tabs,palette,"Cookies","COOKIE_POLICY.md");
        add(tabs,palette,"Refunds","REFUND_POLICY.md");
        add(tabs,palette,"Licenses","THIRD_PARTY_NOTICES.md");
        return tabs;
    }

    private static void add(JTabbedPane tabs, Palette palette, String title, String resource) {
        JTextPane text = new JTextPane(); text.setEditable(false);
        text.setFocusTraversalKeysEnabled(true);
        text.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, java.util.Set.of(KeyStroke.getKeyStroke("TAB")));
        text.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, java.util.Set.of(KeyStroke.getKeyStroke("shift TAB")));
        text.setBackground(palette.controlSurface()); text.setForeground(palette.text);
        text.setSelectionColor(palette.accent);text.setSelectedTextColor(palette.selectionText());
        text.setBorder(new EmptyBorder(24,28,24,28));
        text.getAccessibleContext().setAccessibleName(title + " document");
        text.getAccessibleContext().setAccessibleDescription("Read-only local document. Use arrow keys or Page Down to read; Tab to leave.");
        String contents;
        try (var in = PolicyPages.class.getResourceAsStream("/legal/" + resource)) {
            contents = in == null ? "Document unavailable. Obtain a complete release." : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { contents = "Document could not be read. Obtain a complete release."; }
        StyledDocument doc = text.getStyledDocument();
        for (String line : contents.replaceAll("(?m)^\\h*$\\R", "").split("\\R", -1)) {
            boolean heading = line.startsWith("#");
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setFontFamily(style,"Segoe UI");StyleConstants.setFontSize(style,heading?(line.startsWith("# ")?22:16):14);
            StyleConstants.setBold(style,heading);
            StyleConstants.setSpaceAbove(style,heading?10:0);
            StyleConstants.setSpaceBelow(style,heading?6:2);
            StyleConstants.setLineSpacing(style,0.10f);
            StyleConstants.setForeground(style,palette.text);
            try { int start=doc.getLength(); doc.insertString(start,(heading?line.replaceFirst("^#+\\s*",""):line)+"\n",style); doc.setParagraphAttributes(start,doc.getLength()-start,style,false); }
            catch (BadLocationException error) { throw new IllegalStateException(error); }
        }
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line),new EmptyBorder(1,1,1,1)));
        scroll.getViewport().setBackground(palette.controlSurface());scroll.getVerticalScrollBar().setUnitIncrement(22);
        ModernUi.scroll(scroll.getVerticalScrollBar(),palette);tabs.addTab(title,scroll);
    }
}
