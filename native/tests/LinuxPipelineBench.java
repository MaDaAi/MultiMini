import app.multimin.backend.WindowFrameReader;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures the Linux X11 capture pipeline from the Java side.
 *
 * Starts the Python backend the way PythonBridge does, registers one X11 window
 * capture, then reads frames exactly like MainWindow: a frame_ready event wakes a
 * worker that asks WindowFrameReader for the newest frame (with a 100 ms poll as
 * fallback). Every frame is decoded for the counter the test window draws, and
 * the raw per-frame records are written as JSON for the orchestrator to analyse.
 *
 * Arguments are key=value: python, wrapper, pythonpath, stats, xid, path, fps,
 * paced, warmup, seconds, out, clk.
 */
public final class LinuxPipelineBench {
    static final int CELL_W = 16, CELL_H = 24, CELLS = 34;

    static final Map<String, String> arg = new HashMap<>();
    static final Object wake = new Object();
    static volatile boolean pending;
    static volatile boolean stopping;
    static volatile int port;
    static final Map<String, Integer> events = new TreeMap<>();
    static final List<String> errors = Collections.synchronizedList(new ArrayList<>());

    static long ticks(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (Exception e) { return -1; }
    }

    static long decode(BufferedImage image) {
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int w = image.getWidth();
        if (w < CELLS * CELL_W || image.getHeight() < CELL_H) return -1;
        long[] bits = new long[CELLS];
        for (int i = 0; i < CELLS; i++) {
            int rgb = px[(CELL_H / 2) * w + i * CELL_W + CELL_W / 2];
            bits[i] = (rgb & 0xff) > 127 ? 1 : 0;
        }
        if (bits[0] != 1 || bits[1] != 0) return -1;
        long value = 0;
        for (int i = 0; i < 32; i++) value |= bits[i + 2] << i;
        return value;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { int i = a.indexOf('='); arg.put(a.substring(0, i), a.substring(i + 1)); }
        String path = arg.get("path");
        int fps = Integer.parseInt(arg.get("fps"));
        boolean paced = Boolean.parseBoolean(arg.get("paced"));
        double warmup = Double.parseDouble(arg.get("warmup")), seconds = Double.parseDouble(arg.get("seconds"));
        long clk = Long.parseLong(arg.getOrDefault("clk", "100"));

        ProcessBuilder builder = new ProcessBuilder(arg.get("python"), arg.get("wrapper"), "backend");
        builder.environment().put("PYTHONPATH", arg.get("pythonpath"));
        builder.environment().put("MM_BENCH_STATS", arg.get("stats"));
        builder.redirectErrorStream(true);
        long started = System.nanoTime();
        Process process = builder.start();
        Writer writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String first = reader.readLine();
        long readyNanos = System.nanoTime();
        if (first == null || !first.contains("\"ready\"")) {
            System.err.println("backend did not report ready: " + first);
            System.exit(2);
        }
        Matcher m = Pattern.compile("\"frame_port\":(\\d+)").matcher(first);
        if (!m.find()) { System.err.println("no frame port"); System.exit(2); }
        port = Integer.parseInt(m.group(1));
        long pythonPid = process.pid();

        Thread stdout = new Thread(() -> {
            Pattern event = Pattern.compile("\"event\":\"(\\w+)\"");
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher e = event.matcher(line);
                    String name = e.find() ? e.group(1) : "?";
                    synchronized (events) { events.merge(name, 1, Integer::sum); }
                    if (name.equals("frame_ready")) { synchronized (wake) { pending = true; wake.notifyAll(); } }
                    else if (name.equals("error") && errors.size() < 8) errors.add(line);
                }
            } catch (IOException ignored) { }
        }, "bench-backend-stdout");
        stdout.setDaemon(true);
        stdout.start();

        // Same order as MainWindow: fps, registration, active set.
        long registerNanos = System.nanoTime();
        send(writer, "{\"command\":\"set_capture_fps\",\"value\":" + fps + ",\"source_paced\":" + paced + "}");
        send(writer, "{\"command\":\"register_window_capture\",\"id\":\"bench\",\"title\":\"MultiMini Benchmark Source\",\"path\":\"" + path
            + "\",\"hwnd\":" + arg.get("xid") + ",\"method\":\"AUTO\",\"capture_cursor\":false,\"restore_token\":\"\"}");
        send(writer, "{\"command\":\"active_window_captures\",\"paths\":[\"" + path + "\"]}");

        List<long[]> frames = Collections.synchronizedList(new ArrayList<>());
        long[] firstFrame = {0};
        Thread worker = new Thread(() -> {
            WindowFrameReader frameReader = new WindowFrameReader();
            long lastSequence = 0;
            BufferedImage standby = null;
            long lastRequest = 0;
            while (!stopping) {
                synchronized (wake) {
                    long idle = Math.max(1, 100 - (System.nanoTime() - lastRequest) / 1_000_000);
                    if (!pending) { try { wake.wait(idle); } catch (InterruptedException e) { return; } }
                    pending = false;
                }
                lastRequest = System.nanoTime();
                try {
                    long t0 = System.nanoTime();
                    WindowFrameReader.Frame frame = frameReader.readMemory(path, lastSequence, port, standby);
                    long recv = System.nanoTime();
                    if (frame == null) continue;
                    if (firstFrame[0] == 0) firstFrame[0] = recv;
                    long counter = decode(frame.image);
                    frames.add(new long[]{frame.sequence, counter, recv, frame.producedNanos, recv - t0,
                        frame.image.getWidth(), frame.image.getHeight()});
                    lastSequence = frame.sequence;
                    standby = frame.image;
                } catch (IOException e) {
                    if (errors.size() < 8) errors.add("read: " + e);
                }
            }
            frameReader.close();
        }, "bench-frame-reader");
        worker.start();

        Thread.sleep((long) (warmup * 1000));
        long startNanos = System.nanoTime();
        List<long[]> cpu = new ArrayList<>();
        long javaPid = ProcessHandle.current().pid();
        long end = startNanos + (long) (seconds * 1e9);
        while (System.nanoTime() < end) {
            cpu.add(new long[]{System.nanoTime(), ticks(javaPid), ticks(pythonPid)});
            Thread.sleep(500);
        }
        long endNanos = System.nanoTime();
        cpu.add(new long[]{endNanos, ticks(javaPid), ticks(pythonPid)});
        stopping = true;
        synchronized (wake) { wake.notifyAll(); }
        worker.join(3000);

        send(writer, "{\"command\":\"shutdown\"}");
        writer.close();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();

        StringBuilder json = new StringBuilder(1 << 20);
        json.append("{\"startup_ms\":").append((readyNanos - started) / 1e6)
            .append(",\"first_frame_ms\":").append(firstFrame[0] == 0 ? -1 : (firstFrame[0] - registerNanos) / 1e6)
            .append(",\"port\":").append(port).append(",\"clk\":").append(clk)
            .append(",\"t_start_ns\":").append(startNanos).append(",\"t_end_ns\":").append(endNanos)
            .append(",\"cpu\":[");
        for (int i = 0; i < cpu.size(); i++) {
            long[] c = cpu.get(i);
            json.append(i == 0 ? "" : ",").append('[').append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(']');
        }
        json.append("],\"events\":{");
        synchronized (events) {
            boolean firstEvent = true;
            for (Map.Entry<String, Integer> e : events.entrySet()) {
                json.append(firstEvent ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
                firstEvent = false;
            }
        }
        json.append("},\"errors\":[");
        for (int i = 0; i < errors.size(); i++)
            json.append(i == 0 ? "" : ",").append('"').append(errors.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        json.append("],\"frames\":[");
        synchronized (frames) {
            for (int i = 0; i < frames.size(); i++) {
                long[] f = frames.get(i);
                json.append(i == 0 ? "" : ",").append('[');
                for (int k = 0; k < f.length; k++) json.append(k == 0 ? "" : ",").append(f[k]);
                json.append(']');
            }
        }
        json.append("]}");
        Files.writeString(Path.of(arg.get("out")), json);
        System.exit(0);
    }

    static void send(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }
}
