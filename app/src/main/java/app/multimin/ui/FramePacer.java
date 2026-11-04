package app.multimin.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nanosecond cadence with at most one pending EDT tick. Never queues stale frames. */
final class FramePacer {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "multimin-frame-pacer");
        thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable callback;
    private ScheduledFuture<?> task;
    private volatile boolean stopped;
    private int targetFps;

    FramePacer(int fps, Runnable callback) { this.callback = callback; setTargetFps(fps); }

    synchronized void setTargetFps(int fps) {
        if (stopped) return;
        if (targetFps == fps) return;
        targetFps = fps;
        if (task != null) task.cancel(false);
        long period = Math.max(1, 1_000_000_000L / Math.max(1, fps));
        task = scheduler.scheduleAtFixedRate(() -> {
            if (!queued.compareAndSet(false, true)) return;
            SwingUtilities.invokeLater(() -> {
                try { if (!stopped) callback.run(); }
                finally { queued.set(false); }
            });
        }, 0, period, TimeUnit.NANOSECONDS);
    }

    synchronized void stop() { stopped = true; if (task != null) task.cancel(false); scheduler.shutdownNow(); }
}
