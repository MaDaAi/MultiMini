package app.multimin.ui;
import app.multimin.backend.PlatformSupport;
import java.nio.file.*;
import java.awt.image.BufferedImage;

/** Run with a disposable XDG_CONFIG_HOME and XDG_DATA_HOME. */
public final class LinuxPlatformCheck {
    public static void main(String[] args) throws Exception {
        if(!PlatformSupport.isLinux())throw new AssertionError("Linux required");
        Path expected=Path.of(System.getenv("XDG_CONFIG_HOME"),"multimin");
        if(!WorkspaceStore.directory().equals(expected))throw new AssertionError("XDG config path");
        AppPreferences preferences=new AppPreferences();
        preferences.putString("linuxCheck","saved");
        if(!new AppPreferences().getString("linuxCheck","").equals("saved"))throw new AssertionError("Settings persistence");
        ScreenshotStore store=new ScreenshotStore();
        Path photo=store.save(new BufferedImage(7,5,BufferedImage.TYPE_INT_RGB));
        var photos=store.list();
        if(photos.size()!=1)throw new AssertionError("Saved screenshot missing");
        store.keep(photos.get(0),true);
        if(!store.list().get(0).kept())throw new AssertionError("Keep not persistent");
        store.delete(store.list().get(0));
        if(Files.exists(photo))throw new AssertionError("Delete failed");
        System.out.println("PASS: XDG settings persistence, screenshot PNG, gallery Keep/Delete");
    }
}
