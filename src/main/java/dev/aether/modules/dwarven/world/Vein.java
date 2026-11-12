package dev.aether.modules.dwarven.world;

import net.minecraft.core.BlockPos;

import java.util.List;

public record Vein(List<BlockPos> blocks, BlockPos centre, int titanium) {
    public int size() {
        return blocks.size();
    }
}
