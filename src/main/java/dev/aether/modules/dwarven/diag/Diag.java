package dev.aether.modules.dwarven.diag;

import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// the one entry point for diagnostic events and snapshots; does nothing at all unless diagnostics are on
public final class Diag {
    public static final String STATE = "STATE";
    public static final String NAV = "NAV";
    public static final String PATH = "PATH";
    public static final String ROUTE = "ROUTE";
    public static final String HOP = "HOP";
    public static final String ROT = "ROT";
    public static final String MINE = "MINE";
    public static final String RECOVERY = "RECOVERY";
    public static final String AREA = "AREA";
    public static final String COMBAT = "COMBAT";
    public static final String PERF = "PERF";
    public static final String LOG = "LOG";
    public static final String SNAP = "SNAP";

    private static volatile boolean enabled;
    private static volatile Set<String> off = Set.of();
    private static volatile DiagLog log;
    static LongSupplier wall = System::currentTimeMillis;

    private Diag() {
    }

    public static void configure(boolean on, Set<String> disabledCategories, Supplier<DiagLog> factory) {
        enabled = on;
        off = disabledCategories;
        if (on && log == null) log = factory.get();
    }

    // drops the current log so the next configure starts a new session file
    public static void close() {
        DiagLog l = log;
        log = null;
        enabled = false;
        if (l != null) l.flushNow();
    }

    public static boolean enabled() {
        return enabled && log != null;
    }

    public static boolean on(String category) {
        return enabled() && !off.contains(category);
    }

    public static void event(String category, String text) {
        event(category, text, -1);
    }

    public static void event(String category, String text, long durationMs) {
        event(category, text, durationMs < 0 ? -1.0 : (double) durationMs);
    }

    public static void event(String category, String text, double durationMs) {
        if (!on(category)) return;
        log.offer(new DiagLog.Entry(wall.getAsLong(), Ops.clock.getAsLong(), DiagLog.Kind.EVENT, category, text,
                durationMs < 0 ? -1 : (long) (durationMs * 1e6)));
    }

    public static void snapshot(String body) {
        if (!enabled()) return;
        log.offer(new DiagLog.Entry(wall.getAsLong(), Ops.clock.getAsLong(), DiagLog.Kind.SNAPSHOT, SNAP, body, -1));
    }

    public static void flush(long nowMs) {
        DiagLog l = log;
        if (l != null) l.maybeFlush(nowMs);
    }

    public static DiagLog log() {
        return log;
    }

    // which setting governs an operation's events, by the operation's name
    public static String category(String op) {
        if (op.startsWith("nav.plan") || op.startsWith("path.")) return PATH;
        if (op.startsWith("hop.")) return HOP;
        if (op.startsWith("nav.")) return ROUTE;
        if (op.startsWith("rot.")) return ROT;
        if (op.startsWith("mine.")) return MINE;
        if (op.startsWith("recovery.")) return RECOVERY;
        if (op.startsWith("area.")) return AREA;
        if (op.startsWith("combat.")) return COMBAT;
        if (op.startsWith("stage.")) return STATE;
        return NAV;
    }
}
