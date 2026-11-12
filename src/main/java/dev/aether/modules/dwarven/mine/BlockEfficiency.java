package dev.aether.modules.dwarven.mine;

import dev.aether.modules.dwarven.world.OreKind;

import java.util.EnumMap;
import java.util.Map;

// what each ore is worth to the commission in hand and how long it really takes to break. break times start from
// hypixel's block strengths and follow measured breaks once there are some, so nothing assumes which mithril is fastest
public final class BlockEfficiency {
    public enum Mode { FASTEST, BLUE_FIRST, CLOSEST, BALANCED, CUSTOM }

    // titanium work counts titanium; mithril work counts mithril, and titanium only when another commission wants it too
    public record Job(boolean titanium, boolean titaniumAlsoCounts, boolean mineMithrilForTitanium) {}

    // block strengths: gray 500, green (prismarine) 800, light blue wool 1500, titanium 2000
    private static final Map<OreKind, Double> STRENGTH = new EnumMap<>(Map.of(OreKind.MITHRIL_GRAY, 500.0, OreKind.MITHRIL_GREEN, 800.0,
            OreKind.MITHRIL_BLUE, 1500.0, OreKind.TITANIUM, 2000.0));
    private static final double DEFAULT_MS_PER_STRENGTH = 1.2;
    // how much a fresh break moves the running average
    private static final double LEARN_RATE = 0.2;
    private static final int TRUST_AFTER = 3;
    private static final double PRIOR_SPAWN_RATE = 0.05;

    private final Map<OreKind, Double> breakMs = new EnumMap<>(OreKind.class);
    private final Map<OreKind, Integer> samples = new EnumMap<>(OreKind.class);
    private int mithrilBreaks;
    private int titaniumSpawns;

    public void broke(OreKind kind, long ms) {
        if (kind == null || ms <= 0 || ms > 20_000) return;
        int n = samples.merge(kind, 1, Integer::sum);
        breakMs.merge(kind, (double) ms, (old, now) -> n <= 1 ? now : old + (now - old) * LEARN_RATE);
        if (kind.isMithril()) mithrilBreaks++;
    }

    public void titaniumSpawned() {
        titaniumSpawns++;
    }

    // expected milliseconds to break one; an unmeasured ore is scaled from the measured ones by block strength
    public double breakMs(OreKind kind, boolean learning) {
        if (learning && samples.getOrDefault(kind, 0) >= TRUST_AFTER) return breakMs.get(kind);
        double perStrength = DEFAULT_MS_PER_STRENGTH;
        if (learning) {
            double sum = 0;
            int n = 0;
            for (var e : breakMs.entrySet()) {
                if (samples.getOrDefault(e.getKey(), 0) < TRUST_AFTER) continue;
                sum += e.getValue() / STRENGTH.get(e.getKey());
                n++;
            }
            if (n > 0) perStrength = sum / n;
        }
        return STRENGTH.get(kind) * perStrength;
    }

    // share of mithril breaks that turned up titanium, with a prior until enough breaks are counted
    public double spawnRate() {
        if (mithrilBreaks < 20) return PRIOR_SPAWN_RATE;
        return Math.max(0.005, (double) titaniumSpawns / mithrilBreaks);
    }

    // commission progress one block of this ore is worth to the job; zero means don't mine it
    public double contribution(OreKind kind, Job job) {
        if (kind == OreKind.TITANIUM) return job.titanium() || job.titaniumAlsoCounts() ? 1.0 : 0.0;
        if (!job.titanium()) return 1.0;
        return job.mineMithrilForTitanium() ? spawnRate() : 0.0;
    }

    // seconds of work per unit of commission progress for this block, the lower the better
    public double cost(OreKind kind, Job job, Mode mode, double aimMs, double moveMs, boolean learning, Map<OreKind, Double> weights) {
        double value = contribution(kind, job);
        if (value <= 0) return Double.MAX_VALUE;
        double breakTime = breakMs(kind, learning);
        double weight = weights.getOrDefault(kind, 1.0);
        return switch (mode) {
            case FASTEST -> (breakTime + aimMs + moveMs) / value;
            case CLOSEST -> aimMs + moveMs - value;
            case BALANCED -> (0.5 * breakTime + aimMs + moveMs) / value;
            case BLUE_FIRST -> (breakTime + aimMs + moveMs) / value / (kind == OreKind.MITHRIL_BLUE ? Math.max(1.0, weight) * 4.0 : 1.0);
            case CUSTOM -> (breakTime + aimMs + moveMs) / (value * Math.max(0.05, weight));
        };
    }

    public int samples(OreKind kind) {
        return samples.getOrDefault(kind, 0);
    }

    public void reset() {
        breakMs.clear();
        samples.clear();
        mithrilBreaks = 0;
        titaniumSpawns = 0;
    }
}
