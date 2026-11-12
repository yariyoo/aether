package dev.aether.modules.dwarven;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.fight.AttackLog;
import dev.aether.modules.dwarven.fight.KillCredit;
import dev.aether.modules.dwarven.fight.Mana;
import dev.aether.modules.dwarven.fight.Weapons;
import dev.aether.modules.dwarven.fight.Weapons.Kind;
import dev.aether.modules.dwarven.memory.AreaMemory;
import dev.aether.modules.dwarven.memory.SpawnMemory;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.nav.NavFailures;
import dev.aether.modules.dwarven.nav.RoutePlanner;
import dev.aether.modules.dwarven.nav.RoutePlanner.Plan;
import dev.aether.modules.dwarven.nav.RoutePlanner.Warp;
import dev.aether.modules.dwarven.world.WalkReach;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// weapon priority, kill credit, walk checks and the navigation choices that the last log showed going wrong
class EfficiencyTests {
    private static final Weapons.Prefs ALL = new Weapons.Prefs(true, true, true, true, true);
    private static final Set<HopKind> HOPS = EnumSet.allOf(HopKind.class);

    @BeforeEach
    void reset() {
        NavFailures.clear();
    }

    private static Weapons.Item hyperion(int slot, boolean scrolls) {
        List<String> lore = new ArrayList<>(List.of("Gear Score: 900", "Damage: +260", "Strength: +150", ""));
        if (scrolls) lore.addAll(List.of("Ability: Wither Impact  RIGHT CLICK", "Teleports 10 blocks ahead of you.", "Mana Cost: 150", ""));
        lore.add("a LEGENDARY DUNGEON SWORD a");
        return new Weapons.Item(slot, "Heroic Hyperion ✪✪✪✪✪", lore);
    }

    private static Weapons.Item terminator(int slot) {
        return new Weapons.Item(slot, "Precise Terminator", List.of("Damage: +310", "Shortbow: Instantly shoots!", "", "LEGENDARY DUNGEON BOW"));
    }

    private static Weapons.Item sword(int slot, String name, int damage) {
        return new Weapons.Item(slot, name, List.of("Damage: +" + damage, "", "EPIC SWORD"));
    }

    private static final Weapons.Item DRILL = new Weapons.Item(0, "Titanium Drill DR-X555", List.of("Mining Speed: +900", "", "LEGENDARY DRILL"));
    private static final Weapons.Item PLAIN_BOW = new Weapons.Item(6, "Hurricane Bow", List.of("Damage: +500", "", "LEGENDARY BOW"));

    @Test
    void hyperionWithWitherImpactAndManaComesFirst() {
        Weapons.Choice c = Weapons.choose(List.of(DRILL, terminator(2), hyperion(3, true)), 0, "drill", 800, ALL, Set.of());
        assertEquals(Kind.HYPERION, c.kind());
        assertEquals(3, c.slot());
        assertEquals(150, c.manaCost());
    }

    @Test
    void aHyperionWithoutTheAbilityIsStillFirstAndFightsAsASword() {
        Weapons.Choice c = Weapons.choose(List.of(DRILL, terminator(2), hyperion(3, false)), 0, "drill", 800, ALL, Set.of());
        assertEquals(Kind.HYPERION, c.kind());
        assertFalse(c.impact());
        assertTrue(c.reason().contains("without Wither Impact"));
    }

    @Test
    void lowManaKeepsTheHyperionAndLeavesTheSwitchToMeleeToTheFighter() {
        List<Weapons.Item> bar = List.of(DRILL, terminator(2), hyperion(3, true));
        Weapons.Choice low = Weapons.choose(bar, 0, "drill", 90, ALL, Set.of());
        assertEquals(Kind.HYPERION, low.kind());
        assertTrue(low.impact());
        assertTrue(low.reason().contains("left-clicking"));
        assertEquals(Kind.HYPERION, Weapons.choose(bar, 0, "drill", -1, ALL, Set.of()).kind());
    }

    @Test
    void theTerminatorIsUsedWhenThereIsNoHyperion() {
        assertEquals(Kind.TERMINATOR, Weapons.choose(List.of(DRILL, terminator(4)), 0, "drill", 500, ALL, Set.of()).kind());
    }

    @Test
    void otherwiseTheHardestHittingSwordOrShortbowAndNeverAPlainBow() {
        List<Weapons.Item> bar = List.of(DRILL, sword(1, "Aspect of the Dragons", 225), sword(2, "Livid Dagger", 210), PLAIN_BOW);
        Weapons.Choice c = Weapons.choose(bar, 0, "drill", 500, ALL, Set.of());
        assertEquals(Kind.MELEE, c.kind());
        assertEquals(1, c.slot());
        assertEquals(Kind.TOOL, Weapons.choose(List.of(DRILL, PLAIN_BOW), 0, "drill", 500, ALL, Set.of()).kind());
    }

