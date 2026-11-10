package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// a box the miner has to stand in, for commissions that only count inside one part of the mines
public record MiningArea(String name, BlockPos min, BlockPos max, BlockPos anchor) {
    public static MiningArea around(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                    BlockPos anchor, int margin) {
        return new MiningArea(name,
                new BlockPos(Math.min(minX, maxX) - margin, Math.min(minY, maxY) - margin, Math.min(minZ, maxZ) - margin),
                new BlockPos(Math.max(minX, maxX) + margin, Math.max(minY, maxY) + margin, Math.max(minZ, maxZ) + margin),
                anchor);
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    // straight-line distance from a block centre to the nearest point of the standing box
    public double distanceTo(BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        double dx = Math.max(Math.max(min.getX() - c.x, 0.0), c.x - (max.getX() + 1));
        double dy = Math.max(Math.max(min.getY() - c.y, 0.0), c.y - (max.getY() + 2));
        double dz = Math.max(Math.max(min.getZ() - c.z, 0.0), c.z - (max.getZ() + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
