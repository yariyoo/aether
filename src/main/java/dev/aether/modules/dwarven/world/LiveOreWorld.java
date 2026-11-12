package dev.aether.modules.dwarven.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class LiveOreWorld implements OreWorld {
    // the blocks hypixel builds mithril and titanium out of in the dwarven mines
    private static final Map<Block, OreKind> KINDS = new HashMap<>();

    static {
        KINDS.put(Blocks.WOOL.gray(), OreKind.MITHRIL_GRAY);
        KINDS.put(Blocks.DYED_TERRACOTTA.cyan(), OreKind.MITHRIL_GRAY);
        KINDS.put(Blocks.PRISMARINE, OreKind.MITHRIL_GREEN);
        KINDS.put(Blocks.PRISMARINE_BRICKS, OreKind.MITHRIL_GREEN);
        KINDS.put(Blocks.DARK_PRISMARINE, OreKind.MITHRIL_GREEN);
        KINDS.put(Blocks.WOOL.lightBlue(), OreKind.MITHRIL_BLUE);
        KINDS.put(Blocks.POLISHED_DIORITE, OreKind.TITANIUM);
    }

    private final ClientLevel level;
    private final Entity viewer;

    public LiveOreWorld(ClientLevel level, Entity viewer) {
        this.level = level;
        this.viewer = viewer;
    }

    public static OreKind kindOf(BlockState state) {
        return KINDS.get(state.getBlock());
    }

    @Override
    public OreKind oreAt(BlockPos pos) {
        return KINDS.get(level.getBlockState(pos).getBlock());
    }

    @Override
    public boolean passable(BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean solid(BlockPos pos) {
        return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
    }

    @Override
    public BlockPos rayHit(Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
