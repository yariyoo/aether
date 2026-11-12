package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

// the stand cells a player can walk to from here over loaded blocks: one-block climbs, drops of up to three,
// diagonals only when both sides are open. a stand outside this set isn't worth sending the pathfinder to
public final class WalkReach {
    private static final int MAX_DROP = 3;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private WalkReach() {
    }

    public static Set<BlockPos> from(OreWorld world, BlockPos start, int radius, int maxNodes) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        BlockPos first = settle(world, start);
        if (first == null) return seen;
        seen.add(first);
        open.add(first);
        while (!open.isEmpty() && seen.size() < maxNodes) {
            BlockPos at = open.poll();
            for (int[] s : STEPS) {
                if (s[0] != 0 && s[1] != 0 && !(clear(world, at.offset(s[0], 0, 0)) && clear(world, at.offset(0, 0, s[1])))) continue;
                BlockPos next = step(world, at, s[0], s[1]);
                if (next == null || Math.abs(next.getX() - start.getX()) > radius || Math.abs(next.getZ() - start.getZ()) > radius) continue;
                if (seen.add(next)) open.add(next);
            }
        }
        return seen;
    }

    // where a step sideways ends: on the same level, one block up if there's headroom to jump, or after a short drop
    private static BlockPos step(OreWorld world, BlockPos at, int dx, int dz) {
        BlockPos flat = at.offset(dx, 0, dz);
        if (standable(world, flat)) return flat;
        BlockPos up = flat.above();
        if (standable(world, up) && world.passable(at.above(2))) return up;
        if (!clear(world, flat)) return null;
        for (int d = 1; d <= MAX_DROP; d++) {
            BlockPos down = flat.below(d);
            if (standable(world, down)) return down;
            if (!world.passable(down)) return null;
        }
        return null;
    }

    // anything with collision holds us up here, slabs and stairs included, unlike a mining stand which wants a full block
    static boolean standable(OreWorld world, BlockPos feet) {
        return world.passable(feet) && world.passable(feet.above()) && !world.passable(feet.below());
    }

    private static boolean clear(OreWorld world, BlockPos feet) {
        return world.passable(feet) && world.passable(feet.above());
    }

    // the player may be mid-jump or on a slab; start from the first stand cell at or just below the feet
    private static BlockPos settle(OreWorld world, BlockPos feet) {
        for (int d = 0; d <= 2; d++) {
            BlockPos p = feet.below(d);
            if (standable(world, p)) return p;
        }
        return null;
    }
}
