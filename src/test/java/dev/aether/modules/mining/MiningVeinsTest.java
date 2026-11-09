package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningVeinsTest {
    private static final BlockPos PLAYER = new BlockPos(0, 64, 0);

    @Test
    void groupsTouchingBlocksIncludingDiagonals() {
        List<MiningVeins.Vein> veins = MiningVeins.group(List.of(
                new BlockPos(5, 64, 0), new BlockPos(6, 65, 1), new BlockPos(7, 65, 1)), PLAYER);

        assertEquals(1, veins.size());
        assertEquals(3, veins.getFirst().blocks().size());
    }

    @Test
    void ordersVeinsAndTheirBlocksNearestFirst() {
        List<MiningVeins.Vein> veins = MiningVeins.group(List.of(
                new BlockPos(12, 64, 0), new BlockPos(4, 64, 0), new BlockPos(3, 64, 0)), PLAYER);

        assertEquals(2, veins.size());
        assertEquals(new BlockPos(3, 64, 0), veins.get(0).blocks().getFirst());
        assertEquals(new BlockPos(12, 64, 0), veins.get(1).blocks().getFirst());
        assertTrue(veins.get(0).distance() < veins.get(1).distance());
    }

    @Test
    void keepsBlocksTwoApartInSeparateVeins() {
        List<MiningVeins.Vein> veins = MiningVeins.group(List.of(
                new BlockPos(3, 64, 0), new BlockPos(5, 64, 0)), PLAYER);

        assertEquals(2, veins.size());
    }

    @Test
    void handlesNoBlocks() {
        assertTrue(MiningVeins.group(List.of(), PLAYER).isEmpty());
    }
}
