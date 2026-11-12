package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// where on a block to aim. with variation it picks one of the visible faces and a spot inside it, fixed for that
// block so the aim never wanders while digging, and checked by a ray so it always hits the block it's meant to
public final class AimPoints {
    // stays well inside the face, so the ray can't graze an edge
    private static final double MAX_OFFSET = 0.32;

    private AimPoints() {
    }

    public static Vec3 pick(OreWorld world, Vec3 eye, BlockPos block, double reach, double amount, long seed) {
        if (amount <= 0) return StandPlanner.visiblePoint(world, eye, block, reach);
        Vec3 centre = Vec3.atCenterOf(block);
        if (centre.distanceTo(eye) > reach + 0.9) return null;
        List<Direction> faces = new ArrayList<>();
        for (Direction face : Direction.values()) {
            if (!world.passable(block.relative(face))) continue;
            Vec3 point = facePoint(centre, face, 0, 0);
            if (point.distanceTo(eye) <= reach && hits(world, eye, point, block)) faces.add(face);
        }
        if (faces.isEmpty()) return StandPlanner.visiblePoint(world, eye, block, reach);
        long h = mix(block.asLong() ^ seed);
        // nearer faces are favoured; variation lets the others come up now and then
        faces.sort((a, b) -> Double.compare(facePoint(centre, a, 0, 0).distanceTo(eye), facePoint(centre, b, 0, 0).distanceTo(eye)));
        int index = (Math.floorMod(h, 100) < amount * 35 && faces.size() > 1) ? 1 + (int) Math.floorMod(h >>> 8, faces.size() - 1) : 0;
        Direction face = faces.get(index);
        double u = ((h >>> 16 & 0xFFFF) / 65535.0 * 2 - 1) * MAX_OFFSET * amount;
        double v = ((h >>> 32 & 0xFFFF) / 65535.0 * 2 - 1) * MAX_OFFSET * amount;
        Vec3 point = facePoint(centre, face, u, v);
        if (point.distanceTo(eye) <= reach && hits(world, eye, point, block)) return point;
        Vec3 plain = facePoint(centre, face, 0, 0);
        return hits(world, eye, plain, block) ? plain : StandPlanner.visiblePoint(world, eye, block, reach);
    }

    // a point on the face, moved u and v along the face's two in-plane axes
    static Vec3 facePoint(Vec3 centre, Direction face, double u, double v) {
        double nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        double ux = ny != 0 ? 1 : nz != 0 ? 1 : 0, uy = 0, uz = nx != 0 ? 1 : 0;
        double vx = 0, vy = ny != 0 ? 0 : 1, vz = ny != 0 ? 1 : 0;
        return centre.add(nx * 0.48 + ux * u + vx * v, ny * 0.48 + uy * u + vy * v, nz * 0.48 + uz * u + vz * v);
    }

    private static boolean hits(OreWorld world, Vec3 eye, Vec3 point, BlockPos block) {
        return block.equals(world.rayHit(eye, point.add(point.subtract(eye).normalize().scale(0.1))));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
