package app.multimin.ui;

import app.multimin.backend.PythonBridge;
import app.multimin.backend.DisplayCapture;
import app.multimin.backend.WindowFrameReader;
import app.multimin.model.Source;
import app.multimin.model.View;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Area;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainWindow extends JFrame {
    private final Palette palette = new Palette();
    private final PythonBridge backend;
    private final List<View> views = new ArrayList<>();
    private final Map<View, AbstractButton> viewButtons = new HashMap<>();
    private final BackgroundPanel root = new BackgroundPanel(palette);
    private final JPanel configuration = new JPanel(new BorderLayout());
    private JComponent sourcesPanelRef;
    private JComponent propertiesPanelRef;
    private final JPanel canvasPanel = new JPanel(new BorderLayout());
    private final JPanel topBar = new GlassPanel(palette);
    private final JList<Source> sourceList = new JList<>();
    private final JLabel viewTitle = new JLabel();
    private final JLabel layerTitle = new JLabel("No source selected", SwingConstants.LEFT);
    private final JLabel layerSubtitle = new JLabel(" ");
    private JComponent sourceBadge;
    private final JLabel backendStatus = new JLabel("Starting capture service…");
    private final JLabel performanceStatus = new JLabel();
    private final JLabel inputKeyHint = new JLabel();
    private final JLabel layerKeyHint = new JLabel();
    private final JPanel debugPanel = new JPanel(new GridLayout(0, 1));
    private final JLabel multitaskInputLabel = new JLabel("Input target: desktop");
    private final JSlider opacity = new JSlider(0, 100, 100);
    private final JSlider scale = new JSlider(5, 250, 38);
    private final List<JTextField> percentageFields = new ArrayList<>();
    private final JComboBox<String> accentMode = new JComboBox<>(new String[]{"Blue", "Red", "Orange", "Yellow", "Green", "Purple", "White", "Black", "Custom…"});
    private LogoIcon appLogo;
    private PreviewPanel preview;
    private final List<DisplayCapture> displayCaptures = new ArrayList<>();
    private final Map<Integer, BufferedImage> pendingDisplayFrames = new ConcurrentHashMap<>();
    private final AtomicBoolean displayFrameUpdateQueued = new AtomicBoolean();
    private final AtomicBoolean windowFrameScanQueued = new AtomicBoolean();
    private final AtomicBoolean newFramePending = new AtomicBoolean();
    private volatile List<Source> captureSnapshot = List.of();
    private volatile boolean captureClosing;
    private volatile long lastFrameRequestNanos;
    private List<String> activeCapturePaths = List.of();
    private boolean captureActivitySent;
    private final WindowFrameReader windowFrameReader = new WindowFrameReader();
    private final Map<Source, Long> lastFrameCounts = new HashMap<>();
    private final java.util.concurrent.ExecutorService windowFrameWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "multimin-window-frame-reader"); thread.setDaemon(true); return thread;
    });
    private JWindow multitaskWindow;
    private JWindow multitaskControls;
    private final Path windowFramesDirectory;
    private final FramePacer windowFrameTimer;
    private int lastAppliedCaptureFps;
    private long lastPerformanceSampleNanos = System.nanoTime();
    private final javax.swing.Timer performanceTimer;
    private final AppPreferences preferences = new AppPreferences();
    private int performanceFps = Math.max(0, Math.min(1000,
        !preferences.getBoolean("captureFpsChoiceV2", false) && preferences.getInt("captureFps", 0) == 60
            ? 0 : preferences.getInt("captureFps", 0)));
    private int revealCornerIndex = Math.clamp(preferences.getInt("revealCorner", 0), 0, 4);
    private String stopKeyBinding = platformKey(preferences.getString("stopKey", "END"));
    private String pendingWindowSourceName;
    private boolean pendingWindowNameManual;
    private String pendingImageName;
    private String swapKeyBinding = platformKey(preferences.getString("swapInputKey", "RIGHT_CTRL"));
    private String layerSwapKeyBinding = platformKey(preferences.getString("swapLayersKey", "PAUSE"));
    private boolean recordingLayerSwapKey;
    private JButton layerSwapKeyButton;
    private boolean recordingSwapKey;
    private boolean recordingStopKey;
    private boolean settingsHotkeyRecording;
    private String stagedSwapKeyBinding;
    private String stagedLayerSwapKeyBinding;
    private String stagedStopKeyBinding;
    private JButton swapKeyButton;
    private JButton stopKeyButton;
    private JButton multitaskExitButton;
    private JLabel multitaskTargetLabel;
    private javax.swing.Timer inputNoticeTimer;
    /**
     * MacBook keyboards have no End, Pause, Insert or Right Ctrl, so the Windows
     * defaults map to Mac-friendly ones. Bindings the user recorded are kept.
     */
    static String platformKey(String binding) {
        if (!app.multimin.backend.PlatformSupport.isMac() || binding == null) return binding;
        return switch (binding) {
            case "END" -> "CTRL+ALT+E";
            case "RIGHT_CTRL" -> "RIGHT_ALT";
            case "PAUSE" -> "CTRL+ALT+L";
            case "INSERT" -> "CTRL+ALT+S";
            case "CTRL+SHIFT+F12" -> "CTRL+ALT+R";
            default -> binding;
        };
    }
    private String screenshotKey=platformKey(preferences.getString("screenshotKey","INSERT")), regionScreenshotKey=platformKey(preferences.getString("regionScreenshotKey","CTRL+SHIFT+F12"));
    private ScreenshotSelection screenshotSelection;
    { if("SHIFT+INSERT".equals(regionScreenshotKey)){regionScreenshotKey="CTRL+SHIFT+F12";preferences.putString("regionScreenshotKey",regionScreenshotKey);} }
    private void configureScreenshotKeys(){backend.send("{\"command\":\"screenshot_shortcuts\",\"full\":\""+screenshotKey+"\",\"region\":\""+regionScreenshotKey+"\"}");}
    private JButton screenshotKeyRecorder(String binding){JButton button=quietButton(prettyKeyBinding(binding));button.putClientProperty("binding",binding);button.addActionListener(e->{button.putClientProperty("recording",true);button.setText("Press shortcut…");button.requestFocusInWindow();});button.addKeyListener(new java.awt.event.KeyAdapter(){@Override public void keyPressed(KeyEvent e){if(!Boolean.TRUE.equals(button.getClientProperty("recording")))return;e.consume();if(e.getKeyCode()==KeyEvent.VK_ESCAPE){button.putClientProperty("recording",false);button.setText(prettyKeyBinding((String)button.getClientProperty("binding")));return;}String key=keyBindingForEvent(e);if(key!=null){button.putClientProperty("binding",key);button.putClientProperty("recording",false);button.setText(prettyKeyBinding(key));}}});return button;}
    private void saveMultitaskScreenshot(java.awt.image.BufferedImage image){new SwingWorker<Path,Void>(){protected Path doInBackground()throws Exception{return new ScreenshotStore().save(image);}protected void done(){try{Path saved=get();if(gallery!=null&&gallery.isDisplayable())gallery.reload(saved);if(multitask){revealExit();multitaskTargetLabel.setText("Screenshot saved");multitaskTargetLabel.setVisible(true);inputNoticeTimer.restart();}}catch(Exception error){showNotice("Screenshot","Could not save: "+error.getMessage());}}}.execute();}
    private void captureMultitaskScreenshot(boolean region){
        if(!multitask||snapshotPending||screenshotSelection!=null)return;snapshotPending=true;
        GraphicsConfiguration screen=multitaskWindow.getGraphicsConfiguration();Rectangle bounds=multitaskWindow.getBounds();
        multitaskControls.setVisible(false);if(exitHideTimer!=null)exitHideTimer.stop();if(exitHoverTimer!=null)exitHoverTimer.stop();
        javax.swing.Timer settle=new javax.swing.Timer(40,e->new SwingWorker<java.awt.image.BufferedImage,Void>(){
            protected java.awt.image.BufferedImage doInBackground()throws Exception{if(app.multimin.backend.PlatformSupport.isPortable())return linuxComposition();Rectangle physical=PreviewPanel.toPhysicalScreenBounds(bounds,screen);long target=app.multimin.backend.NativeWindowCompositor.destination(physical.x,physical.y,physical.width,physical.height);int affinity=app.multimin.backend.NativeWindowCompositor.captureAffinity(target,0);if(affinity<0)throw new IllegalStateException("Could not capture multitask composition");java.awt.image.MultiResolutionImage capture;try{capture=new Robot(screen.getDevice()).createMultiResolutionScreenCapture(bounds);}finally{app.multimin.backend.NativeWindowCompositor.captureAffinity(target,affinity);}Image best=capture.getResolutionVariants().stream().max(java.util.Comparator.comparingLong(i->(long)i.getWidth(null)*i.getHeight(null))).orElseThrow();java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(best.getWidth(null),best.getHeight(null),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.drawImage(best,0,0,null);g.dispose();return image;}
            protected void done(){snapshotPending=false;if(!multitask)return;try{var image=get();if(region){backend.send("{\"command\":\"screenshot_selection\",\"enabled\":true}");screenshotSelection=new ScreenshotSelection(multitaskWindow,bounds,image,palette,MainWindow.this::saveMultitaskScreenshot,()->{screenshotSelection=null;if(multitask&&multitaskWindow!=null){multitaskWindow.setVisible(true);
            if (app.multimin.backend.PlatformSupport.isPortable() && multitaskWindow != null && !"native".equals(backend.linuxInputMode())) {
                multitaskWindow.setFocusableWindowState(true);multitaskWindow.requestFocus(); preview.setFocusable(true); preview.requestFocusInWindow();
            }multitaskWindow.toFront();}backend.send("{\"command\":\"screenshot_selection\",\"enabled\":false}");if(multitask&&exitHoverTimer!=null)exitHoverTimer.start();});multitaskWindow.setVisible(false);screenshotSelection.setVisible(true);screenshotSelection.toFront();screenshotSelection.requestFocus();}else{saveMultitaskScreenshot(image);exitHoverTimer.start();}}catch(Exception | LinkageError error){if(screenshotSelection!=null){screenshotSelection.dispose();screenshotSelection=null;}if(multitask&&multitaskWindow!=null)multitaskWindow.setVisible(true);
            if (app.multimin.backend.PlatformSupport.isPortable() && multitaskWindow != null && !"native".equals(backend.linuxInputMode())) {
                multitaskWindow.setFocusableWindowState(true);multitaskWindow.requestFocus(); preview.setFocusable(true); preview.requestFocusInWindow();
            }backend.send("{\"command\":\"screenshot_selection\",\"enabled\":false}");if(exitHoverTimer!=null)exitHoverTimer.start();showNotice("Screenshot","Could not capture: "+error.getMessage());}}
        }.execute());settle.setRepeats(false);settle.start();
    }
    private ScreenshotGallery gallery;
    private boolean snapshotPending;
    private boolean shuttingDown;
    private javax.swing.Timer snapshotTimeout, exitHideTimer, exitHoverTimer;
    private Rectangle exitHotspot;
    private void openGallery() { if(gallery==null||!gallery.isDisplayable()) gallery=new ScreenshotGallery(palette); gallery.setVisible(true); gallery.toFront(); }
    private void takeSnapshot() {
        if(snapshotPending)return;
        if(app.multimin.backend.PlatformSupport.isPortable()) {
            snapshotPending=true;
            new SwingWorker<Path,Void>() {
                protected Path doInBackground() throws Exception { return new ScreenshotStore().save(linuxComposition()); }
                protected void done(){snapshotPending=false;try{openGallery();gallery.reload(get());}catch(Exception error){showNotice("Screenshot", "Could not save composition: "+error.getMessage());}}
            }.execute();
            return;
        }
        if(!backend.isReady()){snapshotPending=true;captureSnapshotFallback();return;}
        snapshotPending=true;
        backend.setSnapshotHandler(result -> SwingUtilities.invokeLater(()->{
            if(shuttingDown)return; snapshotPending=false;if(snapshotTimeout!=null)snapshotTimeout.stop();
            if(result.startsWith("ERROR:")){snapshotPending=true;captureSnapshotFallback();}
            else {openGallery();gallery.reload(Path.of(result));}
        }));
        snapshotTimeout=new javax.swing.Timer(30000,e->{snapshotPending=false;showNotice("Screenshot","Capture did not respond. Please try again.");});snapshotTimeout.setRepeats(false);snapshotTimeout.start();backend.requestSnapshot();
    }
    private java.awt.image.BufferedImage linuxComposition() throws Exception {
        final java.awt.image.BufferedImage[] result={null};
        Runnable render=()->{
            var transform=preview.getGraphicsConfiguration().getDefaultTransform();
            int width=Math.max(1,(int)Math.round(preview.getWidth()*transform.getScaleX()));
            int height=Math.max(1,(int)Math.round(preview.getHeight()*transform.getScaleY()));
            var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=image.createGraphics();
            graphics.scale(transform.getScaleX(),transform.getScaleY());
            preview.printAll(graphics);graphics.dispose();result[0]=image;
        };
        if(SwingUtilities.isEventDispatchThread())render.run();else SwingUtilities.invokeAndWait(render);
        return result[0];
    }
    private void captureSnapshotFallback() {
        // A loose JAR need not have Pillow installed. Use Java's built-in screen
        // capture at native pixel dimensions for the monitor containing this app.
        GraphicsConfiguration screen=getGraphicsConfiguration();
        new SwingWorker<Path,Void>() {
            @Override protected Path doInBackground() throws Exception {
                java.awt.image.MultiResolutionImage capture=new Robot(screen.getDevice()).createMultiResolutionScreenCapture(screen.getBounds());
                Image best=capture.getResolutionVariants().stream().max(java.util.Comparator.comparingLong(i->(long)i.getWidth(null)*i.getHeight(null))).orElseThrow();
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(best.getWidth(null),best.getHeight(null),java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D pixels=image.createGraphics();pixels.drawImage(best,0,0,null);pixels.dispose();
                return new ScreenshotStore().save(image);
            }
            @Override protected void done() {if(shuttingDown)return;snapshotPending=false;try{Path path=get();openGallery();gallery.reload(path);}catch(Exception error){showNotice("Screenshot","Could not capture the screen: "+error.getMessage());}}
        }.execute();
    }
    private void overlayControlRegion(boolean visible) {
        Rectangle r=visible&&exitHotspot!=null ? PreviewPanel.toPhysicalScreenBounds(exitHotspot,multitaskControls.getGraphicsConfiguration()) : new Rectangle();
        backend.send("{\"command\":\"set_overlay_control_region\",\"x\":"+r.x+",\"y\":"+r.y+",\"width\":"+r.width+",\"height\":"+r.height+"}");
    }
    private void revealExit() { if(multitaskControls==null||linuxPermissionDialog)return;boolean wasVisible=multitaskControls.isVisible();multitaskControls.setVisible(true);if(!wasVisible)overlayControlRegion(true);if(exitHideTimer!=null)exitHideTimer.restart(); }

    private View activeView;
    private boolean multitask;
    private boolean linuxPermissionDialog;
    private long lastInputCheck;
    private Source linuxDirectSource;
    private Rectangle linuxDirectHole;
    /** Background capture rate while the real window is presented natively. */
    private static final int LINUX_BACKGROUND_CAPTURE_FPS=12;
    private void linuxBackgroundCapture(Source source,int fps) {
        if(source==null||source.captureFramePath==null)return;
        backend.send("{\"command\":\"linux_background_capture\",\"path\":\""+source.captureFramePath.replace("\\","\\\\").replace("\"","\\\"")+"\",\"fps\":"+fps+"}");
    }
    private void resetLinuxDirectPresentation() {
        if(linuxDirectSource==null)return;
        if(multitaskWindow!=null)multitaskWindow.setShape(null);
        linuxBackgroundCapture(linuxDirectSource,0);
        linuxDirectSource=null;linuxDirectHole=null;preview.setLinuxDirectSource(null);
    }
    private void updateLinuxDirectPresentation() {
        // Off by default: on KDE/XWayland the overlay hole showed black instead of the
        // real window for single-source game capture. Capture composition is used
        // instead. Opt in with -Dmultimin.linuxDirectPresentation=true for testing.
        if(!app.multimin.backend.PlatformSupport.isPortable() || multitaskWindow==null)return;
        if(!Boolean.getBoolean("multimin.linuxDirectPresentation")){resetLinuxDirectPresentation();return;}
        Source source=multitask && !linuxPermissionDialog && "native".equals(backend.linuxInputMode())
            ? PreviewPanel.linuxDirectCandidate(activeView):null;
        Rectangle hole=source==null?null:preview.sourceBoundsInPanel(activeView.sources.indexOf(source));
        if(hole!=null && (hole.isEmpty() || !new Rectangle(0,0,preview.getWidth(),preview.getHeight()).contains(hole)))source=null;
        if(source==null){resetLinuxDirectPresentation();return;}
        if(source==linuxDirectSource && hole.equals(linuxDirectHole))return;
        try {
            var shape=new java.awt.geom.Area(new Rectangle(0,0,multitaskWindow.getWidth(),multitaskWindow.getHeight()));
            shape.subtract(new java.awt.geom.Area(hole));
            multitaskWindow.setShape(shape);
            Toolkit.getDefaultToolkit().sync();
            if(linuxDirectSource!=null&&linuxDirectSource!=source)linuxBackgroundCapture(linuxDirectSource,0);
            linuxDirectSource=source;linuxDirectHole=new Rectangle(hole);preview.setLinuxDirectSource(source);
            linuxBackgroundCapture(source,LINUX_BACKGROUND_CAPTURE_FPS);
        } catch(UnsupportedOperationException | IllegalArgumentException error) { resetLinuxDirectPresentation(); }
    }
    private final WorkspaceStore workspaceStore = new WorkspaceStore();
    private javax.swing.Timer workspaceTimer;
    private boolean workspaceWritable = true, restoredTargetsRequested;
    private String pendingImagePath = "";

    public MainWindow(PythonBridge backend) {
        super(System.getProperty("jpackage.app-version")!=null ? "MultiMini" : Boolean.getBoolean("multimin.dev.gameCapture") ? "MultiMini Experimental" : "MultiMini");
        this.backend = backend;
        try { appLogo = new LogoIcon(36); setIconImages(List.of(new LogoIcon(16).toImage(), new LogoIcon(32).toImage(), new LogoIcon(64).toImage(), new LogoIcon(256).toImage())); if(Taskbar.isTaskbarSupported()&&Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))Taskbar.getTaskbar().setIconImage(new LogoIcon(256).toImage()); }
        catch (Exception ignored) { appLogo = null; }
        Path frameDirectory;
        try { frameDirectory = Files.createTempDirectory("multimin-window-frames-"); }
        catch (java.io.IOException error) { frameDirectory = Path.of(System.getProperty("java.io.tmpdir"), "multimin-window-frames"); }
        windowFramesDirectory = frameDirectory;
        int restoredActive = 0;
        try { var saved = workspaceStore.load(); views.addAll(saved.views()); restoredActive = saved.active(); }
        catch (java.io.IOException error) { workspaceWritable = false; SwingUtilities.invokeLater(() -> showNotice("Workspace recovery", error.getMessage())); }
        if (views.isEmpty()) { views.add(new View("Work")); views.add(new View("Media")); views.add(new View("Game")); }
        activeView = views.get(restoredActive);
        for (View view : views) for (int i=0;i<view.sources.size();i++) {
            Source source=view.sources.get(i);
            if(source.kind==Source.Kind.IMAGE && !source.imagePath.isBlank())try{source.liveFrame=ImageIO.read(Path.of(source.imagePath).toFile());}catch(Exception ignored){source.activeCaptureBackend="Image file unavailable";}
        }
        preview = new PreviewPanel(palette, activeView);
        preview.setNativePresentationAllowed(performanceFps==0 || performanceFps>=currentMonitorRefreshRate());
        preview.onSelectionChanged(index -> sourceList.setSelectedIndex(index));
        preview.onSourceChanged(this::syncSelection);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(520, 420)); setSize(Math.min(1180, usable.width - 60), Math.min(740, usable.height - 60)); setLocationRelativeTo(null); setExtendedState(JFrame.MAXIMIZED_BOTH);
        if("Light".equals(preferences.getString("themeMode","Dark")))palette.light();
        applyUiDefaults();LegalConsent.setPalette(palette);
        buildUi();refreshTheme();
        bindShortcuts();
        backend.setAppPathHandler(AppIcons::register);
        AppIcons.onChange(() -> { if (sourceBadge != null) sourceBadge.repaint(); });
        backend.setRestoreTokenHandler((path,token)->SwingUtilities.invokeLater(()->{
            boolean changed=false;
            for(View view:views)for(int i=0;i<view.sources.size();i++){
                Source source=view.sources.get(i);
                if(path.equals(source.captureFramePath)&&!token.equals(source.portalRestoreToken)){source.portalRestoreToken=token;changed=true;}
            }
            if(changed)saveWorkspace();
        }));
        backend.setCaptureStoppedHandler(path->SwingUtilities.invokeLater(()->{
            for(View view:views)for(int i=0;i<view.sources.size();i++){
                Source source=view.sources.get(i);
                if(path.equals(source.captureFramePath)){
                    source.captureFramePath=null;source.nativeWindowHandle=0;source.liveFrame=null;source.standbyFrame=null;
                    source.nativeDisplayFrames=false;source.activeCaptureBackend="Capture stopped · reselect source";
                    backend.removeWindowCapture(path);
                }
            }
            sourceList.repaint();preview.repaint();
        }));
        backend.setQuickSwapHandler(event -> SwingUtilities.invokeLater(() -> {
            if("mac_permissions_needed".equals(event)) showMacPermissions();
            else if("linux_target_lost".equals(event) && multitask) {setMultitask(false);backendStatus.setText("Input target capture stopped; choose or reconnect a source.");}
            else if (("linux_permission_on".equals(event)||"linux_permission_off".equals(event)) && multitask && multitaskWindow!=null) {
                linuxPermissionDialog="linux_permission_on".equals(event);
                if(linuxPermissionDialog){
                    resetLinuxDirectPresentation();
                    if(exitHoverTimer!=null)exitHoverTimer.stop();if(exitHideTimer!=null)exitHideTimer.stop();
                    multitaskControls.setVisible(false);multitaskWindow.setVisible(false);
                } else {
                    multitaskWindow.setVisible(true);multitaskControls.setVisible(true);
                    if(exitHoverTimer!=null)exitHoverTimer.start();
                    SwingUtilities.invokeLater(()->{if(multitask)setControlTarget(activeView.controlSource);});
                }
            }
            else if ("linux_shortcuts".equals(event)) {
                updateHotkeyHints();
                if(multitaskExitButton!=null)multitaskExitButton.setToolTipText("Exit: "+backend.linuxShortcut("toggle_multitask"));
                if(multitaskTargetLabel!=null)multitaskTargetLabel.setToolTipText("Switch input: "+backend.linuxShortcut("quick_swap")+" · visual layers: "+backend.linuxShortcut("layer_swap"));
            }
            else if ("linux_input_mode".equals(event) && !multitask && !shuttingDown && isShowing()) {
                toFront();requestFocus();
            }
            else if ("linux_input_mode".equals(event) && multitask && multitaskWindow!=null) {
                boolean directed=!"native".equals(backend.linuxInputMode());
                backendStatus.setText(app.multimin.backend.PlatformSupport.osLabel()+" input: "+backend.linuxInputMode());
                if("unavailable".equals(backend.linuxInputMode())){revealExit();multitaskTargetLabel.setText("Input unavailable for this target");multitaskTargetLabel.setVisible(true);}
                multitaskWindow.setFocusableWindowState(directed);
                if(directed){multitaskWindow.requestFocus();preview.requestFocusInWindow();}
                updateLinuxDirectPresentation();
            }
            else if ("screenshot_full".equals(event) && multitask) captureMultitaskScreenshot(false);
            else if ("screenshot_region".equals(event) && multitask) captureMultitaskScreenshot(true);
            else if ("quick_swap".equals(event) && multitask) cycleQuickSwapTarget();
            else if ("layer_swap".equals(event) && multitask) swapVisibleLayers();
            else if ("visual_stack_applied".equals(event) && multitask) {
                if (screenshotSelection != null) {
                    screenshotSelection.toFront();
                } else {
                    if (multitaskWindow != null) multitaskWindow.toFront();
                    if (multitaskControls != null) multitaskControls.toFront();
                    backend.send("{\"command\":\"refresh_overlay_hit_test\"}");
                }
            }
            else if ("toggle_multitask".equals(event)) setMultitask(!multitask);
        }));
        backend.setGameStatusHandler((fileName, status) -> SwingUtilities.invokeLater(() -> {
            for (View view : views) for (int index = 0; index < view.sources.size(); index++) {
                Source source = view.sources.get(index);
                if ((source.kind != Source.Kind.GAME && source.kind != Source.Kind.DISPLAY) || source.captureFramePath == null ||
                    !Path.of(source.captureFramePath).getFileName().toString().equals(fileName)) continue;
                if (source.kind == Source.Kind.DISPLAY) {
                    source.nativeDisplayFrames = false;
                    refreshDisplayCaptureActivity();
                    continue;
                }
                source.activeCaptureBackend = "DirectX 11 test capture · " + status.replace('_', ' ');
                if ("waiting_for_game".equals(status) || "unsupported".equals(status) || "stopped".equals(status)) {
                    source.liveFrame = null;
                    source.standbyFrame = null;
                }
                preview.repaint();
            }
        }));
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            if (recordingSwapKey && event.getID() == KeyEvent.KEY_PRESSED) {
                if (settingsHotkeyRecording && event.getKeyCode() == KeyEvent.VK_ESCAPE) { recordingSwapKey = false; updateRecordingButton(swapKeyButton, stagedSwapKeyBinding); return true; }
                String chosen = keyBindingForEvent(event);
                if (chosen != null) {
                    if (settingsHotkeyRecording) stagedSwapKeyBinding = chosen; else swapKeyBinding = chosen;
                    recordingSwapKey = false;
                    if (swapKeyButton != null) swapKeyButton.setText(prettyKeyBinding(chosen));
                    return true;
                }
            }
            if (recordingStopKey && event.getID() == KeyEvent.KEY_PRESSED) {
                if (settingsHotkeyRecording && event.getKeyCode() == KeyEvent.VK_ESCAPE) { recordingStopKey = false; updateRecordingButton(stopKeyButton, stagedStopKeyBinding); return true; }
                String chosen = keyBindingForEvent(event);
                if (chosen != null) {
                    if (settingsHotkeyRecording) stagedStopKeyBinding = chosen; else stopKeyBinding = chosen;
                    recordingStopKey = false;
                    if (stopKeyButton != null) stopKeyButton.setText(prettyKeyBinding(chosen));
                    return true;
                }
            }
            if (recordingLayerSwapKey && event.getID() == KeyEvent.KEY_PRESSED) {
                if (settingsHotkeyRecording && event.getKeyCode() == KeyEvent.VK_ESCAPE) { recordingLayerSwapKey = false; updateRecordingButton(layerSwapKeyButton, stagedLayerSwapKeyBinding); return true; }
                if (event.getKeyCode() == KeyEvent.VK_BACK_SPACE || event.getKeyCode() == KeyEvent.VK_DELETE) {
                    if (settingsHotkeyRecording) stagedLayerSwapKeyBinding = ""; else layerSwapKeyBinding = "";
                    recordingLayerSwapKey = false;
                    if (layerSwapKeyButton != null) layerSwapKeyButton.setText("Unassigned");
                    if (!settingsHotkeyRecording) updateHotkeyHints();
                    return true;
                }
                String chosen = keyBindingForEvent(event);
                if (chosen != null) {
                    if (settingsHotkeyRecording) stagedLayerSwapKeyBinding = chosen; else layerSwapKeyBinding = chosen;
                    recordingLayerSwapKey = false;
                    if (layerSwapKeyButton != null) layerSwapKeyButton.setText(prettyKeyBinding(chosen));
                    return true;
                }
            }
            if (multitask && app.multimin.backend.PlatformSupport.isPortable() && event.getID() == KeyEvent.KEY_PRESSED) {
                String binding = keyBindingForEvent(event);
                if (binding != null && binding.equals(screenshotKey)) { captureMultitaskScreenshot(false); return true; }
                if (binding != null && binding.equals(regionScreenshotKey)) { captureMultitaskScreenshot(true); return true; }
                if (binding != null && binding.equals(stopKeyBinding)) { setMultitask(false); return true; }
                if (binding != null && binding.equals(layerSwapKeyBinding)) { swapVisibleLayers(); return true; }
            }
            if (multitask && (!backend.isReady() || app.multimin.backend.PlatformSupport.isPortable()) && event.getID() == KeyEvent.KEY_PRESSED && keyBindingForEvent(event) != null && keyBindingForEvent(event).equals(swapKeyBinding)) {
                cycleQuickSwapTarget(); return true;
            }
            return false;
        });
        if (app.multimin.backend.PlatformSupport.isPortable())
            new app.multimin.backend.LinuxOverlayInput(backend, preview, () -> multitask);
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (int i = 0; i < screens.length; i++) {
            try {
                final int deviceIndex = i;
                DisplayCapture capture = new DisplayCapture(screens[i]);
                int refresh = screens[i].getDisplayMode().getRefreshRate();
                capture.setTargetFps(Math.min(effectiveCaptureFps(), refresh > 0 ? refresh : 60));
                capture.setEnabled(false);
                capture.setExcludedRegionSupplier(this::multitaskCaptureExclusion);
                capture.start(frame -> displayFrameArrived(deviceIndex, frame), error -> setBackendStatus("Display capture unavailable — " + error.getMessage()));
                displayCaptures.add(capture);
            } catch (Exception error) { setBackendStatus("Display capture unavailable — " + error.getMessage()); }
        }
        lastAppliedCaptureFps = effectiveCaptureFps();
        windowFrameTimer = new FramePacer(lastAppliedCaptureFps, this::refreshWindowFrames);
        performanceTimer = new javax.swing.Timer(1000, event -> refreshPerformanceStats());
        performanceStatus.setVisible(preferences.getBoolean("showPerformanceStats", false));
        performanceTimer.start();
        backend.setCaptureFps(effectiveCaptureFps(), performanceFps == 0);
        backend.setFrameReadyHandler(() -> {
            newFramePending.set(true);
            requestWindowFrames();
        });
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { shutdownServices(); } });
        workspaceTimer = new javax.swing.Timer(5000, event -> saveWorkspace()); workspaceTimer.start();
    }

    Palette uiPalette(){return palette;}
    PythonBridge backendBridge(){return backend;}
    public void checkForUpdatesOnLaunch(){GithubUpdater.checkOnLaunch(this,()->{if(!workspaceWritable)throw new IllegalStateException("Recover your workspace before updating");try{workspaceStore.save(views,activeView);}catch(java.io.IOException error){throw new IllegalStateException("Workspace could not be saved",error);}},()->{shutdownServices();dispose();System.exit(0);});}
    private void buildUi() {
        root.setBackground(palette.background); configuration.setOpaque(false); topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.line), new EmptyBorder(9, 16, 9, 16)));
        JLabel brand = new JLabel("MultiMini"); brand.setFont(Theme.title(15f));
        if (appLogo != null) { brand.setIcon(appLogo); brand.setIconTextGap(10); }
        topBar.add(brand); topBar.add(Box.createHorizontalStrut(25));
        for (View view : views) { JButton tab = quietButton(view.name); tab.addActionListener(event -> switchView(view)); installViewMenu(tab,view); viewButtons.put(view, tab); topBar.add(tab); topBar.add(Box.createHorizontalStrut(4)); }
        JButton addView = quietButton("＋"); addView.setToolTipText("Add a view"); addView.addActionListener(event -> addView()); topBar.add(addView); topBar.add(Box.createHorizontalGlue());
        sourcesPanelRef = sourcesPanel(); propertiesPanelRef = propertiesPanel();
        configuration.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcesPanelRef, centerPanel()),
            propertiesPanelRef), BorderLayout.CENTER);
        configureSplitters((JSplitPane) configuration.getComponent(0));
        root.add(topBar, BorderLayout.NORTH); root.add(configuration, BorderLayout.CENTER); setContentPane(root);
        sourceList.setSelectedIndex(0); syncSelection();
    }

    private JPanel sourcesPanel() {
        GlassPanel panel = new GlassPanel(palette); panel.setPreferredSize(new Dimension(265, 0)); panel.setMinimumSize(new Dimension(70, 0)); panel.setLayout(new BorderLayout(0, 12)); panel.setBorder(new EmptyBorder(16, 10, 10, 10));
        JPanel heading = transparent(new BorderLayout()); JPanel names = transparent(new GridLayout(0, 1, 0, 2)); names.add(sectionLabel("CURRENT VIEW")); viewTitle.setFont(Theme.title(20f)); names.add(viewTitle); heading.add(names); heading.setBorder(new EmptyBorder(0, 4, 0, 0)); JButton add = primaryButton("＋ Add"); add.setPreferredSize(new Dimension(80, 34)); add.addActionListener(event -> addSource()); JPanel addWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 4)); addWrap.add(add); heading.add(addWrap, BorderLayout.EAST); panel.add(heading, BorderLayout.NORTH);
        sourceList.getAccessibleContext().setAccessibleName("Sources in the current view");
        sourceList.setModel(activeView.sources); sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); sourceList.setFixedCellHeight(60); sourceList.setDragEnabled(true); sourceList.setDropMode(DropMode.INSERT); sourceList.setTransferHandler(new SourceReorderHandler()); sourceList.setCellRenderer(new SourceCellRenderer(palette, preferences.getBoolean("guiHelpers",true))); sourceList.addListSelectionListener(event -> syncSelection());
        sourceList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove-source");
        sourceList.getActionMap().put("remove-source", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { removeSelected(); } });
        sourceList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename-source");
        sourceList.getActionMap().put("rename-source", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { renameSelected(); } });
        sourceList.setToolTipText("Click the pointer icon to include a source in the input cycle · double-click to configure");
        sourceList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int index = sourceList.locationToIndex(event.getPoint());
                if (index >= 0 && sourceList.getCellBounds(index, index).contains(event.getPoint()) && event.getX() >= sourceList.getWidth() - 56) {
                    Source source = sourceList.getModel().getElementAt(index);
                    if (event.getX() >= sourceList.getWidth() - 30) {
                        if (source.kind == Source.Kind.DISPLAY || source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME) {
                            source.quickSwapInput = !source.quickSwapInput;
                            if (source.quickSwapInput) source.controlColor = nextControlColor(source);
                        }
                    } else source.visible = !source.visible;
                    sourceList.repaint(); preview.repaint();
                } else if (event.getClickCount() == 2) configureSelectedSource();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) { preview.repaint(); }
        });
        JScrollPane scroller = new JScrollPane(sourceList); scroller.setBorder(BorderFactory.createEmptyBorder());
        styleScrollBar(scroller.getVerticalScrollBar()); styleScrollBar(scroller.getHorizontalScrollBar());
        panel.add(scroller, BorderLayout.CENTER);
        JPanel footer = transparent(new BorderLayout(0, 10)); JPanel helperCopy=transparent(new GridLayout(0,1,0,4)); helperCopy.setBorder(new EmptyBorder(0,4,0,4)); helperCopy.putClientProperty("gui.helper",true); JPanel controls = cardPanel(new GridLayout(1, 0, 2, 0)); controls.setBorder(new EmptyBorder(3,3,3,3));
        JButton plus = toolButton("＋", "Add source"); plus.addActionListener(event -> addSource()); controls.add(plus);
        JButton minus = toolButton("−", "Remove source"); minus.addActionListener(event -> removeSelected()); controls.add(minus);
        JButton duplicate = toolButton("⧉", "Duplicate source"); duplicate.addActionListener(event -> duplicateSelected()); controls.add(duplicate);
        JButton visibility = toolButton("◉", "Show or hide selected source"); visibility.addActionListener(event -> toggleSelectedVisibility()); controls.add(visibility);
        JButton up = toolButton("↑", "Move source up"); up.addActionListener(event -> moveSelected(-1)); controls.add(up);
        JButton down = toolButton("↓", "Move source down"); down.addActionListener(event -> moveSelected(1)); controls.add(down);
        for(Component control:controls.getComponents())if(control instanceof JComponent button){button.putClientProperty("toolbar.outline",true);button.putClientProperty("appearance.ghost",true);}
        footer.add(controls,BorderLayout.NORTH); footer.add(helperCopy,BorderLayout.CENTER);
        JTextArea sourceHint=new JTextArea("Drag to reorder · Double-click to configure\nF2 to rename");sourceHint.setOpaque(false);sourceHint.setEditable(false);sourceHint.setFocusable(false);sourceHint.setLineWrap(true);sourceHint.setWrapStyleWord(true);sourceHint.setForeground(palette.muted);sourceHint.setFont(Theme.regular(11f));helperCopy.add(sourceHint);
        inputKeyHint.setForeground(palette.muted); layerKeyHint.setForeground(palette.muted); inputKeyHint.setFont(Theme.regular(11f)); layerKeyHint.setFont(Theme.regular(11f)); updateHotkeyHints();
        helperCopy.add(inputKeyHint); helperCopy.add(layerKeyHint);
        panel.add(footer, BorderLayout.SOUTH); switchView(activeView); return panel;
    }

    private JPanel centerPanel() {
        canvasPanel.setMinimumSize(new Dimension(100,0));canvasPanel.setOpaque(true);canvasPanel.setBackground(palette.canvas()); JPanel toolbar = transparent(new BorderLayout()); toolbar.setBorder(new EmptyBorder(15, 22, 8, 22)); JLabel title = new JLabel("Preview"); title.setFont(Theme.semibold(13f)); title.setForeground(palette.text); JLabel subtitle = muted("  ·  what multitask mode will show"); subtitle.setFont(Theme.regular(12f)); JPanel titles = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0)); titles.add(title); titles.add(subtitle); toolbar.add(titles); canvasPanel.add(toolbar, BorderLayout.NORTH); canvasPanel.add(preview, BorderLayout.CENTER); backendStatus.setForeground(palette.muted); backendStatus.setBorder(new EmptyBorder(6, 22, 2, 22)); performanceStatus.setForeground(palette.muted); performanceStatus.setFont(performanceStatus.getFont().deriveFont(10f)); performanceStatus.setBorder(new EmptyBorder(0, 22, 7, 22)); debugPanel.setOpaque(false); debugPanel.add(backendStatus); debugPanel.add(performanceStatus); debugPanel.setVisible(preferences.getBoolean("showDebug", preferences.getBoolean("showPerformanceStats", false))); performanceStatus.setVisible(debugPanel.isVisible()); canvasPanel.add(debugPanel, BorderLayout.SOUTH); return canvasPanel;
    }

    private JSplitPane sidebarOuter,sidebarInner;
    private boolean sidebarWidthsRestored;
    private javax.swing.Timer sidebarWidthSaveTimer;
    @Override public void setVisible(boolean visible){super.setVisible(visible);if(visible&&sidebarOuter!=null)restoreSidebarWidths();}
    private void saveSidebarWidths(){
        if(!sidebarWidthsRestored||sidebarOuter==null||!configuration.isShowing())return;
        int left=sidebarInner.getDividerLocation(),right=sidebarOuter.getWidth()-sidebarOuter.getDividerLocation()-sidebarOuter.getDividerSize();
        if(left>=70&&right>=70){if(preferences.getInt("leftPanelWidth",-1)!=left)preferences.putInt("leftPanelWidth",left);if(preferences.getInt("rightPanelWidth",-1)!=right)preferences.putInt("rightPanelWidth",right);}
    }
    private void restoreSidebarWidths(){
        if(sidebarWidthsRestored||sidebarOuter.getWidth()<250||!configuration.isShowing())return;
        // Open at a respectable inspector width; a very wide saved value is capped.
        int right=Math.clamp(Math.min(preferences.getInt("rightPanelWidth",300),380),70,Math.max(70,sidebarOuter.getWidth()-180));
        sidebarOuter.setDividerLocation(sidebarOuter.getWidth()-right-sidebarOuter.getDividerSize());sidebarOuter.doLayout();
        int left=Math.clamp(preferences.getInt("leftPanelWidth",265),70,Math.max(70,sidebarInner.getWidth()-110));sidebarInner.setDividerLocation(left);sidebarInner.doLayout();sidebarWidthsRestored=true;
    }
    private void configureSplitters(JSplitPane outer) {
        JSplitPane inner = (JSplitPane) outer.getLeftComponent();sidebarOuter=outer;sidebarInner=inner;
        sidebarWidthSaveTimer=new javax.swing.Timer(350,event->saveSidebarWidths());sidebarWidthSaveTimer.setRepeats(false);
        java.beans.PropertyChangeListener widthChanged=event->{if(sidebarWidthsRestored&&configuration.isShowing())sidebarWidthSaveTimer.restart();};inner.addPropertyChangeListener("dividerLocation",widthChanged);outer.addPropertyChangeListener("dividerLocation",widthChanged);
        configuration.addHierarchyListener(event->{if((event.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&configuration.isShowing())SwingUtilities.invokeLater(this::restoreSidebarWidths);});
        // Window resizes (maximize, restore, monitor changes) give the extra space to the
        // preview; the inspector keeps the width the user chose.
        outer.addComponentListener(new java.awt.event.ComponentAdapter(){@Override public void componentResized(java.awt.event.ComponentEvent event){
            if(!sidebarWidthsRestored||outer.getWidth()<250)return;
            int right=Math.clamp(Math.min(preferences.getInt("rightPanelWidth",300),380),70,Math.max(70,outer.getWidth()-180));
            int location=outer.getWidth()-right-outer.getDividerSize();
            if(outer.getDividerLocation()!=location)outer.setDividerLocation(location);
        }});
        for (JSplitPane split : List.of(inner, outer)) {
            split.setContinuousLayout(true); split.setOneTouchExpandable(false); split.setBorder(null);
            split.setDividerSize(4); split.setResizeWeight(split == inner ? 0.0 : 1.0);
            split.setBackground(palette.background);
            ModernUi.split(split,palette);
            split.setMinimumSize(new Dimension(0, 0));
        }
        sourcesPanelRef.setMinimumSize(new Dimension(70, 0));
        propertiesPanelRef.setMinimumSize(new Dimension(70, 0));
        inner.setDividerLocation(265); outer.setDividerLocation(Math.max(0, getWidth() - 300));
        splitpaneRemoveBottomArtifacts(outer);
    }

    private void splitpaneRemoveBottomArtifacts(JSplitPane outer) {
        JSplitPane inner = (JSplitPane) outer.getLeftComponent();
        outer.setBackground(palette.background); inner.setBackground(palette.background);
        configuration.setBackground(palette.background);
    }

    private JComponent propertiesPanel() {
        GlassPanel panel = new GlassPanel(palette);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(18, 18, 14, 18));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel selectedHeading=sectionLabel("SELECTED SOURCE"); selectedHeading.setMaximumSize(new Dimension(Integer.MAX_VALUE,24)); selectedHeading.setHorizontalAlignment(SwingConstants.LEFT); selectedHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel headerText = transparent(null); headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS)); headerText.add(selectedHeading);
        sourceBadge = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                Source source = sourceList.getSelectedValue();
                if (source == null) return;
                java.awt.image.BufferedImage icon = source.usesWindowTarget() ? AppIcons.forHandle(source.nativeWindowHandle) : null;
                AppIcons.paintBadge((Graphics2D) graphics, palette, source.kind, icon, 0, 4, 44);
            }
        };
        sourceBadge.setPreferredSize(new Dimension(44, 52)); sourceBadge.setOpaque(false);
        JPanel header = transparent(new BorderLayout(10, 0)); header.add(headerText, BorderLayout.CENTER); header.add(sourceBadge, BorderLayout.EAST);
        header.setAlignmentX(Component.CENTER_ALIGNMENT); header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        panel.add(header);
        layerTitle.setFont(Theme.title(18f));
        layerTitle.setOpaque(false); layerTitle.setForeground(palette.text);
        layerTitle.setBorder(new EmptyBorder(2,2,2,2));
        layerTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        layerTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        layerTitle.setOpaque(false);
        headerText.add(layerTitle);
        layerSubtitle.setFont(Theme.regular(12f)); layerSubtitle.setForeground(palette.muted); layerSubtitle.setBorder(new EmptyBorder(0,2,0,2));
        layerSubtitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20)); layerSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerText.add(layerSubtitle);
        panel.add(Box.createVerticalStrut(20));
        panel.add(centeredSection("APPEARANCE"));
        panel.add(Box.createVerticalStrut(10));
        JPanel opacityRow = transparent(new BorderLayout());
        opacityRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22)); opacityRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        opacityRow.add(propertyLabel("Opacity"), BorderLayout.WEST);
        opacityRow.add(percentageEditor(opacity), BorderLayout.EAST);
        panel.add(opacityRow);
        opacity.setOpaque(false);
        opacity.setUI(new AccentSliderUI(opacity, palette));
        opacity.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); opacity.setAlignmentX(Component.CENTER_ALIGNMENT);
        opacity.addChangeListener(event -> {
            Source source = sourceList.getSelectedValue();
            if (source != null) { source.opacity = opacity.getValue(); backend.setOpacity(source.opacity); preview.repaint(); }
        });
        panel.add(opacity);
        panel.add(Box.createVerticalStrut(12));
        JPanel scaleRow = transparent(new BorderLayout());
        scaleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22)); scaleRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        scaleRow.add(propertyLabel("Size"), BorderLayout.WEST);
        scaleRow.add(percentageEditor(scale), BorderLayout.EAST);
        panel.add(scaleRow);
        scale.setOpaque(false);
        scale.setUI(new AccentSliderUI(scale, palette));
        scale.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); scale.setAlignmentX(Component.CENTER_ALIGNMENT);
        scale.addChangeListener(event -> {
            Source source = sourceList.getSelectedValue();
            if (source != null) {
                int next = scale.getValue();
                if (source.scaleX >= 0 && source.scale > 0) source.scaleX = source.scaleX * next / source.scale;
                if (source.scaleY >= 0 && source.scale > 0) source.scaleY = source.scaleY * next / source.scale;
                source.scale = next; preview.repaint();
            }
        });
        panel.add(scale);
        panel.add(Box.createVerticalStrut(18));
        panel.add(centeredSection("SOURCE TOOLS"));
        panel.add(Box.createVerticalStrut(10));
        JButton configureSource = toolButton("Configure source…", "Change target or capture method");
        configureSource.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); configureSource.setAlignmentX(Component.CENTER_ALIGNMENT);
        configureSource.addActionListener(event -> configureSelectedSource());
        panel.add(configureSource);
        panel.add(Box.createVerticalStrut(8));
        JButton reset = toolButton("Reset size and position", "Reset source transform");
        reset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); reset.setAlignmentX(Component.CENTER_ALIGNMENT);
        reset.addActionListener(event -> resetTransform());
        panel.add(reset);
        panel.add(Box.createVerticalStrut(10));
        JLabel hint = muted("<html>Drag to move; drag edges to resize.<br>Hold Shift to stretch.</html>");
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38)); hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint.putClientProperty("gui.helper",true);
        panel.add(hint);
        panel.add(Box.createVerticalGlue());
        JPanel modeActions = transparent(new BorderLayout(0, 10));
        modeActions.setBorder(new EmptyBorder(12, 0, 0, 0));
        modeActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112)); modeActions.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton start = primaryButton("Start multitasking"); start.addActionListener(event -> setMultitask(true));
        start.setFont(Theme.semibold(14f)); start.setPreferredSize(new Dimension(0, 44)); modeActions.add(start, BorderLayout.NORTH);
        JPanel utilities = transparent(new GridLayout(1, 3, 6, 0));
        JButton snapshot = compactAction("Snapshot", VectorIcon.Type.CAMERA); snapshot.addActionListener(event -> takeSnapshot()); utilities.add(snapshot);
        JButton galleryButton = compactAction("Gallery", VectorIcon.Type.IMAGE); galleryButton.addActionListener(event -> openGallery()); utilities.add(galleryButton);
        JButton settings = compactAction("Settings", VectorIcon.Type.GEAR); settings.addActionListener(event -> showMultitaskSettings()); utilities.add(settings);
        modeActions.add(utilities, BorderLayout.CENTER);
        panel.add(modeActions);
        panel.setPreferredSize(new Dimension(300, 0)); panel.setMinimumSize(new Dimension(70, 0));
        return panel;
    }

    private void switchView(View view) { if (multitask) return; activeView = view; for (Map.Entry<View, AbstractButton> entry : viewButtons.entrySet()) entry.getValue().setSelected(entry.getKey() == view); viewTitle.setText(view.name); sourceList.setModel(view.sources); sourceList.setSelectedIndex(Math.min(1, view.sources.size() - 1)); preview.setView(view); }
    private void syncSelection() { Source source = sourceList.getSelectedValue(); layerTitle.setText(source == null ? "No source selected" : source.name); layerTitle.setFont(Theme.title(18f)); layerSubtitle.setText(source == null ? "Select a source to edit it" : source.kind.label + (source.visible ? "" : " · hidden") + (source.quickSwapInput ? " · in input cycle" : ""));
        if (sourceBadge != null) sourceBadge.repaint();
        if (source != null && source.usesWindowTarget() && source.nativeWindowHandle != 0 && AppIcons.forHandle(source.nativeWindowHandle) == null && backend.isReady() && !app.multimin.backend.PlatformSupport.isPortable())
            backend.requestWindowThumbnails(List.of(source.nativeWindowHandle), 64, (handle, image) -> {}); layerTitle.setForeground(palette.text); if (source != null) { opacity.setValue(source.opacity); scale.setValue(source.scale); preview.setSelectedIndex(sourceList.getSelectedIndex()); } }
    private void setControlTarget(int index) {
        resetLinuxDirectPresentation();
        if (index < 0 || index >= activeView.sources.size()) return;
        activeView.controlSource = index;
        Source source = activeView.sources.get(index);
        String target = source.kind == Source.Kind.DISPLAY || source.inputTarget.isBlank() ? "desktop" : source.inputTarget;
        boolean desktopInput = source.kind == Source.Kind.DISPLAY;
        Rectangle mapped = new Rectangle(), excluded = new Rectangle();
        if (multitask && (source.usesWindowTarget() || app.multimin.backend.PlatformSupport.isPortable())) {
            try { mapped = preview.sourceBoundsOnScreen(index); } catch (IllegalComponentStateException ignored) { }
        }
        if (multitaskExitButton != null && multitaskExitButton.isShowing()) {
            try {
                Point buttonLocation = multitaskExitButton.getLocationOnScreen();
                excluded = PreviewPanel.toPhysicalScreenBounds(
                    new Rectangle(buttonLocation.x, buttonLocation.y, multitaskExitButton.getWidth(), multitaskExitButton.getHeight()),
                    multitaskExitButton.getGraphicsConfiguration());
            }
            catch (IllegalComponentStateException ignored) { }
        }
        if(app.multimin.backend.PlatformSupport.isPortable()) {
            var transform=preview.getGraphicsConfiguration().getDefaultTransform();
            backend.send("{\"command\":\"linux_coordinate_scale\",\"x\":"+transform.getScaleX()+",\"y\":"+transform.getScaleY()+"}");
        }
        backend.setInputTarget(target, source.nativeWindowHandle, mapped.x, mapped.y, mapped.width, mapped.height,
            excluded.x, excluded.y, excluded.width, excluded.height, desktopInput,source.captureFramePath);
        multitaskInputLabel.setText("Input target: " + source.name); updateControlIndicator(); preview.repaint();
    }
    private void refreshNativeLayerLayout() {
        if (!multitask || multitaskWindow == null) return;
        // The preview owns the visual composition. Cutting out window sources
        // exposed their real HWNDs, so focusing an input target also changed
        // what was visible regardless of the source order.
        resetLinuxDirectPresentation();
        multitaskWindow.setShape(null);
        StringBuilder items = new StringBuilder();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < activeView.sources.size(); i++) {
            Source source = activeView.sources.get(i);
            if (!source.visible || !source.usesWindowTarget() || source.nativeWindowHandle == 0
                || !seen.add(source.nativeWindowHandle)) continue;
            Rectangle bounds;
            try { bounds = preview.sourceBoundsOnScreen(i); }
            catch (IllegalComponentStateException error) { continue; }
            if (bounds.width <= 0 || bounds.height <= 0) continue;
            if (items.length() > 0) items.append(',');
            items.append("{\"hwnd\":").append(source.nativeWindowHandle)
                .append(",\"x\":").append(bounds.x).append(",\"y\":").append(bounds.y)
                .append(",\"width\":").append(bounds.width).append(",\"height\":").append(bounds.height)
                .append(",\"opacity\":").append(source.opacity).append('}');
        }
        backend.send("{\"command\":\"set_layer_layout\",\"items\":[" + items + "]}");
    }
    private Color nextControlColor(Source source) {
        Color[] colors = {new Color(166, 111, 224), new Color(77, 190, 135), new Color(239, 153, 71), new Color(76, 190, 210), new Color(232, 102, 144), new Color(230, 199, 76), new Color(105, 145, 245)};
        int index = 0;
        for (Color color : colors) { boolean used = false; for (int i = 0; i < activeView.sources.size(); i++) { Source other = activeView.sources.get(i); if (other != source && other.quickSwapInput && color.equals(other.controlColor)) used = true; } if (!used) return color; index++; }
        return colors[Math.floorMod(index + activeView.sources.size(), colors.length)];
    }
    private void updateControlIndicator() {
        if (multitaskExitButton == null || activeView.sources.isEmpty()) return;
        int i = Math.max(0, Math.min(activeView.controlSource, activeView.sources.size() - 1));
        Color color = activeView.sources.get(i).controlColor;
        multitaskExitButton.setBackground(color); revealExit();
        multitaskExitButton.repaint();
        if (multitaskTargetLabel != null && multitaskControls != null && multitaskControls.isVisible()) {
            Source source = activeView.sources.get(i);
            String shown = source.name.length() > 28 ? source.name.substring(0, 27) + "…" : source.name;
            multitaskTargetLabel.setText(shown);
            multitaskTargetLabel.setToolTipText(source.name);
            multitaskTargetLabel.setForeground(color);
            multitaskTargetLabel.setVisible(true);
            if (inputNoticeTimer != null) inputNoticeTimer.restart();
        }
    }
    private Rectangle multitaskCaptureExclusion() {
        if (multitaskControls != null && multitaskControls.isVisible()) return multitaskControls.getBounds();
        return multitask ? null : getBounds();
    }
    private String keyBindingForEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.VK_CONTROL && event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT) return "RIGHT_CTRL";
        boolean mac = app.multimin.backend.PlatformSupport.isMac();
        // Mac keyboards: Right Option is the natural single-key swap.
        if (mac && code == KeyEvent.VK_ALT && event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT) return "RIGHT_ALT";
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_ALT || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_META) return null;
        // macOS getKeyText returns symbols (e.g. arrows, ⎋); use stable key names there.
        String key = mac ? macKeyName(code) : KeyEvent.getKeyText(code).toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (key == null) return null;
        StringBuilder binding = new StringBuilder();
        if ((event.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) binding.append("CTRL+");
        if ((event.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0) binding.append("SHIFT+");
        if ((event.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0) binding.append("ALT+");
        if (mac && (event.getModifiersEx() & KeyEvent.META_DOWN_MASK) != 0) binding.append("CMD+");
        return binding.append(key).toString();
    }
    /** Stable names for macOS key codes, matching mac_hotkeys.KEYCODES. */
    static String macKeyName(int code) {
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return String.valueOf((char) code);
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return String.valueOf((char) code);
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) return "F" + (code - KeyEvent.VK_F1 + 1);
        if (code >= KeyEvent.VK_F13 && code <= KeyEvent.VK_F20) return "F" + (code - KeyEvent.VK_F13 + 13);
        return switch (code) {
            case KeyEvent.VK_END -> "END"; case KeyEvent.VK_HOME -> "HOME";
            case KeyEvent.VK_PAGE_UP -> "PAGE_UP"; case KeyEvent.VK_PAGE_DOWN -> "PAGE_DOWN";
            case KeyEvent.VK_LEFT -> "LEFT"; case KeyEvent.VK_RIGHT -> "RIGHT"; case KeyEvent.VK_UP -> "UP"; case KeyEvent.VK_DOWN -> "DOWN";
            case KeyEvent.VK_ESCAPE -> "ESCAPE"; case KeyEvent.VK_ENTER -> "ENTER"; case KeyEvent.VK_TAB -> "TAB";
            case KeyEvent.VK_SPACE -> "SPACE"; case KeyEvent.VK_BACK_SPACE -> "BACK_SPACE"; case KeyEvent.VK_DELETE -> "DELETE";
            case KeyEvent.VK_HELP, KeyEvent.VK_INSERT -> "INSERT"; case KeyEvent.VK_PAUSE -> "PAUSE";
            case KeyEvent.VK_MINUS -> "MINUS"; case KeyEvent.VK_EQUALS -> "EQUALS"; case KeyEvent.VK_COMMA -> "COMMA";
            case KeyEvent.VK_PERIOD -> "PERIOD"; case KeyEvent.VK_SLASH -> "SLASH"; case KeyEvent.VK_SEMICOLON -> "SEMICOLON";
            case KeyEvent.VK_QUOTE -> "QUOTE"; case KeyEvent.VK_OPEN_BRACKET -> "OPEN_BRACKET"; case KeyEvent.VK_CLOSE_BRACKET -> "CLOSE_BRACKET";
            case KeyEvent.VK_BACK_SLASH -> "BACK_SLASH"; case KeyEvent.VK_BACK_QUOTE -> "BACK_QUOTE";
            default -> null;
        };
    }
    private String prettyKeyBinding(String binding) {
        if ("RIGHT_CTRL".equals(binding)) return "Right Ctrl";
        if (app.multimin.backend.PlatformSupport.isMac()) {
            if ("RIGHT_ALT".equals(binding)) return "Right Option";
            return binding.replace("CTRL+", "Control + ").replace("ALT+", "Option + ").replace("CMD+", "Command + ")
                .replace("SHIFT+", "Shift + ").replace("_", " ");
        }
        return binding.replace("_", " ").replace("+", " + ");
    }
    private void displayFrameArrived(int deviceIndex, java.awt.image.BufferedImage frame) {
        pendingDisplayFrames.put(deviceIndex, frame);
        queueDisplayFrameUpdate();
    }

    private void queueDisplayFrameUpdate() {
        if (!displayFrameUpdateQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                for (Map.Entry<Integer, BufferedImage> entry : pendingDisplayFrames.entrySet()) {
                    int index = entry.getKey(); BufferedImage frame = entry.getValue();
                    if (!pendingDisplayFrames.remove(index, frame)) continue;
                    for (View view : views) for (int i = 0; i < view.sources.size(); i++) {
                        Source source = view.sources.get(i);
                        if (source.kind == Source.Kind.DISPLAY && source.displayDeviceIndex == index && !source.nativeDisplayFrames) source.liveFrame = frame;
                    }
                }
                preview.repaint();
            } finally {
                displayFrameUpdateQueued.set(false);
                if (!pendingDisplayFrames.isEmpty()) queueDisplayFrameUpdate();
            }
        });
    }

    private void cycleQuickSwapTarget() {
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < activeView.sources.size(); i++) {
            Source source = activeView.sources.get(i);
            if (source.visible && source.quickSwapInput && (source.kind == Source.Kind.DISPLAY || source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME)
                && (!app.multimin.backend.PlatformSupport.isPortable() || (source.liveFrame!=null && (!source.usesWindowTarget() || source.nativeWindowHandle!=0)))) targets.add(i);
        }
        if (targets.isEmpty()) { backendStatus.setText("Enable a live source’s input-cycle mouse icon to switch input"); multitaskInputLabel.setText("Input target: no quick-swap sources enabled"); return; }
        int current = targets.indexOf(activeView.controlSource);
        int next = targets.get((current + 1) % targets.size());
        setControlTarget(next);
        backendStatus.setText("Input target: " + activeView.sources.get(next).name + " · Right Ctrl to switch");
    }
    private void swapVisibleLayers() {
        List<Integer> visibleIndices = new ArrayList<>();
        for (int i = 0; i < activeView.sources.size(); i++) {
            if (activeView.sources.get(i).visible) visibleIndices.add(i);
        }
        if (visibleIndices.size() < 2) return;
        Source controlled = activeView.sources.get(activeView.controlSource);
        List<Source> visibleSources = new ArrayList<>();
        for (int index : visibleIndices) visibleSources.add(activeView.sources.get(index));
        for (int i = 0; i < visibleIndices.size(); i++) {
            activeView.sources.set(visibleIndices.get(i), visibleSources.get((i + visibleSources.size() - 1) % visibleSources.size()));
        }
        activeView.controlSource = activeView.sources.indexOf(controlled);
        sourceList.repaint(); preview.repaint();
        refreshNativeLayerLayout();
        String topName = activeView.sources.get(visibleIndices.get(0)).name;
        backendStatus.setText("Visual top: " + topName + " · input stays on " + controlled.name);
        if (multitaskTargetLabel != null) {
            revealExit();multitaskTargetLabel.setText("Top: " + topName);
            multitaskTargetLabel.setVisible(true);
            if (inputNoticeTimer != null) inputNoticeTimer.restart();
        }
    }
    private void installViewMenu(JButton tab,View view){
        JPopupMenu menu=new JPopupMenu();JMenuItem rename=new JMenuItem("Rename view"),duplicate=new JMenuItem("Duplicate view"),delete=new JMenuItem("Delete view");menu.add(rename);menu.add(duplicate);menu.addSeparator();menu.add(delete);ModernUi.popup(menu,palette);tab.setComponentPopupMenu(menu);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){delete.setEnabled(views.size()>1&&!multitask);rename.setEnabled(!multitask);duplicate.setEnabled(!multitask);}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}});
        rename.addActionListener(e->{String name=promptText("Rename view","View name:",view.name);if(name!=null&&!name.isBlank()){view.name=name.trim();tab.setText(view.name);if(activeView==view)viewTitle.setText(view.name);saveWorkspace();}});
        duplicate.addActionListener(e->{if(multitask)return;switchView(view);View copy=new View(view.name+" copy");List<Source> originals=java.util.Collections.list(view.sources.elements());for(Source original:originals){sourceList.setSelectedValue(original,true);duplicateSelected();Source clone=sourceList.getSelectedValue();view.sources.removeElement(clone);clone.x=original.x;clone.y=original.y;clone.displayDeviceIndex=original.displayDeviceIndex;clone.controlColor=original.controlColor;copy.sources.addElement(clone);}copy.controlSource=Math.max(0,Math.min(view.controlSource,copy.sources.size()-1));views.add(copy);JButton button=quietButton(copy.name);button.addActionListener(a->switchView(copy));installViewMenu(button,copy);topBar.add(button,2+viewButtons.size());viewButtons.put(copy,button);topBar.revalidate();switchView(copy);saveWorkspace();});
        delete.addActionListener(e->{if(multitask||views.size()<2)return;switchView(view);while(!view.sources.isEmpty()){sourceList.setSelectedIndex(0);removeSelected();}views.remove(view);topBar.remove(tab);viewButtons.remove(view);switchView(views.get(0));topBar.revalidate();topBar.repaint();saveWorkspace();});
    }
    private void addView() { String name = promptText("New view", "View name:", ""); if (name != null && !name.trim().isEmpty()) { View view = new View(name.trim()); views.add(view); JButton tab = quietButton(view.name); tab.addActionListener(event -> switchView(view)); installViewMenu(tab,view); topBar.add(tab, 2 + viewButtons.size()); viewButtons.put(view, tab); topBar.revalidate(); switchView(view); } }
    private void addSource() {
        Source.Kind kind = chooseSourceKind();
        if (kind == null) return;
        boolean nativeTest = false;
        if (kind == Source.Kind.GAME) {
            if (!app.multimin.backend.PlatformSupport.isPortable()) {
                Object mode = JOptionPane.showInputDialog(this,
                    "Choose a backend. WGC captures game windows without graphics hooks.\nProtected or exclusive fullscreen games may be unsupported.\nThe controlled DX11 renderer is for testing purposes only.",
                    "Experimental game capture", JOptionPane.QUESTION_MESSAGE, null,
                    new String[]{"Game window — WGC compatibility", "Controlled DX11 renderer — testing only"},
                    "Game window — WGC compatibility");
                if (mode == null) return;
                nativeTest = mode.toString().startsWith("Controlled");
            }
        }
        boolean windowBased = kind == Source.Kind.WINDOW || (kind == Source.Kind.GAME && !nativeTest);
        String target = "";
        PythonBridge.WindowTarget windowTarget = null;
        if (windowBased) {
            windowTarget = chooseWindowTarget();
            if (windowTarget == null) return;
            target = windowTarget.title;
        }
        if (nativeTest && !backend.gameAvailable()) { showGameCaptureInfo(); return; }
        int selectedDisplay = 0;
        if (kind == Source.Kind.DISPLAY) {
            selectedDisplay = chooseDisplayDevice();
            if (selectedDisplay < 0) return;
        }
        if (kind == Source.Kind.IMAGE && !loadImageSource()) return;
        String defaultName = kind == Source.Kind.DISPLAY ? "Display " + (selectedDisplay + 1) + " - DC"
            : kind == Source.Kind.IMAGE ? (pendingImageName == null ? "Image" : pendingImageName) + " - IMG"
            : kind == Source.Kind.GAME ? "Game - GC" : kind.label;
        String name = windowBased ? pendingWindowSourceName : promptText("Set up " + kind.label.toLowerCase(), "Source name:", defaultName);
        if (kind == Source.Kind.GAME && windowBased && name != null && name.endsWith(" - WC")) name = name.substring(0, name.length() - 5) + " - GC";
        boolean manuallyNamed = windowBased && pendingWindowNameManual;
        pendingWindowSourceName = null;
        pendingWindowNameManual = false;
        if (name == null || name.trim().isEmpty()) return;
        name = uniqueSourceName(name.trim());
        Source source = new Source(name, kind); source.customName = manuallyNamed || kind != Source.Kind.WINDOW; source.inputTarget = target.trim();
        source.nativeGameTest = nativeTest;
        if (nativeTest) {
            source.scale = 100; source.x = 0; source.y = 0;
            source.captureFramePath = windowFramesDirectory.resolve(java.util.UUID.randomUUID() + ".game").toString();
            source.activeCaptureBackend = "DirectX 11 test capture · connecting";
            backend.startGameCapture(source.captureFramePath);
        }
        if (windowBased) {
            GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            int screenWidth = Math.max(1, screen.getDisplayMode().getWidth());
            int screenHeight = Math.max(1, screen.getDisplayMode().getHeight());
            // PreviewPanel already maps captured pixels to display pixels. Scaling
            // here as well made each new window much smaller than its real size.
            source.scale = 100;
            source.x = Math.max(0, Math.min(100, (int) Math.round(100.0 * windowTarget.left
                / Math.max(1, screenWidth - windowTarget.width))));
            source.y = Math.max(0, Math.min(100, (int) Math.round(100.0 * windowTarget.top
                / Math.max(1, screenHeight - windowTarget.height))));
        }
        source.displayDeviceIndex = selectedDisplay;
        if (source.quickSwapInput) source.controlColor = nextControlColor(source);
        if (windowBased) {
            if (kind == Source.Kind.GAME) { source.captureMethod = app.multimin.backend.PlatformSupport.isPortable() ? "AUTO" : "WGC"; source.quickSwapInput = true; source.controlColor = nextControlColor(source); }
            source.nativeWindowHandle = windowTarget.hwnd;
            source.windowProcess = windowTarget.process;
            source.captureFramePath = windowFramesDirectory.resolve(java.util.UUID.randomUUID() + ".bmp").toString();
            backend.registerWindowCapture(source.name, target.trim(), source.captureFramePath, source.nativeWindowHandle, source.captureMethod, source.captureCursor);
            source.inputTarget = target.trim();
        }
        if (kind == Source.Kind.IMAGE) { source.liveFrame = pendingImage; source.imagePath = pendingImagePath; pendingImageName = null; pendingImage = null; }
        activeView.sources.add(0, source); sourceList.setSelectedIndex(0); preview.repaint();
    }

    private BufferedImage pendingImage;

    private String uniqueSourceName(String requested) {
        return uniqueSourceName(requested, null);
    }

    private String uniqueSourceName(String requested, Source ignored) {
        String base = requested.trim();
        if (!sourceNameExists(base, ignored)) return base;
        for (int number = 2; ; number++) {
            String candidate = base + " (" + number + ")";
            if (!sourceNameExists(candidate, ignored)) return candidate;
        }
    }

    private String automaticWindowSourceName(PythonBridge.WindowTarget target) {
        String executable = target.process == null ? "" : target.process.trim();
        if(executable.startsWith("kwin:"))executable=executable.substring(5);
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        if (slash >= 0) executable = executable.substring(slash + 1);
        executable = executable.replaceAll("^\\[[^]]+\\]:?\\s*", "");
        if (executable.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) executable = executable.substring(0, executable.length() - 4);
        String key = executable.toLowerCase(java.util.Locale.ROOT);
        String title = target.title == null ? "" : target.title.trim().replaceFirst("^\\[[^]]+\\]:?\\s*", "");
        title = title.replaceFirst("\\s+[—–-]\\s+window\\s+\\d+$", "").trim();
        String titleKey = title.toLowerCase(java.util.Locale.ROOT);
        String display;
        switch (key) {
            case "explorer": display = "File Explorer"; break;
            case "chrome": display = "Chrome"; break;
            case "brave": display = "Brave"; break;
            case "msedge": display = "Microsoft Edge"; break;
            case "firefox": display = "Firefox"; break;
            case "spotify": display = "Spotify"; break;
            case "discord": display = "Discord"; break;
            case "chatgpt": display = "ChatGPT"; break;
            case "java": case "javaw": display = titleKey.startsWith("minecraft") ? "Minecraft" : (title.isEmpty() ? "Java application" : title); break;
            default:
                if (titleKey.contains("chatgpt")) display = "ChatGPT";
                else if (titleKey.contains("spotify")) display = "Spotify";
                else if (titleKey.contains("discord")) display = "Discord";
                else if (titleKey.contains("notepad")) display = "Notepad";
                else if (titleKey.contains("file explorer") || titleKey.equals("home") || titleKey.startsWith("home -")) display = "File Explorer";
                else if (titleKey.contains("brave")) display = "Brave";
                else if (titleKey.contains("chrome")) display = "Chrome";
                else if (titleKey.contains("firefox")) display = "Firefox";
                else if (titleKey.contains("edge")) display = "Microsoft Edge";
                else if (executable.isBlank() || key.equals("application") || key.equals("window")) display = title.isBlank() ? "Window" : title;
                else display = executable.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + executable.substring(1);
                break;
        }
        if (display.length() > 36) display = display.substring(0, 35).trim() + "…";
        return display + " - WC";
    }

    private boolean sourceNameExists(String candidate) {
        return sourceNameExists(candidate, null);
    }

    private boolean sourceNameExists(String candidate, Source ignored) {
        for (View view : views) for (int i = 0; i < view.sources.size(); i++)
            if (view.sources.get(i) != ignored && view.sources.get(i).name.equalsIgnoreCase(candidate)) return true;
        return false;
    }

    private int currentMonitorRefreshRate() {
        GraphicsConfiguration configuration = preview == null ? getGraphicsConfiguration() : preview.getGraphicsConfiguration();
        int refresh = (configuration == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
            : configuration.getDevice()).getDisplayMode().getRefreshRate();
        return refresh > 0 ? refresh : 60;
    }
    private int effectiveCaptureFps() {
        int refresh=currentMonitorRefreshRate();
        return Math.max(1, Math.min(1000, performanceFps == 0 ? refresh : Math.min(performanceFps, refresh)));
    }

    private void applyPerformanceFps(int fps) {
        performanceFps = Math.max(0, Math.min(1000, fps));
        preview.setNativePresentationAllowed(performanceFps==0 || performanceFps>=currentMonitorRefreshRate());
        preferences.putInt("captureFps", performanceFps);
        preferences.putBoolean("captureFpsChoiceV2", true);
        lastAppliedCaptureFps = effectiveCaptureFps();
        windowFrameTimer.setTargetFps(backend.hasFrameNotifications() ? Math.min(30, lastAppliedCaptureFps) : lastAppliedCaptureFps);
        if (app.multimin.backend.PlatformSupport.isPortable())
            new app.multimin.backend.LinuxOverlayInput(backend, preview, () -> multitask);
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (int i = 0; i < displayCaptures.size(); i++) {
            int refresh = i < screens.length ? screens[i].getDisplayMode().getRefreshRate() : 0;
            displayCaptures.get(i).setTargetFps(Math.min(effectiveCaptureFps(), refresh > 0 ? refresh : 60));
        }
        backend.setCaptureFps(effectiveCaptureFps(), performanceFps == 0);
    }

    private void refreshPerformanceStats() {
        if (effectiveCaptureFps() != lastAppliedCaptureFps) applyPerformanceFps(performanceFps);
        windowFrameTimer.setTargetFps(backend.hasFrameNotifications() ? Math.min(30, lastAppliedCaptureFps) : lastAppliedCaptureFps);
        lastFrameCounts.keySet().removeIf(source -> !activeView.sources.contains(source));
        long now = System.nanoTime();
        double elapsedSeconds = Math.max(0.001, (now - lastPerformanceSampleNanos) / 1_000_000_000.0);
        lastPerformanceSampleNanos = now;
        if (!performanceStatus.isVisible()) return;
        double captureFps = 0, captureFrameMs = 0;
        for (DisplayCapture capture : displayCaptures) {
            captureFps += capture.getMeasuredFps();
            captureFrameMs = Math.max(captureFrameMs, capture.getLastFrameMillis());
        }
        if (!displayCaptures.isEmpty()) {
            captureFps /= displayCaptures.size();
        }
        performanceStatus.setText(String.format(java.util.Locale.ROOT,
            "Target %d FPS · Preview paint %.1f FPS · Display capture %.1f FPS · Paint %.1f ms · Capture %.1f ms · accelerated surfaces %d",
            lastAppliedCaptureFps, preview.getMeasuredFps(), captureFps, preview.getLastPaintMillis(), captureFrameMs,
            preview.getAcceleratedSourceCount()));
        Source selected = sourceList.getSelectedValue();
        if(selected!=null && selected.nativeCompositionActive) {
            performanceStatus.setText("Target "+lastAppliedCaptureFps+" Hz · "+selected.activeCaptureBackend);
            return;
        }
        if (selected != null && selected.captureFramePath != null) {
            long before = lastFrameCounts.getOrDefault(selected, selected.capturedFrames);
            long unique = Math.max(0, selected.capturedFrames - before);
            lastFrameCounts.put(selected, selected.capturedFrames);
            performanceStatus.setText(performanceStatus.getText() + String.format(java.util.Locale.ROOT,
                " · %s %.1f delivered FPS · frame age %d ms · skipped %d · %d×%d",
                selected.activeCaptureBackend, unique / elapsedSeconds, selected.captureLatencyMillis,
                selected.skippedFrames, selected.liveFrame == null ? 0 : selected.liveFrame.getWidth(),
                selected.liveFrame == null ? 0 : selected.liveFrame.getHeight()));
            performanceStatus.setText(performanceStatus.getText() + String.format(java.util.Locale.ROOT,
                " · publish→paint %.1f ms", selected.publicationToPaintMillis));
        }
    }

    private PythonBridge.WindowTarget chooseWindowTarget() {
        int linuxBackend=0;
        if(app.multimin.backend.PlatformSupport.isWayland()) {
            boolean kde=app.multimin.backend.PlatformSupport.isKde();
            String[] choices=kde
                ? new String[]{"KDE window (recommended)", "X11 / XWayland (compatibility)"}
                : new String[]{"Desktop sharing", "X11 / XWayland (compatibility)"};
            int choice=JOptionPane.showOptionDialog(this,
                kde ? "KDE includes native Wayland and XWayland windows. Use X11 compatibility if needed."
                    : "Desktop sharing captures native windows. X11 compatibility lists X11 / XWayland windows only.",
                "Window capture",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);
            if(choice<0)return null;
            linuxBackend=choice==1?0:kde?1:2;
        }
        if(linuxBackend==2) {
            String name=promptText("Share a window","Source name (the desktop picker opens next):","Window - WC");
            if(name==null || name.isBlank())return null;
            pendingWindowSourceName=name;pendingWindowNameManual=true;
            return new PythonBridge.WindowTarget("portal","Desktop-selected window",0,0,0,800,600);
        }
        final boolean nativeWayland=linuxBackend==1;
        JDialog dialog = dialog("Select a window");
        JPanel body = dialogBody();
        JPanel heading = transparent(new GridLayout(0, 1, 0, 7));
        heading.add(sectionTitle("DETECTED WINDOWS"));
        heading.add(propertyLabel("Source name"));
        JTextField sourceName = new JTextField(); sourceName.getAccessibleContext().setAccessibleName("Source name"); sourceName.setForeground(palette.text); sourceName.setBackground(palette.controlSurface()); sourceName.setOpaque(true); sourceName.setCaretColor(palette.text); sourceName.setFont(Theme.regular(13.5f)); sourceName.setBorder(BorderFactory.createCompoundBorder(new ControlBorder(palette), new EmptyBorder(7, 9, 7, 9))); sourceName.setPreferredSize(new Dimension(0, 34)); heading.add(sourceName); body.add(heading, BorderLayout.NORTH);
        boolean[] manualName = {false}, settingSuggestedName = {false};
        sourceName.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { if (!settingSuggestedName[0]) manualName[0] = true; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        });
        JComboBox<PythonBridge.WindowTarget> windows = new JComboBox<>(); windows.getAccessibleContext().setAccessibleName("Capture window");
        styleCombo(windows); windows.setPreferredSize(new Dimension(0, 34)); windows.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        windows.addActionListener(event -> {
            PythonBridge.WindowTarget selected = (PythonBridge.WindowTarget) windows.getSelectedItem();
            if (selected != null && !manualName[0]) {
                settingSuggestedName[0] = true; sourceName.setText(automaticWindowSourceName(selected)); settingSuggestedName[0] = false;
            }
        });
        JLabel status = new JLabel("Scanning…"); status.setForeground(palette.muted); status.setFont(Theme.regular(12f));
        JButton refresh = toolButton("Refresh", "Refresh the list of open windows");
        WindowPicker picker = new WindowPicker(palette);
        JButton[] confirmButton = {null};
        picker.onSelect(target -> windows.setSelectedItem(target));
        picker.onConfirm(() -> { if (confirmButton[0] != null) confirmButton[0].doClick(); });
        Runnable scan = () -> {
            status.setText("Scanning open windows…"); refresh.setEnabled(false);
            backend.requestWindowList(found -> SwingUtilities.invokeLater(() -> {
                DefaultComboBoxModel<PythonBridge.WindowTarget> model = new DefaultComboBoxModel<>();
                for (PythonBridge.WindowTarget item : found) model.addElement(item);
                windows.setModel(model);
                picker.setTargets(found); picker.setSelected((PythonBridge.WindowTarget) windows.getSelectedItem());
                backend.requestWindowThumbnails(found.stream().map(target -> target.hwnd).toList(), 360, picker::setThumbnail);
                if(!manualName[0] && windows.getSelectedItem() instanceof PythonBridge.WindowTarget selected) {
                    settingSuggestedName[0]=true;sourceName.setText(automaticWindowSourceName(selected));settingSuggestedName[0]=false;
                }
                refresh.setEnabled(true);
                status.setText(found.isEmpty() ? "No windows found. Check that the capture service is running." : found.size() + (nativeWayland?" windows · choose the input window; KDE then asks permission to capture it":" windows found · choose one to capture"));
            }),nativeWayland?"wayland":"x11");
        };
        refresh.addActionListener(event -> scan.run());
        JPanel row = transparent(new BorderLayout(8, 0)); row.add(status, BorderLayout.CENTER); row.add(refresh, BorderLayout.EAST);
        JPanel center = transparent(new BorderLayout(0, 10)); center.add(row, BorderLayout.NORTH); center.add(picker, BorderLayout.CENTER); body.add(center, BorderLayout.CENTER);
        PythonBridge.WindowTarget[] result = {null};
        JButton cancel = toolButton("Cancel", "Close without adding a source"); cancel.addActionListener(event -> dialog.dispose());
        JButton select = primaryButton("Capture selected window"); confirmButton[0] = select; select.addActionListener(event -> { result[0] = (PythonBridge.WindowTarget) windows.getSelectedItem(); if (result[0] != null && !sourceName.getText().trim().isEmpty()) { pendingWindowSourceName = sourceName.getText().trim(); pendingWindowNameManual = manualName[0]; dialog.dispose(); } });
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.add(cancel); actions.add(select); body.add(actions, BorderLayout.SOUTH);
        JPanel nameRow = transparent(new BorderLayout(10, 0)); JLabel nameLabel = propertyLabel("Source name"); nameRow.add(nameLabel, BorderLayout.WEST); nameRow.add(sourceName, BorderLayout.CENTER);
        heading.removeAll(); JLabel pickTitle = new JLabel("Choose a window"); pickTitle.setFont(Theme.title(17f)); pickTitle.setForeground(palette.text); heading.add(pickTitle); heading.add(nameRow);
        dialog.setContentPane(body); dialog.setSize(900, 680); dialog.setMinimumSize(new Dimension(620, 480)); dialog.setLocationRelativeTo(this); scan.run(); dialog.setVisible(true);
        return result[0];
    }

    /** Display chooser: the window-picker cards with a live snapshot of each monitor. */
    private int chooseDisplayDevice() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (devices.length == 0) return -1;
        GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        List<PythonBridge.WindowTarget> displays = new ArrayList<>();
        for (int i = 0; i < devices.length; i++) {
            DisplayMode mode = devices[i].getDisplayMode();
            Rectangle bounds = devices[i].getDefaultConfiguration().getBounds();
            PythonBridge.WindowTarget item = new PythonBridge.WindowTarget(mode.getWidth() + " × " + mode.getHeight() + (devices[i].equals(primary) ? " · primary" : ""),
                "Display " + (i + 1), i + 1, bounds.x, bounds.y, bounds.width, bounds.height);
            displays.add(item);
        }
        // Snapshots are taken before the dialog opens so it never appears in them.
        java.util.Map<Long, java.awt.image.BufferedImage> snapshots = new java.util.HashMap<>();
        if (!app.multimin.backend.PlatformSupport.isWayland()) {
            for (int i = 0; i < devices.length; i++) {
                try {
                    Rectangle bounds = devices[i].getDefaultConfiguration().getBounds();
                    java.awt.image.BufferedImage shot = new Robot(devices[i]).createScreenCapture(bounds);
                    int w = 420, h = Math.max(1, shot.getHeight() * w / Math.max(1, shot.getWidth()));
                    java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = small.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(shot, 0, 0, w, h, null); g.dispose();
                    snapshots.put((long) (i + 1), small);
                } catch (Exception | Error ignored) { }
            }
        }
        JDialog dialog = dialog("Choose a display"); JPanel body = dialogBody();
        JLabel title = new JLabel("Choose a display"); title.setFont(Theme.title(17f)); title.setForeground(palette.text);
        JPanel intro = transparent(new GridLayout(0, 1, 0, 2)); intro.add(title); intro.add(muted("The whole monitor is shown live in this view")); body.add(intro, BorderLayout.NORTH);
        WindowPicker picker = new WindowPicker(palette); picker.setDisplayMode(true);
        picker.setTargets(displays);
        for (PythonBridge.WindowTarget item : displays) picker.setThumbnail(item.hwnd, snapshots.get(item.hwnd));
        int initial = 0; for (int i = 0; i < devices.length; i++) if (devices[i].equals(primary)) initial = i;
        picker.setSelected(displays.get(initial));
        int[] result = {-1};
        JButton cancel = toolButton("Cancel", "Close without choosing"); cancel.addActionListener(event -> dialog.dispose());
        JButton choose = primaryButton("Capture selected display");
        choose.addActionListener(event -> { if (picker.selected() != null) { result[0] = (int) picker.selected().hwnd - 1; dialog.dispose(); } });
        picker.onConfirm(choose::doClick);
        body.add(picker, BorderLayout.CENTER);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.add(cancel); actions.add(choose); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(Math.min(900, 300 + devices.length * 260), 470); dialog.setMinimumSize(new Dimension(520, 400)); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        return result[0];
    }

    private int chooseDisplayDeviceList() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (devices.length == 0) return -1;
        JComboBox<String> choices = new JComboBox<>(); choices.getAccessibleContext().setAccessibleName("Display to capture");
        for (int i = 0; i < devices.length; i++) {
            DisplayMode mode = devices[i].getDisplayMode();
            choices.addItem("Display " + (i + 1) + " (" + devices[i].getIDstring() + ") — " + mode.getWidth() + " × " + mode.getHeight() + (devices[i].equals(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()) ? " — primary" : ""));
        }
        int result = JOptionPane.showConfirmDialog(this, choices, "Choose a physical display", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION ? choices.getSelectedIndex() : -1;
    }

    private void showGameCaptureInfo() {
        showNotice("Game capture unavailable",
            "The controlled DX11 test renderer could not initialize. Use Game window — WGC compatibility to select a running game window. " +
            "The experimental installer includes the test renderer; updating MultiMini or your graphics driver may resolve initialization problems.");
    }

    private void showMultitaskSettings() {
        settingsHotkeyRecording = true;
        stagedSwapKeyBinding = swapKeyBinding; stagedLayerSwapKeyBinding = layerSwapKeyBinding; stagedStopKeyBinding = stopKeyBinding;
        JDialog dialog = dialog("Settings");
        JPanel body = dialogBody();
        body.setBackground(palette.panel);
        body.setBorder(new EmptyBorder(12,12,12,12));
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);
        tabs.setFont(Theme.regular(13.5f));
        tabs.setForeground(palette.text); tabs.setBackground(palette.background);
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override protected void installDefaults(){super.installDefaults();tabInsets=new Insets(11,14,11,20);contentBorderInsets=new Insets(16,20,16,16);tabAreaInsets=new Insets(4,2,4,10);}
            @Override protected int calculateTabWidth(int placement,int index,FontMetrics metrics){return 168;}
            @Override protected int calculateTabHeight(int placement,int index,int fontHeight){return 40;}
            @Override protected void paintTabArea(Graphics graphics,int placement,int selectedIndex){Graphics2D g=Theme.quality(graphics);g.setColor(palette.background);g.fillRect(0,0,calculateTabAreaWidth(placement,runCount,maxTabWidth)+2,tabs.getHeight());g.dispose();super.paintTabArea(graphics,placement,selectedIndex);}
            @Override protected void paintTabBackground(Graphics graphics,int placement,int index,int x,int y,int w,int h,boolean selected){Graphics2D g=Theme.quality(graphics);if(selected){g.setColor(palette.accentTint(palette.panel,palette.isLight()?0.12f:0.20f));g.fill(Theme.round(x+4,y+2,w-12,h-4,Theme.RADIUS));g.setColor(palette.accent);g.fill(Theme.round(x+4,y+11,3,h-22,3));}else if(getRolloverTab()==index){g.setColor(palette.hover(palette.background));g.fill(Theme.round(x+4,y+2,w-12,h-4,Theme.RADIUS));}g.dispose();}
            @Override protected void paintText(Graphics graphics,int placement,Font font,FontMetrics metrics,int index,String title,Rectangle bounds,boolean selected){Graphics2D g=Theme.quality(graphics);VectorIcon.Type type=title.startsWith("General")?VectorIcon.Type.GEAR:title.startsWith("Controls")?VectorIcon.Type.MOUSE:title.startsWith("Performance")?VectorIcon.Type.BOLT:VectorIcon.Type.POINTER;Rectangle tab=rects[index];Color color=selected?palette.text:Palette.blend(palette.muted,palette.text,0.3f);paintSettingsIcon(g,title,tab.x+19,tab.y+(tab.height-16)/2,color);g.setFont(selected?Theme.semibold(13.5f):Theme.regular(13.5f));g.setColor(color);FontMetrics m=g.getFontMetrics();g.drawString(title,tab.x+46,tab.y+(tab.height-m.getHeight())/2+m.getAscent());g.dispose();}
            @Override protected void paintTabBorder(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected){}
            @Override protected void paintContentBorder(Graphics g,int placement,int selected){
                if(selected<0)return;
                Component page=tabs.getComponentAt(selected); Rectangle r=page.getBounds();
                g.setColor(palette.panel);g.fillRect(r.x-18,r.y-14,r.width+32,r.height+28);
                g.setColor(palette.line);g.drawLine(r.x-20,0,r.x-20,tabs.getHeight());
            }
            @Override protected void paintFocusIndicator(Graphics g,int p,Rectangle[] r,int i,Rectangle icon,Rectangle text,boolean selected){if(tabs.hasFocus()&&selected){Graphics2D a=Theme.quality(g);a.setColor(palette.controlAccent());a.draw(Theme.round(r[i].x+3,r[i].y+1,r[i].width-10,r[i].height-2,Theme.RADIUS));a.dispose();}}
        });
        JPanel fields = settingsPage(); JScrollPane generalScroll=new JScrollPane(fields); generalScroll.setBorder(null);generalScroll.setOpaque(false);generalScroll.getViewport().setOpaque(false);generalScroll.getVerticalScrollBar().setUnitIncrement(20);styleScrollBar(generalScroll.getVerticalScrollBar());tabs.addTab("General",generalScroll);
        fields.setBorder(new EmptyBorder(2, 0, 8, 0));
        fields.add(settingsGroup("Application"));
        JLabel version = new JLabel(System.getProperty("jpackage.app-version", "Development")); version.setForeground(palette.text); version.setFont(Theme.semibold(13.5f)); version.setHorizontalAlignment(SwingConstants.RIGHT);
        String buildNote = Boolean.getBoolean("multimin.dev.gameCapture") || app.multimin.backend.PlatformSupport.isPortable() ? "Experimental capture features enabled" : "Safety build";
        if (app.multimin.backend.PlatformSupport.isPortable()) buildNote = app.multimin.backend.PlatformSupport.osStatus() + " · " + buildNote;
        fields.add(settingsRow(app.multimin.backend.PlatformSupport.osLabel() + " Build", buildNote, version));
        JButton updates = toolButton("Updates…", "Check GitHub for a newer version");
        updates.addActionListener(event -> GithubUpdater.show(this, () -> { if(!workspaceWritable) throw new IllegalStateException("Recover your workspace before updating"); try { workspaceStore.save(views,activeView); } catch(java.io.IOException error) { throw new IllegalStateException("Workspace could not be saved",error); } }, () -> { shutdownServices(); dispose(); System.exit(0); }));
        fields.add(settingsRow("Updates", "Private downloads · preserves your workspace", updates));
        if (app.multimin.backend.PlatformSupport.isMac()) {
            JButton permissions = toolButton("Permissions…", "Check Screen Recording, Accessibility and Input Monitoring");
            permissions.addActionListener(event -> showMacPermissions());
            fields.add(settingsRow("Mac permissions", "Screen Recording, Accessibility, Input Monitoring", permissions));
        }
        JButton legal = toolButton("Read policies", "Terms, privacy and licences"); legal.addActionListener(event -> LegalConsent.showDocuments(this));
        fields.add(settingsRow("Publisher: Mansoor Alakoozai", "Canada · support through GitHub Issues", legal));
        fields.add(settingsGroup("Appearance"));
        JComboBox<String> stagedAccent = new JComboBox<>(new String[]{"Blue", "Red", "Orange", "Yellow", "Green", "Purple", "White", "Black", "Custom…"});
        styleCombo(stagedAccent); stagedAccent.setSelectedItem(accentMode.getSelectedItem());
        stagedAccent.setPreferredSize(new Dimension(150, 30));
        JComboBox<String> themeChoice=new JComboBox<>(new String[]{"Dark","Light"});styleCombo(themeChoice);themeChoice.setSelectedItem(preferences.getString("themeMode","Dark"));fields.add(settingsRow("Theme","Application appearance",themeChoice));
        fields.add(settingsRow("Accent colour", "Highlights and active controls", stagedAccent));
        JCheckBox guiHelpers=new JCheckBox("Show GUI helpers",preferences.getBoolean("guiHelpers",true));
        guiHelpers.setOpaque(false);guiHelpers.setForeground(palette.text);guiHelpers.setIcon(new AccentCheckIcon(palette));
        fields.add(settingsRow("GUI helpers", "Source types, shortcut hints and preview guidance",guiHelpers));
        fields = settingsPage(); JScrollPane controlsScroll=new JScrollPane(fields);controlsScroll.setBorder(null);controlsScroll.getViewport().setOpaque(false);controlsScroll.setOpaque(false);controlsScroll.getVerticalScrollBar().setUnitIncrement(20);styleScrollBar(controlsScroll.getVerticalScrollBar());tabs.addTab("Controls", controlsScroll);
        fields.add(settingsGroup("Keyboard shortcuts"));
        swapKeyButton = toolButton(prettyKeyBinding(swapKeyBinding), "Click to record a key or key combination");
        styleKeyButton(swapKeyButton);
        swapKeyButton.addActionListener(event -> { recordingSwapKey = true; swapKeyButton.setText("Press keys…  ·  Esc to cancel"); }); fields.add(settingsRow("Swap input", "Choose the controlled source", swapKeyButton));
        layerSwapKeyButton = toolButton(layerSwapKeyBinding.isBlank() ? "Unassigned" : prettyKeyBinding(layerSwapKeyBinding),
            "Click and press a shortcut; Backspace clears it");
        styleKeyButton(layerSwapKeyButton);
        layerSwapKeyButton.addActionListener(event -> { recordingLayerSwapKey = true; layerSwapKeyButton.setText("Press keys…  ·  Esc to cancel"); });
        fields.add(settingsRow("Swap visual layers", "Bring the next layer to the top", layerSwapKeyButton));
        stopKeyButton = toolButton(prettyKeyBinding(stopKeyBinding), "Click to record the stop-multitasking key");
        styleKeyButton(stopKeyButton);
        stopKeyButton.addActionListener(event -> { recordingStopKey = true; stopKeyButton.setText("Press keys…  ·  Esc to cancel"); }); fields.add(settingsRow("Stop multitasking", "Exit the overlay", stopKeyButton));
        JButton fullScreenshotButton=screenshotKeyRecorder(screenshotKey), regionScreenshotButton=screenshotKeyRecorder(regionScreenshotKey);
        fields.add(settingsRow("Screenshot", "Full multitask view · multitask mode only",fullScreenshotButton));
        fields.add(settingsRow("Region screenshot", "Drag a rectangle · multitask mode only",regionScreenshotButton));
        fields.add(settingsGroup("Multitasking"));
        JComboBox<String> corner = new JComboBox<>(new String[]{"Top-center", "Top-left", "Top-right", "Bottom-left", "Bottom-right"}); styleCombo(corner); corner.setSelectedIndex(revealCornerIndex); corner.setPreferredSize(new Dimension(150, 30)); fields.add(settingsRow("Exit button corner", "Position of the overlay close button", corner));
        fields = settingsPage(); tabs.addTab("Performance", fields);
        fields.add(settingsGroup("Performance & diagnostics"));
        JComboBox<String> fps = new JComboBox<>(new String[]{"Auto (monitor refresh)", "30 FPS", "60 FPS", "90 FPS", "120 FPS", "144 FPS", "165 FPS", "240 FPS"});
        fps.setSelectedItem(performanceFps == 0 ? "Auto (monitor refresh)" : performanceFps + " FPS"); styleCombo(fps); fps.setPreferredSize(new Dimension(170, 30)); fields.add(settingsRow("Capture target", "Maximum preview and capture rate", fps));
        JCheckBox showStats = new JCheckBox("Show debug output", debugPanel.isVisible());
        showStats.setOpaque(false); showStats.setForeground(palette.text); showStats.setIcon(new AccentCheckIcon(palette)); fields.add(settingsRow("Diagnostics", "Show status and performance details", showStats));
        boolean originalDebugVisibility = debugPanel.isVisible();
        boolean[] settingsApplied = {false};
        JLabel statsNote = new JLabel("<html>Auto follows your monitor's refresh rate. Actual delivery depends on the<br>capture method, source updates and hardware. Native DWM presentation<br>avoids pixel transfer for supported window-only layouts.</html>");
        statsNote.setForeground(palette.muted); statsNote.setFont(statsNote.getFont().deriveFont(10f)); fields.add(statsNote);
        tabs.addTab("Help", PolicyPages.create(palette, true));
        for(int i=0;i<tabs.getTabCount();i++){
            Component page=tabs.getComponentAt(i);page.setBackground(palette.panel);
            if(page instanceof JScrollPane scroll){scroll.getViewport().setOpaque(true);scroll.getViewport().setBackground(palette.panel);}
        }
        body.add(tabs, BorderLayout.CENTER);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); JButton cancel = toolButton("Cancel", "Close without applying"); cancel.addActionListener(event -> dialog.dispose());
        Runnable applySettings = () -> {
            recordingSwapKey = false; recordingStopKey = false; recordingLayerSwapKey = false;
            screenshotKey=(String)fullScreenshotButton.getClientProperty("binding");regionScreenshotKey=(String)regionScreenshotButton.getClientProperty("binding");preferences.putString("screenshotKey",screenshotKey);preferences.putString("regionScreenshotKey",regionScreenshotKey);configureScreenshotKeys();
            swapKeyBinding = stagedSwapKeyBinding; layerSwapKeyBinding = stagedLayerSwapKeyBinding; stopKeyBinding = stagedStopKeyBinding;
            preferences.putString("swapInputKey", swapKeyBinding); preferences.putString("swapLayersKey", layerSwapKeyBinding); preferences.putString("stopKey", stopKeyBinding);
            preferences.putBoolean("guiHelpers",guiHelpers.isSelected());
            updateHotkeyHints();
            String theme=String.valueOf(themeChoice.getSelectedItem());preferences.putString("themeMode",theme);if("Light".equals(theme))palette.light();else palette.dark();LegalConsent.setPalette(palette);
            accentMode.setSelectedItem(stagedAccent.getSelectedItem()); applyAccent();
            revealCornerIndex = corner.getSelectedIndex();
            preferences.putInt("revealCorner", revealCornerIndex);
            String selectedFps = String.valueOf(fps.getSelectedItem());
            applyPerformanceFps(selectedFps.startsWith("Auto") ? 0 : Integer.parseInt(selectedFps.split(" ")[0]));
            preferences.putBoolean("showDebug", showStats.isSelected());
            preferences.putBoolean("showPerformanceStats", showStats.isSelected());
            setDebugVisible(showStats.isSelected());
            refreshPerformanceStats();
            if (multitask) backend.setMultitaskOverlay(true, true, stopKeyBinding, swapKeyBinding, layerSwapKeyBinding);
            settingsApplied[0] = true;
        };
        JButton apply = toolButton("Apply", "Apply without closing"); apply.addActionListener(event -> applySettings.run());
        JButton ok = primaryButton("OK"); ok.addActionListener(event -> { applySettings.run(); dialog.dispose(); });
        actions.setOpaque(true);actions.setBackground(palette.panel);
        actions.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,palette.line),new EmptyBorder(12,0,0,0)));
        actions.add(cancel); actions.add(apply); actions.add(ok); body.add(actions, BorderLayout.SOUTH);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { recordingSwapKey = false; recordingStopKey = false; recordingLayerSwapKey = false; settingsHotkeyRecording = false; if (!settingsApplied[0]) setDebugVisible(originalDebugVisibility); } });
        dialog.setContentPane(body); dialog.setSize(720, 520); dialog.setMinimumSize(new Dimension(640, 470)); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
    private JPanel settingsPage() {
        JPanel page=new JPanel(new java.awt.GridBagLayout()) {
            private int row;
            { java.awt.GridBagConstraints space=new java.awt.GridBagConstraints();space.gridx=0;space.gridy=1000;space.weighty=1;super.addImpl(Box.createVerticalGlue(),space,-1); }
            @Override protected void addImpl(Component component,Object constraints,int index) {
                java.awt.GridBagConstraints cell=new java.awt.GridBagConstraints();cell.gridx=0;cell.gridy=row++;cell.weightx=1;cell.fill=java.awt.GridBagConstraints.HORIZONTAL;cell.anchor=java.awt.GridBagConstraints.NORTH;cell.insets=new Insets(4,2,4,2);
                super.addImpl(component,cell,index);
            }
        };
        page.setOpaque(false);
        return page;
    }
    private void setDebugVisible(boolean visible) {
        debugPanel.setVisible(visible); performanceStatus.setVisible(visible);
        canvasPanel.revalidate(); canvasPanel.repaint();
    }
    private JPanel settingsGroup(String title) {
        JPanel group = transparent(new BorderLayout());
        JLabel heading = new JLabel(title.toUpperCase(java.util.Locale.ROOT)); heading.setForeground(palette.muted);
        heading.setFont(Theme.eyebrow(10.5f));
        heading.setBorder(new EmptyBorder(14, 0, 6, 0));
        group.add(heading, BorderLayout.NORTH);
        JComponent rule = new JComponent() { @Override protected void paintComponent(Graphics g){g.setColor(palette.hairline());g.drawLine(0,0,getWidth(),0);} };
        rule.setPreferredSize(new Dimension(1,1)); group.add(rule, BorderLayout.SOUTH);
        return group;
    }
    private JPanel settingsRow(String title, String description, JComponent control) {
        JPanel row = transparent(new BorderLayout(18, 0));
        JPanel copy = transparent(new GridLayout(0, 1, 0, 2));
        JLabel name = new JLabel(title); name.setForeground(palette.text); name.setFont(Theme.regular(13.5f));
        JLabel detail = new JLabel(description); detail.setForeground(palette.muted); detail.setFont(Theme.regular(11.5f));
        copy.add(name); copy.add(detail); row.add(copy, BorderLayout.CENTER);
        name.setLabelFor(control); control.getAccessibleContext().setAccessibleName(title); control.getAccessibleContext().setAccessibleDescription(description);
        control.setPreferredSize(new Dimension(176, 34)); row.add(control, BorderLayout.EAST);
        row.setBorder(new EmptyBorder(6, 0, 6, 0)); row.setMinimumSize(new Dimension(0, 44)); row.setPreferredSize(new Dimension(400, 50));
        return row;
    }
    private void styleKeyButton(JButton button) {
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(palette.accent, 1),
            new EmptyBorder(4, 8, 4, 8)));
        button.setToolTipText("Click to record a shortcut. Press Escape to cancel recording.");
    }
    private void updateHotkeyHints() {
        inputKeyHint.setText(prettyKeyBinding(swapKeyBinding) + " swaps input");
        layerKeyHint.setText(layerSwapKeyBinding.isBlank() ? "Layer swap: unassigned" :
            prettyKeyBinding(layerSwapKeyBinding) + " swaps layers");
        inputKeyHint.setToolTipText("Change in Settings → Swap input keybind");
        layerKeyHint.setToolTipText("Change in Settings → Swap visual layers key");
        if(app.multimin.backend.PlatformSupport.isWayland()) {
            inputKeyHint.setToolTipText("X11/XWayland: "+prettyKeyBinding(swapKeyBinding)+" · native Wayland desktop binding: "+backend.linuxShortcut("quick_swap"));
            layerKeyHint.setToolTipText("X11/XWayland: "+prettyKeyBinding(layerSwapKeyBinding)+" · native Wayland desktop binding: "+backend.linuxShortcut("layer_swap"));
            inputKeyHint.setText("X11: "+prettyKeyBinding(swapKeyBinding)+" · Wayland: "+backend.linuxShortcut("quick_swap"));
            layerKeyHint.setText("X11: "+prettyKeyBinding(layerSwapKeyBinding)+" · Wayland: "+backend.linuxShortcut("layer_swap"));
        }
        inputKeyHint.revalidate(); layerKeyHint.revalidate();
    }
    private void updateRecordingButton(JButton button, String value) {
        if (button != null) button.setText(value == null || value.isBlank() ? "Unassigned" : prettyKeyBinding(value));
    }
    private void styleScrollBar(JScrollBar bar) { ModernUi.scroll(bar,palette); }
    private boolean loadImageSource() {
        if (app.multimin.backend.PlatformSupport.isPortable()) {
            if(!backend.isReady()){showNotice("Image source","Wait for the capture service to start.");return false;}
            JDialog waiting=dialog("Choose an image");
            waiting.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            waiting.add(new JLabel("Select an image in the desktop file picker."));
            waiting.setSize(400,120); waiting.setLocationRelativeTo(this);
            String[] path={""};
            backend.requestImageFile(selected->SwingUtilities.invokeLater(()->{path[0]=selected;waiting.dispose();}));
            waiting.setVisible(true);
            if(path[0].isBlank())return false;
            try { pendingImage=ImageIO.read(Path.of(path[0]).toFile()); }
            catch(Exception error){pendingImage=null;}
            if(pendingImage==null){showNotice("Image source","That file could not be opened as an image.");return false;}
            pendingImagePath=path[0];pendingImageName=Path.of(path[0]).getFileName().toString();
            int extension=pendingImageName.lastIndexOf('.');if(extension>0)pendingImageName=pendingImageName.substring(0,extension);
            return true;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an image source");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "bmp", "gif"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        try { pendingImage = ImageIO.read(chooser.getSelectedFile()); }
        catch (Exception ignored) { pendingImage = null; }
        if (pendingImage == null) { showNotice("Image source", "That file could not be opened as an image."); return false; }
        pendingImageName = chooser.getSelectedFile().getName();
        pendingImagePath = chooser.getSelectedFile().getAbsolutePath();
        int extension = pendingImageName.lastIndexOf('.'); if (extension > 0) pendingImageName = pendingImageName.substring(0, extension);
        return true;
    }
    private void applyAccent() { String name = String.valueOf(accentMode.getSelectedItem()); Color color; if ("Red".equals(name)) color = new Color(235, 82, 86); else if ("Orange".equals(name)) color = new Color(241, 145, 64); else if ("Yellow".equals(name)) color = new Color(239, 205, 76); else if ("Green".equals(name)) color = new Color(83, 190, 123); else if ("Purple".equals(name)) color = new Color(161, 111, 224); else if ("White".equals(name)) color = Color.WHITE; else if ("Black".equals(name)) color = new Color(20, 22, 25); else if ("Custom…".equals(name)) color = JColorChooser.showDialog(this, "Choose accent colour", palette.accent); else color = new Color(103, 153, 255); if (color != null) palette.accent = color; refreshTheme(); }
    private void refreshTheme() { canvasPanel.setBackground(palette.canvas()); preview.setGuiHelpers(preferences.getBoolean("guiHelpers",true)); if(gallery!=null)gallery.refreshTheme(); applyUiDefaults(); retheme(this); for(Window owned:getOwnedWindows()){retheme(owned);owned.repaint();} opacity.setUI(new AccentSliderUI(opacity, palette)); scale.setUI(new AccentSliderUI(scale, palette)); for (JTextField field : percentageFields) { field.setBackground(palette.controlSurface()); field.setForeground(palette.text); field.setCaretColor(palette.text); field.setBorder(BorderFactory.createLineBorder(palette.accent)); } layerTitle.setFont(new Font("Segoe UI",Font.BOLD,20)); layerTitle.setForeground(palette.text); root.setBackground(palette.background); sourceList.setCellRenderer(new SourceCellRenderer(palette, preferences.getBoolean("guiHelpers",true))); sourceList.repaint(); topBar.repaint(); opacity.repaint(); scale.repaint(); repaint(); }
    private void retheme(Component component){
        if(component instanceof JComponent helper&&Boolean.TRUE.equals(helper.getClientProperty("gui.helper")))helper.setVisible(preferences.getBoolean("guiHelpers",true));
        if(component.getFont()!=null&&!Theme.isThemeFont(component.getFont()))component.setFont(Theme.regular(component.getFont().getSize2D()).deriveFont(component.getFont().getStyle()));
        Palette dark=new Palette(),light=new Palette();light.light();Color fg=component.getForeground(),bg=component.getBackground();
        if(component instanceof JLabel || component instanceof javax.swing.text.JTextComponent || component instanceof JCheckBox || component instanceof JRadioButton || component instanceof AbstractButton){component.setForeground(fg!=null&&(fg.equals(dark.muted)||fg.equals(light.muted))?palette.muted:palette.text);}
        if(bg!=null&&(bg.equals(dark.background)||bg.equals(light.background)||bg.equals(dark.primary)||bg.equals(light.primary)))component.setBackground(palette.background);
        if(bg!=null&&(bg.equals(dark.panel)||bg.equals(light.panel)||bg.equals(dark.panelRaised)||bg.equals(light.panelRaised)||bg.equals(dark.controlSurface())||bg.equals(light.controlSurface())))component.setBackground(palette.controlSurface());
        if(component instanceof JList<?> || component instanceof JViewport){component.setBackground(palette.panel);component.setForeground(palette.text);}
        if(component==topBar)topBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,palette.line),new EmptyBorder(9,16,9,16)));
        if(component instanceof JComboBox<?> combo)combo.setBorder(new ControlBorder(palette));
        if(component instanceof JComboBox<?> || component instanceof JTabbedPane || component instanceof JMenuItem){component.setForeground(palette.text);component.setBackground(palette.controlSurface());}
        if(component instanceof javax.swing.text.JTextComponent text){if(text.isOpaque())text.setBackground(palette.controlSurface());text.setCaretColor(palette.text);text.setSelectionColor(palette.accent);text.setSelectedTextColor(palette.selectionText());}
        if(component instanceof JTextPane pane) {
            var attributes=new javax.swing.text.SimpleAttributeSet();
            javax.swing.text.StyleConstants.setForeground(attributes,palette.text);
            pane.getStyledDocument().setCharacterAttributes(0,pane.getDocument().getLength(),attributes,false);
        }
        if(component instanceof Container container)for(Component child:container.getComponents())retheme(child);
    }
    private void applyUiDefaults() {
        for(String type:new String[]{"Label","Button","ToggleButton","TextField","FormattedTextField","ComboBox","List","CheckBox","RadioButton","Menu","MenuItem","TabbedPane","TextArea","TextPane","EditorPane"})
            UIManager.put(type+".font",new javax.swing.plaf.FontUIResource(Theme.regular(13f)));
        for(String type:new String[]{"Menu","MenuItem","PopupMenu","TabbedPane","TextArea","TextPane","EditorPane"}){UIManager.put(type+".background",palette.controlSurface());UIManager.put(type+".foreground",palette.text);UIManager.put(type+".selectionBackground",palette.accent);UIManager.put(type+".selectionForeground",palette.selectionText());}

        UIManager.put("ToolTip.background",palette.controlSurface()); UIManager.put("ToolTip.foreground",palette.text); UIManager.put("ToolTip.border",BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line),new EmptyBorder(5,8,5,8))); UIManager.put("ToolTip.font",Theme.regular(12f));
        UIManager.put("Panel.background", palette.background); UIManager.put("Label.foreground", palette.text);
        UIManager.put("Button.background", palette.panelRaised); UIManager.put("Button.foreground", palette.text); UIManager.put("Button.select", palette.accent.darker());
        UIManager.put("List.background", palette.panel); UIManager.put("List.foreground", palette.text); UIManager.put("List.selectionBackground", palette.accent); UIManager.put("List.selectionForeground", palette.selectionText());
        UIManager.put("Viewport.background", palette.panel); UIManager.put("ScrollPane.background", palette.panel);
        UIManager.put("ComboBox.background", palette.controlSurface()); UIManager.put("ComboBox.foreground", palette.text); UIManager.put("ComboBox.selectionBackground", palette.accent); UIManager.put("ComboBox.selectionForeground", palette.selectionText());
        UIManager.put("CheckBox.foreground", palette.text); UIManager.put("CheckBox.select", palette.accent); UIManager.put("RadioButton.foreground", palette.text); UIManager.put("RadioButton.select", palette.accent); UIManager.put("Slider.background", palette.background);
        UIManager.put("TextField.background", palette.controlSurface()); UIManager.put("TextField.foreground", palette.text); UIManager.put("TextField.caretForeground", palette.text); UIManager.put("TextField.selectionBackground", palette.accent); UIManager.put("TextField.selectionForeground", palette.selectionText());
        UIManager.put("OptionPane.background", palette.background); UIManager.put("OptionPane.messageForeground", palette.text);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File"); JMenuItem snapshot = new JMenuItem("Temporary snapshot"); snapshot.addActionListener(event -> takeSnapshot()); JMenuItem exit = new JMenuItem("Exit"); exit.addActionListener(event -> { shutdownServices(); dispose(); }); file.add(snapshot); file.addSeparator(); file.add(exit);
        JMenu view = new JMenu("View"); JMenuItem multitaskItem = new JMenuItem("Enter multitask mode"); multitaskItem.addActionListener(event -> setMultitask(true)); JMenuItem fit = new JMenuItem("Fit source to canvas"); fit.addActionListener(event -> resetTransform()); view.add(multitaskItem); view.add(fit);
        JMenu help = new JMenu("Help"); JMenuItem privacy = new JMenuItem("Privacy status"); privacy.addActionListener(event -> showNotice("Privacy", "Capture stays local. Recording is disabled. Temporary snapshots expire after seven days.")); help.add(privacy);
        bar.setBackground(palette.panel); bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.line));
        for (JMenu menu : new JMenu[]{file, view, help}) { menu.setForeground(palette.text); }
        bar.add(file); bar.add(view); bar.add(help); return bar;
    }

    private void moveSelected(int direction) {
        int from = sourceList.getSelectedIndex(); if (from < 0) return;
        int to = Math.max(0, Math.min(activeView.sources.size() - 1, from + direction)); if (from == to) return;
        Source controlled = activeView.sources.get(Math.min(activeView.controlSource, activeView.sources.size() - 1));
        Source source = activeView.sources.remove(from); activeView.sources.add(to, source); activeView.controlSource = activeView.sources.indexOf(controlled); sourceList.setSelectedIndex(to); preview.repaint();
    }

    private void duplicateSelected() {
        Source original = sourceList.getSelectedValue(); if (original == null) return;
        Source copy = new Source(uniqueSourceName(original.name + " copy"), original.kind); copy.customName = original.customName; copy.captureCursor = original.captureCursor; copy.visible = original.visible; copy.quickSwapInput = original.quickSwapInput; copy.inputTarget = original.inputTarget; copy.opacity = original.opacity; copy.scale = original.scale; copy.scaleX = original.scaleX; copy.scaleY = original.scaleY; copy.freeTransform = original.freeTransform; copy.x = Math.min(100, original.x + 5); copy.y = Math.min(100, original.y + 5); copy.liveFrame = original.kind == Source.Kind.IMAGE ? original.liveFrame : null;
        if (copy.quickSwapInput) copy.controlColor = nextControlColor(original);
        copy.nativeWindowHandle = original.nativeWindowHandle;
        copy.windowProcess = original.windowProcess;
        copy.imagePath = original.imagePath;
        copy.captureMethod = original.captureMethod;
        copy.nativeGameTest = original.nativeGameTest;
        copy.nativePresentationEnabled = original.nativePresentationEnabled;
        if (original.captureFramePath != null && original.kind != Source.Kind.DISPLAY) {
            copy.captureFramePath = windowFramesDirectory.resolve(java.util.UUID.randomUUID()
                + (original.nativeGameTest ? ".game" : ".bmp")).toString();
            if (original.nativeGameTest) backend.startGameCapture(copy.captureFramePath);
            else backend.registerWindowCapture(copy.name, original.inputTarget, copy.captureFramePath,
                copy.nativeWindowHandle, copy.captureMethod, copy.captureCursor);
        }
        int index = sourceList.getSelectedIndex(); activeView.sources.add(index, copy); sourceList.setSelectedIndex(index); preview.repaint();
    }

    private void removeSelected() {
        int index = sourceList.getSelectedIndex(); if (index < 0) return;
        Source source = activeView.sources.get(index);
        if (source.captureFramePath != null) {
            if (source.nativeGameTest) backend.stopGameCapture(source.captureFramePath);
            else backend.removeWindowCapture(source.captureFramePath);
        }
        activeView.sources.remove(index); activeView.controlSource = Math.min(activeView.controlSource, activeView.sources.size() - 1); sourceList.setSelectedIndex(Math.min(index, activeView.sources.size() - 1)); preview.repaint();
    }

    private void toggleSelectedVisibility() {
        Source source = sourceList.getSelectedValue(); if (source == null) return;
        source.visible = !source.visible; sourceList.repaint(); preview.repaint();
    }

    private void renameSelected() {
        Source source = sourceList.getSelectedValue(); if (source == null) return;
        String name = promptText("Rename source", "Source name:", source.name); if (name != null && !name.trim().isEmpty()) { source.name = uniqueSourceName(name.trim(), source); source.customName = true; sourceList.repaint(); syncSelection(); preview.repaint(); }
    }

    private void configureSelectedSource() {
        if (app.multimin.backend.PlatformSupport.isPortable()) {
            Source source = sourceList.getSelectedValue();
            if (source != null && source.kind == Source.Kind.DISPLAY) {
                if (source.captureFramePath != null) backend.removeWindowCapture(source.captureFramePath);
                source.portalRestoreToken = "";
                source.nativeDisplayAttempted = false; source.nativeDisplayFrames = false;
                source.liveFrame = null; source.lastMemorySequence = 0;
                return;
            }
        }

        Source source = sourceList.getSelectedValue();
        if (source == null) return;
        if (source.usesWindowTarget()) {
            showWindowSourceSettings(source);
        } else if (source.kind == Source.Kind.DISPLAY) {
            int device = chooseDisplayDevice();
            if (device >= 0) { source.displayDeviceIndex = device; source.liveFrame = null; preview.repaint(); }
        } else if (source.kind == Source.Kind.IMAGE) {
            if (loadImageSource()) { source.liveFrame = pendingImage; pendingImage = null; preview.repaint(); }
        } else if (source.kind == Source.Kind.VIDEO) {
            showNotice("Video source", "Video playback is not implemented yet. This source cannot be configured until playback support is added.");
        } else if (source.kind == Source.Kind.GAME) {
            showNotice("DirectX 11 test capture", "This source launches the controlled DirectX 11 test renderer. " +
                "To change its name use F2; to restart capture, remove and add it again. Third-party game hooks are not implemented.");
        }
    }

    private void showWindowSourceSettings(Source source) {
        boolean linux = app.multimin.backend.PlatformSupport.isPortable();
        JDialog dialog = dialog(source.kind == Source.Kind.GAME
            ? linux ? "Configure game window capture" : "Configure game window — WGC compatibility"
            : "Configure window capture");
        JPanel body = dialogBody();
        JPanel fields = settingsPage();
        boolean kdeTarget=app.multimin.backend.PlatformSupport.isPortable() && source.windowProcess.startsWith("kwin:");
        boolean portalTarget=app.multimin.backend.PlatformSupport.isPortable() && ("portal".equals(source.windowProcess)||kdeTarget);
        JTextField name = new JTextField(source.name); name.getAccessibleContext().setAccessibleName("Source name");
        name.setBorder(BorderFactory.createCompoundBorder(new ControlBorder(palette),new EmptyBorder(6,8,6,8)));name.setFont(Theme.regular(13.5f));name.setSelectionColor(palette.accent);name.setSelectedTextColor(palette.selectionText());
        name.setForeground(palette.text); name.setBackground(palette.controlSurface()); name.setCaretColor(palette.text);
        boolean[] manualName = {source.customName || portalTarget}, settingSuggestedName = {false};
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { if (!settingSuggestedName[0]) manualName[0] = true; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        });
        JComboBox<PythonBridge.WindowTarget> targets = new JComboBox<>(); targets.getAccessibleContext().setAccessibleName("Capture target"); styleCombo(targets);
        targets.addActionListener(event -> {
            PythonBridge.WindowTarget selected = (PythonBridge.WindowTarget) targets.getSelectedItem();
            if (selected != null && !manualName[0]) {
                settingSuggestedName[0] = true; name.setText(automaticWindowSourceName(selected)); settingSuggestedName[0] = false;
            }
        });
        JLabel scanStatus = muted("Scanning open windows…");
        JButton scan = toolButton("Refresh", "Refresh detected application windows");
        WindowPicker targetPicker = new WindowPicker(palette);
        targetPicker.onSelect(target -> targets.setSelectedItem(target));
        targetPicker.setPreferredSize(new Dimension(600, 330));
        Runnable refresh = () -> {
            if(portalTarget && !kdeTarget) {
                targets.setModel(new DefaultComboBoxModel<>(new PythonBridge.WindowTarget[]{
                    new PythonBridge.WindowTarget("portal","Desktop-selected window",0,0,0,800,600)}));
                scan.setEnabled(false);scanStatus.setText("The desktop window picker opens after Apply changes.");return;
            }
            scanStatus.setText("Scanning open windows…"); scan.setEnabled(false);
            backend.requestWindowList(found -> SwingUtilities.invokeLater(() -> {
                DefaultComboBoxModel<PythonBridge.WindowTarget> model = new DefaultComboBoxModel<>();
                for (PythonBridge.WindowTarget item : found) model.addElement(item);
                targets.setModel(model);
                for (int i = 0; i < model.getSize(); i++) if (model.getElementAt(i).hwnd == source.nativeWindowHandle) targets.setSelectedIndex(i);
                targetPicker.setTargets(found); targetPicker.setSelected((PythonBridge.WindowTarget) targets.getSelectedItem());
                backend.requestWindowThumbnails(found.stream().map(target -> target.hwnd).toList(), 360, targetPicker::setThumbnail);
                scan.setEnabled(true); scanStatus.setText(found.size() + " windows found");
            }),kdeTarget?"wayland":"x11");
        };
        scan.addActionListener(event -> refresh.run());
        fields.add(propertyLabel("Source name")); fields.add(name);
        if (portalTarget && !kdeTarget) {
            fields.add(propertyLabel("Capture target")); JPanel targetRow = transparent(new BorderLayout(6, 0)); targetRow.add(targets); targetRow.add(scan, BorderLayout.EAST); fields.add(targetRow); fields.add(scanStatus);
        } else {
            JPanel targetHeader = transparent(new BorderLayout(8, 0)); JPanel targetCopy = transparent(new GridLayout(0, 1, 0, 2)); targetCopy.add(propertyLabel("Capture target")); targetCopy.add(scanStatus);
            targetHeader.add(targetCopy, BorderLayout.CENTER); JPanel scanWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 4)); scanWrap.add(scan); targetHeader.add(scanWrap, BorderLayout.EAST);
            fields.add(targetHeader); fields.add(targetPicker);
        }
        JCheckBox captureCursor = new JCheckBox("Capture cursor in this source", source.captureCursor); captureCursor.setOpaque(false); captureCursor.setForeground(palette.text); captureCursor.setIcon(new AccentCheckIcon(palette)); fields.add(captureCursor);
        JCheckBox nativePresentation=new JCheckBox(linux?"Native presentation when this is the only visible window":"Native window presentation in multitask mode",source.nativePresentationEnabled);
        nativePresentation.setOpaque(false);nativePresentation.setForeground(palette.text);nativePresentation.setIcon(new AccentCheckIcon(palette));
        fields.add(nativePresentation);
        if(app.multimin.backend.PlatformSupport.isPortable() && !portalTarget) captureCursor.setEnabled(false);
        fields.add(propertyLabel("Capture method"));
        JComboBox<String> method = new JComboBox<>(app.multimin.backend.PlatformSupport.isPortable() ? new String[]{app.multimin.backend.PlatformSupport.isMac()?"macOS ScreenCaptureKit":portalTarget?"Desktop sharing portal":"X11 compositor drawable"} : new String[]{"Automatic (recommended)", "Window surface (may work when covered)", "Visible desktop (window must be unobstructed)"});
        method.getAccessibleContext().setAccessibleName("Capture method"); styleCombo(method); method.setSelectedIndex(app.multimin.backend.PlatformSupport.isPortable()?0:"WINDOW".equals(source.captureMethod) ? 1 : "SCREEN".equals(source.captureMethod) ? 2 : 0); fields.add(method);
        if (source.kind == Source.Kind.GAME && !linux) { fields.remove(method); JLabel nativeMethod=new JLabel("Windows Graphics Capture · WGC compatibility");nativeMethod.setOpaque(true);nativeMethod.setBackground(palette.controlSurface());nativeMethod.setForeground(palette.text);nativeMethod.setBorder(new EmptyBorder(10,10,10,10));fields.add(nativeMethod); }
        JLabel methodHint = muted(linux
            ? source.kind == Source.Kind.GAME
                ? (portalTarget
                    ? "Best-effort capture through desktop window sharing. Protected or exclusive-fullscreen games may not work; game input support varies."
                    : app.multimin.backend.PlatformSupport.isMac()
                        ? "Untested macOS preview. Needs Screen Recording and Accessibility permission; game input support varies."
                        : "Best-effort X11 window capture. Protected or exclusive-fullscreen games may not work; game input support varies.")
                : "Capture uses the "+app.multimin.backend.PlatformSupport.osLabel()+" backend; native Windows presentation is unavailable."
            : source.kind == Source.Kind.GAME ? "WGC captures the selected game window; graphics API hooks are not active." : "Automatic tries WGC, then compatibility capture."); fields.add(methodHint);
        body.add(fields, BorderLayout.CENTER);
        JButton cancel = toolButton("Cancel", "Discard changes"); cancel.addActionListener(event -> dialog.dispose());
        JButton save = primaryButton("Apply changes"); save.addActionListener(event -> {
            PythonBridge.WindowTarget target = (PythonBridge.WindowTarget) targets.getSelectedItem();
            if (target == null || name.getText().trim().isEmpty()) { showNotice("Window capture", "Choose a detected window and enter a source name."); return; }
            source.name = uniqueSourceName(name.getText().trim(), source); source.customName = manualName[0]; source.inputTarget = target.title; source.nativeWindowHandle = target.hwnd; source.windowProcess = target.process; source.portalRestoreToken = "";
            source.captureMethod = source.kind == Source.Kind.GAME && !linux ? "WGC" : method.getSelectedIndex() == 1 ? "WINDOW" : method.getSelectedIndex() == 2 ? "SCREEN" : "AUTO";
            source.captureCursor = captureCursor.isSelected();
            source.nativePresentationEnabled=nativePresentation.isSelected();
            source.liveFrame = null; source.lastCaptureModified = 0;
            if(source.captureFramePath==null) source.captureFramePath=windowFramesDirectory.resolve(java.util.UUID.randomUUID()+".bmp").toString();
            backend.registerWindowCapture(source.name, source.inputTarget, source.captureFramePath, source.nativeWindowHandle, source.captureMethod, source.captureCursor);
            sourceList.repaint(); syncSelection(); preview.repaint(); dialog.dispose();
        });
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.add(cancel); actions.add(save); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(portalTarget && !kdeTarget ? 680 : 860, portalTarget && !kdeTarget ? 550 : 800); dialog.setMinimumSize(new Dimension(560,500)); dialog.setLocationRelativeTo(this); refresh.run(); dialog.setVisible(true);
    }

    private void resetTransform() {
        Source source = sourceList.getSelectedValue(); if (source == null) return;
        if (source.kind == Source.Kind.DISPLAY || source.usesWindowTarget()) { source.scale = 100; source.scaleX = source.scaleY = -1; source.freeTransform = false; source.x = 0; source.y = 0; }
        else { source.scale = 38; source.x = 56; source.y = 52; }
        syncSelection(); preview.repaint();
    }

    private void setMultitask(boolean enabled) {
        if (enabled && !backend.isReady()) {
            showNotice("Multitask input unavailable", "The Python input service has not started. Check the status bar and restart MultiMin from this build.");
            return;
        }
        if(enabled && !multitask && app.multimin.backend.PlatformSupport.isPortable()) {
            for(int i=0;i<activeView.sources.size();i++) {
                Source source=activeView.sources.get(i);
                if(source.visible && source.quickSwapInput && (source.usesWindowTarget() || source.kind==Source.Kind.DISPLAY) && source.liveFrame==null) {
                    showNotice("Capture not ready","Wait for this source's first captured frame, or disable its input-cycle icon: "+source.name);return;
                }
            }
        }
        if (app.multimin.backend.PlatformSupport.isPortable()) {
            for(View view:views)for(int i=0;i<view.sources.size();i++){
                Source source=view.sources.get(i);
                source.inputReferenceWidth=enabled && source.liveFrame!=null?source.liveFrame.getWidth():0;
                source.inputReferenceHeight=enabled && source.liveFrame!=null?source.liveFrame.getHeight():0;
            }
        }
        if (multitask == enabled) return; multitask = enabled; GraphicsDevice device = getGraphicsConfiguration().getDevice();
        preview.setLocked(enabled);
        if (enabled) {
            refreshDisplayCaptureActivity();
            if (activeView.sources.size() > 0) {
                activeView.controlSource = Math.max(0, Math.min(activeView.controlSource, activeView.sources.size() - 1));
                multitaskInputLabel.setText("Input target: " + activeView.sources.get(activeView.controlSource).name);
            }
            preview.setLocked(true);
            multitaskWindow = new JWindow();
            if(app.multimin.backend.PlatformSupport.isPortable()) multitaskWindow.setType(Window.Type.POPUP);
            multitaskWindow.setFocusableWindowState(false);
            multitaskWindow.setAlwaysOnTop(true);
            // Avoid the per-pixel translucent XWayland update path when
            // the composition already paints an opaque black background.
            multitaskWindow.setBackground(app.multimin.backend.PlatformSupport.isPortable() && !preview.needsTransparentOverlay()
                ? Color.BLACK : new Color(0, 0, 0, 0));
            multitaskWindow.setContentPane(preview);
            Rectangle screenBounds = device.getDefaultConfiguration().getBounds();
            if (app.multimin.backend.PlatformSupport.isMac()) {
                // Keep the overlay below the menu bar (and the camera notch on
                // MacBooks); macOS would otherwise push or clip a full-screen window.
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(device.getDefaultConfiguration());
                screenBounds = new Rectangle(screenBounds.x, screenBounds.y + insets.top, screenBounds.width,
                    Math.max(1, screenBounds.height - insets.top));
            }
            multitaskWindow.setBounds(screenBounds);
            multitaskWindow.setVisible(true);
            if(app.multimin.backend.PlatformSupport.isPortable()) {
                long xid=app.multimin.backend.LinuxWindowIdentity.get(multitaskWindow);
                backend.send("{\"command\":\"linux_overlay_window\",\"xid\":"+xid+"}");
            }
            if (app.multimin.backend.PlatformSupport.isPortable() && multitaskWindow != null && !"native".equals(backend.linuxInputMode())) {
                multitaskWindow.setFocusableWindowState(true);multitaskWindow.requestFocus(); preview.setFocusable(true); preview.requestFocusInWindow();
            }
            multitaskControls = new JWindow();
            if(app.multimin.backend.PlatformSupport.isPortable()) multitaskControls.setType(Window.Type.POPUP);
            multitaskControls.setFocusableWindowState(false);
            multitaskControls.setAlwaysOnTop(true);
            multitaskControls.setBackground(new Color(0, 0, 0, 0));
            JPanel controlsPanel = new JPanel(null);
            controlsPanel.setOpaque(false);
            multitaskTargetLabel = new JLabel();
            multitaskTargetLabel.setFont(multitaskTargetLabel.getFont().deriveFont(Font.BOLD, 13f));
            multitaskTargetLabel.setOpaque(false);
            JButton exitOverlay = new ExitCircleButton(); multitaskExitButton = exitOverlay;
            exitOverlay.setToolTipText("Exit multitask mode"); exitOverlay.addActionListener(event -> setMultitask(false));
            multitaskControls.setContentPane(controlsPanel);
            Rectangle screen = screenBounds;
            int buttonX = revealCornerIndex == 0 ? screen.x + (screen.width - 38) / 2
                : (revealCornerIndex == 1 || revealCornerIndex == 3) ? screen.x + 10 : screen.x + screen.width - 48;
            int buttonY = revealCornerIndex <= 2 ? screen.y + 10 : screen.y + screen.height - 48;
            int controlsWidth = 300;
            boolean labelBeforeButton = buttonX + controlsWidth > screen.x + screen.width;
            controlsPanel.add(exitOverlay);controlsPanel.add(multitaskTargetLabel);
            exitOverlay.setBounds(labelBeforeButton?262:0,0,38,38);multitaskTargetLabel.setBounds(labelBeforeButton?0:46,0,250,38);
            int controlsX = labelBeforeButton ? buttonX - (controlsWidth - 38) : buttonX;
            controlsX = Math.max(screen.x, Math.min(controlsX, screen.x + screen.width - controlsWidth));
            buttonY = Math.max(screen.y, Math.min(buttonY, screen.y + screen.height - 38));
            multitaskControls.setBounds(controlsX, buttonY, controlsWidth, 38);
            inputNoticeTimer = new javax.swing.Timer(1000, event -> multitaskTargetLabel.setVisible(false));
            inputNoticeTimer.setRepeats(false);
            multitaskControls.setVisible(true);
            if(app.multimin.backend.PlatformSupport.isPortable()) {
                long controlXid=app.multimin.backend.LinuxWindowIdentity.get(multitaskControls);
                backend.send("{\"command\":\"linux_overlay_controls\",\"xid\":"+controlXid+"}");
            }
            Point exitPoint=exitOverlay.getLocationOnScreen();exitHotspot=new Rectangle(exitPoint.x,exitPoint.y,38,38);
            exitHideTimer=new javax.swing.Timer(2600,event->{if(exitHotspot!=null&&MouseInfo.getPointerInfo()!=null&&exitHotspot.contains(MouseInfo.getPointerInfo().getLocation()))return;multitaskControls.setVisible(false);overlayControlRegion(false);});exitHideTimer.setRepeats(false);
            exitHoverTimer=new javax.swing.Timer(100,event->{if(exitHotspot!=null&&MouseInfo.getPointerInfo()!=null&&exitHotspot.contains(MouseInfo.getPointerInfo().getLocation()))revealExit();});exitHoverTimer.start();updateControlIndicator();revealExit();overlayControlRegion(true);
            PythonBridge.prepareNativeDisplayCapture();
            setVisible(false);
            configureScreenshotKeys();backend.setMultitaskOverlay(true, true, stopKeyBinding, swapKeyBinding, layerSwapKeyBinding);
            SwingUtilities.invokeLater(() -> { refreshNativeLayerLayout(); setControlTarget(activeView.controlSource); });
        } else {
            resetLinuxDirectPresentation();
            linuxPermissionDialog=false;
            if(screenshotSelection!=null)screenshotSelection.dispose();
            if(exitHideTimer!=null)exitHideTimer.stop();if(exitHoverTimer!=null)exitHoverTimer.stop();exitHotspot=null;
            backend.setMultitaskOverlay(false, false, stopKeyBinding);
            if (inputNoticeTimer != null) { inputNoticeTimer.stop(); inputNoticeTimer = null; }
            multitaskTargetLabel = null;
            if (multitaskControls != null) { multitaskControls.setVisible(false); multitaskControls.dispose(); multitaskControls = null; }
            multitaskExitButton = null;
            if (multitaskWindow != null) { multitaskWindow.setVisible(false); multitaskWindow.remove(preview); multitaskWindow.dispose(); multitaskWindow = null; }
            preview.setLocked(false);
            canvasPanel.add(preview, BorderLayout.CENTER);
            setVisible(true);
            refreshDisplayCaptureActivity();
        }
        root.revalidate(); root.repaint(); preview.revalidate(); preview.repaint();
    }

    private void refreshDisplayCaptureActivity() {
        for (int device = 0; device < displayCaptures.size(); device++) {
            boolean needed = false;
            for (int i = 0; i < activeView.sources.size(); i++) {
                Source source = activeView.sources.get(i);
                if (preview.isSourceRendered(i) && source.kind == Source.Kind.DISPLAY && source.displayDeviceIndex == device) {
                    if ((app.multimin.backend.PlatformSupport.isPortable() || (displayCaptures.size() == 1 && device == 0)) && !source.nativeDisplayAttempted && backend.isReady()) {
                        source.nativeDisplayAttempted = true;
                        if (app.multimin.backend.PlatformSupport.isPortable() || PythonBridge.prepareNativeDisplayCapture()) {
                            source.captureFramePath = windowFramesDirectory.resolve(java.util.UUID.randomUUID() + ".monitor").toString();
                            backend.send("{\"command\":\"register_display_capture\",\"monitor\":" + (device+1) + ",\"path\":\"" + source.captureFramePath.replace("\\", "\\\\") + "\",\"restore_token\":\"" + source.portalRestoreToken.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                        }
                    }
                    if (!source.nativeDisplayFrames) needed = true;
                }
            }
            displayCaptures.get(device).setEnabled(needed && !app.multimin.backend.PlatformSupport.isPortable());
        }
    }

    private void refreshWindowFrames() {
        updateLinuxDirectPresentation();
        if(multitask && app.multimin.backend.PlatformSupport.isPortable() && System.nanoTime()-lastInputCheck>250_000_000L) {
            lastInputCheck=System.nanoTime();backend.send("{\"command\":\"linux_check_input\"}");
        }
        preview.refreshNativePresentation();
        if(multitask && multitaskExitButton!=null) {
            boolean nativeSurfaces=false;
            for(int i=0;i<activeView.sources.size();i++)nativeSurfaces|=activeView.sources.get(i).nativeCompositionActive;
            nativeSurfaces|=preview.hasLinuxDirectSource();
            String exitKey=prettyKeyBinding(stopKeyBinding);
            if(app.multimin.backend.PlatformSupport.isWayland() && activeView.controlSource>=0 && activeView.controlSource<activeView.sources.size()) {
                Source controlled=activeView.sources.get(activeView.controlSource);
                if(controlled.kind==Source.Kind.DISPLAY || controlled.windowProcess.startsWith("kwin:")) exitKey=backend.linuxShortcut("toggle_multitask");
            }
            multitaskExitButton.setToolTipText("Exit multitask mode · "+exitKey+" · "+(nativeSurfaces?"Native window composition":"Capture preview"));
        }
        refreshDisplayCaptureActivity();
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < activeView.sources.size(); i++) {
            Source source = activeView.sources.get(i);
            if (preview.isSourceRendered(i) && !source.nativeCompositionActive && source.captureFramePath != null) sources.add(source);
        }
        List<String> activePaths = sources.stream().filter(source -> source.usesWindowTarget() || source.kind == Source.Kind.DISPLAY)
            .map(source -> source.captureFramePath).toList();
        if (backend.isReady() && (!captureActivitySent || !activePaths.equals(activeCapturePaths))) {
            captureActivitySent=true;
            activeCapturePaths = activePaths;
            backend.setActiveWindowCaptures(activePaths);
        }
        boolean changed = !sources.equals(captureSnapshot);
        captureSnapshot = List.copyOf(sources);
        if (changed || !backend.hasFrameNotifications() || System.nanoTime()-lastFrameRequestNanos >= 100_000_000L)
            requestWindowFrames();
    }

    private synchronized void requestWindowFrames() {
        if (captureClosing || !windowFrameScanQueued.compareAndSet(false, true)) return;
        List<Source> sources = captureSnapshot;
        newFramePending.set(false);
        lastFrameRequestNanos = System.nanoTime();
        if (sources.isEmpty()) { windowFrameScanQueued.set(false); return; }
        windowFrameWorker.execute(() -> {
            Map<Source, WindowFrameReader.Frame> frames = new HashMap<>();
            Map<Source, long[]> versions = new HashMap<>();
            Map<Source,String> identifiers=new HashMap<>();
            for (Source source : sources) {
                String framePath=source.captureFramePath;
                if(framePath==null)continue;
                identifiers.put(source,framePath);
                try {
                    WindowFrameReader.Frame frame = null;
                    try { frame = windowFrameReader.readMemory(framePath, source.lastMemorySequence,
                        backend.frameStreamPort(), source.standbyFrame); }
                    catch (java.io.IOException ignored) { }
                    if (frame != null) {
                        frames.put(source, frame); continue;
                    }
                    Path path = Path.of(framePath);
                    if (!Files.isRegularFile(path)) continue;
                    long modified = Files.getLastModifiedTime(path).toMillis(), size = Files.size(path);
                    if (modified == source.lastCaptureModified && size == source.lastCaptureSize) continue;
                    frame = windowFrameReader.readDisk(framePath, source.lastCaptureModified, source.lastCaptureSize);
                    if (frame != null) { frames.put(source, frame); versions.put(source, new long[]{modified, size}); }
                } catch (Exception ignored) { }
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    for (Map.Entry<Source, WindowFrameReader.Frame> entry : frames.entrySet()) {
                        Source source = entry.getKey();
                        if(!java.util.Objects.equals(identifiers.get(source),source.captureFramePath))continue;
                        WindowFrameReader.Frame frame = entry.getValue();
                        long[] version = versions.get(source);
                        if (frame.backend.contains("memory")) {
                            if (source.lastMemorySequence > 0 && frame.sequence > source.lastMemorySequence + 1)
                                source.skippedFrames += frame.sequence - source.lastMemorySequence - 1;
                            source.lastMemorySequence = frame.sequence;
                        } else if (version != null) {
                            source.lastCaptureModified = version[0]; source.lastCaptureSize = version[1];
                        }
                        BufferedImage previous = source.liveFrame;
                        source.liveFrame = frame.image;
                        if (source.kind == Source.Kind.DISPLAY && frame.backend.contains("memory")) source.nativeDisplayFrames = true;
                        source.standbyFrame = frame.backend.contains("memory") ? previous : null;
                        source.activeCaptureBackend = source.nativeGameTest
                            ? "DirectX 11 test capture · CPU transfer" : source.kind == Source.Kind.GAME
                            ? app.multimin.backend.PlatformSupport.isPortable()
                                ? app.multimin.backend.PlatformSupport.osLabel() + " game-window capture · " + frame.backend
                                : "WGC game-window compatibility · " + (frame.backend.contains("shared") ? "shared memory" : "socket memory") : frame.backend;
                        if(source==linuxDirectSource)source.activeCaptureBackend="Native Linux window presentation · capture retained for screenshots";
                        source.captureLatencyMillis = frame.latencyMillis; source.capturedFrames++;
                        source.producedNanos = frame.producedNanos;
                    }
                    if (!frames.isEmpty() && !preview.hasLinuxDirectSource()) preview.repaint();
                } finally {
                    windowFrameScanQueued.set(false);
                    if (newFramePending.get()) requestWindowFrames();
                }
            });
        });
    }

    private synchronized void shutdownServices() {
        resetLinuxDirectPresentation();
        if(sidebarWidthSaveTimer!=null)sidebarWidthSaveTimer.stop();saveSidebarWidths();
        shuttingDown=true;if(screenshotSelection!=null)screenshotSelection.dispose();if(multitask){backend.setMultitaskOverlay(false,false,stopKeyBinding);multitask=false;}if(multitaskControls!=null)multitaskControls.dispose();if(multitaskWindow!=null)multitaskWindow.dispose();backend.setSnapshotHandler(ignored -> {});
        if(exitHideTimer!=null)exitHideTimer.stop();if(exitHoverTimer!=null)exitHoverTimer.stop();if(snapshotTimeout!=null)snapshotTimeout.stop();if(gallery!=null)gallery.dispose();
        if (workspaceTimer != null) workspaceTimer.stop();
        saveWorkspace();
        captureClosing = true;
        backend.setFrameReadyHandler(null);
        if (performanceTimer != null) performanceTimer.stop();
        if (windowFrameTimer != null) windowFrameTimer.stop();
        windowFrameWorker.shutdownNow();
        windowFrameReader.close();
        for (DisplayCapture capture : displayCaptures) capture.close();
        backend.close();
        try (var files = Files.list(windowFramesDirectory)) { files.forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); }
        catch (Exception ignored) { }
        try { Files.deleteIfExists(windowFramesDirectory); } catch (Exception ignored) { }
    }

    private void bindShortcuts() {
        KeyStroke toggle = KeyStroke.getKeyStroke("ctrl shift SPACE");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(toggle, "toggle-multitask");
        root.getActionMap().put("toggle-multitask", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { setMultitask(!multitask); } });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "exit-multitask");
        root.getActionMap().put("exit-multitask", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { if (multitask) setMultitask(false); } });
    }

    private void saveWorkspace() {
        saveSidebarWidths();
        if (!workspaceWritable) return;
        try { workspaceStore.save(views, activeView); }
        catch (java.io.IOException error) { backendStatus.setText("Workspace save failed: " + error.getMessage()); }
    }
    public void setBackendStatus(String status) { SwingUtilities.invokeLater(() -> {
        backendStatus.setText(status);
        if (backend.isReady() && !restoredTargetsRequested) {
            restoredTargetsRequested = true;
            if (app.multimin.backend.PlatformSupport.isMac()) checkMacPermissionsOnLaunch();
            backend.requestWindowList(targets -> SwingUtilities.invokeLater(() -> {
                for(View view:views) for(int i=0;i<view.sources.size();i++) {
                    Source source=view.sources.get(i);
                    if(!source.usesWindowTarget() || source.captureFramePath!=null) continue;
                    if(app.multimin.backend.PlatformSupport.isPortable() && "portal".equals(source.windowProcess)){
                        // A saved portal grant can reconnect without the picker; without one, wait for the user.
                        if(source.portalRestoreToken.isBlank()){source.activeCaptureBackend="Reselect source in desktop picker";continue;}
                        source.captureFramePath=windowFramesDirectory.resolve(java.util.UUID.randomUUID()+".bmp").toString();
                        backend.registerWindowCapture(source.name,source.inputTarget,source.captureFramePath,0,source.captureMethod,source.captureCursor,source.portalRestoreToken);
                        continue;
                    }
                    if (source.kind==Source.Kind.GAME && !app.multimin.backend.PlatformSupport.isPortable() && !Boolean.getBoolean("multimin.dev.gameCapture")) { source.activeCaptureBackend="Experimental source unavailable in this build";continue; }
                    var matches=targets.stream().filter(t -> t.title.equals(source.inputTarget) && !source.windowProcess.isBlank() && source.windowProcess.equalsIgnoreCase(t.process)).toList();
                    if(matches.size()!=1){source.activeCaptureBackend="Reselect target: window missing or ambiguous";continue;}
                    source.nativeWindowHandle=matches.get(0).hwnd;
                    source.captureFramePath=windowFramesDirectory.resolve(java.util.UUID.randomUUID()+".bmp").toString();
                    backend.registerWindowCapture(source.name,source.inputTarget,source.captureFramePath,source.nativeWindowHandle,source.captureMethod,source.captureCursor,source.portalRestoreToken);
                }
                preview.repaint();
            }));
        }
    }); }

    // ---- macOS permissions -------------------------------------------------
    private JDialog macPermissionDialog;
    private boolean macPermissionsChecked;
    /** At launch: ask the backend once; open the guide only if something is missing. */
    private void checkMacPermissionsOnLaunch() {
        backend.setMacPermissionHandler((screen, accessibility, input) -> SwingUtilities.invokeLater(() -> {
            if (macPermissionsChecked) return;
            macPermissionsChecked = true;
            backend.setMacPermissionHandler(null);
            if (!screen || !accessibility) showMacPermissions();
        }));
        backend.send("{\"command\":\"mac_permissions\"}");
    }
    /**
     * Guided window: macOS lets only the user switch these on, so each row shows
     * the status, an Allow button that brings up macOS's prompt and the exact
     * Settings list, and ticks itself off as soon as the switch is on.
     */
    void showMacPermissions() {
        if (!app.multimin.backend.PlatformSupport.isMac()) return;
        if (macPermissionDialog != null && macPermissionDialog.isDisplayable()) { macPermissionDialog.toFront(); return; }
        JDialog dialog = dialog("MultiMini needs permission"); dialog.setModal(false); macPermissionDialog = dialog;
        JPanel body = dialogBody();
        JLabel intro = new JLabel("<html><b>macOS asks you to allow a few things once.</b><br>Click Allow, then turn on <b>MultiMini</b> in the list that opens. This window updates by itself.</html>");
        intro.setForeground(palette.text); body.add(intro, BorderLayout.NORTH);
        JPanel rows = transparent(new GridLayout(0, 1, 0, 10));
        String[][] items = {
            {"screen", "Screen Recording", "Show the windows and displays you add"},
            {"accessibility", "Accessibility", "Hotkeys, and clicks/keys reaching the app you control"},
            {"input", "Input Monitoring", "Optional: only if hotkeys still don't work"}};
        java.util.Map<String, JLabel> states = new java.util.HashMap<>();
        java.util.Map<String, JButton> buttons = new java.util.HashMap<>();
        for (String[] item : items) {
            JPanel row = transparent(new BorderLayout(10, 0));
            JLabel state = new JLabel("…"); state.setFont(state.getFont().deriveFont(Font.BOLD, 16f)); state.setPreferredSize(new Dimension(22, 22));
            JLabel text = new JLabel("<html><b>" + item[1] + "</b><br><span style='font-size:90%'>" + item[2] + "</span></html>"); text.setForeground(palette.text);
            JButton allow = toolButton("Allow", "Open the macOS prompt and Settings for " + item[1]);
            allow.addActionListener(event -> backend.send("{\"command\":\"mac_request_permission\",\"kind\":\"" + item[0] + "\"}"));
            row.add(state, BorderLayout.WEST); row.add(text, BorderLayout.CENTER); row.add(allow, BorderLayout.EAST);
            rows.add(row); states.put(item[0], state); buttons.put(item[0], allow);
        }
        body.add(rows, BorderLayout.CENTER);
        JLabel hint = muted("Screen Recording takes effect after a restart.");
        JButton later = toolButton("Later", "Close this window");
        JButton restart = primaryButton("Restart MultiMini");
        restart.setEnabled(false);
        later.addActionListener(event -> dialog.dispose());
        restart.addActionListener(event -> restartMacApp());
        JPanel actions = transparent(new BorderLayout(8, 0)); actions.add(hint, BorderLayout.CENTER);
        JPanel buttonsRow = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); buttonsRow.add(later); buttonsRow.add(restart); actions.add(buttonsRow, BorderLayout.EAST);
        body.add(actions, BorderLayout.SOUTH);
        boolean[] firstStatus = {true}; boolean[] startedScreen = {false};
        backend.setMacPermissionHandler((screen, accessibility, input) -> SwingUtilities.invokeLater(() -> {
            if (!dialog.isDisplayable()) return;
            if (firstStatus[0]) { firstStatus[0] = false; startedScreen[0] = screen; }
            boolean[] granted = {screen, accessibility, input};
            for (int i = 0; i < items.length; i++) {
                JLabel state = states.get(items[i][0]);
                state.setText(granted[i] ? "✓" : "✕");
                state.setForeground(granted[i] ? new Color(64, 190, 120) : new Color(230, 90, 90));
                buttons.get(items[i][0]).setVisible(!granted[i]);
            }
            boolean ready = screen && accessibility;
            restart.setEnabled(ready);
            hint.setText(ready ? (startedScreen[0] ? "All set. You can close this window." : "All set. Restart once so screen capture can start.")
                : "Screen Recording takes effect after a restart.");
        }));
        javax.swing.Timer poll = new javax.swing.Timer(1500, event -> backend.send("{\"command\":\"mac_permissions\"}"));
        poll.setInitialDelay(0); poll.start();
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { poll.stop(); backend.setMacPermissionHandler(null); } });
        dialog.setContentPane(body); dialog.setSize(520, 330); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
    /** Save, start a fresh copy of the app bundle after this one exits, and quit. */
    private void restartMacApp() {
        try {
            java.nio.file.Path bundle = MacUpdater.appBundle();
            saveWorkspace();
            new ProcessBuilder("/bin/sh", "-c", "sleep 1; /usr/bin/open -n \"$0\"", bundle.toString()).start();
            shutdownServices(); dispose(); System.exit(0);
        } catch (Exception error) {
            showNotice("Restart MultiMini", "Quit MultiMini (Command+Q) and open it again so the new permissions take effect.");
        }
    }

    private void showNotice(String title, String message) {
        JDialog dialog = dialog(title); JPanel body = dialogBody();
        JTextArea text = new JTextArea(message); text.setWrapStyleWord(true); text.setLineWrap(true); text.setEditable(false); text.setFocusable(false); text.setOpaque(false); text.setForeground(palette.text); text.setFont(UIManager.getFont("Label.font"));
        body.add(text, BorderLayout.CENTER); JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0)); JButton ok = toolButton("OK", "Close"); ok.addActionListener(event -> dialog.dispose()); actions.add(ok); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(390, 165); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private String promptText(String title, String prompt, String initial) {
        JDialog dialog = dialog(title); JPanel body = dialogBody(); body.add(new JLabel(prompt), BorderLayout.NORTH);
        JTextField field = new JTextField(initial, 24); field.getAccessibleContext().setAccessibleName(prompt); field.setBackground(palette.controlSurface()); field.setForeground(palette.text); field.setCaretColor(palette.text); field.setSelectionColor(palette.accent); field.setSelectedTextColor(palette.selectionText()); field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line), new EmptyBorder(6, 8, 6, 8))); body.add(field, BorderLayout.CENTER);
        final String[] result = {null}; JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); JButton cancel = toolButton("Cancel", "Cancel"); cancel.addActionListener(event -> dialog.dispose()); JButton save = primaryButton("Save"); save.addActionListener(event -> { result[0] = field.getText(); dialog.dispose(); }); actions.add(cancel); actions.add(save); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(390, 175); dialog.setLocationRelativeTo(this); dialog.setVisible(true); return result[0];
    }

    private Source.Kind chooseSourceKind() {
        JDialog dialog = dialog("Add source"); JPanel body = dialogBody(); JPanel intro = transparent(new GridLayout(0, 1, 0, 2)); JLabel introTitle = new JLabel("Add a source"); introTitle.setFont(Theme.title(17f)); introTitle.setForeground(palette.text); intro.add(introTitle); intro.add(muted("Choose what to show in this view")); body.add(intro, BorderLayout.NORTH); JPanel choices = transparent(new GridLayout(0, 1, 0, 8)); final Source.Kind[] result = {null};
        for (Source.Kind kind : Source.Kind.values()) { if (kind == Source.Kind.VIDEO || (kind == Source.Kind.GAME && !app.multimin.backend.PlatformSupport.isPortable() && !Boolean.getBoolean("multimin.dev.gameCapture"))) continue; String blurb = switch (kind) { case DISPLAY -> "A whole monitor, live"; case WINDOW -> "One app window, even when covered"; case GAME -> "A game window, tuned for motion"; case IMAGE -> "A picture from your computer"; default -> ""; };
            Color tint = SourceCellRenderer.kindColor(kind);
            ModernButton choice = new ModernButton("<html><span style='font-size:110%'>" + kind.label + "</span><br><span style='color:" + String.format("#%06x", palette.muted.getRGB() & 0xFFFFFF) + "'>" + blurb + "</span></html>", new VectorIcon(VectorIcon.forSource(kind), 20, Palette.blend(tint, palette.text, palette.isLight() ? 0f : 0.2f)), palette, false);
            choice.setToolTipText("Add " + kind.label.toLowerCase()); choice.setIconTextGap(14); choice.setBorder(new EmptyBorder(10, 16, 10, 14)); choice.setHorizontalAlignment(SwingConstants.LEFT); choice.addActionListener(event -> { result[0] = kind; dialog.dispose(); }); choices.add(choice); }
        body.add(choices, BorderLayout.CENTER); JButton cancel = toolButton("Cancel", "Close"); cancel.addActionListener(event -> dialog.dispose()); JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(420, 150 + choices.getComponentCount() * 66); dialog.setLocationRelativeTo(this); dialog.setVisible(true); return result[0];
    }

    private JDialog dialog(String title) { JDialog dialog = new JDialog(this, title, true); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.getContentPane().setBackground(palette.background); return dialog; }
    private JPanel dialogBody() { JPanel body = new GlassPanel(palette); body.setLayout(new BorderLayout(14, 18)); body.setBorder(new EmptyBorder(22, 22, 18, 22)); return body; }
    private JButton quietButton(String text) { Icon icon = null; String label = text; if ("＋".equals(text)) { icon = new VectorIcon(VectorIcon.Type.ADD, 12, ()->palette.text); label = ""; } else if ("Snapshot".equals(text)) icon = new VectorIcon(VectorIcon.Type.CAMERA, 13, ()->palette.text); ModernButton button = new ModernButton(label, icon, palette, false); button.putClientProperty("appearance.ghost", true); button.setBorder(new EmptyBorder(6, 12, 6, 12)); return button; }
    private JButton primaryButton(String text) { Icon icon = text.contains("Add") ? new VectorIcon(VectorIcon.Type.ADD, 12, palette::selectionText) : text.contains("multitasking") ? new VectorIcon(VectorIcon.Type.PLAY, 12, palette::selectionText) : null; return new ModernButton(text.replace("＋ ", ""), icon, palette, true); }
    private JButton toolButton(String text, String tooltip) { VectorIcon.Type type = null; if ("＋".equals(text)) type = VectorIcon.Type.ADD; else if ("−".equals(text)) type = VectorIcon.Type.REMOVE; else if ("⧉".equals(text)) type = VectorIcon.Type.COPY; else if ("◉".equals(text)) type = VectorIcon.Type.EYE; else if ("↑".equals(text)) type = VectorIcon.Type.UP; else if ("↓".equals(text)) type = VectorIcon.Type.DOWN; Icon icon = type == null ? null : new VectorIcon(type, 13, ()->palette.text); ModernButton button = new ModernButton(type == null ? text : "", icon, palette, false); button.setToolTipText(tooltip); return button; }
    private JButton compactAction(String label, VectorIcon.Type icon) {
        ModernButton button = new ModernButton(label, new VectorIcon(icon, 16, () -> palette.text), palette, false);
        button.putClientProperty("appearance.ghost", true);
        button.setVerticalTextPosition(SwingConstants.BOTTOM); button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setIconTextGap(5); button.setBorder(new EmptyBorder(8, 2, 7, 2));
        button.setFont(Theme.regular(11.5f)); button.setToolTipText(label);
        return button;
    }
    private JLabel centeredSection(String text) { JLabel label=sectionTitle(text); label.setHorizontalAlignment(SwingConstants.LEFT); label.setMaximumSize(new Dimension(Integer.MAX_VALUE,34)); label.setAlignmentX(Component.CENTER_ALIGNMENT); return label; }
    private JLabel sectionTitle(String text) { JLabel label = sectionLabel(text); label.setBorder(new EmptyBorder(8, 0, 6, 0)); return label; }
    /** Settings navigation icons: gear, mouse, lightning bolt and question mark (16 px box). */
    private static void paintSettingsIcon(Graphics2D g, String title, int x, int y, Color color) {
        g = (Graphics2D) g.create(); g.translate(x, y); g.setColor(color);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (title.startsWith("Controls")) {
            g.draw(Theme.round(3.5f, 1f, 9f, 14f, 9f));          // mouse body
            g.drawLine(8, 1, 8, 6);                                 // button split
            g.fill(Theme.round(7f, 3.5f, 2f, 3f, 2f));             // scroll wheel
        } else if (title.startsWith("Performance")) {
            java.awt.geom.Path2D bolt = new java.awt.geom.Path2D.Float();
            bolt.moveTo(9.5, 0.5); bolt.lineTo(2.5, 9); bolt.lineTo(7.5, 9); bolt.lineTo(6, 15.5);
            bolt.lineTo(13.5, 6.5); bolt.lineTo(8.5, 6.5); bolt.closePath();
            g.fill(bolt);
        } else if (title.startsWith("Help")) {
            g.drawOval(1, 1, 14, 14);
            java.awt.geom.Path2D mark = new java.awt.geom.Path2D.Float();
            mark.moveTo(5.8, 6.2); mark.curveTo(5.8, 3.6, 10.4, 3.6, 10.4, 6.2); mark.curveTo(10.4, 8, 8, 8.2, 8, 10);
            g.draw(mark); g.fill(new java.awt.geom.Ellipse2D.Float(7f, 11.6f, 2.2f, 2.2f));
        } else {
            new VectorIcon(VectorIcon.Type.GEAR, 16, color).paintIcon(null, g, 0, 0);
        }
        g.dispose();
    }
    /** Small uppercase eyebrow label used for section headings. */
    private JLabel sectionLabel(String text) { JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT)); label.setForeground(palette.muted); label.setFont(Theme.eyebrow(10.5f)); return label; }
    /** Transparent container painted as a rounded card behind its children. */
    private JPanel cardPanel(LayoutManager layout) {
        JPanel panel = new JPanel(layout) { @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.quality(graphics); g.setColor(palette.card()); g.fill(Theme.round(0, 0, getWidth(), getHeight(), Theme.RADIUS));
            g.setColor(palette.hairline()); g.draw(Theme.round(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, Theme.RADIUS)); g.dispose(); } };
        panel.setOpaque(false); return panel;
    }
    private JLabel propertyLabel(String text) { JLabel label = new JLabel(text); label.setForeground(palette.text); label.setFont(Theme.regular(13f)); return label; }
    private JPanel percentageEditor(JSlider slider) {
        CardLayout cards = new CardLayout();
        JPanel holder = transparent(cards);
        holder.setPreferredSize(new Dimension(62, 25));
        String accessibleLabel=slider==opacity?"Opacity":"Size";
        slider.getAccessibleContext().setAccessibleName(accessibleLabel);
        JButton value = new ModernButton(slider.getValue() + "%", null, palette, false);
        value.putClientProperty("appearance.textOnly",true);
        value.getAccessibleContext().setAccessibleName(accessibleLabel + " percentage");
        value.setToolTipText("Click to enter a value from " + slider.getMinimum() + "% to " + slider.getMaximum() + "%");
        value.setForeground(palette.muted); value.setBorder(new EmptyBorder(0, 2, 0, 0));
        value.setOpaque(false); value.setContentAreaFilled(false); value.setFocusPainted(false);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        JTextField editor = new JTextField();
        editor.getAccessibleContext().setAccessibleName(accessibleLabel + " percentage");
        editor.setHorizontalAlignment(SwingConstants.RIGHT);
        editor.setBackground(palette.controlSurface()); editor.setForeground(palette.text);
        editor.setCaretColor(palette.text); editor.setBorder(BorderFactory.createLineBorder(palette.accent));
        percentageFields.add(editor);
        holder.add(value, "value"); holder.add(editor, "editor");
        boolean[] cancelled = {false};
        Runnable commit = () -> {
            if (cancelled[0]) { cancelled[0] = false; return; }
            try {
                int parsed = Integer.parseInt(editor.getText().trim().replace("%", ""));
                if (parsed >= slider.getMinimum() && parsed <= slider.getMaximum()) slider.setValue(parsed);
                else Toolkit.getDefaultToolkit().beep();
            } catch (NumberFormatException error) { Toolkit.getDefaultToolkit().beep(); }
            value.setText(slider.getValue() + "%"); cards.show(holder, "value");
        };
        value.addActionListener(event -> { editor.setText(Integer.toString(slider.getValue())); cards.show(holder, "editor"); SwingUtilities.invokeLater(() -> { editor.requestFocusInWindow(); editor.selectAll(); }); });
        editor.addActionListener(event -> commit.run());
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-edit");
        editor.getActionMap().put("cancel-edit", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent event) { cancelled[0] = true; cards.show(holder, "value"); value.requestFocusInWindow(); } });
        editor.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent event) { if (editor.isVisible()) commit.run(); else cancelled[0] = false; } });
        slider.addChangeListener(event -> { value.setText(slider.getValue() + "%"); if (!editor.isVisible()) editor.setText(Integer.toString(slider.getValue())); });
        return holder;
    }
    private void styleCombo(JComboBox<?> combo) {
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override protected JButton createArrowButton(){
                JButton arrow=new JButton(){@Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(palette.muted);g.setStroke(new BasicStroke(1.5f));int x=getWidth()/2,y=getHeight()/2;g.drawLine(x-4,y-2,x,y+2);g.drawLine(x,y+2,x+4,y-2);g.dispose();}};
                arrow.setBorder(BorderFactory.createEmptyBorder());arrow.setContentAreaFilled(false);arrow.setFocusable(false);arrow.setPreferredSize(new Dimension(28,28));return arrow;
            }
            @Override public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus){}
            @Override public void paint(Graphics graphics,JComponent c){Graphics2D g=Theme.quality(graphics);g.setColor(palette.controlSurface());g.fill(Theme.round(0,0,c.getWidth(),c.getHeight(),8));g.dispose();super.paint(graphics,c);}
            @Override protected javax.swing.plaf.basic.ComboPopup createPopup(){return new javax.swing.plaf.basic.BasicComboPopup(comboBox){@Override protected JScrollPane createScroller(){JScrollPane pane=super.createScroller();styleScrollBar(pane.getVerticalScrollBar());pane.setBorder(BorderFactory.createEmptyBorder());return pane;}};}
        });
        combo.setOpaque(false); combo.setBackground(palette.controlSurface()); combo.setForeground(palette.text); combo.setBorder(new ControlBorder(palette)); combo.setFont(Theme.regular(13f));
        combo.setRenderer((list, value, index, selected, focus) -> {
            JLabel item = new JLabel(value == null ? "" : value.toString()); item.setOpaque(index >= 0); item.setBorder(new EmptyBorder(6, 10, 6, 10)); item.setFont(Theme.regular(13f));
            item.setBackground(selected && index >= 0 ? palette.accentTint(palette.controlSurface(), 0.30f) : palette.controlSurface());
            item.setForeground(palette.text); return item;
        });
    }
    private JLabel muted(String text) { JLabel label = new JLabel(text); label.setForeground(palette.muted); label.setFont(Theme.regular(11f)); return label; }
    private JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
}
