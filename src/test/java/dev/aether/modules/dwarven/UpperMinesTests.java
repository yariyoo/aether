package dev.aether.modules.dwarven;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.dwarven.commission.CommissionScheduler;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Rules;
import dev.aether.modules.dwarven.commission.CommissionScheduler.Strategy;
import dev.aether.modules.dwarven.memory.AreaMemory;
import dev.aether.modules.dwarven.memory.AreaMemory.Confidence;
import dev.aether.modules.dwarven.memory.SpotMemory;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.mine.ApproachMemory;
import dev.aether.modules.dwarven.nav.NavFailures;
import dev.aether.modules.dwarven.nav.RoutePlanner;
import dev.aether.modules.dwarven.nav.RoutePlanner.Warp;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the 16:15 loop: upper mines reached three times, called rampart's quarry each time, and restarted from the forge
class UpperMinesTests {
    private static final String UPPER = "Upper Mines";
    private static final String RAMPARTS = "Rampart's Quarry";
    // the learned mining spot it kept heading for, and where it stood when it judged the zone
    private static final BlockPos SPOT = new BlockPos(-110, 167, -70);
    private static final BlockPos ARRIVED = new BlockPos(-108, 166, -69);
    private static final BlockPos FORGE = new BlockPos(0, 149, -69);

    @BeforeEach
    void reset() {
        NavFailures.clear();
    }

    private static AreaMemory seededAreas() throws Exception {
        JsonObject seed;
        try (var in = UpperMinesTests.class.getResourceAsStream("/assets/aether/dwarven/seed.json")) {
            assertNotNull(in);
            seed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        AreaMemory areas = new AreaMemory();
        JsonObject a = seed.getAsJsonObject("areas");
        for (String area : a.keySet()) {
            for (var el : a.getAsJsonArray(area)) {
                JsonArray p = el.getAsJsonArray();
                areas.observe(area, new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
            }
        }
        return areas;
    }

    private static SpotMemory upperMinesSpot() {
        SpotMemory spots = new SpotMemory();
        spots.visit(SPOT, UPPER, 1000);
        SpotMemory.Spot s = spots.at(SPOT);
        spots.mined(s, true, 900);
        return spots;
    }

    @Test
    void theSeedPutsTheArrivalPointInUpperMinesNotRampartsQuarry() throws Exception {
        AreaMemory areas = seededAreas();
        // built from a recorded run with readings right after teleports thrown out; this is the evidence the zone
        // line was stale when it said rampart's quarry here
        assertNotEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, ARRIVED));
        assertTrue(areas.contains(UPPER, ARRIVED));
        assertFalse(areas.contains(RAMPARTS, ARRIVED));
    }

    @Test
    void theZoneLineIsNotTrustedUntilItCatchesUpWithATeleport() {
        ZoneReading z = new ZoneReading();
        long t = 1_000_000;
        // walking through rampart's quarry, then the last aotv lands in upper mines with the sidebar not yet refreshed
        z.tick(-97.6, 161.5, -60.1, RAMPARTS, t);
        for (int i = 1; i <= 20; i++) z.tick(-97.6, 161.5, -60.1, RAMPARTS, t + i * 50L);
        t += 1000;
        z.tick(-105.5, 165.5, -67.5, RAMPARTS, t);
        assertFalse(z.fresh(t + 50));
        assertFalse(z.fresh(t + 1000));
        // the sidebar updates: the reading is fresh at once and says where we really are
        z.tick(-105.5, 165.5, -67.5, UPPER, t + 1100);
        assertTrue(z.fresh(t + 1100));
        assertEquals(UPPER, z.zone());
        // and if it never changes, it is trusted once the settle time has passed
        ZoneReading y = new ZoneReading();
        y.tick(0, 100, 0, RAMPARTS, 0);
        y.tick(10, 100, 0, RAMPARTS, 50);
        assertFalse(y.fresh(50 + ZoneReading.SETTLE_MS - 1));
        assertTrue(y.fresh(50 + ZoneReading.SETTLE_MS));
        // walking speed never counts as a teleport
        ZoneReading w = new ZoneReading();
        w.tick(0, 100, 0, UPPER, 0);
        w.tick(0.28, 100, 0, UPPER, 50);
        assertTrue(w.fresh(60));
    }

