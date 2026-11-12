package dev.aether.modules.dwarven.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// where each named zone really is, learned from the scoreboard's zone line as the player moves around
public final class AreaMemory {
    private static final int CELL = 4;
    private final Map<String, Set<Long>> cells = new HashMap<>();
    private boolean dirty;

    private static String key(String area) {
        return area.strip().toLowerCase(Locale.ROOT);
    }

    public void observe(String area, BlockPos pos) {
        if (area == null || area.isBlank()) return;
        if (cells.computeIfAbsent(key(area), k -> new HashSet<>()).add(cell(pos))) dirty = true;
    }

    public boolean knows(String area) {
        Set<Long> seen = cells.get(key(area));
        return seen != null && !seen.isEmpty();
    }

    public boolean contains(String area, BlockPos pos) {
        Set<Long> seen = cells.get(key(area));
        if (seen == null) return false;
        int cx = Math.floorDiv(pos.getX(), CELL), cy = Math.floorDiv(pos.getY(), CELL), cz = Math.floorDiv(pos.getZ(), CELL);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (seen.contains(BlockPos.asLong(cx + dx, cy + dy, cz + dz))) return true;
        }
        return false;
    }

    // the zone a position was seen in, when exactly one zone claims it
    public String areaAt(BlockPos pos) {
        String found = null;
        for (String area : cells.keySet()) {
            if (area.startsWith("@") || !contains(area, pos)) continue;
            if (found != null) return null;
            found = area;
        }
        return found;
    }

    public BlockPos nearestPoint(String area, BlockPos from) {
        return nearestPoint(area, from, java.util.List.of(), 0);
    }

    // like nearestPoint, but skips anywhere within avoidRadius of a goal that already failed
    public BlockPos nearestPoint(String area, BlockPos from, java.util.Collection<BlockPos> avoid, double avoidRadius) {
        Set<Long> seen = cells.get(key(area));
        if (seen == null || seen.isEmpty()) return null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (long packed : seen) {
            BlockPos p = new BlockPos(BlockPos.getX(packed) * CELL + CELL / 2, BlockPos.getY(packed) * CELL, BlockPos.getZ(packed) * CELL + CELL / 2);
            boolean nearFailure = false;
            for (BlockPos a : avoid) if (a.distSqr(p) <= avoidRadius * avoidRadius) nearFailure = true;
            if (nearFailure) continue;
            double d = p.distSqr(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    // the scoreboard said this place is somewhere else: forget the cells around it for this area
    public void reject(String area, BlockPos pos) {
        Set<Long> seen = cells.get(key(area));
        if (seen == null) return;
        int cx = Math.floorDiv(pos.getX(), CELL), cy = Math.floorDiv(pos.getY(), CELL), cz = Math.floorDiv(pos.getZ(), CELL);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (seen.remove(BlockPos.asLong(cx + dx, cy + dy, cz + dz))) dirty = true;
        }
    }

    public boolean dirty() {
        return dirty;
    }

    private static long cell(BlockPos pos) {
        return BlockPos.asLong(Math.floorDiv(pos.getX(), CELL), Math.floorDiv(pos.getY(), CELL), Math.floorDiv(pos.getZ(), CELL));
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        cells.forEach((area, seen) -> {
            JsonArray a = new JsonArray();
            seen.forEach(a::add);
            root.add(area, a);
        });
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        cells.clear();
        for (String area : root.keySet()) {
            Set<Long> seen = new HashSet<>();
            for (JsonElement el : root.getAsJsonArray(area)) seen.add(el.getAsLong());
            cells.put(area, seen);
        }
    }
}
