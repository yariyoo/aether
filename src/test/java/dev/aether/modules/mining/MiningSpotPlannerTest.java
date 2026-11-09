package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningSpotPlannerTest {
    private static final double REACH = 4.4;
    private static final BlockPos PLAYER = new BlockPos(0, 64, 0);

    private static FakeMiningWorld floor() {
        return new FakeMiningWorld().floor(63, -40, 40, -40, 40);
    }

    private static List<BlockPos> wall(FakeMiningWorld world, int x, int fromZ, int toZ) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int z = fromZ; z <= toZ; z++) {
            for (int y = 64; y <= 66; y++) {
                world.ore(x, y, z, MiningBlockKind.GRAY_MITHRIL);
                blocks.add(new BlockPos(x, y, z));
            }
        }
        return blocks;
    }

    @Test
    void prefersARichSpotOverANearerSingleBlock() {
        FakeMiningWorld world = floor().ore(4, 64, 0, MiningBlockKind.GRAY_MITHRIL);
        List<BlockPos> ore = new ArrayList<>(List.of(new BlockPos(4, 64, 0)));
        ore.addAll(wall(world, 14, -3, 3));
        List<MiningVeins.Vein> veins = MiningVeins.group(ore, PLAYER);

        List<MiningSpotPlanner.Plan> plans = MiningSpotPlanner.rank(world, veins, ore, PLAYER, REACH, Set.of(), 8);

        assertEquals(2, plans.size());
        assertTrue(plans.getFirst().reachable() > 5, "rich wall should reach many blocks");
        assertTrue(plans.getFirst().spot().getX() > 8, "should head for the wall, not the lone block");
    }

    @Test
    void stillTakesAnEqualSpotThatIsCloser() {
        FakeMiningWorld world = floor();
        List<BlockPos> ore = new ArrayList<>(wall(world, 6, -1, 1));
        ore.addAll(wall(world, 30, -1, 1));
        List<MiningVeins.Vein> veins = MiningVeins.group(ore, PLAYER);

        List<MiningSpotPlanner.Plan> plans = MiningSpotPlanner.rank(world, veins, ore, PLAYER, REACH, Set.of(), 8);

        assertTrue(plans.getFirst().spot().getX() < 10);
    }

    @Test
    void avoidsSpotsThatFailedBefore() {
        FakeMiningWorld world = floor();
        List<BlockPos> ore = wall(world, 6, -1, 1);
        List<MiningVeins.Vein> veins = MiningVeins.group(ore, PLAYER);
        BlockPos first = MiningSpotPlanner.rank(world, veins, ore, PLAYER, REACH, Set.of(), 8).getFirst().spot();

        List<MiningSpotPlanner.Plan> retry = MiningSpotPlanner.rank(world, veins, ore, PLAYER, REACH, Set.of(first), 8);

        assertFalse(retry.isEmpty(), "another spot next to the same wall should still be offered");
        assertFalse(retry.stream().anyMatch(p -> p.spot().equals(first)));
    }
}
