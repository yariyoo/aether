package dev.aether.modules.mining;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class LiveMiningView implements MiningView {
    private final ClientLevel level;
    private final Entity viewer;

    LiveMiningView(ClientLevel level, Entity viewer) {
        this.level = level;
        this.viewer = viewer;
    }

    @Override
    public MiningBlockKind kindAt(BlockPos pos) {
        return MiningBlockKind.fromBlock(level.getBlockState(pos).getBlock());
    }

    @Override
    public boolean isOpen(BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public BlockPos firstHit(Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
