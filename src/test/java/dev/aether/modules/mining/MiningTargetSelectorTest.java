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

class MiningTargetSelectorTest {
    private static final Set<MiningBlockKind> ALL = EnumSet.allOf(MiningBlockKind.class);
    private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);
    private static final Vec3 LOOK_NORTH = new Vec3(0, 0, -1);
    private static final double REACH = 4.4;

    private static MiningTargetSelector.Target select(FakeMiningWorld world, Set<MiningBlockKind> allowed, boolean preferTitanium) {
        return MiningTargetSelector.select(world, EYE, LOOK_NORTH, REACH, allowed, preferTitanium, Set.of());
    }

    @Test
    void picksTheBlockClosestToWhereThePlayerIsLooking() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(0, 1, -2, MiningBlockKind.GRAY_MITHRIL)
                .ore(2, 1, 0, MiningBlockKind.GRAY_MITHRIL);

        MiningTargetSelector.Target target = select(world, ALL, false);

        assertNotNull(target);
        assertEquals(new BlockPos(0, 1, -2), target.pos());
    }

    @Test
    void ignoresBlocksHiddenBehindStone() {
        FakeMiningWorld world = new FakeMiningWorld()
                .stone(0, 1, -1)
                .ore(0, 1, -2, MiningBlockKind.BLUE_MITHRIL);

        assertNull(select(world, ALL, false));
    }

    @Test
    void ignoresBlocksOutOfReach() {
        FakeMiningWorld world = new FakeMiningWorld().ore(0, 1, -6, MiningBlockKind.GREEN_MITHRIL);

        assertNull(select(world, ALL, false));
    }

    @Test
    void ignoresDisabledBlockTypes() {
        FakeMiningWorld world = new FakeMiningWorld().ore(0, 1, -2, MiningBlockKind.GRAY_MITHRIL);

        assertNull(select(world, EnumSet.of(MiningBlockKind.TITANIUM), false));
    }

    @Test
    void prefersTitaniumWhenAsked() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(0, 1, -2, MiningBlockKind.GRAY_MITHRIL)
                .ore(3, 1, 0, MiningBlockKind.TITANIUM);

        assertEquals(MiningBlockKind.GRAY_MITHRIL, select(world, ALL, false).kind());
        assertEquals(MiningBlockKind.TITANIUM, select(world, ALL, true).kind());
    }

    @Test
    void skipsBlocksTheMinerGaveUpOn() {
        FakeMiningWorld world = new FakeMiningWorld()
                .ore(0, 1, -2, MiningBlockKind.GRAY_MITHRIL)
                .ore(2, 1, 0, MiningBlockKind.GRAY_MITHRIL);

        MiningTargetSelector.Target target = MiningTargetSelector.select(world, EYE, LOOK_NORTH, REACH, ALL, false,
                Set.of(new BlockPos(0, 1, -2)));

        assertEquals(new BlockPos(2, 1, 0), target.pos());
    }

    @Test
    void aimsAtAnExposedFaceInsideTheBlock() {
        FakeMiningWorld world = new FakeMiningWorld().ore(0, 1, -2, MiningBlockKind.GRAY_MITHRIL);

        MiningTargetSelector.Target target = select(world, ALL, false);

        assertEquals(new BlockPos(0, 1, -2), BlockPos.containing(target.aim()));
        assertTrue(EYE.distanceTo(target.aim()) <= REACH);
    }
}
