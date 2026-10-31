package app.multimin.backend;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.awt.Image;
import java.awt.Graphics2D;
import java.awt.Color;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Captures one physical display locally; frames are kept in memory only. */
public final class DisplayCapture implements AutoCloseable {
    private final Robot robot;
    private final Rectangle bounds;
    private final double scaleX;
    private final double scaleY;
    private final int displayModeWidth;
    private final int displayModeHeight;
    private final ScheduledExecutorService capture = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "multimin-display-capture");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private volatile boolean enabled = true;
    private volatile Supplier<Rectangle> excludedRegion = () -> null;
    private volatile Consumer<BufferedImage> frameConsumer;
    private volatile Consumer<Exception> errorConsumer;
    private volatile int targetFps = 10;
    private volatile double measuredFps;
    private volatile double lastFrameMillis;
    private final AtomicLong framesInWindow = new AtomicLong();
    private volatile long sampleStartedNanos = System.nanoTime();
    private volatile ScheduledFuture<?> captureTask;

    public DisplayCapture() throws Exception {
        this(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
    }

    public DisplayCapture(GraphicsDevice screen) throws Exception {
        bounds = screen.getDefaultConfiguration().getBounds();
        scaleX = screen.getDefaultConfiguration().getDefaultTransform().getScaleX();
        scaleY = screen.getDefaultConfiguration().getDefaultTransform().getScaleY();
        displayModeWidth = screen.getDisplayMode().getWidth();
        displayModeHeight = screen.getDisplayMode().getHeight();
        robot = new Robot(screen);
    }

    public void start(Consumer<BufferedImage> onFrame, Consumer<Exception> onError) {
        if (running) return;
        running = true;
        frameConsumer = onFrame;
        errorConsumer = onError;
        scheduleCapture();
    }

    public synchronized void setTargetFps(int fps) {
        targetFps = Math.max(1, Math.min(240, fps));
        if (running) scheduleCapture();
    }

    public double getMeasuredFps() { return measuredFps; }
    public double getLastFrameMillis() { return lastFrameMillis; }

    private synchronized void scheduleCapture() {
        if (!running) return;
        if (captureTask != null) captureTask.cancel(false);
        long period = Math.max(1, (long) Math.ceil(1000.0 / targetFps));
        captureTask = capture.scheduleAtFixedRate(() -> {
            if (!running || !enabled) return;
            long started = System.nanoTime();
            try {
                MultiResolutionImage capture = robot.createMultiResolutionScreenCapture(bounds);
                Image variant = capture.getResolutionVariants().stream()
                    .max(java.util.Comparator.comparingLong(image -> (long) image.getWidth(null) * image.getHeight(null)))
                    .orElseGet(() -> capture.getResolutionVariant(
                        Math.max(displayModeWidth, (int) Math.round(bounds.width * scaleX)),
                        Math.max(displayModeHeight, (int) Math.round(bounds.height * scaleY))));
                if (variant.getWidth(null) < displayModeWidth || variant.getHeight(null) < displayModeHeight) {
                    variant = capture.getResolutionVariant(displayModeWidth, displayModeHeight);
                }
                BufferedImage frame;
                if (variant instanceof BufferedImage image && image.getWidth() == displayModeWidth && image.getHeight() == displayModeHeight) frame = image;
                else {
                    frame = new BufferedImage(displayModeWidth, displayModeHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D draw = frame.createGraphics(); draw.drawImage(variant, 0, 0, displayModeWidth, displayModeHeight, null); draw.dispose();
                }
                Rectangle excluded = excludedRegion.get();
                if (excluded != null) {
                    Rectangle clipped = excluded.intersection(bounds);
                    if (!clipped.isEmpty()) {
                        Graphics2D graphics = frame.createGraphics();
                        graphics.setColor(Color.BLACK);
                        int left = (int) Math.round((clipped.x - bounds.x) * frame.getWidth() / (double) bounds.width);
                        int top = (int) Math.round((clipped.y - bounds.y) * frame.getHeight() / (double) bounds.height);
                        int width = (int) Math.round(clipped.width * frame.getWidth() / (double) bounds.width);
                        int height = (int) Math.round(clipped.height * frame.getHeight() / (double) bounds.height);
                        graphics.fillRect(left, top, width, height);
                        graphics.dispose();
                    }
                }
                frameConsumer.accept(frame);
                lastFrameMillis = (System.nanoTime() - started) / 1_000_000.0;
                long count = framesInWindow.incrementAndGet();
                long now = System.nanoTime();
                long elapsed = now - sampleStartedNanos;
                if (elapsed >= 1_000_000_000L) {
                    measuredFps = count * 1_000_000_000.0 / elapsed;
                    framesInWindow.set(0);
                    sampleStartedNanos = now;
                }
            }
            catch (Exception error) { errorConsumer.accept(error); }
        }, 0, period, TimeUnit.MILLISECONDS);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setExcludedRegionSupplier(Supplier<Rectangle> supplier) { excludedRegion = supplier == null ? () -> null : supplier; }

    @Override public void close() {
        running = false;
        if (captureTask != null) captureTask.cancel(true);
        capture.shutdownNow();
    }
}
