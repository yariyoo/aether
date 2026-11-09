package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the only window the mining logic has onto the world, so it can run against a fake one in tests
public interface MiningView {
    MiningBlockKind kindAt(BlockPos pos);

    boolean isOpen(BlockPos pos);

    BlockPos firstHit(Vec3 from, Vec3 to);

    // stone and the other blocks real veins sit in, as opposed to building materials
    boolean isNaturalHost(BlockPos pos);

    String nameAt(BlockPos pos);
}
