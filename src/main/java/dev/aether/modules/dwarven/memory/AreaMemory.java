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
    public enum Confidence { VERIFIED_CORRECT_AREA, LIKELY_CORRECT_AREA, UNVERIFIED, VERIFIED_WRONG_AREA }

    private static final int CELL = 4;
    private static final String WRONG_PREFIX = "!wrong:";
    // a destination within this of one confirmed to be in another zone shares its verdict
    private static final double WRONG_RADIUS = 3.0;
    // points on a border need a little more travel than interior ones to be worth it
    private static final double BORDER_PENALTY = 12.0;
    private final Map<String, Set<Long>> cells = new HashMap<>();
    // destinations reached and found, once the scoreboard caught up, to be in another zone
    private final Map<String, Set<Long>> wrongGoals = new HashMap<>();
    private boolean dirty;

    private static String key(String area) {
        return area.strip().toLowerCase(Locale.ROOT);
    }

    public void observe(String area, BlockPos pos) {
        if (area == null || area.isBlank()) return;
        if (cells.computeIfAbsent(key(area), k -> new HashSet<>()).add(cell(pos))) dirty = true;
        // standing on a spot marked wrong and seeing the right zone is new evidence that overturns the verdict
        Set<Long> wrong = wrongGoals.get(key(area));
        if (wrong != null && wrong.removeIf(g -> BlockPos.of(g).distSqr(pos) <= 2.25)) dirty = true;
    }

    public void markWrongGoal(String area, BlockPos goal) {
        if (area == null || goal == null) return;
        if (wrongGoals.computeIfAbsent(key(area), k -> new HashSet<>()).add(goal.asLong())) dirty = true;
    }

    public boolean wrongGoal(String area, BlockPos pos) {
        Set<Long> wrong = area == null ? null : wrongGoals.get(key(area));
        if (wrong == null) return false;
        for (long g : wrong) if (BlockPos.of(g).distSqr(pos) <= WRONG_RADIUS * WRONG_RADIUS) return true;
        return false;
    }

    // how sure we are that standing here counts as being in the area
    public Confidence confidence(String area, BlockPos pos) {
        if (wrongGoal(area, pos)) return Confidence.VERIFIED_WRONG_AREA;
        String at = areaAt(pos);
        if (at != null && !at.equalsIgnoreCase(key(area))) return Confidence.VERIFIED_WRONG_AREA;
        if (!contains(area, pos)) return Confidence.UNVERIFIED;
        Set<Long> own = cells.get(key(area));
        // its own cell seen in this area and no other zone claiming anything around it: an interior point
        return own.contains(cell(pos)) && at != null ? Confidence.VERIFIED_CORRECT_AREA : Confidence.LIKELY_CORRECT_AREA;
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

    // a point belongs to an area unless it is known to be in a different one
    public boolean allows(String area, BlockPos pos) {
        if (area == null) return true;
        return confidence(area, pos) != Confidence.VERIFIED_WRONG_AREA;
    }

    // the best place to head for in an area: never a confirmed-wrong one, interior points before border ones
    public BlockPos bestPoint(String area, BlockPos from, java.util.Collection<BlockPos> avoid, double avoidRadius) {
        Set<Long> seen = cells.get(key(area));
        if (seen == null || seen.isEmpty()) return null;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (long packed : seen) {
            BlockPos p = centre(packed);
            boolean nearFailure = false;
            for (BlockPos a : avoid) if (a.distSqr(p) <= avoidRadius * avoidRadius) nearFailure = true;
            if (nearFailure) continue;
            Confidence c = confidence(area, p);
            if (c == Confidence.VERIFIED_WRONG_AREA) continue;
            double score = Math.sqrt(p.distSqr(from)) + (c == Confidence.VERIFIED_CORRECT_AREA ? 0 : BORDER_PENALTY);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private static BlockPos centre(long packed) {
        return new BlockPos(BlockPos.getX(packed) * CELL + CELL / 2, BlockPos.getY(packed) * CELL, BlockPos.getZ(packed) * CELL + CELL / 2);
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
            BlockPos p = centre(packed);
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
        wrongGoals.forEach((area, goals) -> {
            JsonArray a = new JsonArray();
            goals.forEach(a::add);
            root.add(WRONG_PREFIX + area, a);
        });
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        cells.clear();
        wrongGoals.clear();
        for (String area : root.keySet()) {
            Set<Long> seen = new HashSet<>();
            for (JsonElement el : root.getAsJsonArray(area)) seen.add(el.getAsLong());
            if (area.startsWith(WRONG_PREFIX)) wrongGoals.put(area.substring(WRONG_PREFIX.length()), seen);
            else cells.put(area, seen);
        }
    }
}
