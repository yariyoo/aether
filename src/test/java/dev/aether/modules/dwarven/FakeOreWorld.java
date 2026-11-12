package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.world.OreKind;
import dev.aether.modules.dwarven.world.OreWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// a tiny block world: everything is air unless set as stone or ore
final class FakeOreWorld implements OreWorld {
    private final Set<BlockPos> stone = new HashSet<>();
    private final Map<BlockPos, OreKind> ore = new HashMap<>();

    FakeOreWorld floor(int x1, int z1, int x2, int z2, int y) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) stone.add(new BlockPos(x, y, z));
        return this;
    }

    FakeOreWorld stone(int x, int y, int z) {
        stone.add(new BlockPos(x, y, z));
        return this;
    }

    FakeOreWorld ore(int x, int y, int z, OreKind kind) {
        ore.put(new BlockPos(x, y, z), kind);
        return this;
    }

    Set<BlockPos> orePositions() {
        return ore.keySet();
    }

    @Override
    public OreKind oreAt(BlockPos pos) {
        return ore.get(pos);
    }

    @Override
    public boolean passable(BlockPos pos) {
        return !stone.contains(pos) && !ore.containsKey(pos);
    }

    @Override
    public boolean solid(BlockPos pos) {
        return !passable(pos);
    }

    @Override
    public BlockPos rayHit(Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from);
        int n = (int) Math.ceil(step.length() * 20);
        for (int i = 0; i <= n; i++) {
            BlockPos at = BlockPos.containing(from.add(step.scale(i / (double) Math.max(1, n))));
            if (!passable(at)) return at;
        }
        return null;
    }
}
