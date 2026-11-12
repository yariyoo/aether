package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.fight.AttackLog;
import dev.aether.modules.dwarven.fight.AttackLog.Mode;
import dev.aether.modules.dwarven.memory.SpawnMemory;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTests {
    @Test
    void attacksAndKillsAreCountedSeparately() {
        AttackLog log = new AttackLog();
        log.recordHit(1, 1000);
        log.recordHit(2, 1100);
        assertEquals(2, log.attacks());
        assertEquals(0, log.kills());
        assertTrue(log.recordDeath(1));
        // a delayed second sighting of the same death isn't a second kill
        assertFalse(log.recordDeath(1));
        // a mob we never hit dying nearby isn't ours
        assertFalse(log.recordDeath(3));
        assertEquals(1, log.kills());
    }

    @Test
    void aRecentlyHitMobIsLeftAloneWhileItsOutcomeIsPending() {
        AttackLog log = new AttackLog();
        log.recordHit(7, 1000);
        assertTrue(log.pending(7, 1500, 1500));
        // still alive well after the hit: it survived and can be attacked again
        assertFalse(log.pending(7, 2600, 1500));
        log.recordDeath(7);
        assertFalse(log.pending(7, 1200, 1500));
        assertEquals(0, log.pendingCount(1200, 2500));
    }

    @Test
    void adaptiveModeLearnsWhenOneHitIsEnough() {
        AttackLog log = new AttackLog();
        assertFalse(log.moveOnAfterHit(Mode.ADAPTIVE, true));
        assertTrue(log.moveOnAfterHit(Mode.FAST, true));
        assertFalse(log.moveOnAfterHit(Mode.CONFIRMED, true));
        for (int id = 0; id < 5; id++) {
            log.recordHit(id, 1000 + id);
            log.recordDeath(id);
        }
        assertTrue(log.moveOnAfterHit(Mode.ADAPTIVE, true));
        // mobs that start needing several hits switch it back to staying on target
        for (int id = 10; id < 20; id++) {
            log.recordHit(id, 2000);
            log.recordHit(id, 2100);
            log.recordHit(id, 2200);
            log.recordDeath(id);
        }
        assertFalse(log.moveOnAfterHit(Mode.ADAPTIVE, true));
    }

    @Test
    void waitsAtTheBusiestSpawnAndSkipsOnesFoundEmpty() {
        SpawnMemory spawns = new SpawnMemory();
        BlockPos busy = new BlockPos(0, 128, 150);
        BlockPos quiet = new BlockPos(30, 128, 150);
        for (int i = 0; i < 6; i++) spawns.sighted("Glacite Walker", busy, 10_000 + i);
        spawns.sighted("Glacite Walker", quiet, 10_000);
        BlockPos feet = new BlockPos(15, 128, 150);
        BlockPos first = spawns.best(List.of("Glacite Walker"), feet, 20_000, 60_000, Set.of());
        assertTrue(first.distSqr(busy) < 36);
        spawns.empty(first, List.of("Glacite Walker"), 20_000);
        BlockPos second = spawns.best(List.of("Glacite Walker"), feet, 21_000, 60_000, Set.of());
        assertNotEquals(first, second);
        assertNull(spawns.best(List.of("Goblin"), feet, 21_000, 60_000, Set.of()));
    }
}
