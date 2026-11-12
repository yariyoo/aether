package dev.aether.modules.dwarven;

import java.util.EnumMap;
import java.util.Map;

// every kind of failure gets a fixed number of tries with a growing wait, so recovery can never loop forever
public final class RetryBudget {
    public enum Failure { NAVIGATION, NO_ORE, CLAIM, COMMISSIONS_UNREADABLE, TOOL_MISSING, WRONG_AREA, MINING_STALLED }

    public record Verdict(boolean retry, long waitMs, int attempt) {}

    private final Map<Failure, Integer> counts = new EnumMap<>(Failure.class);
    private final int maxAttempts;
    private final long baseWaitMs;

    public RetryBudget(int maxAttempts, long baseWaitMs) {
        this.maxAttempts = maxAttempts;
        this.baseWaitMs = baseWaitMs;
    }

    public Verdict fail(Failure failure) {
        int attempt = counts.merge(failure, 1, Integer::sum);
        if (attempt > maxAttempts) return new Verdict(false, 0, attempt);
        return new Verdict(true, baseWaitMs * (1L << Math.min(attempt - 1, 4)), attempt);
    }

    public void succeeded(Failure failure) {
        counts.remove(failure);
    }

    public int attempts(Failure failure) {
        return counts.getOrDefault(failure, 0);
    }

    public int total() {
        int sum = 0;
        for (int v : counts.values()) sum += v;
        return sum;
    }

    public void reset() {
        counts.clear();
    }
}
