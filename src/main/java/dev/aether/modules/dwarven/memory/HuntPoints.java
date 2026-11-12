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

// where each kind of mob was fought in the recorded runs, so a slayer job knows where to look first
public final class HuntPoints {
    private static final Map<String, List<BlockPos>> POINTS = new HashMap<>();

    private HuntPoints() {}

    static void load(JsonObject hunts) {
        POINTS.clear();
        for (String mob : hunts.keySet()) {
            List<BlockPos> list = new ArrayList<>();
            for (JsonElement el : hunts.getAsJsonArray(mob)) {
                JsonArray p = el.getAsJsonArray();
                list.add(new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
            }
            POINTS.put(mob.toLowerCase(Locale.ROOT), list);
        }
    }

    public static void forget(BlockPos near, double radius) {
        for (List<BlockPos> list : POINTS.values()) list.removeIf(p -> p.distSqr(near) <= radius * radius);
    }

    public static List<BlockPos> points(Collection<String> mobs) {
        List<BlockPos> out = new ArrayList<>();
        for (String m : mobs) out.addAll(POINTS.getOrDefault(m.toLowerCase(Locale.ROOT), List.of()));
        return out;
    }

    public static BlockPos nearest(Collection<String> mobs, BlockPos from) {
        return nearest(mobs, from, p -> true);
    }

    public static BlockPos nearest(Collection<String> mobs, BlockPos from, java.util.function.Predicate<BlockPos> allowed) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos p : points(mobs)) {
            if (!allowed.test(p)) continue;
            double d = p.distSqr(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }
}
