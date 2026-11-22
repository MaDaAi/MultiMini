package app.multimin.ui;

import app.multimin.model.Source;
import app.multimin.model.View;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;

/**
 * Renders the configure-mode UI to PNGs for visual review. Run with a
 * disposable APPDATA/LOCALAPPDATA/user.home; never touches real settings.
 * The capture backend is not started.
 */
public final class RedesignShots {
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]); Files.createDirectories(out);
        boolean light = args.length > 1 && args[1].equals("light");
        SwingUtilities.invokeAndWait(() -> {
            try { run(light); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(500);
        System.exit(0);
    }

    static BufferedImage sample(int w, int h, Color a, Color b, String label) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, a, w, h, b)); g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 40));
        for (int i = 0; i < 6; i++) g.fillRoundRect(40 + i * (w / 7), h / 3, w / 9, h / 3, 18, 18);
        g.setColor(new Color(0, 0, 0, 90)); g.fillRect(0, 0, w, h / 14);
        g.setColor(Color.WHITE); g.setFont(new Font("Segoe UI", Font.BOLD, h / 12)); g.drawString(label, 40, h - h / 8);
        g.dispose(); return image;
    }

    static void run(boolean light) throws Exception {
        AppPreferences prefs = new AppPreferences();
        prefs.putString("themeMode", light ? "Light" : "Dark");
        MainWindow window = new MainWindow(new app.multimin.backend.PythonBridge());
        Field viewsField = MainWindow.class.getDeclaredField("activeView"); viewsField.setAccessible(true);
        View view = (View) viewsField.get(window);
        view.sources.clear();
        Source chrome = new Source("Chrome - WC", Source.Kind.WINDOW); chrome.liveFrame = sample(1600, 900, new Color(40, 70, 140), new Color(20, 30, 60), "Chrome");
        chrome.scale = 55; chrome.x = 5; chrome.y = 10; chrome.quickSwapInput = true; chrome.controlColor = new Color(90, 200, 140);
        Source game = new Source("Minecraft - GC", Source.Kind.GAME); game.liveFrame = sample(1920, 1080, new Color(60, 140, 80), new Color(30, 60, 30), "Minecraft");
        game.scale = 45; game.x = 92; game.y = 85; game.quickSwapInput = true; game.controlColor = new Color(170, 120, 240); game.opacity = 90;
        Source notes = new Source("Goodnotes - WC", Source.Kind.WINDOW); notes.visible = false;
        Source image = new Source("Wallpaper - IMG", Source.Kind.IMAGE); image.liveFrame = sample(1280, 720, new Color(120, 60, 140), new Color(30, 20, 50), "Image");
        view.sources.addElement(chrome); view.sources.addElement(game); view.sources.addElement(notes); view.sources.addElement(image);
        @SuppressWarnings("unchecked") JList<Source> list = (JList<Source>) field(window, "sourceList");
        list.setSelectedIndex(0);
        window.setLocation(-4000, 0); // off-screen, app's own default (maximized) size
        window.setVisible(true);
        window.validate();
        pump(1500);
        System.out.println("right panel width " + ((JComponent) field(window, "propertiesPanelRef")).getWidth() + " / left " + ((JComponent) field(window, "sourcesPanelRef")).getWidth());
        shoot(window.getRootPane(), "1-main" + (light ? "-light" : ""));

        // Dialogs: capture each shortly after it opens, then close it.
        capture("2-settings" + (light ? "-light" : ""), () -> invoke(window, "showMultitaskSettings"));
        capture("3-add-source" + (light ? "-light" : ""), () -> invoke(window, "chooseSourceKind"));
        capture("4-configure" + (light ? "-light" : ""), () -> invoke(window, "configureSelectedSource"));
        ScreenshotStore store = new ScreenshotStore();
        Files.createDirectories(store.directory);
        for (int i = 0; i < 3; i++) ImageIO.write(sample(1600, 900, new Color(40 + i * 40, 70, 140 - i * 30), new Color(20, 30, 60), "Shot " + (i + 1)), "png", store.directory.resolve("snapshot-2026112" + i + "-1200" + i + ".png").toFile());
        var photos = store.list(); if (!photos.isEmpty()) store.keep(photos.get(0), true);
        invoke(window, "openGallery");
        Field galleryField = MainWindow.class.getDeclaredField("gallery"); galleryField.setAccessible(true);
        Window gallery = (Window) galleryField.get(window);
        gallery.setLocation(-4000, 0); gallery.validate();
        @SuppressWarnings("unchecked") JList<Object> photoList = (JList<Object>) field(gallery, "photos");
        // Wait for the background listing and thumbnails, pumping events, then shoot.
        long until = System.currentTimeMillis() + 2500;
        java.awt.EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        while (System.currentTimeMillis() < until) { AWTEvent event = queue.peekEvent() == null ? null : queue.getNextEvent(); if (event != null) { Object src = event.getSource(); if (event instanceof ActiveEvent active) active.dispatch(); else if (src instanceof Component c) c.dispatchEvent(event); } else Thread.sleep(20); if (photoList.getModel().getSize() > 0 && photoList.getSelectedIndex() < 0) photoList.setSelectedIndex(1); }
        shoot(((RootPaneContainer) gallery).getRootPane(), "5-gallery" + (light ? "-light" : ""));
        gallery.dispose();
        Class<?> picker = null;
        try { picker = Class.forName("app.multimin.ui.WindowPicker"); } catch (ClassNotFoundException ignored) { }
        if (picker != null) {
            Method demo = picker.getDeclaredMethod("demo", MainWindow.class); demo.setAccessible(true);
            capture("6-picker" + (light ? "-light" : ""), () -> { try { demo.invoke(null, window); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        // Display picker with sample monitor images (never the real screen).
        capture("7-displays" + (light ? "-light" : ""), () -> {
            JDialog d = new JDialog(window, "Choose a display", true);
            WindowPicker p = new WindowPicker(window.uiPalette()); p.setDisplayMode(true);
            java.util.List<app.multimin.backend.PythonBridge.WindowTarget> displays = java.util.List.of(
                new app.multimin.backend.PythonBridge.WindowTarget("2560 × 1440 · primary", "Display 1", 1, 0, 0, 2560, 1440),
                new app.multimin.backend.PythonBridge.WindowTarget("1920 × 1080", "Display 2", 2, 2560, 0, 1920, 1080));
            p.setTargets(displays); p.setSelected(displays.get(0));
            p.setThumbnail(1, sample(420, 236, new Color(30, 60, 120), new Color(15, 20, 40), "Display 1"));
            p.setThumbnail(2, sample(420, 236, new Color(90, 50, 120), new Color(25, 15, 40), "Display 2"));
            JPanel b = new GlassPanel(window.uiPalette()); b.setLayout(new BorderLayout()); b.setBorder(BorderFactory.createEmptyBorder(22, 22, 18, 22)); b.add(p);
            d.setContentPane(b); d.setSize(820, 420); d.setLocation(-4000, 0); d.setVisible(true);
        });
        if (!light) {
            // Switch to light without restarting: nothing dark may remain.
            Palette palette = window.uiPalette(); palette.light();
            invoke(window, "refreshTheme"); pump(600);
            shoot(window.getRootPane(), "8-switched-to-light");
        }
        window.dispose();
    }

    static void pump(long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        java.awt.EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        while (System.currentTimeMillis() < until) {
            AWTEvent event = queue.peekEvent() == null ? null : queue.getNextEvent();
            if (event == null) { Thread.sleep(15); continue; }
            if (event instanceof ActiveEvent active) active.dispatch(); else if (event.getSource() instanceof Component c) c.dispatchEvent(event);
        }
    }
    static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    static void invoke(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (Exception e) { throw new RuntimeException(e); } }

    static void capture(String name, Runnable open) {
        Timer timer = new Timer(700, e -> {
            for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing()) {
                d.validate(); shoot(d.getRootPane(), name); d.dispose();
            }
        });
        timer.setRepeats(false); timer.start();
        open.run();
    }

    static void shoot(JComponent component, String name) {
        try {
            component.validate();
            BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); component.printAll(g); g.dispose();
            ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
            System.out.println("shot " + name + " " + image.getWidth() + "x" + image.getHeight());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
