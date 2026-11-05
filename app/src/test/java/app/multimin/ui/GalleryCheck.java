package app.multimin.ui;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/** Isolated filesystem and Swing regression checks; never reads user screenshots. */
public final class GalleryCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory(Path.of(args[0]),"gallery-test-");ScreenshotStore store=new ScreenshotStore(folder);
        long now=System.currentTimeMillis();Path a=folder.resolve("snapshot-a.png"),b=folder.resolve("snapshot-b.png");
        BufferedImage image=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(new Color(25,32,48));g.fillRect(0,0,640,360);g.setColor(new Color(103,153,255));g.fillRoundRect(40,50,560,230,24,24);g.setColor(Color.WHITE);g.setFont(new Font(Font.DIALOG,Font.BOLD,32));g.drawString("MultiMini screenshot",130,180);g.dispose();ImageIO.write(image,"png",a.toFile());ImageIO.write(image,"png",b.toFile());
        Path saved=store.save(image);check(ImageIO.read(saved.toFile()).getWidth()==640,"PNG snapshot saved at original resolution");store.delete(new ScreenshotStore.Photo(saved,now,false));
        Files.setLastModifiedTime(b,FileTime.fromMillis(now-ScreenshotStore.RETENTION-1000));check(store.list().size()==1,"expiry deletion");
        var photo=store.list().get(0);store.keep(photo,true);Files.setLastModifiedTime(a,FileTime.fromMillis(now-ScreenshotStore.RETENTION-1000));check(store.list().get(0).kept(),"Keep survives expiration");store.keep(store.list().get(0),false);check(!store.list().get(0).kept()&&store.list().get(0).remaining(System.currentTimeMillis())>ScreenshotStore.RETENTION-5000,"unkeep fresh interval");
        check(new ScreenshotStore.Photo(a,now-1000,false).shortTime(now).equals("6D"),"days badge");check(new ScreenshotStore.Photo(a,now-ScreenshotStore.RETENTION+4*3600000,false).shortTime(now).equals("4h"),"hours badge");check(!photo.time(now).contains("seconds"),"no seconds");
        try{store.delete(new ScreenshotStore.Photo(folder.getParent().resolve("snapshot-outside.png"),now,false));throw new AssertionError("outside path accepted");}catch(java.io.IOException expected){}
        Palette p=new Palette();ScreenshotGallery[] gallery={null};
        SwingUtilities.invokeAndWait(()->{
            JScrollBar bar=new JScrollBar();ModernUi.scroll(bar,p);bar.updateUI();check(bar.getUI().getClass().getName().contains("ModernUi"),"scrollbar survives refresh");
            JSplitPane split=new JSplitPane();ModernUi.split(split,p);split.setDividerLocation(123);split.updateUI();check(split.getUI().getClass().getName().contains("ModernUi")&&split.getDividerSize()==4,"divider survives refresh");check(split.getDividerLocation()==123,"divider location survives refresh");
            gallery[0]=new ScreenshotGallery(p,store);gallery[0].setSize(1050,680);gallery[0].addNotify();gallery[0].validate();
        });
        Thread.sleep(600);
        SwingUtilities.invokeAndWait(()->{gallery[0].photos.setSelectedIndex(0);});Thread.sleep(500);
        if(args.length>1&&args[1].equals("--live")) {
            java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeAndWait(()->{gallery[0].addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosed(java.awt.event.WindowEvent e){closed.countDown();}});gallery[0].setLocationRelativeTo(null);gallery[0].setVisible(true);});
            closed.await();
        }
        SwingUtilities.invokeAndWait(()->{BufferedImage ui=new BufferedImage(1050,680,BufferedImage.TYPE_INT_RGB);Graphics2D paint=ui.createGraphics();gallery[0].getContentPane().printAll(paint);paint.dispose();try{ImageIO.write(ui,"png",Path.of(args[0],"gallery-check.png").toFile());}catch(Exception ex){throw new RuntimeException(ex);}check(gallery[0].keep.isEnabled(),"single-selection actions enabled");gallery[0].photos.clearSelection();check(!gallery[0].keep.isEnabled(),"no-selection actions disabled");gallery[0].dispose();});
        store.delete(store.list().get(0));check(store.list().isEmpty(),"delete photo and marker");Files.deleteIfExists(folder.resolve(".screenshots.lock"));Files.delete(folder);System.out.println("Gallery, retention and theme-delegate checks passed.");
    }
}
