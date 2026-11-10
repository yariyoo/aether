package dev.aether.modules.commission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommissionParserTest {
    @Test
    void readsTheCommissionsSectionOfTheTabList() {
        List<Commission> list = CommissionParser.parseTab(List.of(
                "Info", "Area: Dwarven Mines", "", "Info", "Commissions:",
                "Royal Mines Mithril: 6.8%", "Lava Springs Mithril: DONE", "Rampart's Quarry Titanium: 0%",
                "2x Mithril Powder Collector: 12%", "", "Pet:", "[Lvl 100] Scatha"));

        assertEquals(4, list.size());
        assertEquals("Royal Mines Mithril", list.get(0).name());
        assertEquals(0.068, list.get(0).progress(), 1e-9);
        assertTrue(list.get(1).done());
        assertEquals(1.0, list.get(1).progress());
        assertEquals("Rampart's Quarry Titanium", list.get(2).name());
        assertEquals("2x Mithril Powder Collector", list.get(3).name());
    }

    @Test
    void findsNothingOutsideTheMines() {
        assertTrue(CommissionParser.parseTab(List.of("Info", "Area: Hub", "Pet:", "Mithril: 12,000")).isEmpty());
    }

    @Test
    void picksOnlyFinishedCommissionsFromTheMenu() {
        var done = new CommissionParser.MenuSlot(15, "Commission #4", List.of(
                "rewards.", "Goblin Raid Slayer", "Rewards", "- 6 Bits", "COMPLETED", "Click to claim rewards!"));
        var open = new CommissionParser.MenuSlot(12, "Commission #2 NEW", List.of(
                "rewards.", "Rampart's Quarry Titanium", "Progress", "                      70%"));
        var milestone = new CommissionParser.MenuSlot(30, "Commission Milestones", List.of("COMPLETED"));

        var ready = CommissionParser.claimable(List.of(open, done, milestone));

        assertEquals(1, ready.size());
        assertEquals(15, ready.getFirst().slot());
    }

    @Test
    void recognisesTheCompletionChatLine() {
        assertEquals("rampart's quarry titanium", CommissionParser.completedFromChat(
                "RAMPART'S QUARRY TITANIUM Commission Complete! Visit the King to claim your rewards!"));
        assertEquals("2x mithril powder collector", CommissionParser.completedFromChat(
                "2X MITHRIL POWDER COLLECTOR Commission Complete! Visit the King to claim your rewards!"));
        assertNull(CommissionParser.completedFromChat("[31] QdByOdy: Commission Complete! lol"));
        assertFalse(CommissionParser.completedFromChat("You used your Pickobulus Pickaxe Ability!") != null);
    }
}
