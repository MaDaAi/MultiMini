package app.multimin.ui;

import javax.swing.*;
import java.awt.*;

/** Contrast and offline document coverage for the Help/policy revision. */
public final class AppearancePolicyCheck {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void checkText(Container parent) {
        for(Component child:parent.getComponents()) {
            if(child instanceof JTextPane text) {
                check(!text.getText().contains("Document unavailable"),"Missing bundled document");
                check(text.getText().length()>250,"Incomplete document");
                check(!text.isEditable() && text.isFocusable(),"Documents must be keyboard readable");
                check(text.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS).contains(KeyStroke.getKeyStroke("TAB")),"Tab must leave document");
                check(text.getAccessibleContext().getAccessibleName()!=null,"Document has no accessible name");
            }
            if(child instanceof Container container)checkText(container);
        }
    }
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(boolean light:new boolean[]{false,true}) {
                Palette p=new Palette();if(light)p.light();
                for(Color surface:new Color[]{p.panel,p.background,p.controlSurface()}) {
                    check(Palette.contrastRatio(p.text,surface)>=4.5,"Body text contrast");
                    check(Palette.contrastRatio(p.muted,surface)>=4.5,"Secondary text contrast");
                }
                check(Palette.contrastRatio(p.controlBorder(),p.controlSurface())>=3,"Control boundary contrast");
                for(Color accent:new Color[]{new Color(103,153,255),new Color(235,82,86),new Color(241,145,64),new Color(239,205,76),new Color(83,190,123),new Color(161,111,224),Color.WHITE,new Color(20,22,25)}) {
                    p.accent=accent;
                    check(Palette.contrastRatio(p.selectionText(),accent)>=4.5,"Primary action text contrast");
                    check(Palette.contrastRatio(p.controlAccent(),p.panel)>=3,"Slider/accent contrast");
                    check(Palette.contrastRatio(p.text,p.selectionSurface())>=4.5,"Selected row text contrast");
                }
                JTabbedPane pages=PolicyPages.create(p,true);
                check(pages.getTabCount()==6,"Missing Guide or policy tab");checkText(pages);
                check(PolicyPages.create(p,false).getTabCount()==5,"Onboarding policy coverage");
                ModernButton icon=new ModernButton("",new VectorIcon(VectorIcon.Type.ADD,13,p.text),p,false);
                icon.setToolTipText("Add source");
                check("Add source".equals(icon.getAccessibleContext().getAccessibleName()),"Icon button accessible name");
            }
            System.out.println("PASS: text >=4.5:1, control cues >=3:1, eight accents in both themes; six offline Help pages and named icon buttons");
        });
    }
}
