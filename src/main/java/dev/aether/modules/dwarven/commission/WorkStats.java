package dev.aether.modules.dwarven.commission;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

// how long each commission really took from 0 to done, learned from finished ones and blended over time
public final class WorkStats {
    private static final double BLEND = 0.3;
    private final Map<String, Double> seconds = new HashMap<>();
    private final double fallback;

    public WorkStats(double fallbackSeconds) {
        this.fallback = fallbackSeconds;
    }

    public double secondsFor(String commission) {
        return seconds.getOrDefault(commission, fallback);
    }

    // progressGained is the share of the commission done in that time, so partial runs still teach something
    public void record(String commission, double tookSeconds, double progressGained) {
        if (progressGained < 0.2 || tookSeconds <= 0) return;
        double full = tookSeconds / progressGained;
        seconds.merge(commission, full, (old, now) -> old * (1 - BLEND) + now * BLEND);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        seconds.forEach(o::addProperty);
        return o;
    }

    public void load(JsonObject o) {
        for (String key : o.keySet()) seconds.put(key, o.get(key).getAsDouble());
    }
}
