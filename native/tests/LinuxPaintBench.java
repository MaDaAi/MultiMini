package app.multimin.ui;

import app.multimin.backend.WindowFrameReader;
import app.multimin.model.Source;
import app.multimin.model.View;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures the Java half of the Linux frame pipeline with the real classes.
 *
 * A Python producer (scripts/bench-frame-producer.py) publishes moving frames through the
 * real FrameStream and prints frame_ready lines. This class reads them with the real
 * WindowFrameReader on a worker thread, applies them on the EDT exactly like
 * MainWindow.requestWindowFrames, and paints them with the real PreviewPanel /
 * FrameTextureCache in a visible frame. Only public API and package-private classes that
 * exist on every revision are used, so the same class can measure a baseline jar.
 *
 * Arguments are key=value: python, producer, pythonpath, width, height, fps, mode
 * (configure|locked), kind (window|display), warmup, seconds, out, sync, micro.
 */
public final class LinuxPaintBench {
    static final Map<String, String> arg = new HashMap<>();
    static final AtomicBoolean scanQueued = new AtomicBoolean();
    static final AtomicBoolean newFramePending = new AtomicBoolean();
    static volatile boolean recording;
    static volatile boolean stopping;
    static volatile int port;
    static volatile long producerPid;

    // Samples, only touched on the EDT (apply, paint) or the worker (read); merged at the end.
    static final double[] NONE = new double[0];
    static final List<Double> readMs = Collections.synchronizedList(new ArrayList<>());
    static final List<Double> applyMs = Collections.synchronizedList(new ArrayList<>());
    static final List<Double> paintMs = Collections.synchronizedList(new ArrayList<>());
    static final List<Double> ageMs = Collections.synchronizedList(new ArrayList<>());
    static final List<Double> readAgeMs = Collections.synchronizedList(new ArrayList<>());
    static long paints, distinctFramesPainted, delivered, reads, emptyReads;
    static long lastCaptured = -1;

    static ExecutorService worker;
    static WindowFrameReader reader;
    static Source source;
    static PreviewPanel preview;
    static final String ID = "bench";

    static void request() {
        if (stopping || !scanQueued.compareAndSet(false, true)) return;
        newFramePending.set(false);
        worker.execute(() -> {
            WindowFrameReader.Frame frame = null;
            long t0 = System.nanoTime();
            try { frame = reader.readMemory(ID, source.lastMemorySequence, port, source.standbyFrame); }
            catch (IOException ignored) { }
            double elapsed = (System.nanoTime() - t0) / 1e6;
            WindowFrameReader.Frame result = frame;
            if (recording) { reads++; if (result == null) emptyReads++; else readMs.add(elapsed); }
            SwingUtilities.invokeLater(() -> {
                long a0 = System.nanoTime();
                try {
                    if (result != null) {
                        if (source.lastMemorySequence > 0 && result.sequence > source.lastMemorySequence + 1)
                            source.skippedFrames += result.sequence - source.lastMemorySequence - 1;
                        source.lastMemorySequence = result.sequence;
                        BufferedImage previous = source.liveFrame;
                        source.liveFrame = result.image;
                        source.standbyFrame = previous;
                        source.captureLatencyMillis = result.latencyMillis;
                        source.capturedFrames++;
                        source.producedNanos = result.producedNanos;
                        if (recording) readAgeMs.add((double) result.latencyMillis);
                        preview.repaint();
                    }
                } finally {
                    if (recording && result != null) applyMs.add((System.nanoTime() - a0) / 1e6);
                    scanQueued.set(false);
                    if (newFramePending.get()) request();
                }
            });
        });
    }

