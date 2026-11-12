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
    // a kill proves a place more than a sighting does, and is remembered for longer
    private static final double KILL_WEIGHT = 3.0;
    private static final double KILL_HALF_LIFE_MS = 6 * 3_600_000.0;
    private static final double WALK_SPEED = 5.0;
    private static final double FIGHT_SECONDS = 20.0;
    private static final double RESPAWN_SECONDS = 30.0;

    public static final class Spot {
        public final BlockPos pos;
        public double sightings;
        public long lastSeen;
        long lastEmpty;
        public int kills;
        public long lastKill;
        public double emptyVisits;

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
            for (Spot s : forMob.values()) {
                if (s.pos.distSqr(near) > CELL * CELL * 2) continue;
                s.emptyVisits = emptyDecayed(s, now) + 1;
                s.lastEmpty = now;
                dirty = true;
            }
        }
    }

    // a confirmed kill here; true the first time a place is proven by one
    public boolean killed(String mob, BlockPos at, long now) {
        BlockPos cell = cellOf(at);
        Spot s = spots.computeIfAbsent(key(mob), k -> new HashMap<>()).computeIfAbsent(cell.asLong(), k -> new Spot(cell));
        boolean first = s.kills == 0;
        s.kills++;
        s.lastKill = now;
        s.emptyVisits = 0;
        if (s.lastSeen == 0) s.lastSeen = now;
        dirty = true;
        return first;
    }

    private static double emptyDecayed(Spot s, long now) {
        return s.lastEmpty == 0 ? s.emptyVisits : s.emptyVisits * Math.pow(0.5, (now - s.lastEmpty) / HALF_LIFE_MS);
    }

    // kills over the time a visit costs: getting there, fighting, and waiting out respawns where it has been empty
    public double productivity(Spot s, BlockPos from, long now) {
        double kills = 0.5 + decayed(s, now) + KILL_WEIGHT * s.kills * (s.lastKill == 0 ? 1 : Math.pow(0.5, (now - s.lastKill) / KILL_HALF_LIFE_MS));
        double seconds = Math.sqrt(s.pos.distSqr(from)) / WALK_SPEED + FIGHT_SECONDS + RESPAWN_SECONDS * emptyDecayed(s, now);
        return kills / seconds;
    }

    public List<Spot> productive(BlockPos from, Collection<String> mobs, long now, int limit) {
        List<Spot> out = new ArrayList<>();
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob != null) for (Spot s : forMob.values()) if (s.kills > 0) out.add(s);
        }
        out.sort((a, b) -> Double.compare(productivity(b, from, now), productivity(a, from, now)));
        return out.subList(0, Math.min(limit, out.size()));
    }

    private static double decayed(Spot s, long now) {
        if (s.lastSeen == 0) return s.sightings;
        return s.sightings * Math.pow(0.5, (now - s.lastSeen) / HALF_LIFE_MS);
    }

    // the best place to wait: busy, close, and not just found empty
    public BlockPos best(Collection<String> mobs, BlockPos from, long now, long emptyRecheckMs, Set<BlockPos> avoid) {
        return best(mobs, from, now, emptyRecheckMs, avoid, p -> true);
    }

    // allowed keeps the choice inside the hunt's own area
    public BlockPos best(Collection<String> mobs, BlockPos from, long now, long emptyRecheckMs, Set<BlockPos> avoid,
                         java.util.function.Predicate<BlockPos> allowed) {
        Spot best = null;
        double bestScore = 0;
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob == null) continue;
            for (Spot s : forMob.values()) {
                if (avoid.contains(s.pos) || s.lastEmpty > 0 && now - s.lastEmpty < emptyRecheckMs || !allowed.test(s.pos)) continue;
                double score = productivity(s, from, now);
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }
        return best == null ? null : best.pos;
    }

    // a spot shown to be in the wrong area is dropped outright
    public void forget(BlockPos near, Collection<String> mobs, double radius) {
        for (String m : mobs) {
            Map<Long, Spot> forMob = spots.get(key(m));
            if (forMob != null && forMob.values().removeIf(s -> s.pos.distSqr(near) <= radius * radius)) dirty = true;
        }
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
                if (s.lastSeen == 0 && s.kills == 0 && s.emptyVisits == 0) continue;
                JsonArray e = new JsonArray();
                e.add(s.pos.asLong());
                e.add(s.sightings);
                e.add(s.lastSeen);
                e.add(s.kills);
                e.add(s.lastKill);
                e.add(s.emptyVisits);
                e.add(s.lastEmpty);
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
                if (e.size() > 6) {
                    s.kills = e.get(3).getAsInt();
                    s.lastKill = e.get(4).getAsLong();
                    s.emptyVisits = e.get(5).getAsDouble();
                    s.lastEmpty = e.get(6).getAsLong();
                }
            }
        }
    }
}
