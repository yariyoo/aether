package dev.aether.modules.commission;

import dev.aether.modules.mining.MiningBlockKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommissionTaskTest {
    @Test
    void areaCommissionsMineTheRightOreInTheirArea() {
        CommissionTask task = CommissionTask.of("Rampart's Quarry Titanium");
        assertTrue(task.isMining());
        assertEquals("Rampart's Quarry", task.area().name());
        assertEquals(1, task.ores().size());
        assertTrue(task.ores().contains(MiningBlockKind.TITANIUM));

        CommissionTask royal = CommissionTask.of("Royal Mines Mithril");
        assertEquals("Royal Mines", royal.area().name());
        assertTrue(royal.ores().contains(MiningBlockKind.BLUE_MITHRIL));
    }

    @Test
    void generalMiningCommissionsWorkAnywhere() {
        assertNull(CommissionTask.of("Mithril Miner").area());
        assertNull(CommissionTask.of("Titanium Miner").area());
        assertTrue(CommissionTask.of("Mithril Miner").isMining());
    }

    @Test
    void sortsTheRestIntoCombatAndEvents() {
        assertEquals(CommissionTask.Kind.COMBAT, CommissionTask.of("Goblin Slayer").kind());
        assertEquals(CommissionTask.Kind.COMBAT, CommissionTask.of("Glacite Walker Slayer").kind());
        assertEquals(CommissionTask.Kind.COMBAT, CommissionTask.of("Treasure Hoarder Puncher").kind());
        assertEquals(CommissionTask.Kind.EVENT, CommissionTask.of("Goblin Raid Slayer").kind());
        assertEquals(CommissionTask.Kind.UNKNOWN, CommissionTask.of("Something New").kind());
    }

    @Test
    void ignoresPowderCollectorAndRaffle() {
        assertEquals(CommissionTask.Kind.IGNORED, CommissionTask.of("2x Mithril Powder Collector").kind());
        assertEquals(CommissionTask.Kind.IGNORED, CommissionTask.of("Raffle").kind());
    }
}