    @Test
    void theMiningToolIsTheLastResortAndNothingUsableIsReported() {
        assertEquals(Kind.TOOL, Weapons.choose(List.of(DRILL), 0, "drill", 500, ALL, Set.of()).kind());
        Weapons.Choice none = Weapons.choose(List.of(), -1, null, 500, ALL, Set.of());
        assertNull(none.kind());
        assertTrue(none.reason().startsWith("no usable combat equipment"));
        Weapons.Prefs noTool = new Weapons.Prefs(true, true, true, true, false);
        assertNull(Weapons.choose(List.of(DRILL), 0, "drill", 500, noTool, Set.of()).kind());
    }

    @Test
    void aWeaponFoundNotToDoDamageIsSkippedAndAutoOffKeepsTheTool() {
        List<Weapons.Item> bar = List.of(DRILL, terminator(2), hyperion(3, true));
        assertEquals(Kind.TERMINATOR, Weapons.choose(bar, 0, "drill", 800, ALL, EnumSet.of(Kind.HYPERION)).kind());
        Weapons.Prefs off = new Weapons.Prefs(false, true, true, true, true);
        assertEquals(Kind.TOOL, Weapons.choose(bar, 0, "drill", 800, off, Set.of()).kind());
    }

    @Test
    void manaIsReadFromTheActionBar() {
        int[] m = Mana.parse("2,345/2,345❤     1,203/2,000✎ Mana     ");
        assertEquals(1203, m[0]);
        assertEquals(2000, m[1]);
        assertNull(Mana.parse("2,345/2,345❤"));
    }

    @Test
    void aTargetThatSurvivesItsFirstHitIsEngagedAgainAndNotCountedAsAKill() {
        AttackLog log = new AttackLog();
        log.recordHit(7, 1000);
        assertTrue(log.pending(7, 1500, 1500));
        assertFalse(log.pending(7, 3000, 1500));
        assertEquals(0, log.kills());
        log.recordHit(7, 3000);
        assertTrue(log.recordDeath(7));
        assertEquals(1, log.kills());
        assertEquals(2, log.get(7).hits);
    }

    @Test
    void killsThatNeverMoveTheCommissionDropThatMobType() {
        KillCredit credit = new KillCredit();
        long t = 0;
        credit.killed("Goblin", t);
        credit.progressed(t + 2000);
        for (int i = 0; i < 5; i++) {
            credit.killed("Weakling", t += 1000);
            credit.tick(t);
        }
        String dropped = null;
        for (int i = 0; i < 10 && dropped == null; i++) dropped = credit.tick(t += 2000);
        assertEquals("Weakling", dropped);
        assertFalse(credit.counts("Weakling"));
        assertTrue(credit.counts("Goblin"));
        assertTrue(credit.verified("Goblin"));
        // a verified type is never dropped, even after kills the tab list didn't show yet
        for (int i = 0; i < 6; i++) credit.killed("Goblin", t += 100);
        for (int i = 0; i < 10; i++) credit.tick(t += 2000);
        assertTrue(credit.counts("Goblin"));
    }

    @Test
    void aStandUpALedgeWithNoWayUpIsNotWalkable() {
        FakeOreWorld w = new FakeOreWorld().floor(0, 0, 10, 10, 99);
        // a pillar three blocks high; its top is a fine stand for mining but nothing climbs to it
        for (int y = 100; y <= 102; y++) w.stone(5, y, 5);
        Set<BlockPos> reach = WalkReach.from(w, new BlockPos(1, 100, 1), 20, 5000);
        assertTrue(reach.contains(new BlockPos(9, 100, 9)));
        assertFalse(reach.contains(new BlockPos(5, 103, 5)));
        // a single step is climbed and a short drop is walked down
        w.stone(2, 100, 1);
        assertTrue(WalkReach.from(w, new BlockPos(1, 100, 1), 20, 5000).contains(new BlockPos(2, 101, 1)));
    }

