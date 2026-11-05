package app.multimin;

import app.multimin.backend.PythonBridge;
import app.multimin.ui.MainWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        java.util.Properties features = new java.util.Properties();
        try (var input = Main.class.getResourceAsStream("/build.properties")) {
            if (input != null) features.load(input);
        } catch (java.io.IOException ignored) { }
        System.setProperty("multimin.dev.gameCapture", features.getProperty("experimentalGameCapture", "false"));
        System.setProperty("multimin.nativeWindowPresentation", features.getProperty("nativeWindowPresentation", "false"));
        System.setProperty("multimin.publicUpdates", features.getProperty("publicUpdates", "false"));
        // Java2D uses Direct3D on supported Windows systems. Retain an opt-out
        // for drivers where accelerated volatile surfaces are unreliable.
        if ("1".equals(System.getenv("MULTIMIN_SOFTWARE_RENDERER")))
            System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // Use Swing's cross-platform delegates so controls honor the app palette
        // instead of Windows' native white combo boxes and dialog surfaces.
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) { }
        UIManager.put("Panel.background", new java.awt.Color(24, 27, 33));
        UIManager.put("Label.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("Button.background", new java.awt.Color(48, 54, 65));
        UIManager.put("Button.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("List.background", new java.awt.Color(30, 34, 41));
        UIManager.put("List.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("List.selectionBackground", new java.awt.Color(63, 91, 151));
        UIManager.put("List.selectionForeground", java.awt.Color.WHITE);
        UIManager.put("ScrollPane.background", new java.awt.Color(30, 34, 41));
        UIManager.put("Viewport.background", new java.awt.Color(30, 34, 41));
        UIManager.put("ComboBox.background", new java.awt.Color(48, 54, 65));
        UIManager.put("ComboBox.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("CheckBox.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("RadioButton.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("OptionPane.background", new java.awt.Color(24, 27, 33));
        UIManager.put("OptionPane.messageForeground", new java.awt.Color(232, 235, 240));
        UIManager.put("TextField.background", new java.awt.Color(48, 54, 65));
        UIManager.put("TextField.foreground", new java.awt.Color(232, 235, 240));
        UIManager.put("TextField.caretForeground", java.awt.Color.WHITE);
        UIManager.put("ComboBox.selectionBackground", new java.awt.Color(63, 91, 151));
        UIManager.put("ComboBox.selectionForeground", java.awt.Color.WHITE);
        UIManager.put("Slider.background", new java.awt.Color(24, 27, 33));
        UIManager.put("ScrollBar.background", new java.awt.Color(35, 39, 48));
        UIManager.put("ScrollBar.thumb", new java.awt.Color(82, 90, 105));

        try { if(app.multimin.backend.NativeWindowCompositor.available())app.multimin.backend.NativeWindowCompositor.initializeIdentity(); } catch(LinkageError ignored) {}
        SwingUtilities.invokeLater(() -> {
            if (!app.multimin.ui.LegalConsent.ensureAccepted()) return;
            PythonBridge backend = new PythonBridge();
            MainWindow window = new MainWindow(backend);
            window.setVisible(true);
            app.multimin.ui.WindowBranding.apply(window);
            backend.start(window::setBackendStatus);
            window.checkForUpdatesOnLaunch();
        });
    }
}
