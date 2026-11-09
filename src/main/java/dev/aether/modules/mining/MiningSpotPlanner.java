package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

// ranks standing spots by how much ore they reach against how far away they are, like a player
// settling into a rich spot instead of chasing the nearest lone block
public final class MiningSpotPlanner {
    private static final double EYE_HEIGHT = 1.62;
    // a spot this many blocks further away has to reach twice the ore to be worth the walk
    private static final double DISTANCE_SCALE = 12.0;
    private static final int BLOCKS_TRIED_PER_VEIN = 4;

    public record Plan(BlockPos spot, BlockPos forBlock, List<BlockPos> vein, int reachable,
                       double distance, double score) {}

    private MiningSpotPlanner() {}

    public static List<Plan> rank(MiningView view, List<MiningVeins.Vein> veins, List<BlockPos> ore,
                                  BlockPos feet, double reach, Set<BlockPos> badSpots, int maxVeins) {
        List<Plan> plans = new ArrayList<>();
        for (int v = 0; v < Math.min(maxVeins, veins.size()); v++) {
            List<BlockPos> blocks = veins.get(v).blocks();
            for (int b = 0; b < Math.min(BLOCKS_TRIED_PER_VEIN, blocks.size()); b++) {
                BlockPos spot = MiningSpotFinder.find(view, blocks.get(b), feet, reach, badSpots);
                if (spot == null) continue;
                int reachable = reachableFrom(view, spot, ore, reach);
                double distance = Math.sqrt(spot.distSqr(feet));
                plans.add(new Plan(spot, blocks.get(b), blocks, reachable, distance,
                        reachable / (1.0 + distance / DISTANCE_SCALE)));
                break;
            }
        }
        plans.sort(Comparator.comparingDouble(Plan::score).reversed());
        return plans;
    }

    public static int reachableFrom(MiningView view, BlockPos spot, List<BlockPos> ore, double reach) {
        Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + EYE_HEIGHT, spot.getZ() + 0.5);
        double limitSqr = (reach + 1.0) * (reach + 1.0);
        int count = 0;
        for (BlockPos pos : ore) {
            if (Vec3.atCenterOf(pos).distanceToSqr(eye) > limitSqr) continue;
            if (MiningSpotFinder.canMineFrom(view, eye, pos, reach)) count++;
        }
        return count;
    }
}