    @Test
    void withNoConnectedRouteFromTheLiftTheForgeIsUsedInsteadOfAStraightLineGuess() {
        TrailMemory t = new TrailMemory();
        // a short dead-end trail around the lift, and the real route from the forge
        t.seedPath(List.of(new BlockPos(-49, 200, -122), new BlockPos(-45, 200, -122), new BlockPos(-41, 200, -122)));
        t.seedPath(List.of(new BlockPos(0, 149, -69), new BlockPos(0, 149, -65)));
        t.seedHop(new Hop(HopKind.ETHERWARP, 0.5, 149, -64.5, -100.5, 168, -70.5));
        t.seedPath(List.of(new BlockPos(-101, 168, -71), new BlockPos(-105, 168, -71), new BlockPos(-109, 168, -71)));
        BlockPos lift = new BlockPos(-49, 200, -122), upper = new BlockPos(-110, 168, -70), forge = new BlockPos(0, 149, -69);
        Plan here = RoutePlanner.plan(t, lift, upper, 24, HOPS, Set.of(), Set.of(), true);
        assertEquals(RoutePlanner.Kind.NONE, here.kind());
        assertTrue(here.reason().startsWith("blocked"));
        Plan fromForge = RoutePlanner.plan(t, forge, upper, 24, HOPS, Set.of(), Set.of(), true);
        assertTrue(fromForge.kind().reachesGoal());
        // the old decision compared straight-line time and stayed; now an unroutable start goes via the forge
        Warp w = RoutePlanner.forgeDecision(true, forge, lift, 12, 25, 40, false, false, here.kind().reachesGoal());
        assertEquals(Warp.NO_ROUTE_HERE, w);
        assertTrue(RoutePlanner.warps(w));
    }

    @Test
    void aTeleportThatKeepsMissingLosesToASlightlyLongerReliableWalk() {
        TrailMemory t = new TrailMemory();
        List<BlockPos> walk = new ArrayList<>();
        for (int x = 0; x <= 16; x += 4) walk.add(new BlockPos(x, 100, 0));
        t.seedPath(walk);
        // the shortcut saves a little walking; two recent misses make it not worth the risk
        Hop shortcut = new Hop(HopKind.AOTV, 0.5, 100, 0.5, 16.5, 100, 0.5);
        t.seedHop(shortcut);
        BlockPos a = new BlockPos(0, 100, 0), b = new BlockPos(16, 100, 0);
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().anyMatch(x -> shortcut.equals(x.hop())));
        NavFailures.hopMissed(shortcut);
        NavFailures.hopMissed(shortcut);
        t.hopPenalty(h -> 1.0 * 8.0 * NavFailures.misses(h));
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().noneMatch(x -> shortcut.equals(x.hop())));
        t.hopPenalty(null);
    }

    @Test
    void walkingFallbackOffNeverPathfindsBlind() {
        TrailMemory empty = new TrailMemory();
        BlockPos a = new BlockPos(0, 100, 0);
        assertEquals(RoutePlanner.Kind.DIRECT, RoutePlanner.plan(empty, a, a.offset(20, 0, 0), 24, HOPS, Set.of(), Set.of(), true).kind());
        assertEquals(RoutePlanner.Kind.NONE, RoutePlanner.plan(empty, a, a.offset(20, 0, 0), 24, HOPS, Set.of(), Set.of(), true,
                RoutePlanner.options(true, false)).kind());
    }

    @Test
    void fightSpotsOutsideTheHuntsAreaAreNeverChosen() {
        AreaMemory areas = new AreaMemory();
        BlockPos basin = new BlockPos(21, 144, -3), burrows = new BlockPos(-135, 144, 135);
        areas.observe("Forge Basin", basin);
        areas.observe("Goblin Burrows", burrows);
        assertFalse(areas.allows("Goblin Burrows", basin));
        assertTrue(areas.allows("Goblin Burrows", burrows));
        assertTrue(areas.allows("Goblin Burrows", new BlockPos(500, 100, 500)));
        SpawnMemory spawns = new SpawnMemory();
        for (int i = 0; i < 20; i++) spawns.sighted("Fireslinger", basin, 1000 + i);
        spawns.sighted("Goblin", burrows, 1000);
        List<String> goblins = List.of("Goblin", "Knifethrower", "Fireslinger");
        assertTrue(spawns.best(goblins, new BlockPos(20, 144, -4), 2000, 0, Set.of()).distSqr(basin) < 100);
        BlockPos chosen = spawns.best(goblins, new BlockPos(20, 144, -4), 2000, 0, Set.of(), p -> areas.allows("Goblin Burrows", p));
        assertNotNull(chosen);
        assertTrue(chosen.distSqr(burrows) < 100);
    }

    @Test
    void theBundledGoblinSpawnsAreAllInTheBurrows() throws Exception {
        JsonObject seed;
        try (var in = EfficiencyTests.class.getResourceAsStream("/assets/aether/dwarven/seed.json")) {
            assertNotNull(in);
            seed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject hunts = seed.getAsJsonObject("hunts");
        for (String mob : List.of("Goblin", "Knifethrower", "Fireslinger")) {
            for (var el : hunts.getAsJsonArray(mob)) {
                JsonArray p = el.getAsJsonArray();
                int x = p.get(0).getAsInt(), z = p.get(2).getAsInt();
                // the forge basin sits around x -26..30, z -30..2; the burrows far to the south-west
                assertTrue(x < -50 && z > 80, mob + " spawn at " + x + ", " + z + " is outside the burrows");
            }
        }
    }
}
