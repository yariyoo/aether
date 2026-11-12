package dev.aether.modules.dwarven.fight;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// what each mob we swung at did afterwards: attacks and confirmed deaths are kept apart, and one-hit kills are learned
public final class AttackLog {
    public enum Mode { ADAPTIVE, FAST, CONFIRMED }

    public static final class Entry {
        public final int id;
        public long firstHitAt;
        public long lastHitAt;
        public int hits;
        public boolean deathSeen;

        Entry(int id) {
            this.id = id;
        }
    }

    // adaptive mode needs a few deaths before trusting that one hit is enough
    private static final int LEARN_MIN_KILLS = 4;
    private static final double ONE_HIT_RATE = 0.8;
    private static final int HISTORY = 20;
    private static final long FORGET_MS = 30_000L;

    private final Map<Integer, Entry> entries = new HashMap<>();
    private final ArrayDeque<Integer> recentKillHits = new ArrayDeque<>();
    private int kills;
    private int attacks;

    public void recordHit(int id, long now) {
        Entry e = entries.computeIfAbsent(id, Entry::new);
        if (e.hits == 0) e.firstHitAt = now;
        e.hits++;
        e.lastHitAt = now;
        attacks++;
    }

    // returns true the first time a death is seen for a mob we had hit
    public boolean recordDeath(int id) {
        Entry e = entries.get(id);
        if (e == null || e.deathSeen) return false;
        e.deathSeen = true;
        kills++;
        recentKillHits.addLast(e.hits);
        if (recentKillHits.size() > HISTORY) recentKillHits.removeFirst();
        return true;
    }

    public Entry get(int id) {
        return entries.get(id);
    }

    public int kills() {
        return kills;
    }

    public int attacks() {
        return attacks;
    }

    public double oneHitRate() {
        if (recentKillHits.isEmpty()) return 0;
        int one = 0;
        for (int h : recentKillHits) if (h <= 1) one++;
        return (double) one / recentKillHits.size();
    }

    // whether hits can move on without waiting to see the mob die
    public boolean moveOnAfterHit(Mode mode, boolean learning) {
        return switch (mode) {
            case FAST -> true;
            case CONFIRMED -> false;
            case ADAPTIVE -> !learning || recentKillHits.size() >= LEARN_MIN_KILLS && oneHitRate() >= ONE_HIT_RATE;
        };
    }

    // a mob hit moments ago is left alone while its outcome is pending, unless it has visibly survived for a while
    public boolean pending(int id, long now, long revisitMs) {
        Entry e = entries.get(id);
        return e != null && !e.deathSeen && now - e.lastHitAt < revisitMs;
    }

    public int pendingCount(long now, long confirmMs) {
        int n = 0;
        for (Entry e : entries.values()) if (!e.deathSeen && now - e.lastHitAt < confirmMs) n++;
        return n;
    }

    public Iterable<Entry> unresolved() {
        return () -> entries.values().stream().filter(e -> !e.deathSeen).iterator();
    }

    public void forgetOld(long now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastHitAt > FORGET_MS) it.remove();
        }
    }

    public void reset() {
        entries.clear();
    }
}
