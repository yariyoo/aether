package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningVeinsTest {
    private static final Set<MiningBlockKind> ALL = EnumSet.allOf(MiningBlockKind.class);
    private static final BlockPos PLAYER = new BlockPos(0, 64, 0);

    @Test
    void groupsTouchingBlocksIncludingDiagonals() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(5, 64, 0, MiningBlockKind.GRAY_MITHRIL)
                .ore(6, 65, 1, MiningBlockKind.GREEN_MITHRIL)
                .ore(7, 65, 1, MiningBlockKind.GRAY_MITHRIL);

        List<MiningVeins.Vein> veins = MiningVeins.find(world, PLAYER, 16, 8, ALL, Set.of());

        assertEquals(1, veins.size());
        assertEquals(3, veins.getFirst().blocks().size());
    }

    @Test
    void ordersVeinsAndTheirBlocksNearestFirst() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(12, 64, 0, MiningBlockKind.GRAY_MITHRIL)
                .ore(4, 64, 0, MiningBlockKind.BLUE_MITHRIL)
                .ore(3, 64, 0, MiningBlockKind.BLUE_MITHRIL);

        List<MiningVeins.Vein> veins = MiningVeins.find(world, PLAYER, 16, 8, ALL, Set.of());

        assertEquals(2, veins.size());
        assertEquals(new BlockPos(3, 64, 0), veins.get(0).blocks().getFirst());
        assertEquals(new BlockPos(12, 64, 0), veins.get(1).blocks().getFirst());
        assertTrue(veins.get(0).distance() < veins.get(1).distance());
    }

    @Test
    void leavesOutDisabledSkippedAndOutOfRangeBlocks() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(3, 64, 0, MiningBlockKind.TITANIUM)
                .ore(0, 64, 3, MiningBlockKind.GRAY_MITHRIL)
                .ore(30, 64, 0, MiningBlockKind.GRAY_MITHRIL);

        List<MiningVeins.Vein> veins = MiningVeins.find(world, PLAYER, 16, 8,
                EnumSet.of(MiningBlockKind.GRAY_MITHRIL), Set.of(new BlockPos(0, 64, 3)));

        assertTrue(veins.isEmpty());
    }
}
