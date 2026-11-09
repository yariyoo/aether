package dev.aether.modules.mining;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public enum MiningBlockKind {
    GRAY_MITHRIL,
    GREEN_MITHRIL,
    BLUE_MITHRIL,
    TITANIUM;

    // hypixel skins mithril and titanium as these vanilla blocks in the dwarven mines
    public static MiningBlockKind fromBlock(Block block) {
        if (block == Blocks.WOOL.gray() || block == Blocks.DYED_TERRACOTTA.cyan()) return GRAY_MITHRIL;
        if (block == Blocks.PRISMARINE || block == Blocks.PRISMARINE_BRICKS || block == Blocks.DARK_PRISMARINE) {
            return GREEN_MITHRIL;
        }
        if (block == Blocks.WOOL.lightBlue()) return BLUE_MITHRIL;
        if (block == Blocks.POLISHED_DIORITE) return TITANIUM;
        return null;
    }
}
