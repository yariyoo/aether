package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningPickobulusTest {
    private static final Set<MiningBlockKind> MITHRIL = EnumSet.of(MiningBlockKind.GRAY_MITHRIL);
    private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);

    @Test
    void aimsAtTheMiddleOfTheDensestPatch() {
        FakeMiningWorld world = new FakeMiningWorld().ore(3, 1, 0, MiningBlockKind.GRAY_MITHRIL);
        // a 3x3 wall of ore north of the player, one block deep
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) world.ore(x, y, -3, MiningBlockKind.GRAY_MITHRIL);
        }

        MiningPickobulus.Blast blast = MiningPickobulus.best(world, EYE, 4.4, MITHRIL, Set.of());

        assertNotNull(blast);
        assertEquals(new BlockPos(0, 1, -3), blast.pos());
        assertEquals(9, blast.ore());
        assertTrue(blast.ore() >= MiningPickobulus.MIN_ORE);
    }

    @Test
    void ignoresOreItIsNotAllowedToMine() {
        FakeMiningWorld world = new FakeMiningWorld();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) world.ore(x, y, -3, MiningBlockKind.BLUE_MITHRIL);
        }
        assertNull(MiningPickobulus.best(world, EYE, 4.4, MITHRIL, Set.of()));
    }
}
