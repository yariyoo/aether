package dev.aether.modules.dwarven.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// every finished trip by where it started and where it went, and how each way of moving has done, so the next
// trip of the same kind is priced by what it actually took instead of by distance
public final class RouteHistory {
    // what a failed trip costs on top of its own time: the retry wait, a replan, often a forge warp
    public static final double FAILURE_SECONDS = 30.0;
    // a trip this much quicker than the best before it is a faster way found, not noise
    private static final double FASTER = 0.9;
    private static final int TRUSTED = 2;

    public static final class Journey {
        public int trips;
        public int successes;
        public int failures;
        public long msSum;
        public long bestMs;
        public long lastMs;
        public int recoveries;
        public int hops;

        public double meanSeconds() {
            return successes == 0 ? 0 : msSum / 1000.0 / successes;
        }

        public double failureRate() {
            return trips == 0 ? 0 : (double) failures / trips;
        }
    }

    public static final class Method {
        public int successes;
        public int failures;
        public long successMs;
        public long failureMs;

        // successes and failures both start at one, so a couple of lucky tries don't make a method look certain
        public double successRate() {
            return (successes + 1.0) / (successes + failures + 2.0);
        }

        public double successSeconds(double fallback) {
            return successes == 0 ? fallback : successMs / 1000.0 / successes;
        }

        public double failureSeconds(double fallback) {
            return failures == 0 ? fallback : failureMs / 1000.0 / failures;
        }
    }

    public record Outcome(boolean firstSuccess, boolean faster, double previousMeanSeconds, double savedSeconds) {}

    private final Map<String, Journey> journeys = new HashMap<>();
    private final Map<String, Method> methods = new HashMap<>();
    private double savedSeconds;
    private boolean dirty;

    public boolean dirty() {
        return dirty;
    }

    private static String key(String from, String to) {
        return from + " -> " + to;
    }

    public Outcome record(String from, String to, boolean success, long ms, int hops, int recoveries) {
        Journey j = journeys.computeIfAbsent(key(from, to), k -> new Journey());
        double before = j.meanSeconds();
        boolean hadSuccess = j.successes > 0;
        boolean faster = success && hadSuccess && ms < j.bestMs * FASTER;
        j.trips++;
        j.recoveries += recoveries;
        if (success) {
            j.successes++;
            j.msSum += ms;
            j.lastMs = ms;
            j.hops = hops;
            j.bestMs = j.bestMs == 0 ? ms : Math.min(j.bestMs, ms);
        } else {
            j.failures++;
        }
        double saved = success && hadSuccess ? before - ms / 1000.0 : 0;
        savedSeconds += saved;
        dirty = true;
        return new Outcome(success && !hadSuccess, faster, before, saved);
    }

    public Journey journey(String from, String to) {
        return journeys.get(key(from, to));
    }

    // measured trip time once a couple of trips have finished, with failures priced in; fallback until then
    public double expectedSeconds(String from, String to, double fallback) {
        Journey j = journeys.get(key(from, to));
        if (j == null || j.successes < TRUSTED) return fallback;
        return j.meanSeconds() + j.failureRate() * FAILURE_SECONDS;
    }

    public boolean trusted(String from, String to) {
        Journey j = journeys.get(key(from, to));
        return j != null && j.successes >= TRUSTED;
    }

    public void method(String name, boolean success, long ms) {
        Method m = methods.computeIfAbsent(name, k -> new Method());
        if (success) {
            m.successes++;
            m.successMs += ms;
        } else {
            m.failures++;
            m.failureMs += ms;
        }
        dirty = true;
    }

    public Method method(String name) {
        return methods.computeIfAbsent(name, k -> new Method());
    }

    public int count() {
        return journeys.size();
    }

    public int verifiedCount() {
        return (int) journeys.values().stream().filter(j -> j.successes > 0).count();
    }

    public double savedSeconds() {
        return savedSeconds;
    }

    // average trip time to each destination over every start that reached it
    public List<String> byDestination(int limit) {
        Map<String, long[]> by = new HashMap<>();
        journeys.forEach((k, j) -> {
            if (j.successes == 0) return;
            long[] a = by.computeIfAbsent(k.substring(k.indexOf(" -> ") + 4), x -> new long[2]);
            a[0] += j.msSum;
            a[1] += j.successes;
        });
        List<String> out = new ArrayList<>();
        by.entrySet().stream().sorted(Comparator.comparingLong(e -> -e.getValue()[1])).limit(limit)
                .forEach(e -> out.add(String.format("%s %.1fs over %d trips", e.getKey(), e.getValue()[0] / 1000.0 / e.getValue()[1], e.getValue()[1])));
        return out;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject js = new JsonObject();
        journeys.forEach((k, j) -> {
            JsonArray a = new JsonArray();
            for (long v : new long[]{j.trips, j.successes, j.failures, j.msSum, j.bestMs, j.lastMs, j.recoveries, j.hops}) a.add(v);
            js.add(k, a);
        });
        root.add("journeys", js);
        JsonObject ms = new JsonObject();
        methods.forEach((k, m) -> {
            JsonArray a = new JsonArray();
            for (long v : new long[]{m.successes, m.failures, m.successMs, m.failureMs}) a.add(v);
            ms.add(k, a);
        });
        root.add("methods", ms);
        root.addProperty("saved", savedSeconds);
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        journeys.clear();
        methods.clear();
        if (root.has("journeys")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("journeys").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                Journey j = new Journey();
                j.trips = a.get(0).getAsInt();
                j.successes = a.get(1).getAsInt();
                j.failures = a.get(2).getAsInt();
                j.msSum = a.get(3).getAsLong();
                j.bestMs = a.get(4).getAsLong();
                j.lastMs = a.get(5).getAsLong();
                j.recoveries = a.get(6).getAsInt();
                j.hops = a.get(7).getAsInt();
                journeys.put(e.getKey(), j);
            }
        }
        if (root.has("methods")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("methods").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                Method m = new Method();
                m.successes = a.get(0).getAsInt();
                m.failures = a.get(1).getAsInt();
                m.successMs = a.get(2).getAsLong();
                m.failureMs = a.get(3).getAsLong();
                methods.put(e.getKey(), m);
            }
        }
        if (root.has("saved")) savedSeconds = root.get("saved").getAsDouble();
        dirty = false;
    }
}
