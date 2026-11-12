package dev.aether.modules.dwarven.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// where each kind of mob keeps showing up, so the hunter waits for respawns in the busiest place nearby
public final class SpawnMemory {
    private static final int CELL = 6;
    // a sighting counts for less as it ages, so the ranking follows where mobs appear now
    private static final double HALF_LIFE_MS = 20 * 60_000.0;
    private static final double DISTANCE_SCALE = 24.0;

    public static final class Spot {
        public final BlockPos pos;
        public double sightings;
        public long lastSeen;
        long lastEmpty;

        Spot(BlockPos pos) {
            this.pos = pos;
        }
    }

    private final Map<String, Map<Long, Spot>> spots = new HashMap<>();
    private boolean dirty;

    public boolean dirty() {
        return dirty;
    }

    private static String key(String mob) {
        return mob.strip().toLowerCase(Locale.ROOT);
    }

    private static BlockPos cellOf(BlockPos p) {
        return new BlockPos(Math.floorDiv(p.getX(), CELL) * CELL + CELL / 2, p.getY(), Math.floorDiv(p.getZ(), CELL) * CELL + CELL / 2);
    }

    public void sighted(String mob, BlockPos at, long now) {
        BlockPos cell = cellOf(at);
        Spot s = spots.computeIfAbsent(key(mob), k -> new HashMap<>()).computeIfAbsent(cell.asLong(), k -> new Spot(cell));
        s.sightings = decayed(s, now) + 1;
        s.lastSeen = now;
        dirty = true;
    }

    public void seed(String mob, BlockPos at) {
        BlockPos cell = cellOf(at);
        spots.computeIfAbsent(key(mob), k -> new HashMap<>()).computeIfAbsent(cell.asLong(), k -> {
            Spot s = new Spot(cell);
            s.sightings = 1;
            return s;
        });
    }

    public void empty(BlockPos near, Collection<String> mobs, long now) {
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob == null) continue;
            for (Spot s : forMob.values()) if (s.pos.distSqr(near) <= CELL * CELL * 2) s.lastEmpty = now;
        }
    }

    private static double decayed(Spot s, long now) {
        if (s.lastSeen == 0) return s.sightings;
        return s.sightings * Math.pow(0.5, (now - s.lastSeen) / HALF_LIFE_MS);
    }

    // the best place to wait: busy, close, and not just found empty
    public BlockPos best(Collection<String> mobs, BlockPos from, long now, long emptyRecheckMs, Set<BlockPos> avoid) {
        Spot best = null;
        double bestScore = 0;
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob == null) continue;
            for (Spot s : forMob.values()) {
                if (avoid.contains(s.pos) || s.lastEmpty > 0 && now - s.lastEmpty < emptyRecheckMs) continue;
                double score = (0.5 + decayed(s, now)) / (1.0 + Math.sqrt(s.pos.distSqr(from)) / DISTANCE_SCALE);
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }
        return best == null ? null : best.pos;
    }

    public List<BlockPos> all(Collection<String> mobs) {
        List<BlockPos> out = new ArrayList<>();
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob != null) forMob.values().forEach(s -> out.add(s.pos));
        }
        return out;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        spots.forEach((mob, cells) -> {
            JsonArray a = new JsonArray();
            for (Spot s : cells.values()) {
                if (s.lastSeen == 0) continue;
                JsonArray e = new JsonArray();
                e.add(s.pos.asLong());
                e.add(s.sightings);
                e.add(s.lastSeen);
                a.add(e);
            }
            root.add(mob, a);
        });
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        for (String mob : root.keySet()) {
            Map<Long, Spot> cells = spots.computeIfAbsent(mob, k -> new HashMap<>());
            for (JsonElement el : root.getAsJsonArray(mob)) {
                JsonArray e = el.getAsJsonArray();
                BlockPos p = BlockPos.of(e.get(0).getAsLong());
                Spot s = cells.computeIfAbsent(p.asLong(), k -> new Spot(p));
                s.sightings = e.get(1).getAsDouble();
                s.lastSeen = e.get(2).getAsLong();
            }
        }
    }
}
