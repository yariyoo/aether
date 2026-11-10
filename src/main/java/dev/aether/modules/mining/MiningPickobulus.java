package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// picks where to throw pickobulus so its blast takes out the most wanted ore
public final class MiningPickobulus {
    // pickobulus mines the ore within about two blocks of where it lands
    static final double BLAST_RADIUS = 2.5;
    public static final int MIN_ORE = 8;

    public record Blast(BlockPos pos, Vec3 aim, int ore) {}

    private MiningPickobulus() {}

    public static Blast best(MiningView view, Vec3 eye, double reach, Set<MiningBlockKind> allowed, Set<BlockPos> skip) {
        int r = (int) Math.ceil(reach + BLAST_RADIUS);
        BlockPos centre = BlockPos.containing(eye);
        List<BlockPos> ore = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            MiningBlockKind kind = view.kindAt(pos);
            if (kind != null && allowed.contains(kind) && !skip.contains(pos)) ore.add(pos.immutable());
        }
        double radiusSqr = BLAST_RADIUS * BLAST_RADIUS;
        double reachSqr = reach * reach;
        Blast best = null;
        for (BlockPos pos : ore) {
            if (Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr) continue;
            int count = 0;
            for (BlockPos other : ore) {
                if (other.distSqr(pos) <= radiusSqr) count++;
            }
            if (best != null && count <= best.ore()) continue;
            Vec3 aim = visiblePoint(view, eye, pos);
            if (aim != null) best = new Blast(pos, aim, count);
        }
        return best;
    }

    private static Vec3 visiblePoint(MiningView view, Vec3 eye, BlockPos pos) {
        Vec3 centre = Vec3.atCenterOf(pos);
        if (pos.equals(view.firstHit(eye, centre))) return centre;
        for (Direction face : Direction.values()) {
            if (!view.isOpen(pos.relative(face))) continue;
            Vec3 point = centre.add(face.getStepX() * 0.49, face.getStepY() * 0.49, face.getStepZ() * 0.49);
            if (pos.equals(view.firstHit(eye, point))) return point;
        }
        return null;
    }
}
