package dev.aether.modules.dwarven.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

// timed operations: wall-clock elapsed time from start to finish on a monotonic clock, which includes waiting for ticks
// and the server, unlike cpu time. open operations are watched for overrunning without making progress
public final class Ops {
    // a moving operation counts as stalled when it neither finished nor reported progress for this long
    static final long STALL_MS = 1500L;
    private static final int RECENT = 512;

    public static final class Op {
        public final String name;
        final long startNs;
        long lastProgressNs;
        final long expectedMs;
        boolean warned;
        boolean slowNoted;
        boolean done;
        String detail = "";

        Op(String name, long expectedMs, long now) {
            this.name = name;
            this.expectedMs = expectedMs;
            this.startNs = now;
            this.lastProgressNs = now;
        }

        public void progress() {
            lastProgressNs = clock.getAsLong();
        }

        public void detail(String d) {
            detail = d == null ? "" : d;
        }

        public long elapsedMs() {
            return (clock.getAsLong() - startNs) / 1_000_000L;
        }

        public long elapsedNs() {
            return clock.getAsLong() - startNs;
        }

        // returns the elapsed milliseconds; an operation is only ever counted once
        public long end(String outcome) {
            long ns = elapsedNs();
            long ms = ns / 1_000_000L;
            synchronized (Ops.class) {
                if (done) return ms;
                done = true;
                OPEN.remove(this);
                addNs(name, ns);
                lastEnded = String.format("%s:%.1fms", name, ns / 1e6);
            }
            Diag.event(Diag.category(name), name + " " + (outcome == null ? "done" : outcome)
                    + (detail.isEmpty() ? "" : " (" + detail + ")"), ns / 1e6);
            return ms;
        }

        public void cancel(String why) {
            synchronized (Ops.class) {
                if (done) return;
                done = true;
                OPEN.remove(this);
            }
            Diag.event(Diag.category(name), name + " cancelled after " + elapsedMs() + "ms: " + why);
        }

        public boolean done() {
            return done;
        }
    }

    public record Summary(String name, int count, double meanMs, double p95Ms, double maxMs, double totalMs) {}

    // kept in nanoseconds: route planning takes about a millisecond, which whole milliseconds would round away
    private static final class Stat {
        int count;
        long totalNs;
        long maxNs;
        final long[] recent = new long[RECENT];
        int recentN;
        int next;

        void add(long ns) {
            count++;
            totalNs += ns;
            maxNs = Math.max(maxNs, ns);
            recent[next] = ns;
            next = (next + 1) % RECENT;
            recentN = Math.min(recentN + 1, RECENT);
        }

        // over the most recent samples, nearest-rank
        double p95Ms() {
            if (recentN == 0) return 0;
            long[] s = Arrays.copyOf(recent, recentN);
            Arrays.sort(s);
            int rank = (int) Math.ceil(0.95 * recentN) - 1;
            return s[Math.max(0, Math.min(recentN - 1, rank))] / 1e6;
        }
    }

    static LongSupplier clock = System::nanoTime;
    private static volatile String lastEnded = "-";
    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private static final List<Op> OPEN = new ArrayList<>();

    private Ops() {
    }

    public static synchronized Op begin(String name, long expectedMs) {
        Op op = new Op(name, expectedMs, clock.getAsLong());
        OPEN.add(op);
        return op;
    }

    // for durations measured elsewhere, e.g. between two ticks
    public static synchronized void record(String name, long ms) {
        addNs(name, ms * 1_000_000L);
    }

    public static synchronized void recordNanos(String name, long ns) {
        addNs(name, ns);
    }

    private static synchronized void addNs(String name, long ns) {
        STATS.computeIfAbsent(name, k -> new Stat()).add(Math.max(0, ns));
    }

    public static synchronized Summary summary(String name) {
        Stat s = STATS.get(name);
        if (s == null) return new Summary(name, 0, 0, 0, 0, 0);
        return new Summary(name, s.count, s.totalNs / 1e6 / s.count, s.p95Ms(), s.maxNs / 1e6, s.totalNs / 1e6);
    }

    public static synchronized List<Summary> all() {
        List<Summary> out = new ArrayList<>();
        for (String n : STATS.keySet()) out.add(summary(n));
        return out;
    }

    public static synchronized double meanMs(String name, double fallback) {
        Stat s = STATS.get(name);
        return s == null || s.count == 0 ? fallback : s.totalNs / 1e6 / s.count;
    }

    // warnings for open operations past their expected time: slow but still progressing is noted once and left alone,
    // no progress at all is a stall. nothing here interrupts anything; recovery stays with the code that owns the operation
    public static synchronized List<String> watchdog(double slack) {
        List<String> out = new ArrayList<>();
        long now = clock.getAsLong();
        for (Op op : OPEN) {
            if (op.expectedMs <= 0) continue;
            long elapsed = (now - op.startNs) / 1_000_000L;
            long limit = (long) (op.expectedMs * slack);
            if (elapsed <= limit) continue;
            long sinceProgress = (now - op.lastProgressNs) / 1_000_000L;
            if (sinceProgress >= STALL_MS && !op.warned) {
                op.warned = true;
                out.add(String.format("[PERF] WARNING: %s exceeded expected duration by %dms with no progress for %dms%s",
                        op.name, elapsed - op.expectedMs, sinceProgress, op.detail.isEmpty() ? "" : " (" + op.detail + ")"));
            } else if (sinceProgress < STALL_MS && !op.slowNoted) {
                op.slowNoted = true;
                out.add(String.format("[PERF] %s is %dms past its expected %dms but still progressing", op.name, elapsed - op.expectedMs,
                        op.expectedMs));
            }
        }
        return out;
    }

    public static synchronized void cancelAll(String why) {
        Iterator<Op> it = new ArrayList<>(OPEN).iterator();
        while (it.hasNext()) it.next().cancel(why);
        OPEN.clear();
    }

    public static String lastEnded() {
        return lastEnded;
    }

    // the longest-running open operation and how long it has been waiting
    public static synchronized String oldestOpen() {
        Op oldest = null;
        for (Op op : OPEN) if (oldest == null || op.startNs < oldest.startNs) oldest = op;
        return oldest == null ? "-" : oldest.name + ":" + oldest.elapsedMs() + "ms";
    }

    public static synchronized int openCount() {
        return OPEN.size();
    }

    public static synchronized void reset() {
        STATS.clear();
        OPEN.clear();
    }
}
