package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningSpotMemoryTest {
    private static final MiningArea AREA = MiningArea.around("Test", 0, 100, 0, 20, 100, 20, new BlockPos(10, 100, 10), 0);
    private static final BlockPos FEET = new BlockPos(0, 100, 0);

    private static MiningSpotMemory seeded() {
        MiningSpotMemory memory = new MiningSpotMemory();
        memory.load(new StringReader("""
                {"spots":[{"pos":[10,100,10],"mined":40},{"pos":[5,100,0],"mined":3},{"pos":[200,100,0],"mined":99}]}
                """));
        return memory;
    }

    @Test
    void prefersSpotsThatGaveMoreOreWithinTheArea() {
        assertEquals(new BlockPos(10, 100, 10), seeded().best(AREA, FEET, 0L, Set.of()));
    }

    @Test
    void emptySpotsWaitBeforeBeingCheckedAgain() {
        MiningSpotMemory memory = seeded();
        long now = 1_000_000L;
        memory.recordEmpty(new BlockPos(10, 100, 10), now);
        assertEquals(new BlockPos(5, 100, 0), memory.best(AREA, FEET, now + 1000, Set.of()));
        assertEquals(new BlockPos(10, 100, 10), memory.best(AREA, FEET, now + MiningSpotMemory.RECHECK_MS + 1, Set.of()));
    }

    @Test
    void repeatedlyUnreachableSpotsAreIgnoredForAWhile() {
        MiningSpotMemory memory = seeded();
        long now = 1_000_000L;
        for (int i = 0; i < MiningSpotMemory.FAILURES_BEFORE_IGNORING; i++) {
            memory.recordUnreachable(new BlockPos(10, 100, 10), now);
        }
        assertEquals(new BlockPos(5, 100, 0), memory.best(AREA, FEET, now, Set.of()));
        memory.recordUnreachable(new BlockPos(5, 100, 0), now);
        assertEquals(new BlockPos(5, 100, 0), memory.best(AREA, FEET, now, Set.of()));
    }

    @Test
    void miningNextToASpotCountsForIt() {
        MiningSpotMemory memory = seeded();
        int before = memory.size();
        memory.recordMined(new BlockPos(11, 100, 10));
        assertEquals(before, memory.size());
        memory.recordMined(new BlockPos(15, 100, 15));
        assertEquals(before + 1, memory.size());
        assertTrue(memory.isDirty());
    }

    @Test
    void savedSpotsReloadWithTheirCounts() {
        MiningSpotMemory memory = seeded();
        memory.recordMined(new BlockPos(15, 100, 15));
        MiningSpotMemory reloaded = new MiningSpotMemory();
        reloaded.load(new StringReader(memory.toJson().toString()));
        assertEquals(memory.size(), reloaded.size());
        assertNull(reloaded.best(AREA, new BlockPos(10, 100, 10), 0L, Set.of(new BlockPos(5, 100, 0), new BlockPos(15, 100, 15))));
    }
}
