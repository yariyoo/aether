package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// finds where to stand so the most ore can be mined without moving again
public final class StandPlanner {
    public record Stand(BlockPos feet, List<BlockPos> reachable, int titanium) {}

    public static final double EYE_HEIGHT = 1.62;
    private static final int SEARCH = 4;

    private StandPlanner() {}

    public static List<Stand> plan(OreWorld world, Vein vein, Collection<BlockPos> allOre, double reach, Set<BlockPos> avoid) {
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos ore : vein.blocks()) {
            for (int dx = -SEARCH; dx <= SEARCH; dx++) for (int dz = -SEARCH; dz <= SEARCH; dz++) for (int dy = -3; dy <= 2; dy++) {
                BlockPos feet = ore.offset(dx, dy, dz);
                if (!avoid.contains(feet) && standable(world, feet)) candidates.add(feet);
            }
        }
        List<BlockPos> nearby = new ArrayList<>();
        for (BlockPos ore : allOre) {
            if (ore.distManhattan(vein.centre()) <= 24) nearby.add(ore);
        }
        List<Stand> stands = new ArrayList<>();
        for (BlockPos feet : candidates) {
            List<BlockPos> reachable = reachableFrom(world, feet, nearby, reach);
            if (reachable.isEmpty()) continue;
            int titanium = 0;
            for (BlockPos p : reachable) if (world.oreAt(p) == OreKind.TITANIUM) titanium++;
            stands.add(new Stand(feet, reachable, titanium));
        }
        stands.sort((a, b) -> Integer.compare(b.reachable().size(), a.reachable().size()));
        return stands;
    }

    public static boolean standable(OreWorld world, BlockPos feet) {
        return world.passable(feet) && world.passable(feet.above()) && world.solid(feet.below());
    }

    public static List<BlockPos> reachableFrom(OreWorld world, BlockPos feet, Collection<BlockPos> ore, double reach) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : ore) {
            if (visiblePoint(world, eye, p, reach) != null) out.add(p);
        }
        return out;
    }

    // a point on an open face of the block that the eye can see within reach, or null
    public static Vec3 visiblePoint(OreWorld world, Vec3 eye, BlockPos block, double reach) {
        Vec3 centre = Vec3.atCenterOf(block);
        if (centre.distanceTo(eye) > reach + 0.9) return null;
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (!world.passable(block.relative(face))) continue;
            Vec3 point = centre.add(face.getStepX() * 0.48, face.getStepY() * 0.48, face.getStepZ() * 0.48);
            double d = point.distanceTo(eye);
            if (d > reach || d >= bestDistance) continue;
            BlockPos hit = world.rayHit(eye, point.add(point.subtract(eye).normalize().scale(0.1)));
            if (block.equals(hit)) {
                best = point;
                bestDistance = d;
            }
        }
        return best;
    }
}
