package app.multimin.ui;

import app.multimin.model.Source;
import app.multimin.model.View;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

public final class LinuxDirectPresentationCheck {
    private static void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);
    }

    private static Source source(Source.Kind kind) {
        Source source=new Source("fixture",kind);
        source.nativeWindowHandle=17;
        source.liveFrame=new BufferedImage(20,12,BufferedImage.TYPE_INT_RGB);
        source.liveFrame.setRGB(0,0,0xffff0000);
        return source;
    }

    private static View view(Source... sources) {
        View view=new View("fixture");
        for(Source source:sources)view.sources.addElement(source);
        return view;
    }

    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Source window=source(Source.Kind.WINDOW);
            View single=view(window);
            single.controlSource=0;
            check(PreviewPanel.linuxDirectCandidate(single)==window,"single window should qualify");

            Source game=source(Source.Kind.GAME);
            View gameView=view(game);
            gameView.controlSource=0;
            check(PreviewPanel.linuxDirectCandidate(gameView)==game,"single game should qualify");

            Source hidden=source(Source.Kind.IMAGE);
            hidden.visible=false;
            View withHidden=view(window,hidden);
            withHidden.controlSource=0;
            check(PreviewPanel.linuxDirectCandidate(withHidden)==window,"hidden source should not disqualify");

            check(PreviewPanel.linuxDirectCandidate(view(window,source(Source.Kind.IMAGE)))==null,"multiple visible sources must reject");
            window.opacity=99;
            check(PreviewPanel.linuxDirectCandidate(single)==null,"partial opacity must reject");
            window.opacity=100;
            single.controlSource=1;
            check(PreviewPanel.linuxDirectCandidate(single)==null,"different control source must reject");
            single.controlSource=0;
            window.nativePresentationEnabled=false;
            check(PreviewPanel.linuxDirectCandidate(single)==null,"disabled native presentation must reject");
            window.nativePresentationEnabled=true;
            window.liveFrame=null;
            check(PreviewPanel.linuxDirectCandidate(single)==null,"missing live frame must reject");
            window.liveFrame=new BufferedImage(20,12,BufferedImage.TYPE_INT_RGB);
            window.nativeWindowHandle=0;
            check(PreviewPanel.linuxDirectCandidate(single)==null,"missing native handle must reject");

            Source image=source(Source.Kind.IMAGE);
            image.scale=100;image.x=0;image.y=0;
            View screenshotView=view(image);
            PreviewPanel panel=new PreviewPanel(new Palette(),screenshotView);
            panel.setSize(40,24);
            panel.setLocked(true);
            panel.setLinuxDirectSource(image);
            check(panel.hasLinuxDirectSource(),"direct source should be recorded");
            BufferedImage screenshot=new BufferedImage(40,24,BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=screenshot.createGraphics();
            panel.printAll(graphics);
            graphics.dispose();
            check((screenshot.getRGB(0,0)&0x00ffffff)==0x00ff0000,"printAll should render the frame of a direct source");
            panel.removeNotify();
            System.out.println("PASS: Linux direct presentation eligibility and screenshot rendering");
        });
    }
}
