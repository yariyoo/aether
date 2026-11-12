package dev.aether.modules.dwarven.diag;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// diagnostic records go into a bounded queue on the game thread and are written in batches elsewhere. a full queue
// drops the record and counts it, so the game thread never waits on the disk
public final class DiagLog {
    public enum Kind { EVENT, SNAPSHOT }

    // immutable, so the writer thread never touches live game state
    public record Entry(long wallMs, long monoNs, Kind kind, String category, String text, long durationNs) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final long FLUSH_EVERY_MS = 250L;
    // a flush that was submitted but never ran (its task dropped) stops blocking new ones after this
    private static final long FLUSH_STALE_MS = 3000L;

    private final Path dir;
    private final String baseName;
    private final ArrayBlockingQueue<Entry> queue;
    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private final int ringCapacity;
    private final long maxFileBytes;
    private final int rotations;
    private final Executor writer;
    private final long sessionStartNs;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedReported = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile long flushSubmittedAt;
    private long lastFlushAt;
    private final Object fileLock = new Object();

    public DiagLog(Path dir, String baseName, int queueCapacity, int ringCapacity, long maxFileBytes, int rotations, Executor writer,
                   long sessionStartNs) {
        this.dir = dir;
        this.baseName = baseName;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
        this.ringCapacity = Math.max(16, ringCapacity);
        this.maxFileBytes = Math.max(64 * 1024, maxFileBytes);
        this.rotations = Math.max(1, rotations);
        this.writer = writer;
        this.sessionStartNs = sessionStartNs;
    }

    public boolean offer(Entry e) {
        synchronized (ring) {
            ring.addLast(e);
            while (ring.size() > ringCapacity) ring.removeFirst();
        }
        if (queue.offer(e)) return true;
        dropped.incrementAndGet();
        return false;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public int queued() {
        return queue.size();
    }

    // called from the game thread; hands the batch to the writer when it's time or the queue is filling up
    public void maybeFlush(long nowMs) {
        if (queue.isEmpty()) return;
        boolean due = nowMs - lastFlushAt >= FLUSH_EVERY_MS || queue.size() >= queue.remainingCapacity();
        if (!due) return;
        if (flushing.get() && nowMs - flushSubmittedAt < FLUSH_STALE_MS) return;
        flushing.set(true);
        flushSubmittedAt = nowMs;
        lastFlushAt = nowMs;
        writer.execute(this::flushNow);
    }

    public void flushNow() {
        try {
            List<Entry> batch = new ArrayList<>(queue.size() + 1);
            queue.drainTo(batch);
            long lost = dropped.get() - droppedReported.get();
            if (batch.isEmpty() && lost == 0) return;
            StringBuilder out = new StringBuilder(batch.size() * 160);
            if (lost > 0) {
                droppedReported.addAndGet(lost);
                out.append("[DIAG] ").append(lost).append(" records dropped because the queue was full (").append(dropped.get())
                        .append(" this session)\n");
            }
            for (Entry e : batch) format(out, e).append('\n');
            synchronized (fileLock) {
                Files.createDirectories(dir);
                Path file = dir.resolve(baseName + ".log");
                if (Files.exists(file) && Files.size(file) + out.length() > maxFileBytes) rotate();
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.append(out);
                }
            }
            written.addAndGet(batch.size());
        } catch (IOException ignored) {
            // diagnostics must never take the macro down; the records are simply lost
        } finally {
            flushing.set(false);
        }
    }

    // name.log becomes name.1.log and so on, the oldest falling off the end
    private void rotate() throws IOException {
        Path oldest = dir.resolve(baseName + "." + rotations + ".log");
        Files.deleteIfExists(oldest);
        for (int i = rotations - 1; i >= 1; i--) {
            Path from = dir.resolve(baseName + "." + i + ".log");
            if (Files.exists(from)) Files.move(from, dir.resolve(baseName + "." + (i + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(dir.resolve(baseName + ".log"), dir.resolve(baseName + ".1.log"), StandardCopyOption.REPLACE_EXISTING);
    }

    public StringBuilder format(StringBuilder out, Entry e) {
        out.append('[').append(TIME.format(Instant.ofEpochMilli(e.wallMs()))).append("] ");
        out.append(String.format("+%.3fs ", (e.monoNs() - sessionStartNs) / 1e9));
        out.append('[').append(e.category()).append("] ").append(e.text());
        if (e.durationNs() >= 0) out.append(String.format(" (%.1fms)", e.durationNs() / 1e6));
        return out;
    }

    public List<Entry> recent() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    // writes what the ring buffer holds plus a summary to its own file; done by the writer, never the game thread
    public void export(Path file, String summary, Runnable done) {
        List<Entry> copy = recent();
        writer.execute(() -> {
            try {
                StringBuilder out = new StringBuilder(copy.size() * 160 + summary.length() + 64);
                out.append(summary).append("\n\n");
                for (Entry e : copy) format(out, e).append('\n');
                Files.createDirectories(file.getParent());
                Files.writeString(file, out, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // nothing to recover; the caller is told it finished either way
            }
            if (done != null) done.run();
        });
    }
}