    @Test
    void aDestinationConfirmedInTheWrongAreaIsRuledOutForThatArea() throws Exception {
        AreaMemory areas = seededAreas();
        SpotMemory spots = upperMinesSpot();
        assertEquals(SPOT, spots.best(UPPER, 5000, 60_000, 60, Set.of(), p -> 10, p -> areas.allows(UPPER, p)).pos);
        areas.markWrongGoal(UPPER, SPOT);
        assertEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, SPOT));
        assertEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, SPOT.offset(1, 0, 1)));
        assertEquals(null, spots.best(UPPER, 5000, 60_000, 60, Set.of(), p -> 10, p -> areas.allows(UPPER, p)));
        // only for upper mines: the same spot is a fine rampart's quarry destination
        assertFalse(areas.wrongGoal(RAMPARTS, SPOT));
    }

    @Test
    void theVerdictSurvivesSavingAndLoadingAndANewTrip() throws Exception {
        AreaMemory areas = seededAreas();
        areas.markWrongGoal(UPPER, SPOT);
        AreaMemory reloaded = new AreaMemory();
        reloaded.load(JsonParser.parseString(areas.toJson().toString()).getAsJsonObject());
        assertEquals(Confidence.VERIFIED_WRONG_AREA, reloaded.confidence(UPPER, SPOT));
        // a forge warp and a fresh navigation record change nothing about it
        NavFailures.clear();
        BlockPos next = reloaded.bestPoint(UPPER, FORGE, List.of(), 0);
        assertNotNull(next);
        assertTrue(next.distSqr(SPOT) > 9);
    }

    @Test
    void standingThereAndSeeingTheRightZoneOverturnsTheVerdict() {
        AreaMemory areas = new AreaMemory();
        areas.observe(UPPER, SPOT);
        areas.markWrongGoal(UPPER, SPOT);
        assertEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, SPOT));
        areas.observe(UPPER, SPOT.offset(1, 0, 0));
        assertNotEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, SPOT));
    }

    @Test
    void theNextPickIsANearbyUpperMinesPointInsideTheArea() throws Exception {
        AreaMemory areas = seededAreas();
        areas.markWrongGoal(UPPER, SPOT);
        areas.markWrongGoal(UPPER, ARRIVED);
        BlockPos next = areas.bestPoint(UPPER, ARRIVED, List.of(), 0);
        assertNotNull(next);
        assertNotEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, next));
        // close enough to walk, so no forge reset
        assertTrue(Math.sqrt(next.distSqr(ARRIVED)) <= RoutePlanner.MAX_WALK, "next pick " + next + " is too far to walk");
    }

    @Test
    void interiorPointsComeBeforeBorderPoints() {
        AreaMemory areas = new AreaMemory();
        // a border cell 4 blocks away, shared with the next zone, and an interior one 10 blocks away
        areas.observe(UPPER, new BlockPos(4, 100, 0));
        areas.observe(RAMPARTS, new BlockPos(8, 100, 0));
        areas.observe(UPPER, new BlockPos(-10, 100, 0));
        BlockPos pick = areas.bestPoint(UPPER, new BlockPos(0, 100, 0), List.of(), 0);
        assertTrue(pick.getX() < 0, "picked " + pick);
        assertEquals(Confidence.VERIFIED_CORRECT_AREA, areas.confidence(UPPER, new BlockPos(-10, 100, 0)));
        assertEquals(Confidence.UNVERIFIED, areas.confidence(UPPER, new BlockPos(500, 100, 500)));
    }

    @Test
    void aWrongAreaArrivalNoLongerSendsTheNextTripToTheForge() {
        TrailMemory t = new TrailMemory();
        BlockPos alt = ARRIVED.offset(-4, 2, -6);
        Set<HopKind> hops = EnumSet.allOf(HopKind.class);
        RoutePlanner.Plan local = RoutePlanner.plan(t, ARRIVED, alt, 24, hops, Set.of(), Set.of(), true);
        assertEquals(RoutePlanner.Kind.DIRECT, local.kind());
        // before: the arrival was recorded as a navigation failure from here, which forces the forge
        NavFailures.record(SPOT, ARRIVED, "wrong area", 1000);
        assertEquals(Warp.AFTER_FAILURE_HERE, RoutePlanner.forgeDecision(true, FORGE, ARRIVED, 12, 2, 30,
                false, NavFailures.lastFor(alt, ARRIVED, 2000) != null || NavFailures.lastFor(SPOT, ARRIVED, 2000) != null, true));
        // now nothing is recorded for a wrong destination, and a short local walk wins
        NavFailures.clear();
        Warp w = RoutePlanner.forgeDecision(true, FORGE, ARRIVED, 12, 2, 30, false, NavFailures.lastFor(alt, ARRIVED, 2000) != null,
                local.kind().reachesGoal());
        assertEquals(Warp.NOT_QUICKER, w);
        assertFalse(RoutePlanner.warps(w));
    }

    @Test
    void theSameWrongDestinationIsNeverPickedAgain() throws Exception {
        AreaMemory areas = seededAreas();
        Set<BlockPos> picked = new HashSet<>();
        BlockPos at = ARRIVED;
        // keep arriving somewhere wrong: every verdict removes that choice, so no destination repeats
        for (int i = 0; i < 6; i++) {
            BlockPos next = areas.bestPoint(UPPER, at, List.of(), 0);
            if (next == null) break;
            assertTrue(picked.add(next), "picked " + next + " twice");
            areas.markWrongGoal(UPPER, next);
            at = next;
        }
        assertTrue(picked.size() >= 2);
    }

    @Test
    void ramparsDoneThenUpperMinesRecoversLocallyFromAWrongArrival() throws Exception {
        Rules rules = new Rules(Strategy.FASTEST, true, true, true, true, 0.25, true, true);
        List<String> tab = new ArrayList<>(List.of("Area: Dwarven Mines", "Commissions:", " Rampart's Quarry Titanium: DONE",
                " Upper Mines Titanium: 0%", "", "Powders:"));
        var after = CommissionReader.fromTab(tab);
        // the claimed commission is replaced; the next pick is upper mines
        tab.remove(2);
        var chosen = CommissionScheduler.choose(CommissionReader.fromTab(tab), rules, "Rampart's Quarry Titanium", o -> 10, c -> 60);
        assertEquals("Upper Mines Titanium", chosen.commission().name());
        assertTrue(after.stream().anyMatch(c -> c.done()));

        AreaMemory areas = seededAreas();
        SpotMemory spots = upperMinesSpot();
        // 1: the destination is the learned spot
        BlockPos dest = spots.best(UPPER, 5000, 60_000, 60, Set.of(), p -> 10, p -> areas.allows(UPPER, p)).pos;
        assertEquals(SPOT, dest);
        // 2: arrived right after the last aotv; the zone line still says rampart's quarry and isn't believed yet
        ZoneReading zone = new ZoneReading();
        zone.tick(-97.6, 161.5, -60.1, RAMPARTS, 0);
        zone.tick(-105.5, 165.5, -67.5, RAMPARTS, 1000);
        assertFalse(zone.fresh(1050));
        // 3: the sidebar stays rampart's after settling, so this time it really is the wrong area
        assertTrue(zone.fresh(1000 + ZoneReading.SETTLE_MS));
        assertEquals(RAMPARTS, zone.zone());
        areas.markWrongGoal(UPPER, dest);
        areas.markWrongGoal(UPPER, ARRIVED);
        // 4: an alternative inside upper mines, walkable from here
        assertEquals(null, spots.best(UPPER, 5000, 60_000, 60, Set.of(), p -> 10, p -> areas.allows(UPPER, p)));
        BlockPos alt = areas.bestPoint(UPPER, ARRIVED, List.of(), 0);
        assertNotNull(alt);
        assertTrue(Math.sqrt(alt.distSqr(ARRIVED)) <= RoutePlanner.MAX_WALK);
        // 5: walking there, the sidebar updates to upper mines and the arrival counts
        double x = -105.5, y = 165.5, zz = -67.5;
        long t = 2600;
        for (int i = 1; i <= 40; i++) {
            x += (alt.getX() + 0.5 - x) / (41 - i);
            y += (alt.getY() - y) / (41 - i);
            zz += (alt.getZ() + 0.5 - zz) / (41 - i);
            zone.tick(x, y, zz, i < 30 ? RAMPARTS : UPPER, t += 50);
        }
        assertTrue(zone.fresh(t));
        assertEquals(UPPER, zone.zone());
        areas.observe(UPPER, alt);
        assertNotEquals(Confidence.VERIFIED_WRONG_AREA, areas.confidence(UPPER, alt));
    }

    @Test
    void standsBehindAPlaceAWalkGotStuckAtAreSkipped() {
        ApproachMemory m = new ApproachMemory();
        BlockPos from = new BlockPos(-39, 137, 1);
        // the walk toward -42,135,9 got stuck two blocks out
        m.stuck(from, new BlockPos(-40, 137, 4), 10_000);
        assertTrue(m.blocks(from, new BlockPos(-41, 136, 7), 1000));
        assertTrue(m.blocks(from.offset(1, 0, 0), new BlockPos(-42, 135, 9), 1000));
        // a stand in another direction, or closer than the obstruction, is still fair game
        assertFalse(m.blocks(from, new BlockPos(-33, 137, 0), 1000));
        assertFalse(m.blocks(from, new BlockPos(-39, 137, 2), 1000));
        // from somewhere else entirely the record doesn't apply, and it expires
        assertFalse(m.blocks(new BlockPos(-60, 137, 20), new BlockPos(-41, 136, 7), 1000));
        assertFalse(m.blocks(from, new BlockPos(-41, 136, 7), 20_000));
    }
}
