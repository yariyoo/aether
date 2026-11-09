package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MiningVeins {
    public record Vein(List<BlockPos> blocks, double distance) {}

    private MiningVeins() {}

    // groups touching target blocks (including diagonals) into veins, nearest vein first,
    // each vein's blocks ordered nearest first
    public static List<Vein> find(MiningView view, BlockPos center, int horizontalRadius, int verticalRadius,
                                  Set<MiningBlockKind> allowed, Set<BlockPos> skip) {
        Set<BlockPos> targets = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    MiningBlockKind kind = view.kindAt(cursor);
                    if (kind != null && allowed.contains(kind) && !skip.contains(cursor)) {
                        targets.add(cursor.immutable());
                    }
                }
            }
        }

        List<Vein> veins = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : targets) {
            if (!seen.add(start)) continue;
            List<BlockPos> blocks = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos at = queue.poll();
                blocks.add(at);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos next = at.offset(dx, dy, dz);
                            if (targets.contains(next) && seen.add(next)) queue.add(next);
                        }
                    }
                }
            }
            blocks.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
            veins.add(new Vein(blocks, Math.sqrt(blocks.getFirst().distSqr(center))));
        }
        veins.sort(Comparator.comparingDouble(Vein::distance));
        return veins;
    }
}
