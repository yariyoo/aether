package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// groups ore into veins: blocks belong together when a chain of near neighbours links them
public final class VeinFinder {
    // one missing block between two ores still reads as the same vein once mined blocks turn to bedrock
    private static final int LINK = 2;

    private VeinFinder() {}

    public static List<Vein> find(Collection<BlockPos> ore, OreWorld world, int minSize) {
        Set<BlockPos> left = new HashSet<>(ore);
        Map<Long, List<BlockPos>> cells = new HashMap<>();
        for (BlockPos p : ore) cells.computeIfAbsent(cell(p), k -> new ArrayList<>()).add(p);
        List<Vein> veins = new ArrayList<>();
        for (BlockPos seed : ore) {
            if (!left.remove(seed)) continue;
            List<BlockPos> blocks = new ArrayList<>();
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
            open.add(seed);
            while (!open.isEmpty()) {
                BlockPos at = open.poll();
                blocks.add(at);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    List<BlockPos> near = cells.get(cellKey((at.getX() >> 2) + dx, (at.getY() >> 2) + dy, (at.getZ() >> 2) + dz));
                    if (near == null) continue;
                    for (BlockPos n : near) {
                        if (Math.abs(n.getX() - at.getX()) <= LINK && Math.abs(n.getY() - at.getY()) <= LINK
                                && Math.abs(n.getZ() - at.getZ()) <= LINK && left.remove(n)) open.add(n);
                    }
                }
            }
            if (blocks.size() >= minSize) veins.add(describe(blocks, world));
        }
        return veins;
    }

    private static Vein describe(List<BlockPos> blocks, OreWorld world) {
        long x = 0, y = 0, z = 0;
        int titanium = 0;
        for (BlockPos p : blocks) {
            x += p.getX();
            y += p.getY();
            z += p.getZ();
            if (world.oreAt(p) == OreKind.TITANIUM) titanium++;
        }
        int n = blocks.size();
        return new Vein(List.copyOf(blocks), new BlockPos((int) Math.floorDiv(x, n), (int) Math.floorDiv(y, n), (int) Math.floorDiv(z, n)), titanium);
    }

    private static long cell(BlockPos p) {
        return cellKey(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
    }

    private static long cellKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }
}
