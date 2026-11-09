package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the only window the target selector has onto the world, so it can run against a fake one in tests
public interface MiningView {
    MiningBlockKind kindAt(BlockPos pos);

    boolean isOpen(BlockPos pos);

    BlockPos firstHit(Vec3 from, Vec3 to);
}
