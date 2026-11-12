package dev.aether.modules.dwarven.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

// every standing spot the macro has mined from, with what it actually produced there
public final class SpotMemory {
    public static final class Spot {
        public final BlockPos pos;
        public String area;
        public int visits;
        public int blocks;
        public int titanium;
        public long miningMs;
        public int failures;
        public long lastVisit;
        // not saved: ore comes back, so an empty spot is only a note for this session
        public long emptyAt;
        public long blockedUntil;

        Spot(BlockPos pos, String area) {
            this.pos = pos;
            this.area = area;
        }

        // blocks per minute here, or the prior when the spot hasn't been worked long enough to judge
        public double rate(double prior) {
            if (miningMs < 20_000L) return prior;
            return blocks * 60_000.0 / miningMs;
        }
    }

    private static final int SAME_SPOT = 1;
    private final List<Spot> spots = new ArrayList<>();
    private boolean dirty;

    public List<Spot> all() {
        return spots;
    }

    public boolean dirty() {
        return dirty;
    }

    public Spot at(BlockPos pos) {
        for (Spot s : spots) {
            if (Math.abs(s.pos.getX() - pos.getX()) <= SAME_SPOT && Math.abs(s.pos.getY() - pos.getY()) <= SAME_SPOT
                    && Math.abs(s.pos.getZ() - pos.getZ()) <= SAME_SPOT) return s;
        }
        return null;
    }

    public Spot visit(BlockPos pos, String area, long now) {
        Spot s = at(pos);
        if (s == null) {
            s = new Spot(pos.immutable(), area);
            spots.add(s);
        }
        if (area != null) s.area = area;
        s.visits++;
        s.lastVisit = now;
        dirty = true;
        return s;
    }

    public void mined(Spot spot, boolean titanium, long tookMs) {
        spot.blocks++;
        if (titanium) spot.titanium++;
        spot.miningMs += Math.min(tookMs, 5000L);
        spot.failures = 0;
        spot.emptyAt = 0;
        dirty = true;
    }

    public void idle(Spot spot, long ms) {
        spot.miningMs += ms;
        dirty = true;
    }

    public void empty(Spot spot, long now) {
        spot.emptyAt = now;
    }

    public void unreachable(BlockPos pos, long now, int maxFailures, long blockMs) {
        Spot s = at(pos);
        if (s == null) return;
        if (++s.failures >= maxFailures) {
            s.blockedUntil = now + blockMs;
            s.failures = 0;
        }
        dirty = true;
    }

    // best known spot for an area: learned rate against the time to get there
    public Spot best(String area, long now, long recheckMs, double priorRate, Set<BlockPos> avoid, ToDoubleFunction<BlockPos> travelSeconds) {
        return best(area, now, recheckMs, priorRate, avoid, travelSeconds, p -> true);
    }

    // eligible is the area check: a spot labelled with the area can still have been shown to sit just outside it
    public Spot best(String area, long now, long recheckMs, double priorRate, Set<BlockPos> avoid, ToDoubleFunction<BlockPos> travelSeconds,
                     java.util.function.Predicate<BlockPos> eligible) {
        Spot best = null;
        double bestScore = 0;
        for (Spot s : spots) {
            if (area != null && !area.equalsIgnoreCase(s.area)) continue;
            if (!eligible.test(s.pos)) continue;
            if (avoid.contains(s.pos) || now < s.blockedUntil) continue;
            if (s.emptyAt > 0 && now - s.emptyAt < recheckMs) continue;
            double score = s.rate(priorRate) / (15.0 + travelSeconds.applyAsDouble(s.pos));
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    public JsonObject toJson() {
        JsonArray array = new JsonArray();
        for (Spot s : spots) {
            JsonObject o = new JsonObject();
            o.addProperty("x", s.pos.getX());
            o.addProperty("y", s.pos.getY());
            o.addProperty("z", s.pos.getZ());
            if (s.area != null) o.addProperty("area", s.area);
            o.addProperty("visits", s.visits);
            o.addProperty("blocks", s.blocks);
            o.addProperty("titanium", s.titanium);
            o.addProperty("miningMs", s.miningMs);
            o.addProperty("lastVisit", s.lastVisit);
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("spots", array);
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        spots.clear();
        if (!root.has("spots")) return;
        for (JsonElement el : root.getAsJsonArray("spots")) {
            JsonObject o = el.getAsJsonObject();
            Spot s = new Spot(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
                    o.has("area") ? o.get("area").getAsString() : null);
            s.visits = o.get("visits").getAsInt();
            s.blocks = o.get("blocks").getAsInt();
            s.titanium = o.get("titanium").getAsInt();
            s.miningMs = o.get("miningMs").getAsLong();
            s.lastVisit = o.get("lastVisit").getAsLong();
            spots.add(s);
        }
    }
}
