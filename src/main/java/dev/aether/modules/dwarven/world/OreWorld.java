package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the only window the planning code has on the world, so it runs the same against a fake one in tests
public interface OreWorld {
    OreKind oreAt(BlockPos pos);

    boolean passable(BlockPos pos);

    boolean solid(BlockPos pos);

    // first block a ray from one point to the other runs into, or null when nothing is in the way
    BlockPos rayHit(Vec3 from, Vec3 to);
}
