package app.multimin.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public final class PythonBridge implements AutoCloseable {
    private static volatile Path cachedPythonSource;
    private volatile Process process;
    private volatile BufferedWriter writer;
    private final ConcurrentLinkedQueue<String> pendingCommands = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> quickSwapHandler = ignored -> {};
    private volatile Consumer<List<WindowTarget>> windowListHandler = ignored -> {};
    private volatile java.util.function.BiConsumer<String, String> gameStatusHandler = (ignoredPath, ignoredStatus) -> {};
    private volatile boolean ready;
    private volatile boolean startupComplete;
    private volatile int frameStreamPort;
    private volatile boolean gameAvailable;
    private volatile boolean frameNotifications;
    private volatile Runnable frameReadyHandler = () -> {};
    public boolean hasFrameNotifications() { return frameNotifications; }
    public void setFrameReadyHandler(Runnable handler) { frameReadyHandler = handler == null ? () -> {} : handler; }
    public int frameStreamPort() { return frameStreamPort; }
    public boolean gameAvailable() { return gameAvailable; }
    public static boolean prepareNativeDisplayCapture() {
        try { return SharedFrameBridge.AVAILABLE && SharedFrameBridge.excludeOwnWindows() > 0; }
        catch (LinkageError error) { return false; }
    }
    public void setGameStatusHandler(java.util.function.BiConsumer<String, String> handler) {
        gameStatusHandler = handler == null ? (ignoredPath, ignoredStatus) -> {} : handler;
    }

    public void setQuickSwapHandler(Consumer<String> handler) {
        quickSwapHandler = handler == null ? ignored -> {} : handler;
    }

    public boolean isReady() { return ready; }

    public void start(Consumer<String> status) {
        Thread worker = new Thread(() -> {
            List<List<String>> candidates = pythonCommands();
            String lastError = "No Python runtime was found";
            for (List<String> command : candidates) {
                try {
                    ProcessBuilder builder = new ProcessBuilder(command);
                    Path source = pythonSourceDirectory();
                    if (Files.isDirectory(source)) {
                        String old = builder.environment().getOrDefault("PYTHONPATH", "");
                        builder.environment().put("PYTHONPATH", source.resolve("vendor") + File.pathSeparator + source + File.pathSeparator + old);
                    }
                    builder.redirectErrorStream(true);
                    process = builder.start();
                    writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line != null && line.contains("ready")) {
                        Matcher framePortMatch = Pattern.compile("\\\"frame_port\\\":(\\d+)").matcher(line);
                        if (framePortMatch.find()) frameStreamPort = Integer.parseInt(framePortMatch.group(1));
                        gameAvailable = line.contains("\"game_available\":true");
                        frameNotifications = line.contains("\"frame_notifications\":true");
                        ready = true;
                        startupComplete = true;
                        status.accept("Input passthrough ready · live display active");
                        flushPendingCommands();
                        while ((line = reader.readLine()) != null) {
                            if (line.contains("\"event\":\"frame_ready\"")) frameReadyHandler.run();
                            else if (line.contains("\"event\":\"quick_swap\"")) quickSwapHandler.accept("quick_swap");
                            else if (line.contains("\"event\":\"layer_swap\"")) quickSwapHandler.accept("layer_swap");
                            else if (line.contains("\"event\":\"layer_layout_changed\"")) quickSwapHandler.accept("visual_stack_applied");
                            else if (line.contains("\"event\":\"input_target_changed\"")) quickSwapHandler.accept("visual_stack_applied");
                            else if (line.contains("\"event\":\"toggle_multitask\"")) quickSwapHandler.accept("toggle_multitask");
                            else if (line.contains("\"event\":\"window_list\"")) windowListHandler.accept(parseWindowList(line));
                            else if (line.contains("\"event\":\"game_capture_status\"") || line.contains("\"event\":\"capture_status\"")) {
                                Matcher gameStatus = Pattern.compile("\\\"status\\\":\\\"([^\\\"]+)\\\"").matcher(line);
                                Matcher gameSource = Pattern.compile("([0-9a-fA-F-]+\\.(?:game|monitor))").matcher(line);
                                if (gameStatus.find()) {
                                    status.accept((line.contains(".monitor") ? "Native display capture: " : "DirectX 11 test capture: ") + gameStatus.group(1).replace('_', ' '));
                                    if (gameSource.find()) gameStatusHandler.accept(gameSource.group(1), gameStatus.group(1));
                                }
                            }
                            else if (line.contains("\"event\":\"error\"")) status.accept(line);
                        }
                        ready = false;
                        gameAvailable = false;
                        frameStreamPort = 0;
                        return;
                    }
                    if (line != null) lastError = line;
                    if (process != null) process.destroyForcibly();
                    process = null; writer = null;
                } catch (IOException error) { lastError = error.getMessage(); }
            }
            ready = false;
            startupComplete = true;
            status.accept("Input passthrough unavailable — " + lastError);
            windowListHandler.accept(List.of());
        }, "multimin-python-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void send(String json) {
        if (writer == null) { pendingCommands.add(json); return; }
        try { writer.write(json); writer.newLine(); writer.flush(); }
        catch (IOException ignored) { }
    }

    private synchronized void flushPendingCommands() {
        if (writer == null) return;
        String command;
        try {
            while ((command = pendingCommands.poll()) != null) { writer.write(command); writer.newLine(); }
            writer.flush();
        } catch (IOException ignored) { }
    }

    public void requestSnapshot() { send("{\"command\":\"snapshot\",\"temporary\":true}"); }
    public void setOpacity(int value) { send("{\"command\":\"set_opacity\",\"value\":" + value + "}"); }
    public void setCaptureFps(int value) { send("{\"command\":\"set_capture_fps\",\"value\":" + value + "}"); }
    public void setInputTarget(String name) { setInputTarget(name, 0); }
    public void setInputTarget(String name, long hwnd) { setInputTarget(name, hwnd, 0, 0, 0, 0, 0, 0, 0, 0); }
    public void setInputTarget(String name, long hwnd, int x, int y, int width, int height, int excludeX, int excludeY, int excludeWidth, int excludeHeight) {
        setInputTarget(name, hwnd, x, y, width, height, excludeX, excludeY, excludeWidth, excludeHeight, false);
    }
    public void setInputTarget(String name, long hwnd, int x, int y, int width, int height,
                               int excludeX, int excludeY, int excludeWidth, int excludeHeight,
                               boolean desktopInput) {
        send("{\"command\":\"set_input_target\",\"name\":\"" + escape(name) + "\",\"hwnd\":" + hwnd +
            ",\"x\":" + x + ",\"y\":" + y + ",\"width\":" + width + ",\"height\":" + height +
            ",\"exclude_x\":" + excludeX + ",\"exclude_y\":" + excludeY + ",\"exclude_width\":" + excludeWidth + ",\"exclude_height\":" + excludeHeight +
            ",\"desktop_input\":" + desktopInput + "}");
    }
    public void setMultitaskOverlay(boolean enabled, boolean clickThrough) {
        setMultitaskOverlay(enabled, clickThrough, "END");
    }
    public void setMultitaskOverlay(boolean enabled, boolean clickThrough, String stopKey) {
        setMultitaskOverlay(enabled, clickThrough, stopKey, "RIGHT_CTRL");
    }
    public void setMultitaskOverlay(boolean enabled, boolean clickThrough, String stopKey, String swapKey) {
        setMultitaskOverlay(enabled, clickThrough, stopKey, swapKey, "");
    }
    public void setMultitaskOverlay(boolean enabled, boolean clickThrough, String stopKey, String swapKey, String layerSwapKey) {
        send("{\"command\":\"set_multitask_overlay\",\"enabled\":" + enabled + ",\"click_through\":" + clickThrough + ",\"app_pid\":" + ProcessHandle.current().pid() + ",\"stop_key\":\"" + escape(stopKey) + "\",\"swap_key\":\"" + escape(swapKey) + "\",\"layer_swap_key\":\"" + escape(layerSwapKey) + "\"}");
    }
    public void registerWindowCapture(String id, String title, String path, long hwnd) {
        registerWindowCapture(id, title, path, hwnd, "AUTO");
    }
    public void registerWindowCapture(String id, String title, String path, long hwnd, String method) {
        registerWindowCapture(id, title, path, hwnd, method, true);
    }
    public void registerWindowCapture(String id, String title, String path, long hwnd, String method, boolean captureCursor) {
        send("{\"command\":\"register_window_capture\",\"id\":\"" + escape(id) + "\",\"title\":\"" + escape(title) + "\",\"path\":\"" + escape(path) + "\",\"hwnd\":" + hwnd + ",\"method\":\"" + escape(method) + "\",\"capture_cursor\":" + captureCursor + "}");
    }
    public void requestWindowList(Consumer<List<WindowTarget>> handler) {
        windowListHandler = handler == null ? ignored -> {} : handler;
        if (startupComplete && !ready) { windowListHandler.accept(List.of()); return; }
        send("{\"command\":\"list_windows\"}");
    }
    public void removeWindowCapture(String path) {
        send("{\"command\":\"remove_window_capture\",\"path\":\"" + escape(path) + "\"}");
    }
    public void setActiveWindowCaptures(List<String> paths) {
        send("{\"command\":\"active_window_captures\",\"paths\":[" + paths.stream()
            .map(path -> "\"" + escape(path) + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}");
    }
    public void startGameCapture(String source) {
        send("{\"command\":\"start_game_capture\",\"path\":\"" + escape(source) + "\"}");
    }
    public void stopGameCapture(String source) {
        send("{\"command\":\"stop_game_capture\",\"path\":\"" + escape(source) + "\"}");
    }

    private static List<List<String>> pythonCommands() {
        List<List<String>> commands = new ArrayList<>();
        Path source = pythonSourceDirectory();
        Path bundledRuntime = source.resolve("runtime").resolve("python.exe");
        if (Files.isRegularFile(bundledRuntime)) commands.add(List.of(bundledRuntime.toString(), "-m", "multimin_backend"));
        commands.add(List.of("python-backend.exe"));
        String configured = System.getenv("MULTIMIN_PYTHON");
        if (configured != null && !configured.isBlank()) commands.add(List.of(configured, "-m", "multimin_backend"));
        Path projectVenv = source.resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.isRegularFile(projectVenv)) commands.add(List.of(projectVenv.toString(), "-m", "multimin_backend"));
        Path localRuntimes = Path.of(System.getProperty("user.home"), "AppData", "Local", "Python");
        if (Files.isDirectory(localRuntimes)) {
            try (var entries = Files.list(localRuntimes)) {
                entries.filter(Files::isDirectory).filter(path -> path.getFileName().toString().startsWith("pythoncore-"))
                    .sorted(Comparator.reverseOrder()).map(path -> path.resolve("python.exe"))
                    .filter(Files::isRegularFile).forEach(path -> commands.add(List.of(path.toString(), "-m", "multimin_backend")));
            } catch (IOException ignored) { }
        }
        commands.add(Arrays.asList("python", "-m", "multimin_backend"));
        commands.add(Arrays.asList("py", "-3", "-m", "multimin_backend"));
        return commands;
    }

    private static synchronized Path pythonSourceDirectory() {
        if (cachedPythonSource != null) return cachedPythonSource;
        List<Path> candidates = new ArrayList<>();
        String configuredHome = System.getenv("MULTIMIN_HOME");
        if (configuredHome != null && !configuredHome.isBlank()) candidates.add(Path.of(configuredHome, "python"));
        Path working = Path.of("").toAbsolutePath();
        candidates.add(working.resolve("python"));
        candidates.add(working.resolve("..").resolve("python"));
        try {
            Path location = Path.of(PythonBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(location) ? location : location.getParent();
            for (int depth = 0; depth < 4 && base != null; depth++, base = base.getParent()) candidates.add(base.resolve("python"));
        } catch (Exception ignored) { }
        for (Path candidate : candidates) {
            Path resolved = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved.resolve("multimin_backend"))) return cachedPythonSource = resolved;
        }
        Path bundled = extractBundledPython();
        if (bundled != null) return cachedPythonSource = bundled;
        return cachedPythonSource = working.resolve("python").toAbsolutePath().normalize();
    }
    private static Path extractBundledPython() {
        String[] modules = {"__init__.py", "__main__.py", "service.py", "snapshots.py", "windows_input.py",
            "frame_stream.py", "game_ingest.py"};
        try {
            Path root = Files.createTempDirectory("multimin-backend-");
            Path packageDirectory = Files.createDirectories(root.resolve("multimin_backend"));
            for (String module : modules) {
                String resource = "/bundled-python/multimin_backend/" + module;
                try (InputStream input = PythonBridge.class.getResourceAsStream(resource)) {
                    if (input == null) return null;
                    Files.copy(input, packageDirectory.resolve(module));
                }
            }
            return root;
        } catch (IOException error) { return null; }
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private static List<WindowTarget> parseWindowList(String json) {
        List<WindowTarget> targets = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{\\\"title64\\\":\\\"([^\\\"]*)\\\",\\\"process64\\\":\\\"([^\\\"]*)\\\",\\\"hwnd\\\":(\\d+),\\\"left\\\":(-?\\d+),\\\"top\\\":(-?\\d+),\\\"width\\\":(\\d+),\\\"height\\\":(\\d+)\\}").matcher(json);
        while (matcher.find()) {
            try {
                String title = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
                String process = new String(Base64.getDecoder().decode(matcher.group(2)), StandardCharsets.UTF_8);
                targets.add(new WindowTarget(process, title, Long.parseLong(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                    Integer.parseInt(matcher.group(6)), Integer.parseInt(matcher.group(7))));
            } catch (IllegalArgumentException ignored) { }
        }
        java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();
        for (WindowTarget item : targets) {
            String key = item.process.toLowerCase() + "\u0000" + item.title.toLowerCase();
            int count = occurrences.merge(key, 1, Integer::sum);
            if (count > 1) item.windowNumber = count;
            else {
                int total = 0;
                for (WindowTarget other : targets) if (other.process.equalsIgnoreCase(item.process) && other.title.equalsIgnoreCase(item.title)) total++;
                if (total > 1) item.windowNumber = 1;
            }
        }
        return targets;
    }

    public static final class WindowTarget {
        public final String process, title;
        public final long hwnd;
        public final int left, top, width, height;
        public int windowNumber;
        public WindowTarget(String process, String title, long hwnd, int left, int top, int width, int height) {
            this.process = process; this.title = title; this.hwnd = hwnd;
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
        @Override public String toString() { return "[" + process + "]: " + title + (windowNumber > 0 ? " — window " + windowNumber : ""); }
    }

    @Override public void close() {
        Process running = process;
        List<ProcessHandle> children = running == null ? List.of() : running.descendants().toList();
        send("{\"command\":\"shutdown\"}");
        if (running != null) {
            try { if (!running.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) running.destroy(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); running.destroy(); }
            for (ProcessHandle child : children) if (child.isAlive()) child.destroy();
        }
    }
}
