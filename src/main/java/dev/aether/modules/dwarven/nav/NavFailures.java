package dev.aether.modules.dwarven.nav;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// trips that failed, kept across macro stages so a destination that keeps failing from the same place isn't chosen again
public final class NavFailures {
    // start and goal are compared by region, so a few blocks of drift don't count as a new situation
    private static final int REGION = 24;
    private static final long REMEMBER_MS = 10 * 60_000L;
    private static final int BLOCKED_AFTER = 2;

    public record Failure(BlockPos goal, BlockPos from, String reason, int count, long lastAt) {}

    private static final Map<Long, Failure> FAILURES = new HashMap<>();

    private NavFailures() {}

    private static long key(BlockPos goal, BlockPos from) {
        long g = BlockPos.asLong(Math.floorDiv(goal.getX(), REGION), Math.floorDiv(goal.getY(), REGION), Math.floorDiv(goal.getZ(), REGION));
        long f = BlockPos.asLong(Math.floorDiv(from.getX(), REGION), Math.floorDiv(from.getY(), REGION), Math.floorDiv(from.getZ(), REGION));
        return g * 31 + f;
    }

    public static synchronized Failure record(BlockPos goal, BlockPos from, String reason, long now) {
        prune(now);
        Failure old = FAILURES.get(key(goal, from));
        Failure next = new Failure(goal, from, reason, old == null ? 1 : old.count() + 1, now);
        FAILURES.put(key(goal, from), next);
        return next;
    }

    // the same trip from the same place has failed enough that trying it unchanged would just fail again
    public static synchronized boolean blocked(BlockPos goal, BlockPos from, long now) {
        prune(now);
        Failure f = FAILURES.get(key(goal, from));
        return f != null && f.count() >= BLOCKED_AFTER;
    }

    public static synchronized Failure lastFor(BlockPos goal, BlockPos from, long now) {
        prune(now);
        return FAILURES.get(key(goal, from));
    }

    // goals that failed from near here, so a destination can be swapped for a different part of the same area
    public static synchronized List<BlockPos> failedGoalsFrom(BlockPos from, long now) {
        prune(now);
        List<BlockPos> out = new ArrayList<>();
        for (Failure f : FAILURES.values()) {
            if (Math.floorDiv(f.from().getX(), REGION) == Math.floorDiv(from.getX(), REGION)
                    && Math.floorDiv(f.from().getZ(), REGION) == Math.floorDiv(from.getZ(), REGION)) out.add(f.goal());
        }
        return out;
    }

    public static synchronized void succeeded(BlockPos goal) {
        FAILURES.values().removeIf(f -> f.goal().distSqr(goal) <= REGION * REGION);
    }

    private static void prune(long now) {
        FAILURES.values().removeIf(f -> now - f.lastAt() > REMEMBER_MS);
    }

    public static synchronized void clear() {
        FAILURES.clear();
    }
}
