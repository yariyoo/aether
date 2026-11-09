package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningVeinFilterTest {
    @Test
    void acceptsAVeinEmbeddedInStone() {
        FakeMiningWorld world = new FakeMiningWorld();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) world.stone(x, y, z);
            }
        }
        world.stone.remove(new BlockPos(0, 0, 0));
        world.stone.remove(new BlockPos(1, 0, 0));
        world.stone.remove(new BlockPos(0, 1, 0));
        world.ore(0, 0, 0, MiningBlockKind.GRAY_MITHRIL).ore(1, 0, 0, MiningBlockKind.BLUE_MITHRIL);

        MiningVeinFilter.Surroundings around = MiningVeinFilter.inspect(world,
                List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));

        assertTrue(around.looksNatural(), around.describe());
    }

    @Test
    void rejectsWoolBuiltIntoAWoodenWall() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(0, 1, 0, MiningBlockKind.BLUE_MITHRIL)
                .ore(1, 1, 0, MiningBlockKind.GRAY_MITHRIL)
                .planks(-1, 1, 0).planks(2, 1, 0)
                .planks(0, 0, 0).planks(1, 0, 0)
                .planks(0, 2, 0).planks(1, 2, 0)
                .stone(0, 1, 1);

        MiningVeinFilter.Surroundings around = MiningVeinFilter.inspect(world,
                List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0)));

        assertFalse(around.looksNatural(), around.describe());
        assertEquals(6, around.otherBlocks().get("planks"));
    }

    @Test
    void rejectsAFloatingBlockWithNothingAroundIt() {
        FakeMiningWorld world = new FakeMiningWorld().ore(0, 5, 0, MiningBlockKind.GRAY_MITHRIL);

        assertFalse(MiningVeinFilter.inspect(world, List.of(new BlockPos(0, 5, 0))).looksNatural());
    }
}
