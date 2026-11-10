package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.Predicate;

public final class MiningSpotFinder {
    private static final double EYE_HEIGHT = 1.62;
    private static final int HORIZONTAL_SEARCH = 4;
    private static final int BELOW_SEARCH = 4;
    private static final int ABOVE_SEARCH = 2;
    // the walker stops near the goal rather than on its centre, so leave room to still reach the block
    private static final double REACH_MARGIN = 0.6;

    private MiningSpotFinder() {}

    // the standing block (feet position) nearest the player from which the target can be seen and reached
    public static BlockPos find(MiningView view, BlockPos target, BlockPos playerFeet, double reach) {
        return find(view, target, playerFeet, reach, Set.of());
    }

    public static BlockPos find(MiningView view, BlockPos target, BlockPos playerFeet, double reach, Set<BlockPos> exclude) {
        return find(view, target, playerFeet, reach, feet -> !exclude.contains(feet));
    }

    public static BlockPos find(MiningView view, BlockPos target, BlockPos playerFeet, double reach, Predicate<BlockPos> allowed) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -HORIZONTAL_SEARCH; dx <= HORIZONTAL_SEARCH; dx++) {
            for (int dy = -BELOW_SEARCH; dy <= ABOVE_SEARCH; dy++) {
                for (int dz = -HORIZONTAL_SEARCH; dz <= HORIZONTAL_SEARCH; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!allowed.test(feet) || !isStandable(view, feet) || feet.below().equals(target)) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
                    if (!canMineFrom(view, eye, target, reach - REACH_MARGIN)) continue;
                    double score = Math.sqrt(feet.distSqr(playerFeet));
                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                }
            }
        }
        return best;
    }

    static boolean isStandable(MiningView view, BlockPos feet) {
        return view.isOpen(feet) && view.isOpen(feet.above()) && !view.isOpen(feet.below());
    }

    static boolean canMineFrom(MiningView view, Vec3 eye, BlockPos target, double reach) {
        for (Direction face : Direction.values()) {
            if (!view.isOpen(target.relative(face))) continue;
            Vec3 aim = MiningTargetSelector.faceCenter(target, face);
            if (eye.distanceTo(aim) <= reach && target.equals(view.firstHit(eye, aim))) return true;
        }
        return false;
    }
}
