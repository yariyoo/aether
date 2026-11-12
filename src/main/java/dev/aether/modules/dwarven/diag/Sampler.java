package dev.aether.modules.dwarven.diag;

// decides when a periodic snapshot is due and reports how far the real interval drifted from the asked one. the
// game thread decides when we get to run, so a late sample is recorded as late rather than pretended to be on time
public final class Sampler {
    public record Sample(long intervalNs, long lateNs, int missed) {}

    private long intervalNs;
    private long lastNs = Long.MIN_VALUE;
    private long samples;
    private long lateSamples;
    private long missedDeadlines;

    public Sampler(long intervalMs) {
        interval(intervalMs);
    }

    public void interval(long intervalMs) {
        intervalNs = Math.max(1, intervalMs) * 1_000_000L;
    }

    // a tenth of the interval either side counts as on time, since ticks themselves wobble by a millisecond or two
    public Sample due(long nowNs) {
        long tolerance = intervalNs / 10;
        if (lastNs == Long.MIN_VALUE) {
            lastNs = nowNs;
            samples++;
            return new Sample(0, 0, 0);
        }
        long elapsed = nowNs - lastNs;
        if (elapsed < intervalNs - tolerance) return null;
        long late = Math.max(0, elapsed - intervalNs - tolerance);
        int missed = (int) Math.max(0, (elapsed + tolerance) / intervalNs - 1);
        lastNs = nowNs;
        samples++;
        if (late > 0) lateSamples++;
        missedDeadlines += missed;
        return new Sample(elapsed, late, missed);
    }

    public long samples() {
        return samples;
    }

    public long lateSamples() {
        return lateSamples;
    }

    public long missedDeadlines() {
        return missedDeadlines;
    }

    public void reset() {
        lastNs = Long.MIN_VALUE;
        samples = 0;
        lateSamples = 0;
        missedDeadlines = 0;
    }
}
