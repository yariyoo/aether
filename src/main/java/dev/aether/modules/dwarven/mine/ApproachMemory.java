package dev.aether.modules.dwarven.mine;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

// where walks to mining stands got stuck. a stand whose straight approach from about the same start runs through a
// place we already got stuck at is likely behind the same obstruction, even when it's a few blocks from the failed one
public final class ApproachMemory {
    private static final double SAME_START = 6.0;
    private static final double NEAR_LINE = 2.5;

    private record Blocked(BlockPos from, BlockPos stuckAt, long until) {}

    private final List<Blocked> blocked = new ArrayList<>();

    public void stuck(BlockPos from, BlockPos stuckAt, long until) {
        // getting stuck right where we started says nothing about any direction
        if (from.distSqr(stuckAt) < 1) return;
        blocked.add(new Blocked(from, stuckAt, until));
    }

    public boolean blocks(BlockPos from, BlockPos stand, long now) {
        blocked.removeIf(b -> b.until() <= now);
        for (Blocked b : blocked) {
            if (Math.sqrt(b.from().distSqr(from)) > SAME_START) continue;
            double toStand = Math.sqrt(from.distSqr(stand));
            double toStuck = Math.sqrt(from.distSqr(b.stuckAt()));
            // the stand has to lie beyond the stuck point, and the stuck point near the line toward it
            if (toStand < toStuck - 1.0) continue;
            if (distanceToSegment(b.stuckAt(), from, stand) <= NEAR_LINE) return true;
        }
        return false;
    }

    public void clear() {
        blocked.clear();
    }

    static double distanceToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double ax = a.getX(), ay = a.getY(), az = a.getZ();
        double dx = b.getX() - ax, dy = b.getY() - ay, dz = b.getZ() - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 == 0 ? 0 : ((p.getX() - ax) * dx + (p.getY() - ay) * dy + (p.getZ() - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx - p.getX(), cy = ay + t * dy - p.getY(), cz = az + t * dz - p.getZ();
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}
