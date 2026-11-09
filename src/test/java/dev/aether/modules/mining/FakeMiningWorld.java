package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class FakeMiningWorld implements MiningView {
    final Map<BlockPos, MiningBlockKind> ores = new HashMap<>();
    final Set<BlockPos> stone = new HashSet<>();

    FakeMiningWorld ore(int x, int y, int z, MiningBlockKind kind) {
        ores.put(new BlockPos(x, y, z), kind);
        return this;
    }

    FakeMiningWorld stone(int x, int y, int z) {
        stone.add(new BlockPos(x, y, z));
        return this;
    }

    FakeMiningWorld floor(int y, int fromX, int toX, int fromZ, int toZ) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                stone(x, y, z);
            }
        }
        return this;
    }

    @Override
    public MiningBlockKind kindAt(BlockPos pos) {
        return ores.get(pos);
    }

    @Override
    public boolean isOpen(BlockPos pos) {
        return !ores.containsKey(pos) && !stone.contains(pos);
    }

    @Override
    public BlockPos firstHit(Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from).scale(1.0 / 400.0);
        Vec3 at = from;
        for (int i = 0; i <= 400; i++) {
            BlockPos cell = BlockPos.containing(at);
            if (!isOpen(cell)) return cell;
            at = at.add(step);
        }
        return null;
    }
}