    /** Wraps the preview to time every paint pass the way a user sees it. */
    static final class Timed extends JPanel {
        final boolean sync;
        Timed(boolean sync) { super(new BorderLayout()); this.sync = sync; setOpaque(true); }
        @Override protected void paintChildren(Graphics g) {
            long t0 = System.nanoTime();
            super.paintChildren(g);
            if (sync) Toolkit.getDefaultToolkit().sync();
            long t1 = System.nanoTime();
            if (!recording) return;
            paints++;
            paintMs.add((t1 - t0) / 1e6);
            long captured = source.capturedFrames;
            if (captured != lastCaptured) {
                lastCaptured = captured;
                distinctFramesPainted++;
                if (source.producedNanos > 0) ageMs.add((t1 - source.producedNanos) / 1e6);
            }
        }
    }

    static double[] stats(List<Double> list) {
        List<Double> copy;
        synchronized (list) { copy = new ArrayList<>(list); }
        if (copy.isEmpty()) return new double[]{0, 0, 0, 0, 0};
        Collections.sort(copy);
        double sum = 0; for (double v : copy) sum += v;
        return new double[]{sum / copy.size(), copy.get(copy.size() / 2), copy.get((int) (copy.size() * 0.95)),
            copy.get(Math.min(copy.size() - 1, (int) (copy.size() * 0.99))), copy.get(copy.size() - 1)};
    }
    static String js(String name, double[] s) {
        return String.format(Locale.ROOT, "\"%s\":{\"mean\":%.3f,\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f,\"max\":%.3f}", name, s[0], s[1], s[2], s[3], s[4]);
    }

