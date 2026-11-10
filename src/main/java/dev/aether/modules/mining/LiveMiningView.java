package dev.aether.modules.mining;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

final class LiveMiningView implements MiningView {
    // the dwarven mines carve veins out of terracotta and polished stone as much as plain stone
    private static final Set<Block> NATURAL_HOSTS = naturalHosts();

    private static Set<Block> naturalHosts() {
        Set<Block> hosts = new HashSet<>(Set.of(
                Blocks.STONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
                Blocks.POLISHED_ANDESITE, Blocks.POLISHED_GRANITE, Blocks.GRAVEL, Blocks.BEDROCK,
                Blocks.CLAY, Blocks.TERRACOTTA));
        hosts.addAll(Blocks.DYED_TERRACOTTA.asList());
        return Set.copyOf(hosts);
    }

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
    public boolean isNaturalHost(BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        return NATURAL_HOSTS.contains(block) || MiningBlockKind.fromBlock(block) != null;
    }

    @Override
    public String nameAt(BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    @Override
    public BlockPos firstHit(Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
