package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class MiningTargetSelector {
    // aim slightly inside the face so the ray lands on this block, not on the boundary with its neighbour
    private static final double FACE_INSET = 0.05;
    // one block of extra distance is worth roughly this many degrees of extra turning
    private static final double DISTANCE_WEIGHT = 8.0;

    public record Target(BlockPos pos, MiningBlockKind kind, Vec3 aim) {}

    private MiningTargetSelector() {}

    public static Target select(MiningView view, Vec3 eye, Vec3 look, double reach,
                                Set<MiningBlockKind> allowed, boolean preferTitanium, Set<BlockPos> skip) {
        if (allowed.isEmpty()) return null;
        int radius = (int) Math.ceil(reach);
        BlockPos center = BlockPos.containing(eye);
        Vec3 lookDir = look.normalize();

        Target best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    MiningBlockKind kind = view.kindAt(cursor);
                    if (kind == null || !allowed.contains(kind) || skip.contains(cursor)) continue;

                    BlockPos pos = cursor.immutable();
                    Vec3 aim = bestVisibleFace(view, eye, lookDir, reach, pos);
                    if (aim == null) continue;

                    double score = angleDegrees(lookDir, aim.subtract(eye)) + eye.distanceTo(aim) * DISTANCE_WEIGHT;
                    if (preferTitanium && kind == MiningBlockKind.TITANIUM) score -= 1000.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Target(pos, kind, aim);
                    }
                }
            }
        }
        return best;
    }

    public static boolean isStillMineable(MiningView view, Target target, Set<MiningBlockKind> allowed) {
        MiningBlockKind kind = view.kindAt(target.pos());
        return kind != null && allowed.contains(kind);
    }

    private static Vec3 bestVisibleFace(MiningView view, Vec3 eye, Vec3 lookDir, double reach, BlockPos pos) {
        Vec3 best = null;
        double bestAngle = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (!view.isOpen(pos.relative(face))) continue;
            Vec3 aim = faceCenter(pos, face);
            if (eye.distanceTo(aim) > reach) continue;
            if (!pos.equals(view.firstHit(eye, aim))) continue;
            double angle = angleDegrees(lookDir, aim.subtract(eye));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = aim;
            }
        }
        return best;
    }

    static Vec3 faceCenter(BlockPos pos, Direction face) {
        double offset = 0.5 - FACE_INSET;
        return Vec3.atCenterOf(pos).add(
                face.getStepX() * offset, face.getStepY() * offset, face.getStepZ() * offset);
    }

    static double angleDegrees(Vec3 a, Vec3 b) {
        double lengths = a.length() * b.length();
        if (lengths == 0.0) return 180.0;
        double cos = Math.max(-1.0, Math.min(1.0, a.dot(b) / lengths));
        return Math.toDegrees(Math.acos(cos));
    }
}