    static long procTicks(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (Exception e) { return -1; }
    }
    static long statusKb(String key) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status")))
                if (line.startsWith(key)) return Long.parseLong(line.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) { }
        return -1;
    }
    static long xvfbPid() {
        try {
            Process p = new ProcessBuilder("pgrep", "-o", "Xvfb").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return out.isEmpty() ? -1 : Long.parseLong(out.split("\\s+")[0]);
        } catch (Exception e) { return -1; }
    }

    /** Isolated cost of the building blocks, single threaded on the EDT, X synchronised. */
    static String micro(int w, int h, GraphicsConfiguration gc) throws Exception {
        StringBuilder out = new StringBuilder("\"micro\":{");
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage pre = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        BufferedImage compat = gc.createCompatibleImage(w, h);
        JFrame f = new JFrame(); f.setUndecorated(true); f.setSize(1280, 720); f.setVisible(true);
        Thread.sleep(500);
        String[] names = {"draw_rgb_native", "draw_argb_native", "draw_argbpre_native", "draw_compatible_native",
            "draw_rgb_scaled_bilinear", "draw_argb_scaled_bilinear", "draw_rgb_scaled_nearest",
            "upload_rgb_to_volatile", "draw_volatile_scaled_bilinear"};
        VolatileImage vol = gc.createCompatibleVolatileImage(w, h, Transparency.TRANSLUCENT);
        vol.validate(gc);
        boolean first = true;
        for (String name : names) {
            double[] samples = new double[40];
            for (int i = -10; i < 30; i++) {
                Graphics2D g = (Graphics2D) f.getGraphics();
                long t0 = System.nanoTime();
                switch (name) {
                    case "draw_rgb_native" -> g.drawImage(rgb, 0, 0, null);
                    case "draw_argb_native" -> g.drawImage(argb, 0, 0, null);
                    case "draw_argbpre_native" -> g.drawImage(pre, 0, 0, null);
                    case "draw_compatible_native" -> g.drawImage(compat, 0, 0, null);
                    case "draw_rgb_scaled_bilinear" -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(rgb, 0, 0, 1280, 720, null); }
                    case "draw_argb_scaled_bilinear" -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(argb, 0, 0, 1280, 720, null); }
                    case "draw_rgb_scaled_nearest" -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); g.drawImage(rgb, 0, 0, 1280, 720, null); }
                    case "upload_rgb_to_volatile" -> { Graphics2D u = vol.createGraphics(); u.setComposite(AlphaComposite.Src); u.drawImage(rgb, 0, 0, null); u.dispose(); }
                    default -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(vol, 0, 0, 1280, 720, null); }
                }
                Toolkit.getDefaultToolkit().sync();
                long t1 = System.nanoTime();
                g.dispose();
                if (i >= 0) samples[i] = (t1 - t0) / 1e6;
            }
            Arrays.sort(samples);
            if (!first) out.append(',');
            first = false;
            out.append(String.format(Locale.ROOT, "\"%s\":%.3f", name, samples[samples.length / 2]));
        }
        f.dispose();
        return out.append('}').toString();
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { int i = a.indexOf('='); arg.put(a.substring(0, i), a.substring(i + 1)); }
        int width = Integer.parseInt(arg.get("width")), height = Integer.parseInt(arg.get("height"));
        if (Boolean.parseBoolean(arg.getOrDefault("micro", "false"))) {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            System.out.println("{" + micro(width, height, gc) + "}");
            System.exit(0);
        }
        int fps = Integer.parseInt(arg.get("fps"));
        boolean locked = arg.get("mode").equals("locked");
        boolean display = arg.getOrDefault("kind", "window").equals("display");
        boolean sync = Boolean.parseBoolean(arg.getOrDefault("sync", "false"));
        double warmup = Double.parseDouble(arg.get("warmup")), seconds = Double.parseDouble(arg.get("seconds"));

        ProcessBuilder builder = new ProcessBuilder(arg.get("python"), arg.get("producer"),
            "--width", String.valueOf(width), "--height", String.valueOf(height), "--fps", String.valueOf(fps),
            "--seconds", String.valueOf(warmup + seconds + 5), "--id", ID);
        builder.environment().put("PYTHONPATH", arg.get("pythonpath"));
        builder.redirectErrorStream(true);
        Process producer = builder.start();
        producerPid = producer.pid();
        BufferedReader lines = new BufferedReader(new InputStreamReader(producer.getInputStream(), StandardCharsets.UTF_8));
        String first = lines.readLine();
        if (first == null || !first.contains("\"ready\"")) { System.err.println("producer failed: " + first); System.exit(2); }
        port = Integer.parseInt(first.replaceAll(".*\"port\":\\s*(\\d+).*", "$1"));

        reader = new WindowFrameReader();
        worker = Executors.newSingleThreadExecutor(t -> { Thread th = new Thread(t, "bench-reader"); th.setDaemon(true); return th; });
        source = new Source("Bench", display ? Source.Kind.DISPLAY : Source.Kind.WINDOW);
        source.captureFramePath = ID;
        source.scale = 100; source.x = 0; source.y = 0;
        source.visible = true;
        View view = new View("bench");
        view.sources.addElement(source);
        if (!display) { Source backdrop = new Source("Backdrop", Source.Kind.IMAGE); backdrop.visible = false; view.sources.addElement(backdrop); }

        JFrame[] frame = new JFrame[1];
        int panelW = locked ? 1920 : 1600, panelH = locked ? 1080 : 900;
        SwingUtilities.invokeAndWait(() -> {
            preview = new PreviewPanel(new Palette(), view);
            Timed wrapper = new Timed(sync);
            wrapper.add(preview, BorderLayout.CENTER);
            frame[0] = new JFrame("bench");
            frame[0].setUndecorated(true);
            frame[0].setContentPane(wrapper);
            frame[0].setSize(panelW, panelH);
            frame[0].setLocation(0, 0);
            preview.setLocked(locked);
            frame[0].setVisible(true);
        });

        // Producer stdout reader: one line per frame_ready, like PythonBridge's reader thread.
        Thread stdout = new Thread(() -> {
            try {
                String line;
                while ((line = lines.readLine()) != null)
                    if (line.contains("\"event\":\"frame_ready\"")) { newFramePending.set(true); request(); }
            } catch (IOException ignored) { }
        }, "bench-stdout");
        stdout.setDaemon(true); stdout.start();

        Thread.sleep((long) (warmup * 1000));
        GarbageCollectorMXBean[] gcs = ManagementFactory.getGarbageCollectorMXBeans().toArray(new GarbageCollectorMXBean[0]);
        long gcCount0 = 0, gcTime0 = 0;
        for (GarbageCollectorMXBean g : gcs) { gcCount0 += g.getCollectionCount(); gcTime0 += g.getCollectionTime(); }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpu0 = os.getProcessCpuTime(), wall0 = System.nanoTime();
        long py0 = procTicks(producerPid), xvfb = xvfbPid(), xv0 = procTicks(xvfb);
        long seq0 = source.lastMemorySequence;
        long skipped0 = source.skippedFrames;
        SwingUtilities.invokeAndWait(() -> { recording = true; });
        long maxHeap = 0;
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < end) {
            Thread.sleep(250);
            Runtime rt = Runtime.getRuntime();
            maxHeap = Math.max(maxHeap, rt.totalMemory() - rt.freeMemory());
        }
        recording = false;
        long wall1 = System.nanoTime(), cpu1 = os.getProcessCpuTime();
        long py1 = procTicks(producerPid), xv1 = procTicks(xvfb);
        double wall = (wall1 - wall0) / 1e9;
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean g : gcs) { gcCount += g.getCollectionCount(); gcTime += g.getCollectionTime(); }
        long publishedApprox = source.lastMemorySequence - seq0;
        long skipped = source.skippedFrames - skipped0;
        long clk = 100;
        boolean accelerated = preview.getAcceleratedSourceCount() > 0;
        String json = String.format(Locale.ROOT,
            "{\"width\":%d,\"height\":%d,\"mode\":\"%s\",\"kind\":\"%s\",\"fps_target\":%d,\"seconds\":%.2f,"
                + "\"paints_per_s\":%.2f,\"distinct_frames_painted_per_s\":%.2f,\"delivered_per_s\":%.2f,\"sequence_span_per_s\":%.2f,\"skipped_per_s\":%.2f,\"empty_reads\":%d,"
                + "\"edt_busy_pct\":%.1f,\"jvm_cpu_pct\":%.1f,\"producer_cpu_pct\":%.1f,\"xvfb_cpu_pct\":%.1f,"
                + "\"heap_used_max_mb\":%.1f,\"rss_mb\":%.1f,\"rss_peak_mb\":%.1f,\"gc_count\":%d,\"gc_ms\":%d,\"gc_per_s\":%.2f,\"accelerated_surface\":%b,",
            width, height, arg.get("mode"), arg.getOrDefault("kind", "window"), fps, wall,
            paints / wall, distinctFramesPainted / wall, applyMs.size() / wall, publishedApprox / wall, skipped / wall, emptyReads,
            100.0 * (sum(applyMs) + sum(paintMs)) / 1000.0 / wall, 100.0 * (cpu1 - cpu0) / 1e9 / wall,
            py0 < 0 ? -1 : 100.0 * (py1 - py0) / clk / wall, xv0 < 0 ? -1 : 100.0 * (xv1 - xv0) / clk / wall,
            maxHeap / 1048576.0, statusKb("VmRSS") / 1024.0, statusKb("VmHWM") / 1024.0, gcCount - gcCount0, gcTime - gcTime0, (gcCount - gcCount0) / wall,
            accelerated)
            + js("read_ms", stats(readMs)) + "," + js("apply_ms", stats(applyMs)) + "," + js("paint_ms", stats(paintMs)) + ","
            + js("age_publish_to_paint_ms", stats(ageMs)) + "," + js("read_age_ms", stats(readAgeMs)) + "}";
        String out = arg.get("out");
        if (out != null) Files.writeString(Path.of(out), json);
        System.out.println(json);
        stopping = true;
        producer.destroy();
        System.exit(0);
    }

    static double sum(List<Double> list) { synchronized (list) { double s = 0; for (double v : list) s += v; return s; } }
}
