package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningAreaTest {
    private static final MiningArea LAVA = MiningArea.around("Lava Springs", 39, 198, -25, 55, 202, -9,
            new BlockPos(42, 198, -13), 4);

    @Test
    void containsWhereTheRecordedMacroStoodPlusMargin() {
        assertTrue(LAVA.contains(new BlockPos(42, 198, -13)));
        assertTrue(LAVA.contains(new BlockPos(35, 194, -29)));
        assertFalse(LAVA.contains(new BlockPos(26, 178, -8)), "the spot the old radius check wandered off to");
        assertFalse(LAVA.contains(new BlockPos(28, 215, -19)));
    }

    @Test
    void measuresDistanceToTheBoxNotItsCentre() {
        assertEquals(0.0, LAVA.distanceTo(new BlockPos(50, 200, -20)));
        assertTrue(LAVA.distanceTo(new BlockPos(64, 200, -20)) > 3.0);
        assertTrue(LAVA.distanceTo(new BlockPos(64, 200, -20)) < 5.0);
    }
}
