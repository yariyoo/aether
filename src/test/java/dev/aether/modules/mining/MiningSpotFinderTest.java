package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningSpotFinderTest {
    private static final double REACH = 4.4;

    @Test
    void findsAFloorSpotThatCanReachAndSeeTheBlock() {
        FakeMiningWorld world = new FakeMiningWorld()
                .floor(63, -20, 20, -20, 20)
                .ore(10, 64, 0, MiningBlockKind.GRAY_MITHRIL);

        BlockPos spot = MiningSpotFinder.find(world, new BlockPos(10, 64, 0), new BlockPos(0, 64, 0), REACH);

        assertNotNull(spot);
        assertEquals(64, spot.getY());
        assertTrue(MiningSpotFinder.isStandable(world, spot));
        assertTrue(spot.getX() < 10, "should pick the side facing the player");
    }

    @Test
    void neverStandsOnTheTargetItself() {
        FakeMiningWorld world = new FakeMiningWorld()
                .floor(63, -20, 20, -20, 20)
                .ore(5, 63, 0, MiningBlockKind.BLUE_MITHRIL);

        BlockPos spot = MiningSpotFinder.find(world, new BlockPos(5, 63, 0), new BlockPos(5, 64, 0), REACH);

        assertNotNull(spot);
        assertTrue(!spot.below().equals(new BlockPos(5, 63, 0)));
    }

    @Test
    void returnsNothingWhenTheBlockIsSealedIn() {
        FakeMiningWorld world = new FakeMiningWorld().floor(63, -20, 20, -20, 20);
        BlockPos ore = new BlockPos(5, 64, 0);
        world.ore(5, 64, 0, MiningBlockKind.GRAY_MITHRIL);
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos side = ore.relative(dir);
            if (world.isOpen(side)) world.stone(side.getX(), side.getY(), side.getZ());
        }

        assertNull(MiningSpotFinder.find(world, ore, new BlockPos(0, 64, 0), REACH));
    }
}
