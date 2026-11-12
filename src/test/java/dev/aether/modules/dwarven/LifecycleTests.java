package dev.aether.modules.dwarven;

import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.claim.Rewards;
import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.dwarven.commission.CommissionScheduler;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Rules;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Strategy;
import dev.aether.modules.dwarven.commission.DwarvenCommission;
import dev.aether.modules.dwarven.commission.Objective;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the decisions a whole commission cycle goes through, fed the way the game feeds them, including late and empty reads
class LifecycleTests {
    private static final Rules RULES = new Rules(Strategy.FASTEST, true, true, true, true, 0.25, true, true);

    private static List<String> tab(String... commissions) {
        List<String> lines = new ArrayList<>(List.of("Area: Dwarven Mines", "Commissions:"));
        for (String c : commissions) lines.add(" " + c);
        lines.add("");
        lines.add("Powders:");
        return lines;
    }

    @Test
    void choosesWorksClaimsAndMovesOnToTheNextCommission() {
        var first = CommissionReader.fromTab(tab("Royal Mines Mithril: 20%", "Glacite Walker Slayer: 0%", "Raffle: 0%"));
        var choice = CommissionScheduler.choose(first, RULES, null, o -> o.kind() == Objective.Kind.COMBAT ? 60 : 10, c -> 60);
        assertEquals("Royal Mines Mithril", choice.commission().name());

        // a flickering, empty tab read must not count as the commission going away
        assertTrue(CommissionReader.fromTab(List.of("Area: Dwarven Mines")).isEmpty());

        var later = CommissionReader.fromTab(tab("Royal Mines Mithril: 85%", "Glacite Walker Slayer: 0%", "Raffle: 0%"));
        var stay = CommissionScheduler.choose(later, RULES, "Royal Mines Mithril", o -> 10, c -> 60);
        assertEquals("Royal Mines Mithril", stay.commission().name());

        var done = CommissionReader.fromTab(tab("Royal Mines Mithril: DONE", "Glacite Walker Slayer: 0%", "Raffle: 0%"));
        assertTrue(done.stream().anyMatch(DwarvenCommission::done));
        List<String> item = List.of("Royal Mines Mithril", "", "COMPLETED", "", "Rewards", "- 1,000 Mithril Powder", "- +7.5k Mining Exp",
                "", "Click to claim rewards!");
        assertTrue(CommissionReader.isClaimable(item));
        assertEquals(1_000.0, Rewards.parse(item).mithrilPowder());

        var refreshed = CommissionReader.fromTab(tab("Lava Springs Titanium: 0%", "Glacite Walker Slayer: 0%", "Raffle: 0%"));
        var next = CommissionScheduler.choose(refreshed, RULES, "Royal Mines Mithril", o -> o.kind() == Objective.Kind.COMBAT ? 60 : 10, c -> 60);
        assertEquals("Lava Springs Titanium", next.commission().name());
    }

    @Test
    void anEventIsNeverChosenUnlessTheMacroLetsItThrough() {
        var list = CommissionReader.fromTab(tab("Goblin Raid: 0%", "Raffle: 0%"));
        Rules noEvents = new Rules(Strategy.FASTEST, true, true, true, true, 0.25, true, false);
        assertEquals(null, CommissionScheduler.choose(list, noEvents, null, o -> 0, c -> 60));
        var raid = CommissionScheduler.choose(list.subList(0, 1), RULES, null, o -> 0, c -> 60);
        assertEquals("Goblin Raid", raid.commission().name());
    }

    @Test
    void routesUseRecordedTeleportsOnlyWhenTheGearAllowsThem() {
        TrailMemory trails = new TrailMemory();
        trails.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(4, 100, 0), new BlockPos(8, 100, 0)));
        trails.seedPath(List.of(new BlockPos(60, 120, 0), new BlockPos(64, 120, 0)));
        trails.seedHop(new Hop(HopKind.ETHERWARP, 8.5, 100, 0.5, 60.5, 120, 0.5));

        var withEther = trails.waypoints(new BlockPos(0, 100, 0), new BlockPos(64, 120, 0), 4, 24, EnumSet.allOf(HopKind.class));
        assertFalse(withEther.isEmpty());
        var hop = withEther.stream().filter(w -> w.hop() != null).findFirst().orElse(null);
        assertNotNull(hop);
        // the stop before a teleport is where the teleport starts, so it can be lined up
        int i = withEther.indexOf(hop);
        assertTrue(i > 0 && withEther.get(i - 1).pos().distSqr(new BlockPos(8, 100, 0)) <= 4);

        assertTrue(trails.waypoints(new BlockPos(0, 100, 0), new BlockPos(64, 120, 0), 4, 24, Set.of()).isEmpty());
        assertTrue(trails.waypoints(new BlockPos(0, 100, 0), new BlockPos(64, 120, 0), 4, 24, EnumSet.of(HopKind.AOTV)).isEmpty());
    }

    @Test
    void savedTrailsDropTeleportLinksSoTheyAreNeverWalked() {
        TrailMemory trails = new TrailMemory();
        trails.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(4, 100, 0)));
        trails.seedPath(List.of(new BlockPos(60, 120, 0), new BlockPos(64, 120, 0)));
        trails.seedHop(new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 60.5, 120, 0.5));
        TrailMemory reloaded = new TrailMemory();
        reloaded.load(JsonParser.parseString(trails.toJson().toString()).getAsJsonObject());
        assertTrue(reloaded.waypoints(new BlockPos(0, 100, 0), new BlockPos(64, 120, 0), 4, 24, EnumSet.allOf(HopKind.class)).isEmpty());
        assertFalse(reloaded.waypoints(new BlockPos(0, 100, 0), new BlockPos(4, 100, 0), 4, 24, Set.of()).isEmpty());
    }
}
