package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.dwarven.commission.CommissionScheduler;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Rules;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Strategy;
import dev.aether.modules.dwarven.commission.DwarvenCommission;
import dev.aether.modules.dwarven.commission.CombatTarget;
import dev.aether.modules.dwarven.commission.Objective;
import dev.aether.modules.dwarven.commission.WorkStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommissionTests {
    private static final Rules FASTEST = new Rules(Strategy.FASTEST, true, true, true, true, 0.25, true, true);

    @Test
    void readsTheTabListSection() {
        List<DwarvenCommission> found = CommissionReader.fromTab(List.of("Skills:", " Mining 60", "", "Commissions:",
                " Royal Mines Titanium: 45%", " Mithril Miner: DONE", " Goblin Slayer: 0%", "", "Powders:", " Mithril: 1,000"));
        assertEquals(3, found.size());
        assertEquals("Royal Mines Titanium", found.get(0).name());
        assertEquals(0.45, found.get(0).progress(), 1e-9);
        assertTrue(found.get(1).done());
        assertFalse(found.get(2).done());
    }

    @Test
    void noSectionMeansNoCommissions() {
        assertTrue(CommissionReader.fromTab(List.of("Skills:", " Mining 60")).isEmpty());
    }

    @Test
    void recognisesClaimableMenuItemsAndChat() {
        assertTrue(CommissionReader.isClaimable(List.of("Mine 500 Mithril", "", "COMPLETED", "Click to claim rewards!")));
        assertFalse(CommissionReader.isClaimable(List.of("Mine 500 Mithril", "Progress: 45%")));
        assertEquals("ROYAL MINES TITANIUM", CommissionReader.completedInChat("ROYAL MINES TITANIUM Commission Complete! Visit the King to claim your rewards!"));
        assertNull(CommissionReader.completedInChat("You mined some Mithril"));
    }

    @Test
    void worksOutObjectivesFromNames() {
        Objective royal = Objective.of("Royal Mines Titanium");
        assertEquals(Objective.Kind.MINING, royal.kind());
        assertEquals(Objective.Ore.TITANIUM, royal.ore());
        assertEquals("Royal Mines", royal.area());
        assertTrue(Objective.of("Mithril Miner").anywhere());
        assertEquals("Rampart's Quarry", Objective.of("Rampart's Quarry Mithril").area());
        assertEquals(Objective.Kind.COMBAT, Objective.of("Goblin Slayer").kind());
        assertEquals(Objective.Kind.COMBAT, Objective.of("Star Sentry Puncher").kind());
        assertEquals(Objective.Kind.EVENT, Objective.of("Goblin Raid Slayer").kind());
        assertEquals(Objective.Kind.EVENT, Objective.of("2x Mithril Powder Collector").kind());
        assertEquals(Objective.Kind.EVENT, Objective.of("Lucky Raffle").kind());
        // an area nobody has coded for still parses
        assertEquals("Some New Cavern", Objective.of("Some New Cavern Mithril").area());
    }

    @Test
    void overlapOnlyCountsWhereTheWorkReallyHelps() {
        Objective royalTitanium = Objective.of("Royal Mines Titanium");
        assertTrue(royalTitanium.alsoAdvances(Objective.of("Mithril Miner")));
        assertTrue(royalTitanium.alsoAdvances(Objective.of("Royal Mines Mithril")));
        assertFalse(royalTitanium.alsoAdvances(Objective.of("Lava Springs Mithril")));
        assertFalse(Objective.of("Royal Mines Mithril").alsoAdvances(Objective.of("Titanium Miner")));
    }

    @Test
    void picksTheQuickestAndSkipsWhatItCannotDo() {
        List<DwarvenCommission> list = List.of(new DwarvenCommission("Goblin Slayer", 0, false),
                new DwarvenCommission("Lava Springs Mithril", 0.0, false), new DwarvenCommission("Royal Mines Mithril", 0.8, false));
        var choice = CommissionScheduler.choose(list, FASTEST, null, o -> "Royal Mines".equals(o.area()) ? 40 : 5, c -> 60);
        // 40s travel + 12s of work left beats 5s travel + 60s of work
        assertEquals("Royal Mines Mithril", choice.commission().name());
        Rules noCombat = new Rules(Strategy.FASTEST, true, true, true, true, 0.25, false, true);
        assertNull(CommissionScheduler.choose(List.of(list.get(0)), noCombat, null, o -> 0, c -> 60));
        assertEquals("Goblin Slayer", CommissionScheduler.choose(List.of(list.get(0)), FASTEST, null, o -> 0, c -> 60).commission().name());
    }

    @Test
    void staysOnTheCurrentCommissionUnlessAnotherIsClearlyFaster() {
        List<DwarvenCommission> list = List.of(new DwarvenCommission("Lava Springs Mithril", 0.0, false),
                new DwarvenCommission("Royal Mines Mithril", 0.0, false));
        var slightlyBetter = CommissionScheduler.choose(list, FASTEST, "Royal Mines Mithril", o -> "Royal Mines".equals(o.area()) ? 10 : 2, c -> 60);
        assertEquals("Royal Mines Mithril", slightlyBetter.commission().name());
        var muchBetter = CommissionScheduler.choose(list, FASTEST, "Royal Mines Mithril", o -> "Royal Mines".equals(o.area()) ? 200 : 2, c -> 60);
        assertEquals("Lava Springs Mithril", muchBetter.commission().name());
    }

    @Test
    void overlapMakesACommissionMoreAttractive() {
        List<DwarvenCommission> list = List.of(new DwarvenCommission("Lava Springs Mithril", 0.0, false),
                new DwarvenCommission("Royal Mines Titanium", 0.0, false), new DwarvenCommission("Royal Mines Mithril", 0.0, false));
        var choice = CommissionScheduler.choose(list, FASTEST, null, o -> 10, c -> 60);
        assertEquals("Royal Mines Titanium", choice.commission().name());
        assertEquals(1, choice.alsoAdvanced().size());
    }

    @Test
    void settingsSwitchCommissionKindsOff() {
        Rules noTitanium = new Rules(Strategy.FASTEST, true, false, true, true, 0.25, true, true);
        assertFalse(CommissionScheduler.allowed(Objective.of("Titanium Miner"), noTitanium));
        Rules noAreas = new Rules(Strategy.FASTEST, true, true, false, true, 0.25, true, true);
        assertFalse(CommissionScheduler.allowed(Objective.of("Royal Mines Mithril"), noAreas));
        assertTrue(CommissionScheduler.allowed(Objective.of("Mithril Miner"), noAreas));
    }

    @Test
    void learnsHowLongCommissionsTake() {
        WorkStats stats = new WorkStats(75);
        assertEquals(75, stats.secondsFor("Royal Mines Mithril"));
        stats.record("Royal Mines Mithril", 30, 0.5);
        assertEquals(60, stats.secondsFor("Royal Mines Mithril"), 1e-9);
        stats.record("Royal Mines Mithril", 5, 0.05);
        assertEquals(60, stats.secondsFor("Royal Mines Mithril"), 1e-9);
        WorkStats reloaded = new WorkStats(75);
        reloaded.load(stats.toJson());
        assertEquals(60, reloaded.secondsFor("Royal Mines Mithril"), 1e-9);
    }

    @Test
    void retriesAreBoundedAndBackOff() {
        RetryBudget budget = new RetryBudget(3, 1000);
        assertEquals(1000, budget.fail(RetryBudget.Failure.NAVIGATION).waitMs());
        assertEquals(2000, budget.fail(RetryBudget.Failure.NAVIGATION).waitMs());
        assertTrue(budget.fail(RetryBudget.Failure.NAVIGATION).retry());
        assertFalse(budget.fail(RetryBudget.Failure.NAVIGATION).retry());
        assertTrue(budget.fail(RetryBudget.Failure.CLAIM).retry());
        budget.succeeded(RetryBudget.Failure.NAVIGATION);
        assertTrue(budget.fail(RetryBudget.Failure.NAVIGATION).retry());
    }

    @Test
    void combatCommissionsKnowTheirMobsAndZone() {
        assertEquals("Great Ice Wall", Objective.of("Glacite Walker Slayer").area());
        assertEquals("Goblin Burrows", Objective.of("Goblin Slayer").area());
        assertTrue(CombatTarget.of("Glacite Walker Slayer").mobs().contains("Glacite Walker"));
        assertNull(Objective.of("Treasure Hoarder Puncher").area());
        assertEquals(Objective.Kind.UNKNOWN, Objective.of("Some Future Slayer").kind());
    }

    @Test
    void readsHypixelNameTags() {
        var tag = dev.aether.modules.dwarven.fight.DwarvenFighter.parse("[Lv24] \uE06D Glacite Walker 888/888❤");
        assertEquals("Glacite Walker", tag.name());
        assertEquals(888.0, tag.health());
        assertEquals(15_000.0, dev.aether.modules.dwarven.fight.DwarvenFighter.parse("[Lv61]  Treasure Hoarder 15,000/15,000❤").health());
        assertEquals(1_200.0, dev.aether.modules.dwarven.fight.DwarvenFighter.parse("[Lv30] Goblin 1.2k/2k❤").health());
        assertEquals(0.0, dev.aether.modules.dwarven.fight.DwarvenFighter.parse("[Lv3] Weakling 0/200❤").health());
        assertNull(dev.aether.modules.dwarven.fight.DwarvenFighter.parse("Royal Pigeon"));
    }

    @Test
    void aGoblinRaidCountsOnlyWhileItIsRunning() {
        var running = List.of("www.hypixel.net", "Kill goblins!", "Event: GOBLIN RAID", "Remaining: 306 goblins", "Your kills: 43 ☠");
        var announced = List.of("www.hypixel.net", "Zone: The Forge", "Event: GOBLIN RAID", "Purse: 32,295,478");
        assertTrue(dev.aether.modules.dwarven.event.GoblinRaid.active(running));
        assertFalse(dev.aether.modules.dwarven.event.GoblinRaid.active(announced));
        assertTrue(dev.aether.modules.dwarven.event.GoblinRaid.isRaidCommission("Goblin Raid Slayer"));
        assertFalse(dev.aether.modules.dwarven.event.GoblinRaid.isRaidCommission("Raffle"));
        assertTrue(CombatTarget.of("Goblin Raid").mobs().contains("Weakling"));
    }

    @Test
    void readsCommissionRewardsOffTheItem() {
        var lore = List.of("Mine 250 Mithril", "", "COMPLETED", "", "Rewards", "- 1,600 Mithril Powder (Daily Bonus)",
                "- 5,000 Mithril Powder (Daily Grind HOTM Bonus)", "- 1,000 Mithril Powder", "- +7.5k Mining Exp", "", "Click to claim rewards!");
        var tally = dev.aether.modules.dwarven.claim.Rewards.parse(lore);
        assertEquals(7_600.0, tally.mithrilPowder());
        assertEquals(7_500.0, tally.miningExp());
        assertEquals(0.0, tally.hotmExp());
    }
}
