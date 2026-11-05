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
    private final JLabel layerTitle = new JLabel("No source selected", SwingConstants.CENTER);
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
    private String stopKeyBinding = preferences.getString("stopKey", "END");
    private String pendingWindowSourceName;
    private boolean pendingWindowNameManual;
    private String pendingImageName;
    private String swapKeyBinding = preferences.getString("swapInputKey", "RIGHT_CTRL");
    private String layerSwapKeyBinding = preferences.getString("swapLayersKey", "PAUSE");
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
    private String screenshotKey=preferences.getString("screenshotKey","INSERT"), regionScreenshotKey=preferences.getString("regionScreenshotKey","CTRL+SHIFT+F12");
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
            protected java.awt.image.BufferedImage doInBackground()throws Exception{Rectangle physical=PreviewPanel.toPhysicalScreenBounds(bounds,screen);long target=app.multimin.backend.NativeWindowCompositor.destination(physical.x,physical.y,physical.width,physical.height);int affinity=app.multimin.backend.NativeWindowCompositor.captureAffinity(target,0);if(affinity<0)throw new IllegalStateException("Could not capture multitask composition");java.awt.image.MultiResolutionImage capture;try{capture=new Robot(screen.getDevice()).createMultiResolutionScreenCapture(bounds);}finally{app.multimin.backend.NativeWindowCompositor.captureAffinity(target,affinity);}Image best=capture.getResolutionVariants().stream().max(java.util.Comparator.comparingLong(i->(long)i.getWidth(null)*i.getHeight(null))).orElseThrow();java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(best.getWidth(null),best.getHeight(null),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.drawImage(best,0,0,null);g.dispose();return image;}
            protected void done(){snapshotPending=false;if(!multitask)return;try{var image=get();if(region){backend.send("{\"command\":\"screenshot_selection\",\"enabled\":true}");screenshotSelection=new ScreenshotSelection(multitaskWindow,bounds,image,palette,MainWindow.this::saveMultitaskScreenshot,()->{screenshotSelection=null;if(multitask&&multitaskWindow!=null){multitaskWindow.setVisible(true);multitaskWindow.toFront();}backend.send("{\"command\":\"screenshot_selection\",\"enabled\":false}");if(multitask&&exitHoverTimer!=null)exitHoverTimer.start();});multitaskWindow.setVisible(false);screenshotSelection.setVisible(true);screenshotSelection.toFront();screenshotSelection.requestFocus();}else{saveMultitaskScreenshot(image);exitHoverTimer.start();}}catch(Exception | LinkageError error){if(screenshotSelection!=null){screenshotSelection.dispose();screenshotSelection=null;}if(multitask&&multitaskWindow!=null)multitaskWindow.setVisible(true);backend.send("{\"command\":\"screenshot_selection\",\"enabled\":false}");if(exitHoverTimer!=null)exitHoverTimer.start();showNotice("Screenshot","Could not capture: "+error.getMessage());}}
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
        if(!backend.isReady()){snapshotPending=true;captureSnapshotFallback();return;}
        snapshotPending=true;
        backend.setSnapshotHandler(result -> SwingUtilities.invokeLater(()->{
            if(shuttingDown)return; snapshotPending=false;if(snapshotTimeout!=null)snapshotTimeout.stop();
            if(result.startsWith("ERROR:")){snapshotPending=true;captureSnapshotFallback();}
            else {openGallery();gallery.reload(Path.of(result));}
        }));
        snapshotTimeout=new javax.swing.Timer(30000,e->{snapshotPending=false;showNotice("Screenshot","Capture did not respond. Please try again.");});snapshotTimeout.setRepeats(false);snapshotTimeout.start();backend.requestSnapshot();
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
    private void revealExit() { if(multitaskControls==null)return;boolean wasVisible=multitaskControls.isVisible();multitaskControls.setVisible(true);if(!wasVisible)overlayControlRegion(true);if(exitHideTimer!=null)exitHideTimer.restart(); }

    private View activeView;
    private boolean multitask;
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
        backend.setQuickSwapHandler(event -> SwingUtilities.invokeLater(() -> {
            if ("screenshot_full".equals(event) && multitask) captureMultitaskScreenshot(false);
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
            if (multitask && !backend.isReady() && event.getID() == KeyEvent.KEY_PRESSED && keyBindingForEvent(event) != null && keyBindingForEvent(event).equals(swapKeyBinding)) {
                cycleQuickSwapTarget(); return true;
            }
            return false;
        });
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
    public void checkForUpdatesOnLaunch(){GithubUpdater.checkOnLaunch(this,()->{if(!workspaceWritable)throw new IllegalStateException("Recover your workspace before updating");try{workspaceStore.save(views,activeView);}catch(java.io.IOException error){throw new IllegalStateException("Workspace could not be saved",error);}},()->{shutdownServices();dispose();System.exit(0);});}
    private void buildUi() {
        root.setBackground(palette.background); configuration.setOpaque(false); topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(new EmptyBorder(10, 18, 10, 18));
        JLabel brand = new JLabel("MultiMini"); brand.setFont(brand.getFont().deriveFont(Font.BOLD, 15f));
        if (appLogo != null) { brand.setIcon(appLogo); brand.setIconTextGap(10); }
        topBar.add(brand); topBar.add(Box.createHorizontalStrut(25));
        for (View view : views) { JButton tab = quietButton(view.name); tab.addActionListener(event -> switchView(view)); installViewMenu(tab,view); viewButtons.put(view, tab); topBar.add(tab); }
        JButton addView = quietButton("＋"); addView.addActionListener(event -> addView()); topBar.add(addView); topBar.add(Box.createHorizontalGlue());
        sourcesPanelRef = sourcesPanel(); propertiesPanelRef = propertiesPanel();
        configuration.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcesPanelRef, centerPanel()),
            propertiesPanelRef), BorderLayout.CENTER);
        configureSplitters((JSplitPane) configuration.getComponent(0));
        root.add(topBar, BorderLayout.NORTH); root.add(configuration, BorderLayout.CENTER); setContentPane(root);
        sourceList.setSelectedIndex(0); syncSelection();
    }

    private JPanel sourcesPanel() {
        GlassPanel panel = new GlassPanel(palette); panel.setPreferredSize(new Dimension(265, 0)); panel.setMinimumSize(new Dimension(70, 0)); panel.setLayout(new BorderLayout(0, 10)); panel.setBorder(new EmptyBorder(12, 8, 8, 8));
        JPanel heading = transparent(new BorderLayout()); JPanel names = transparent(new GridLayout(0, 1)); names.add(muted("CURRENT VIEW")); viewTitle.setFont(viewTitle.getFont().deriveFont(Font.BOLD, 23f)); names.add(viewTitle); heading.add(names); JButton add = primaryButton("＋ Add"); add.setPreferredSize(new Dimension(76, 32)); add.addActionListener(event -> addSource()); JPanel addWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 4)); addWrap.add(add); heading.add(addWrap, BorderLayout.EAST); panel.add(heading, BorderLayout.NORTH);
        sourceList.setModel(activeView.sources); sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); sourceList.setFixedCellHeight(46); sourceList.setDragEnabled(true); sourceList.setDropMode(DropMode.INSERT); sourceList.setTransferHandler(new SourceReorderHandler()); sourceList.setCellRenderer(new SourceCellRenderer(palette)); sourceList.addListSelectionListener(event -> syncSelection());
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
        JScrollPane scroller = new JScrollPane(sourceList); scroller.setBorder(BorderFactory.createLineBorder(palette.line));
        styleScrollBar(scroller.getVerticalScrollBar()); styleScrollBar(scroller.getHorizontalScrollBar());
        panel.add(scroller, BorderLayout.CENTER);
        JPanel footer = transparent(new GridLayout(0, 1, 0, 6)); JPanel controls = transparent(new GridLayout(1, 0, 4, 0));
        JButton plus = toolButton("＋", "Add source"); plus.addActionListener(event -> addSource()); controls.add(plus);
        JButton minus = toolButton("−", "Remove source"); minus.addActionListener(event -> removeSelected()); controls.add(minus);
        JButton duplicate = toolButton("⧉", "Duplicate source"); duplicate.addActionListener(event -> duplicateSelected()); controls.add(duplicate);
        JButton visibility = toolButton("◉", "Show or hide selected source"); visibility.addActionListener(event -> toggleSelectedVisibility()); controls.add(visibility);
        JButton up = toolButton("↑", "Move source up"); up.addActionListener(event -> moveSelected(-1)); controls.add(up);
        JButton down = toolButton("↓", "Move source down"); down.addActionListener(event -> moveSelected(1)); controls.add(down);
        footer.add(controls);
        JTextArea sourceHint=new JTextArea("Drag to reorder · Double-click to configure\nF2 to rename");sourceHint.setOpaque(false);sourceHint.setEditable(false);sourceHint.setFocusable(false);sourceHint.setLineWrap(true);sourceHint.setWrapStyleWord(true);sourceHint.setForeground(palette.muted);sourceHint.setFont(UIManager.getFont("Label.font").deriveFont(11f));footer.add(sourceHint);
        inputKeyHint.setForeground(palette.muted); layerKeyHint.setForeground(palette.muted); updateHotkeyHints();
        footer.add(inputKeyHint); footer.add(layerKeyHint);
        panel.add(footer, BorderLayout.SOUTH); switchView(activeView); return panel;
    }

    private JPanel centerPanel() {
        canvasPanel.setMinimumSize(new Dimension(100,0));canvasPanel.setOpaque(false); JPanel toolbar = transparent(new BorderLayout()); toolbar.setBorder(new EmptyBorder(15, 22, 8, 22)); JLabel title = new JLabel("PREVIEW   ·   current composition"); title.setForeground(palette.muted); toolbar.add(title); canvasPanel.add(toolbar, BorderLayout.NORTH); canvasPanel.add(preview, BorderLayout.CENTER); backendStatus.setForeground(palette.muted); backendStatus.setBorder(new EmptyBorder(6, 22, 2, 22)); performanceStatus.setForeground(palette.muted); performanceStatus.setFont(performanceStatus.getFont().deriveFont(10f)); performanceStatus.setBorder(new EmptyBorder(0, 22, 7, 22)); debugPanel.setOpaque(false); debugPanel.add(backendStatus); debugPanel.add(performanceStatus); debugPanel.setVisible(preferences.getBoolean("showDebug", preferences.getBoolean("showPerformanceStats", false))); performanceStatus.setVisible(debugPanel.isVisible()); canvasPanel.add(debugPanel, BorderLayout.SOUTH); return canvasPanel;
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
        int right=Math.clamp(preferences.getInt("rightPanelWidth",300),70,Math.max(70,sidebarOuter.getWidth()-180));
        sidebarOuter.setDividerLocation(sidebarOuter.getWidth()-right-sidebarOuter.getDividerSize());sidebarOuter.doLayout();
        int left=Math.clamp(preferences.getInt("leftPanelWidth",265),70,Math.max(70,sidebarInner.getWidth()-110));sidebarInner.setDividerLocation(left);sidebarInner.doLayout();sidebarWidthsRestored=true;
    }
    private void configureSplitters(JSplitPane outer) {
        JSplitPane inner = (JSplitPane) outer.getLeftComponent();sidebarOuter=outer;sidebarInner=inner;
        sidebarWidthSaveTimer=new javax.swing.Timer(350,event->saveSidebarWidths());sidebarWidthSaveTimer.setRepeats(false);
        java.beans.PropertyChangeListener widthChanged=event->{if(sidebarWidthsRestored&&configuration.isShowing())sidebarWidthSaveTimer.restart();};inner.addPropertyChangeListener("dividerLocation",widthChanged);outer.addPropertyChangeListener("dividerLocation",widthChanged);
        configuration.addHierarchyListener(event->{if((event.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&configuration.isShowing())SwingUtilities.invokeLater(this::restoreSidebarWidths);});
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
        panel.setBorder(new EmptyBorder(16, 16, 14, 16));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel selectedHeading=muted("SELECTED SOURCE"); selectedHeading.setMaximumSize(new Dimension(Integer.MAX_VALUE,24)); selectedHeading.setHorizontalAlignment(SwingConstants.LEFT); selectedHeading.setAlignmentX(Component.CENTER_ALIGNMENT); panel.add(selectedHeading);
        layerTitle.setFont(new Font(Font.DIALOG,Font.BOLD,23));
        layerTitle.setOpaque(false); layerTitle.setForeground(palette.text);
        layerTitle.setBorder(new EmptyBorder(2,2,2,2));
        layerTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        layerTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        layerTitle.setOpaque(false);
        panel.add(layerTitle);
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
        JLabel hint = muted("<html>Drag in the preview to move. Drag edge handles to resize; hold Shift to stretch.</html>");
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38)); hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(hint);
        panel.add(Box.createVerticalGlue());
        JPanel modeActions = new GlassPanel(palette); modeActions.setLayout(new GridLayout(0, 1, 0, 8)); modeActions.setBorder(new EmptyBorder(12, 12, 12, 12)); modeActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200)); modeActions.setAlignmentX(Component.CENTER_ALIGNMENT);
        modeActions.add(muted("MULTITASK")); JButton snapshot = quietButton("Snapshot"); snapshot.addActionListener(event -> { takeSnapshot(); }); modeActions.add(snapshot); JButton galleryButton=quietButton("Gallery");galleryButton.addActionListener(event->openGallery());modeActions.add(galleryButton);
        JButton start = primaryButton("Start multitasking"); start.addActionListener(event -> setMultitask(true)); modeActions.add(start);
        JButton settings = quietButton("Settings"); settings.addActionListener(event -> showMultitaskSettings()); modeActions.add(settings);
        panel.add(modeActions);
        panel.setPreferredSize(new Dimension(300, 0)); panel.setMinimumSize(new Dimension(70, 0));
        return panel;
    }

    private void switchView(View view) { if (multitask) return; activeView = view; for (Map.Entry<View, AbstractButton> entry : viewButtons.entrySet()) entry.getValue().setSelected(entry.getKey() == view); viewTitle.setText(view.name); sourceList.setModel(view.sources); sourceList.setSelectedIndex(Math.min(1, view.sources.size() - 1)); preview.setView(view); }
    private void syncSelection() { Source source = sourceList.getSelectedValue(); layerTitle.setText(source == null ? "No source selected" : source.name); layerTitle.setFont(new Font(Font.DIALOG,Font.BOLD,23)); layerTitle.setForeground(palette.text); if (source != null) { opacity.setValue(source.opacity); scale.setValue(source.scale); preview.setSelectedIndex(sourceList.getSelectedIndex()); } }
    private void setControlTarget(int index) {
        if (index < 0 || index >= activeView.sources.size()) return;
        activeView.controlSource = index;
        Source source = activeView.sources.get(index);
        String target = source.kind == Source.Kind.DISPLAY || source.inputTarget.isBlank() ? "desktop" : source.inputTarget;
        boolean desktopInput = source.kind == Source.Kind.DISPLAY;
        Rectangle mapped = new Rectangle(), excluded = new Rectangle();
        if (multitask && source.usesWindowTarget()) {
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
        backend.setInputTarget(target, source.nativeWindowHandle, mapped.x, mapped.y, mapped.width, mapped.height,
            excluded.x, excluded.y, excluded.width, excluded.height, desktopInput);
        multitaskInputLabel.setText("Input target: " + source.name); updateControlIndicator(); preview.repaint();
    }
    private void refreshNativeLayerLayout() {
        if (!multitask || multitaskWindow == null) return;
        // The preview owns the visual composition. Cutting out window sources
        // exposed their real HWNDs, so focusing an input target also changed
        // what was visible regardless of the source order.
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
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_ALT || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_META) return null;
        String key = KeyEvent.getKeyText(code).toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        StringBuilder binding = new StringBuilder();
        if ((event.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) binding.append("CTRL+");
        if ((event.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0) binding.append("SHIFT+");
        if ((event.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0) binding.append("ALT+");
        return binding.append(key).toString();
    }
    private String prettyKeyBinding(String binding) { return "RIGHT_CTRL".equals(binding) ? "Right Ctrl" : binding.replace("_", " ").replace("+", " + "); }
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
            if (source.visible && source.quickSwapInput && (source.kind == Source.Kind.DISPLAY || source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME)) targets.add(i);
        }
        if (targets.isEmpty()) { backendStatus.setText("Enable a source’s lightning bolt to use Right Ctrl input swap"); multitaskInputLabel.setText("Input target: no quick-swap sources enabled"); return; }
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
            Object mode = JOptionPane.showInputDialog(this,
                "Choose a backend. WGC captures game windows without graphics hooks.\nProtected or exclusive fullscreen games may be unsupported.\nThe controlled DX11 renderer is for testing purposes only.",
                "Experimental game capture", JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Game window — WGC compatibility", "Controlled DX11 renderer — testing only"},
                "Game window — WGC compatibility");
            if (mode == null) return;
            nativeTest = mode.toString().startsWith("Controlled");
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
            if (kind == Source.Kind.GAME) { source.captureMethod = "WGC"; source.quickSwapInput = true; source.controlColor = nextControlColor(source); }
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
        JDialog dialog = dialog("Select a window");
        JPanel body = dialogBody();
        JPanel heading = transparent(new GridLayout(0, 1, 0, 7));
        heading.add(sectionTitle("DETECTED WINDOWS"));
        heading.add(propertyLabel("Source name"));
        JTextField sourceName = new JTextField(); sourceName.setForeground(palette.text); sourceName.setBackground(palette.controlSurface()); sourceName.setOpaque(true); sourceName.setCaretColor(palette.text); sourceName.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line), new EmptyBorder(7, 9, 7, 9))); sourceName.setPreferredSize(new Dimension(0, 34)); heading.add(sourceName); body.add(heading, BorderLayout.NORTH);
        boolean[] manualName = {false}, settingSuggestedName = {false};
        sourceName.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { if (!settingSuggestedName[0]) manualName[0] = true; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        });
        JComboBox<PythonBridge.WindowTarget> windows = new JComboBox<>();
        styleCombo(windows); windows.setPreferredSize(new Dimension(0, 34)); windows.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        windows.addActionListener(event -> {
            PythonBridge.WindowTarget selected = (PythonBridge.WindowTarget) windows.getSelectedItem();
            if (selected != null && !manualName[0]) {
                settingSuggestedName[0] = true; sourceName.setText(automaticWindowSourceName(selected)); settingSuggestedName[0] = false;
            }
        });
        JLabel status = new JLabel("Scanning…"); status.setForeground(palette.muted);
        JButton refresh = toolButton("Scan again", "Refresh the list of open windows");
        Runnable scan = () -> {
            status.setText("Scanning open windows…"); refresh.setEnabled(false);
            backend.requestWindowList(found -> SwingUtilities.invokeLater(() -> {
                DefaultComboBoxModel<PythonBridge.WindowTarget> model = new DefaultComboBoxModel<>();
                for (PythonBridge.WindowTarget item : found) model.addElement(item);
                windows.setModel(model); refresh.setEnabled(true);
                status.setText(found.isEmpty() ? "No windows found. Check that the capture service is running." : found.size() + " windows found · choose one to capture");
            }));
        };
        refresh.addActionListener(event -> scan.run());
        JPanel row = transparent(new BorderLayout(8, 0)); row.add(windows, BorderLayout.CENTER); row.add(refresh, BorderLayout.EAST);
        JPanel center = transparent(new BorderLayout(0, 8)); center.add(row, BorderLayout.NORTH); center.add(status, BorderLayout.CENTER); body.add(center, BorderLayout.CENTER);
        PythonBridge.WindowTarget[] result = {null};
        JButton cancel = toolButton("Cancel", "Close without adding a source"); cancel.addActionListener(event -> dialog.dispose());
        JButton select = primaryButton("Capture selected window"); select.addActionListener(event -> { result[0] = (PythonBridge.WindowTarget) windows.getSelectedItem(); if (result[0] != null && !sourceName.getText().trim().isEmpty()) { pendingWindowSourceName = sourceName.getText().trim(); pendingWindowNameManual = manualName[0]; dialog.dispose(); } });
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.add(cancel); actions.add(select); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(600, 300); dialog.setMinimumSize(new Dimension(520, 280)); dialog.setLocationRelativeTo(this); scan.run(); dialog.setVisible(true);
        return result[0];
    }

    private int chooseDisplayDevice() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (devices.length == 0) return -1;
        JComboBox<String> choices = new JComboBox<>();
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
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13f)); tabs.setForeground(palette.text); tabs.setBackground(palette.panel);
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override protected void installDefaults(){super.installDefaults();tabInsets=new Insets(12,18,12,18);contentBorderInsets=new Insets(14,0,0,0);}
            @Override protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(selected?palette.controlSurface():palette.panel);g.fillRoundRect(x,y,w,h,8,8);if(selected){g.setColor(palette.accent);g.fillRect(x+12,y+h-3,w-24,3);}}
            @Override protected void paintTabBorder(Graphics g,int p,int i,int x,int y,int w,int h,boolean s){}
            @Override protected void paintContentBorder(Graphics g,int p,int s){}
            @Override protected void paintFocusIndicator(Graphics g,int p,Rectangle[] r,int i,Rectangle icon,Rectangle text,boolean s){}
        });
        JPanel fields = settingsPage(); tabs.addTab("General", fields);
        fields.setBorder(new EmptyBorder(2, 0, 8, 0));
        fields.add(settingsGroup("Application"));
        JLabel version = new JLabel(System.getProperty("jpackage.app-version", "Development")); version.setForeground(palette.text); version.setFont(version.getFont().deriveFont(Font.BOLD));
        fields.add(settingsRow("Build", Boolean.getBoolean("multimin.dev.gameCapture") ? "Experimental capture features enabled" : "Safety build", version));
        JButton updates = quietButton("Updates…");
        updates.addActionListener(event -> GithubUpdater.show(this, () -> { if(!workspaceWritable) throw new IllegalStateException("Recover your workspace before updating"); try { workspaceStore.save(views,activeView); } catch(java.io.IOException error) { throw new IllegalStateException("Workspace could not be saved",error); } }, () -> { shutdownServices(); dispose(); System.exit(0); }));
        fields.add(settingsRow("Updates", "Private downloads · preserves your workspace", updates));
        JButton legal = quietButton("Read agreement & privacy"); legal.addActionListener(event -> LegalConsent.showDocuments(this));
        fields.add(settingsRow("Publisher: Mansoor Alakoozai", "Canada · support through GitHub Issues", legal));
        fields.add(settingsGroup("Appearance"));
        JComboBox<String> stagedAccent = new JComboBox<>(new String[]{"Blue", "Red", "Orange", "Yellow", "Green", "Purple", "White", "Black", "Custom…"});
        styleCombo(stagedAccent); stagedAccent.setFocusable(false); stagedAccent.setSelectedItem(accentMode.getSelectedItem());
        stagedAccent.setPreferredSize(new Dimension(150, 30));
        JComboBox<String> themeChoice=new JComboBox<>(new String[]{"Dark","Light"});styleCombo(themeChoice);themeChoice.setSelectedItem(preferences.getString("themeMode","Dark"));fields.add(settingsRow("Theme","Application appearance",themeChoice));
        fields.add(settingsRow("Accent colour", "Highlights and active controls", stagedAccent));
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
        body.add(tabs, BorderLayout.CENTER);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); JButton cancel = toolButton("Cancel", "Close without applying"); cancel.addActionListener(event -> dialog.dispose());
        Runnable applySettings = () -> {
            recordingSwapKey = false; recordingStopKey = false; recordingLayerSwapKey = false;
            screenshotKey=(String)fullScreenshotButton.getClientProperty("binding");regionScreenshotKey=(String)regionScreenshotButton.getClientProperty("binding");preferences.putString("screenshotKey",screenshotKey);preferences.putString("regionScreenshotKey",regionScreenshotKey);configureScreenshotKeys();
            swapKeyBinding = stagedSwapKeyBinding; layerSwapKeyBinding = stagedLayerSwapKeyBinding; stopKeyBinding = stagedStopKeyBinding;
            preferences.putString("swapInputKey", swapKeyBinding); preferences.putString("swapLayersKey", layerSwapKeyBinding); preferences.putString("stopKey", stopKeyBinding);
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
        JButton apply = quietButton("Apply"); apply.addActionListener(event -> applySettings.run());
        JButton ok = primaryButton("OK"); ok.addActionListener(event -> { applySettings.run(); dialog.dispose(); });
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
        JLabel heading = new JLabel(title); heading.setForeground(palette.text);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        heading.setBorder(new EmptyBorder(9, 0, 4, 0));
        group.add(heading, BorderLayout.NORTH);
        JComponent rule = new JComponent() { @Override protected void paintComponent(Graphics g){g.setColor(palette.line);g.drawLine(0,0,getWidth(),0);} };
        rule.setPreferredSize(new Dimension(1,1)); group.add(rule, BorderLayout.SOUTH);
        return group;
    }
    private JPanel settingsRow(String title, String description, JComponent control) {
        JPanel row = transparent(new BorderLayout(18, 0));
        JPanel copy = transparent(new GridLayout(0, 1, 0, 2));
        JLabel name = new JLabel(title); name.setForeground(palette.text);
        JLabel detail = new JLabel(description); detail.setForeground(palette.muted); detail.setFont(detail.getFont().deriveFont(10f));
        copy.add(name); copy.add(detail); row.add(copy, BorderLayout.CENTER);
        control.setPreferredSize(new Dimension(170, 31)); row.add(control, BorderLayout.EAST);
        row.setBorder(new EmptyBorder(4, 0, 4, 0)); row.setMinimumSize(new Dimension(0, 39)); row.setPreferredSize(new Dimension(400, 43));
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
        inputKeyHint.revalidate(); layerKeyHint.revalidate();
    }
    private void updateRecordingButton(JButton button, String value) {
        if (button != null) button.setText(value == null || value.isBlank() ? "Unassigned" : prettyKeyBinding(value));
    }
    private void styleScrollBar(JScrollBar bar) { ModernUi.scroll(bar,palette); }
    private boolean loadImageSource() {
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
    private void refreshTheme() { if(gallery!=null)gallery.refreshTheme(); applyUiDefaults(); retheme(this); for(Window owned:getOwnedWindows()){retheme(owned);owned.repaint();} opacity.setUI(new AccentSliderUI(opacity, palette)); scale.setUI(new AccentSliderUI(scale, palette)); for (JTextField field : percentageFields) { field.setBackground(palette.controlSurface()); field.setForeground(palette.text); field.setCaretColor(palette.text); field.setBorder(BorderFactory.createLineBorder(palette.accent)); } layerTitle.setFont(new Font(Font.DIALOG,Font.BOLD,23)); layerTitle.setForeground(palette.text); root.setBackground(palette.background); sourceList.setCellRenderer(new SourceCellRenderer(palette)); sourceList.repaint(); topBar.repaint(); opacity.repaint(); scale.repaint(); repaint(); }
    private void retheme(Component component){
        Palette dark=new Palette(),light=new Palette();light.light();Color fg=component.getForeground(),bg=component.getBackground();
        if(component instanceof JLabel || component instanceof javax.swing.text.JTextComponent || component instanceof JCheckBox || component instanceof JRadioButton || component instanceof AbstractButton){component.setForeground(fg!=null&&(fg.equals(dark.muted)||fg.equals(light.muted))?palette.muted:palette.text);}
        if(bg!=null&&(bg.equals(dark.background)||bg.equals(light.background)||bg.equals(dark.primary)||bg.equals(light.primary)))component.setBackground(palette.background);
        if(bg!=null&&(bg.equals(dark.panel)||bg.equals(light.panel)||bg.equals(dark.panelRaised)||bg.equals(light.panelRaised)||bg.equals(dark.controlSurface())||bg.equals(light.controlSurface())))component.setBackground(palette.controlSurface());
        if(component instanceof JComboBox<?> || component instanceof JTabbedPane || component instanceof JMenuItem){component.setForeground(palette.text);component.setBackground(palette.controlSurface());}
        if(component instanceof javax.swing.text.JTextComponent text){if(text.isOpaque())text.setBackground(palette.controlSurface());text.setCaretColor(palette.text);text.setSelectionColor(palette.accent);text.setSelectedTextColor(palette.selectionText());}
        if(component instanceof Container container)for(Component child:container.getComponents())retheme(child);
    }
    private void applyUiDefaults() {
        for(String type:new String[]{"Menu","MenuItem","PopupMenu","TabbedPane","TextArea","TextPane","EditorPane"}){UIManager.put(type+".background",palette.controlSurface());UIManager.put(type+".foreground",palette.text);UIManager.put(type+".selectionBackground",palette.accent);UIManager.put(type+".selectionForeground",palette.selectionText());}

        UIManager.put("ToolTip.background",palette.controlSurface()); UIManager.put("ToolTip.foreground",palette.text); UIManager.put("ToolTip.border",BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line),new EmptyBorder(5,8,5,8))); UIManager.put("ToolTip.font",new Font(Font.DIALOG,Font.PLAIN,12));
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
        JDialog dialog = dialog(source.kind == Source.Kind.GAME ? "Configure game window — WGC compatibility" : "Configure window capture");
        JPanel body = dialogBody();
        JPanel fields = settingsPage();
        JTextField name = new JTextField(source.name);
        name.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line),new EmptyBorder(8,10,8,10)));name.setSelectionColor(palette.accent);name.setSelectedTextColor(palette.selectionText());
        name.setForeground(palette.text); name.setBackground(palette.controlSurface()); name.setCaretColor(palette.text);
        boolean[] manualName = {source.customName}, settingSuggestedName = {false};
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { if (!settingSuggestedName[0]) manualName[0] = true; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        });
        JComboBox<PythonBridge.WindowTarget> targets = new JComboBox<>(); styleCombo(targets);
        targets.addActionListener(event -> {
            PythonBridge.WindowTarget selected = (PythonBridge.WindowTarget) targets.getSelectedItem();
            if (selected != null && !manualName[0]) {
                settingSuggestedName[0] = true; name.setText(automaticWindowSourceName(selected)); settingSuggestedName[0] = false;
            }
        });
        JLabel scanStatus = muted("Scanning open windows…");
        JButton scan = toolButton("Scan again", "Refresh detected application windows");
        Runnable refresh = () -> {
            scanStatus.setText("Scanning open windows…"); scan.setEnabled(false);
            backend.requestWindowList(found -> SwingUtilities.invokeLater(() -> {
                DefaultComboBoxModel<PythonBridge.WindowTarget> model = new DefaultComboBoxModel<>();
                for (PythonBridge.WindowTarget item : found) model.addElement(item);
                targets.setModel(model);
                for (int i = 0; i < model.getSize(); i++) if (model.getElementAt(i).hwnd == source.nativeWindowHandle) targets.setSelectedIndex(i);
                scan.setEnabled(true); scanStatus.setText(found.size() + " windows found");
            }));
        };
        scan.addActionListener(event -> refresh.run());
        fields.add(propertyLabel("Source name")); fields.add(name);
        fields.add(propertyLabel("Capture target")); JPanel targetRow = transparent(new BorderLayout(6, 0)); targetRow.add(targets); targetRow.add(scan, BorderLayout.EAST); fields.add(targetRow); fields.add(scanStatus);
        JCheckBox captureCursor = new JCheckBox("Capture cursor in this source", source.captureCursor); captureCursor.setOpaque(false); captureCursor.setForeground(palette.text); captureCursor.setIcon(new AccentCheckIcon(palette)); fields.add(captureCursor);
        JCheckBox nativePresentation=new JCheckBox("Native window presentation in multitask mode",source.nativePresentationEnabled);
        nativePresentation.setOpaque(false);nativePresentation.setForeground(palette.text);nativePresentation.setIcon(new AccentCheckIcon(palette));
        fields.add(nativePresentation);
        fields.add(propertyLabel("Capture method"));
        JComboBox<String> method = new JComboBox<>(new String[]{"Automatic (recommended)", "Window surface (may work when covered)", "Visible desktop (window must be unobstructed)"});
        styleCombo(method); method.setSelectedIndex("WINDOW".equals(source.captureMethod) ? 1 : "SCREEN".equals(source.captureMethod) ? 2 : 0); fields.add(method);
        if (source.kind == Source.Kind.GAME) { fields.remove(method); JLabel nativeMethod=new JLabel("Windows Graphics Capture · WGC compatibility");nativeMethod.setOpaque(true);nativeMethod.setBackground(palette.controlSurface());nativeMethod.setForeground(palette.text);nativeMethod.setBorder(new EmptyBorder(10,10,10,10));fields.add(nativeMethod); }
        JLabel methodHint = muted(source.kind == Source.Kind.GAME ? "WGC captures the selected game window; graphics API hooks are not active." : "Automatic tries WGC, then compatibility capture."); fields.add(methodHint);
        body.add(fields, BorderLayout.CENTER);
        JButton cancel = toolButton("Cancel", "Discard changes"); cancel.addActionListener(event -> dialog.dispose());
        JButton save = primaryButton("Apply changes"); save.addActionListener(event -> {
            PythonBridge.WindowTarget target = (PythonBridge.WindowTarget) targets.getSelectedItem();
            if (target == null || name.getText().trim().isEmpty()) { showNotice("Window capture", "Choose a detected window and enter a source name."); return; }
            source.name = uniqueSourceName(name.getText().trim(), source); source.customName = manualName[0]; source.inputTarget = target.title; source.nativeWindowHandle = target.hwnd; source.windowProcess = target.process;
            source.captureMethod = source.kind == Source.Kind.GAME ? "WGC" : method.getSelectedIndex() == 1 ? "WINDOW" : method.getSelectedIndex() == 2 ? "SCREEN" : "AUTO";
            source.captureCursor = captureCursor.isSelected();
            source.nativePresentationEnabled=nativePresentation.isSelected();
            source.liveFrame = null; source.lastCaptureModified = 0;
            backend.registerWindowCapture(source.name, source.inputTarget, source.captureFramePath, source.nativeWindowHandle, source.captureMethod, source.captureCursor);
            sourceList.repaint(); syncSelection(); preview.repaint(); dialog.dispose();
        });
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.add(cancel); actions.add(save); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(680, 550); dialog.setMinimumSize(new Dimension(560,500)); dialog.setLocationRelativeTo(this); refresh.run(); dialog.setVisible(true);
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
            multitaskWindow.setFocusableWindowState(false);
            multitaskWindow.setAlwaysOnTop(true);
            multitaskWindow.setBackground(new Color(0, 0, 0, 0));
            multitaskWindow.setContentPane(preview);
            Rectangle screenBounds = device.getDefaultConfiguration().getBounds();
            multitaskWindow.setBounds(screenBounds);
            multitaskWindow.setVisible(true);
            multitaskControls = new JWindow();
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
            Point exitPoint=exitOverlay.getLocationOnScreen();exitHotspot=new Rectangle(exitPoint.x,exitPoint.y,38,38);
            exitHideTimer=new javax.swing.Timer(2600,event->{if(exitHotspot!=null&&MouseInfo.getPointerInfo()!=null&&exitHotspot.contains(MouseInfo.getPointerInfo().getLocation()))return;multitaskControls.setVisible(false);overlayControlRegion(false);});exitHideTimer.setRepeats(false);
            exitHoverTimer=new javax.swing.Timer(100,event->{if(exitHotspot!=null&&MouseInfo.getPointerInfo()!=null&&exitHotspot.contains(MouseInfo.getPointerInfo().getLocation()))revealExit();});exitHoverTimer.start();updateControlIndicator();revealExit();overlayControlRegion(true);
            PythonBridge.prepareNativeDisplayCapture();
            setVisible(false);
            configureScreenshotKeys();backend.setMultitaskOverlay(true, true, stopKeyBinding, swapKeyBinding, layerSwapKeyBinding);
            SwingUtilities.invokeLater(() -> { refreshNativeLayerLayout(); setControlTarget(activeView.controlSource); });
        } else {
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
                    if (displayCaptures.size() == 1 && device == 0 && !source.nativeDisplayAttempted && backend.isReady()) {
                        source.nativeDisplayAttempted = true;
                        if (PythonBridge.prepareNativeDisplayCapture()) {
                            source.captureFramePath = windowFramesDirectory.resolve(java.util.UUID.randomUUID() + ".monitor").toString();
                            backend.send("{\"command\":\"register_display_capture\",\"monitor\":1,\"path\":\"" + source.captureFramePath.replace("\\", "\\\\") + "\"}");
                        }
                    }
                    if (!source.nativeDisplayFrames) needed = true;
                }
            }
            displayCaptures.get(device).setEnabled(needed);
        }
    }

    private void refreshWindowFrames() {
        preview.refreshNativePresentation();
        if(multitask && multitaskExitButton!=null) {
            boolean nativeSurfaces=false;
            for(int i=0;i<activeView.sources.size();i++)nativeSurfaces|=activeView.sources.get(i).nativeCompositionActive;
            multitaskExitButton.setToolTipText("Exit multitask mode · "+(nativeSurfaces?"Native window composition":"Capture preview"));
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
            for (Source source : sources) {
                try {
                    WindowFrameReader.Frame frame = null;
                    try { frame = windowFrameReader.readMemory(source.captureFramePath, source.lastMemorySequence,
                        backend.frameStreamPort(), source.standbyFrame); }
                    catch (java.io.IOException ignored) { }
                    if (frame != null) {
                        frames.put(source, frame); continue;
                    }
                    Path path = Path.of(source.captureFramePath);
                    if (!Files.isRegularFile(path)) continue;
                    long modified = Files.getLastModifiedTime(path).toMillis(), size = Files.size(path);
                    if (modified == source.lastCaptureModified && size == source.lastCaptureSize) continue;
                    frame = windowFrameReader.readDisk(source.captureFramePath, source.lastCaptureModified, source.lastCaptureSize);
                    if (frame != null) { frames.put(source, frame); versions.put(source, new long[]{modified, size}); }
                } catch (Exception ignored) { }
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    for (Map.Entry<Source, WindowFrameReader.Frame> entry : frames.entrySet()) {
                        Source source = entry.getKey();
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
                            ? "WGC game-window compatibility · " + (frame.backend.contains("shared") ? "shared memory" : "socket memory") : frame.backend;
                        source.captureLatencyMillis = frame.latencyMillis; source.capturedFrames++;
                        source.producedNanos = frame.producedNanos;
                    }
                    if (!frames.isEmpty()) preview.repaint();
                } finally {
                    windowFrameScanQueued.set(false);
                    if (newFramePending.get()) requestWindowFrames();
                }
            });
        });
    }

    private synchronized void shutdownServices() {
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
            backend.requestWindowList(targets -> SwingUtilities.invokeLater(() -> {
                for(View view:views) for(int i=0;i<view.sources.size();i++) {
                    Source source=view.sources.get(i);
                    if(!source.usesWindowTarget() || source.captureFramePath!=null) continue;
                    if (source.kind==Source.Kind.GAME && !Boolean.getBoolean("multimin.dev.gameCapture")) { source.activeCaptureBackend="Experimental source unavailable in this build";continue; }
                    var matches=targets.stream().filter(t -> t.title.equals(source.inputTarget) && !source.windowProcess.isBlank() && source.windowProcess.equalsIgnoreCase(t.process)).toList();
                    if(matches.size()!=1){source.activeCaptureBackend="Reselect target: window missing or ambiguous";continue;}
                    source.nativeWindowHandle=matches.get(0).hwnd;
                    source.captureFramePath=windowFramesDirectory.resolve(java.util.UUID.randomUUID()+".bmp").toString();
                    backend.registerWindowCapture(source.name,source.inputTarget,source.captureFramePath,source.nativeWindowHandle,source.captureMethod,source.captureCursor);
                }
                preview.repaint();
            }));
        }
    }); }

    private void showNotice(String title, String message) {
        JDialog dialog = dialog(title); JPanel body = dialogBody();
        JTextArea text = new JTextArea(message); text.setWrapStyleWord(true); text.setLineWrap(true); text.setEditable(false); text.setFocusable(false); text.setOpaque(false); text.setForeground(palette.text); text.setFont(UIManager.getFont("Label.font"));
        body.add(text, BorderLayout.CENTER); JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0)); JButton ok = toolButton("OK", "Close"); ok.addActionListener(event -> dialog.dispose()); actions.add(ok); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(390, 165); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private String promptText(String title, String prompt, String initial) {
        JDialog dialog = dialog(title); JPanel body = dialogBody(); body.add(new JLabel(prompt), BorderLayout.NORTH);
        JTextField field = new JTextField(initial, 24); field.setBackground(palette.controlSurface()); field.setForeground(palette.text); field.setCaretColor(palette.text); field.setSelectionColor(palette.accent); field.setSelectedTextColor(palette.selectionText()); field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.line), new EmptyBorder(6, 8, 6, 8))); body.add(field, BorderLayout.CENTER);
        final String[] result = {null}; JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); JButton cancel = toolButton("Cancel", "Cancel"); cancel.addActionListener(event -> dialog.dispose()); JButton save = primaryButton("Save"); save.addActionListener(event -> { result[0] = field.getText(); dialog.dispose(); }); actions.add(cancel); actions.add(save); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(390, 175); dialog.setLocationRelativeTo(this); dialog.setVisible(true); return result[0];
    }

    private Source.Kind chooseSourceKind() {
        JDialog dialog = dialog("Add source"); JPanel body = dialogBody(); body.add(new JLabel("Choose a source type"), BorderLayout.NORTH); JPanel choices = transparent(new GridLayout(0, 1, 0, 6)); final Source.Kind[] result = {null};
        for (Source.Kind kind : Source.Kind.values()) { if (kind == Source.Kind.VIDEO || (kind == Source.Kind.GAME && !Boolean.getBoolean("multimin.dev.gameCapture"))) continue; JButton choice = toolButton(kind.label, "Add " + kind.label.toLowerCase()); choice.setHorizontalAlignment(SwingConstants.LEFT); choice.addActionListener(event -> { result[0] = kind; dialog.dispose(); }); choices.add(choice); }
        body.add(choices, BorderLayout.CENTER); JButton cancel = toolButton("Cancel", "Close"); cancel.addActionListener(event -> dialog.dispose()); JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body); dialog.setSize(360, 320); dialog.setLocationRelativeTo(this); dialog.setVisible(true); return result[0];
    }

    private JDialog dialog(String title) { JDialog dialog = new JDialog(this, title, true); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.getContentPane().setBackground(palette.background); return dialog; }
    private JPanel dialogBody() { JPanel body = new GlassPanel(palette); body.setLayout(new BorderLayout(12, 16)); body.setBorder(new EmptyBorder(18, 18, 14, 18)); return body; }
    private JButton quietButton(String text) { Icon icon = null; String label = text; if ("＋".equals(text)) { icon = new VectorIcon(VectorIcon.Type.ADD, 12, ()->palette.text); label = ""; } else if ("Snapshot".equals(text)) icon = new VectorIcon(VectorIcon.Type.CAMERA, 13, ()->palette.text); return new ModernButton(label, icon, palette, false); }
    private JButton primaryButton(String text) { Icon icon = text.contains("Add") ? new VectorIcon(VectorIcon.Type.ADD, 12, palette::selectionText) : text.contains("multitasking") ? new VectorIcon(VectorIcon.Type.PLAY, 12, palette::selectionText) : null; return new ModernButton(text.replace("＋ ", ""), icon, palette, true); }
    private JButton toolButton(String text, String tooltip) { VectorIcon.Type type = null; if ("＋".equals(text)) type = VectorIcon.Type.ADD; else if ("−".equals(text)) type = VectorIcon.Type.REMOVE; else if ("⧉".equals(text)) type = VectorIcon.Type.COPY; else if ("◉".equals(text)) type = VectorIcon.Type.EYE; else if ("↑".equals(text)) type = VectorIcon.Type.UP; else if ("↓".equals(text)) type = VectorIcon.Type.DOWN; Icon icon = type == null ? null : new VectorIcon(type, 13, ()->palette.text); ModernButton button = new ModernButton(type == null ? text : "", icon, palette, false); button.setToolTipText(tooltip); return button; }
    private JLabel centeredSection(String text) { JLabel label=sectionTitle(text); label.setHorizontalAlignment(SwingConstants.CENTER); label.setMaximumSize(new Dimension(Integer.MAX_VALUE,38)); label.setAlignmentX(Component.CENTER_ALIGNMENT); return label; }
    private JLabel sectionTitle(String text) { JLabel label = muted(text); label.setBorder(new EmptyBorder(8, 0, 7, 0)); return label; }
    private JLabel propertyLabel(String text) { JLabel label = new JLabel(text); label.setForeground(palette.text); return label; }
    private JPanel percentageEditor(JSlider slider) {
        CardLayout cards = new CardLayout();
        JPanel holder = transparent(cards);
        holder.setPreferredSize(new Dimension(62, 25));
        JButton value = new JButton(slider.getValue() + "%");
        value.setToolTipText("Click to enter a value from " + slider.getMinimum() + "% to " + slider.getMaximum() + "%");
        value.setForeground(palette.muted); value.setBorder(new EmptyBorder(0, 2, 0, 0));
        value.setOpaque(false); value.setContentAreaFilled(false); value.setFocusPainted(false);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        JTextField editor = new JTextField();
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
            @Override public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus){g.setColor(palette.controlSurface());g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
            @Override protected javax.swing.plaf.basic.ComboPopup createPopup(){return new javax.swing.plaf.basic.BasicComboPopup(comboBox){@Override protected JScrollPane createScroller(){JScrollPane pane=super.createScroller();styleScrollBar(pane.getVerticalScrollBar());pane.setBorder(BorderFactory.createEmptyBorder());return pane;}};}
        });
        combo.setOpaque(true); combo.setBackground(palette.controlSurface()); combo.setForeground(palette.text); combo.setBorder(BorderFactory.createLineBorder(palette.line));
        combo.setRenderer((list, value, index, selected, focus) -> {
            JLabel item = new JLabel(value == null ? "" : value.toString()); item.setOpaque(true); item.setBorder(new EmptyBorder(4, 7, 4, 7));
            item.setBackground(selected ? palette.accent : palette.controlSurface());
            item.setForeground(selected ? palette.selectionText() : palette.text); return item;
        });
    }
    private JLabel muted(String text) { JLabel label = new JLabel(text); label.setForeground(palette.muted); label.setFont(label.getFont().deriveFont(10f)); return label; }
    private JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
}
