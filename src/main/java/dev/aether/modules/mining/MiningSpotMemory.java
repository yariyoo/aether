package dev.aether.modules.mining;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// standing spots that paid off before, so an area with nothing visible still has somewhere to go and look
public final class MiningSpotMemory {
    // ore regenerates, so an emptied spot is worth another look after a while
    static final long RECHECK_MS = 30_000L;
    static final int FAILURES_BEFORE_IGNORING = 3;
    static final long IGNORE_MS = 600_000L;
    private static final double DISTANCE_SCALE = 12.0;
    private static final double MIN_DISTANCE = 2.0;

    static final class Spot {
        final BlockPos pos;
        int mined;
        int failures;
        long emptyAt;
        long ignoredUntil;

        Spot(BlockPos pos, int mined, int failures) {
            this.pos = pos;
            this.mined = mined;
            this.failures = failures;
        }
    }

    private final List<Spot> spots = new ArrayList<>();
    private boolean dirty;

    public int size() {
        return spots.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public BlockPos best(MiningArea area, BlockPos feet, long now, Set<BlockPos> avoid) {
        Spot best = null;
        double bestScore = 0;
        for (Spot s : spots) {
            if (area != null && !area.contains(s.pos)) continue;
            if (avoid.contains(s.pos) || now < s.ignoredUntil || s.emptyAt > 0 && now - s.emptyAt < RECHECK_MS) continue;
            double distance = Math.sqrt(s.pos.distSqr(feet));
            if (distance < MIN_DISTANCE) continue;
            double score = Math.log1p(s.mined) / (1.0 + distance / DISTANCE_SCALE);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best == null ? null : best.pos;
    }

    public void recordMined(BlockPos feet) {
        Spot s = near(feet);
        if (s == null) {
            s = new Spot(feet.immutable(), 0, 0);
            spots.add(s);
        }
        s.mined++;
        s.failures = 0;
        s.emptyAt = 0;
        dirty = true;
    }

    public void recordEmpty(BlockPos spot, long now) {
        Spot s = near(spot);
        if (s != null) s.emptyAt = now;
    }

    public void recordUnreachable(BlockPos spot, long now) {
        Spot s = near(spot);
        if (s == null) return;
        if (++s.failures >= FAILURES_BEFORE_IGNORING) {
            s.ignoredUntil = now + IGNORE_MS;
            s.failures = 0;
        }
        dirty = true;
    }

    private Spot near(BlockPos pos) {
        for (Spot s : spots) {
            if (s.pos.equals(pos)) return s;
        }
        for (Spot s : spots) {
            if (Math.abs(s.pos.getX() - pos.getX()) <= 1 && Math.abs(s.pos.getY() - pos.getY()) <= 1
                    && Math.abs(s.pos.getZ() - pos.getZ()) <= 1) return s;
        }
        return null;
    }

    // seeds add spots, saved files carry learned counts; neither keeps whether a spot was empty
    public void load(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        for (JsonElement el : root.getAsJsonArray("spots")) {
            JsonObject o = el.getAsJsonObject();
            JsonArray p = o.getAsJsonArray("pos");
            BlockPos pos = new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
            int mined = o.has("mined") ? o.get("mined").getAsInt() : 0;
            int failures = o.has("failures") ? o.get("failures").getAsInt() : 0;
            Spot existing = null;
            for (Spot s : spots) {
                if (s.pos.equals(pos)) existing = s;
            }
            if (existing == null) {
                spots.add(new Spot(pos, mined, failures));
            } else {
                existing.mined = Math.max(existing.mined, mined);
                existing.failures = failures;
            }
        }
    }

    public JsonObject toJson() {
        JsonArray array = new JsonArray();
        for (Spot s : spots) {
            JsonObject o = new JsonObject();
            JsonArray p = new JsonArray();
            p.add(s.pos.getX());
            p.add(s.pos.getY());
            p.add(s.pos.getZ());
            o.add("pos", p);
            o.addProperty("mined", s.mined);
            if (s.failures > 0) o.addProperty("failures", s.failures);
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("spots", array);
        dirty = false;
        return root;
    }
}
