package dev.aether.modules.dwarven;

import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.commission.CombatTarget;
import dev.aether.modules.dwarven.memory.RouteHistory;
import dev.aether.modules.dwarven.memory.SpawnMemory;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import dev.aether.modules.dwarven.nav.RoutePlanner;
import dev.aether.modules.dwarven.nav.RoutePlanner.Plan;
import dev.aether.modules.dwarven.nav.RoutePlanner.Warp;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// what navigation and hunting learn from each trip and fight, and that it survives a restart
class LearningTests {
    private static final Set<HopKind> HOPS = EnumSet.allOf(HopKind.class);
    private static final Set<String> GOBLINS = CombatTarget.of("Goblin Slayer").mobs();

    private static RouteHistory reload(RouteHistory h) {
        RouteHistory r = new RouteHistory();
        r.load(JsonParser.parseString(h.toJson().toString()).getAsJsonObject());
        return r;
    }

    private static TrailMemory reload(TrailMemory t, long[] now) {
        TrailMemory r = new TrailMemory();
        r.clock(() -> now[0]);
        r.load(JsonParser.parseString(t.toJson().toString()).getAsJsonObject());
        return r;
    }

    // a short walk to a teleport whose landing is far along, or a long walk around
    private static TrailMemory shortcut(Hop hop) {
        TrailMemory t = new TrailMemory();
        t.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(4, 100, 0)));
        t.seedHop(hop);
        t.seedPath(List.of(new BlockPos(40, 100, 0), new BlockPos(44, 100, 0)));
        java.util.ArrayList<BlockPos> around = new java.util.ArrayList<>();
        for (int k = 0; k <= 9; k++) around.add(new BlockPos(4 + 4 * k, 100, 3 * Math.min(k, 9 - k)));
        t.seedPath(around);
        return t;
    }

    @Test
    void aCompletedTripIsRememberedAndSurvivesARestart() {
        RouteHistory h = new RouteHistory();
        RouteHistory.Outcome first = h.record("Forge", "Upper Mines", true, 21_400, 3, 0);
        assertTrue(first.firstSuccess());
        assertEquals(1, h.count());
        assertEquals(1, h.verifiedCount());
        RouteHistory r = reload(h);
        RouteHistory.Journey j = r.journey("Forge", "Upper Mines");
        assertNotNull(j);
        assertEquals(1, j.successes);
        assertEquals(21.4, j.meanSeconds(), 1e-9);
        assertEquals(21_400, j.bestMs);
        assertEquals(3, j.hops);
    }

    @Test
    void measuredTripTimesReplaceTheEstimateOnceTrustedAndCountFailures() {
        RouteHistory h = new RouteHistory();
        h.record("Cliffside Veins", "Upper Mines", true, 36_000, 12, 3);
        // one trip isn't enough to overrule the estimate
        assertEquals(50.0, h.expectedSeconds("Cliffside Veins", "Upper Mines", 50.0), 1e-9);
        h.record("Cliffside Veins", "Upper Mines", true, 30_000, 10, 1);
        assertEquals(33.0, h.expectedSeconds("Cliffside Veins", "Upper Mines", 50.0), 1e-9);
        h.record("Cliffside Veins", "Upper Mines", false, 60_000, 0, 4);
        // a third of the trips failed, so a third of the failure cost is added
        assertEquals(33.0 + RouteHistory.FAILURE_SECONDS / 3, h.expectedSeconds("Cliffside Veins", "Upper Mines", 50.0), 1e-9);
        RouteHistory.Outcome quick = h.record("Cliffside Veins", "Upper Mines", true, 20_000, 6, 0);
        assertTrue(quick.faster());
        assertEquals(13.0, quick.savedSeconds(), 1e-9);
        // the second trip saved 6s against the first, this one 13s against the mean before it
        assertEquals(19.0, reload(h).savedSeconds(), 1e-9);
    }

    @Test
    void aTeleportWithABlockedLandingIsLeftOutAcrossRestartsAndEachStrikeLastsLonger() {
        long[] now = {1_000_000};
        Hop hop = new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 40.5, 100, 0.5);
        TrailMemory t = shortcut(hop);
        t.clock(() -> now[0]);
        BlockPos a = new BlockPos(0, 100, 0), b = new BlockPos(44, 100, 0);
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().anyMatch(w -> hop.equals(w.hop())));
        long until = t.invalidate(hop, "there's no room to stand on its landing block");
        assertEquals(30 * 60_000L, until - now[0]);
        assertFalse(t.valid(hop));
        // planning goes the long way rather than trying the broken teleport again, and so does a restarted session
        List<Waypoint> around = t.waypoints(a, b, 4, 24, HOPS, Set.of());
        assertFalse(around.isEmpty());
        assertTrue(around.stream().noneMatch(w -> hop.equals(w.hop())));
        TrailMemory r = reload(t, now);
        assertFalse(r.valid(hop));
        List<Waypoint> afterRestart = r.waypoints(a, b, 4, 24, HOPS, Set.of());
        assertFalse(afterRestart.isEmpty());
        assertTrue(afterRestart.stream().noneMatch(w -> hop.equals(w.hop())));
        assertEquals(1, r.unreliableHopCount());
        // it comes back when its time is up; failing again keeps it out for longer
        now[0] = until + 1;
        assertTrue(r.valid(hop));
        assertEquals(3 * 3_600_000L, r.invalidate(hop, "there's no room to stand on its landing block") - now[0]);
        // seeing the landing clear again brings it back at once, and a real landing wipes the strikes
        r.revalidate(hop);
        assertTrue(r.valid(hop));
        r.hopResult(hop, true, 0.5, 900);
        assertEquals(0, r.hopStats(hop).strikes);
    }

    @Test
    void anUnreliableTeleportLosesToAReliableWalkOnlyWhenItsPenaltyOutweighsTheShortcut() {
        Hop hop = new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 40.5, 100, 0.5);
        TrailMemory t = shortcut(hop);
        BlockPos a = new BlockPos(0, 100, 0), b = new BlockPos(44, 100, 0);
        t.hopResult(hop, false, 0, 0);
        t.hopResult(hop, false, 0, 0);
        // the penalty the navigator gives: failure rate times what a failure costs to recover from
        t.hopPenalty(h -> t.hopStats(h) == null ? 0 : t.hopStats(h).failureRate() * 60);
        List<Waypoint> walk = t.waypoints(a, b, 4, 24, HOPS, Set.of());
        assertFalse(walk.isEmpty());
        assertTrue(walk.stream().noneMatch(w -> hop.equals(w.hop())));
        t.hopPenalty(h -> 0);
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().anyMatch(w -> hop.equals(w.hop())));
    }

    @Test
    void aKnownRouteIsJoinedFromANearbyStartWithoutAnExactMatch() {
        Hop hop = new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 40.5, 100, 0.5);
        TrailMemory t = shortcut(hop);
        Plan p = RoutePlanner.plan(t, new BlockPos(-6, 100, 3), new BlockPos(44, 100, 0), 24, HOPS, Set.of(), Set.of(), true);
        assertTrue(p.kind().reachesGoal(), p.kind() + " " + p.reason());
        assertTrue(p.legs().stream().anyMatch(w -> hop.equals(w.hop())));
    }

    @Test
    void theForgeIsOnlyUsedWhenItGivesARouteOrIsActuallyQuicker() {
        BlockPos forge = new BlockPos(0, 149, -69), feet = new BlockPos(-38, 137, 2);
        // neither start reaches the goal: the partial route from here is quicker, so no warp (the 20:02 rampart's case)
        assertEquals(Warp.NOT_QUICKER, RoutePlanner.forgeDecision(true, forge, feet, 12, 25, 36, false, false, false, false));
        assertFalse(RoutePlanner.warps(Warp.NOT_QUICKER));
        // only the forge has a complete route: warping is right
        assertEquals(Warp.NO_ROUTE_HERE, RoutePlanner.forgeDecision(true, forge, feet, 12, 25, 36, false, false, false, true));
        // both partial, and the forge's is much quicker
        assertEquals(Warp.QUICKER, RoutePlanner.forgeDecision(true, forge, feet, 12, 60, 30, false, false, false, false));
        // a failed trip from the forge is never repeated from there
        assertEquals(Warp.FORGE_FAILED, RoutePlanner.forgeDecision(true, forge, feet, 12, 60, 30, true, false, false, true));
    }

    @Test
    void etherwarpPathfinderLegsArePricedByTheirRecordNotAssumed() {
        RouteHistory h = new RouteHistory();
        RouteHistory.Method m = h.method("etherwarp_leg");
        // nothing measured: even a 36-block leg walks, since a failure costs its own time and then the walk anyway
        assertFalse(RoutePlanner.etherwarpLegWorthIt(36, 12, 5.0, 0.3, m.successRate(), m.successSeconds(2.5), m.failureSeconds(6.0)));
        // four failures like the 19:56 log: still walks
        for (int i = 0; i < 4; i++) h.method("etherwarp_leg", false, 9000);
        assertFalse(RoutePlanner.etherwarpLegWorthIt(36, 12, 5.0, 0.3, m.successRate(), m.successSeconds(2.5), m.failureSeconds(6.0)));
        // a long run of quick successes earns it
        RouteHistory g = new RouteHistory();
        for (int i = 0; i < 20; i++) g.method("etherwarp_leg", true, 1800);
        RouteHistory.Method good = g.method("etherwarp_leg");
        assertTrue(RoutePlanner.etherwarpLegWorthIt(36, 12, 5.0, 0.3, good.successRate(), good.successSeconds(2.5), good.failureSeconds(6.0)));
        assertFalse(RoutePlanner.etherwarpLegWorthIt(10, 12, 5.0, 0.3, good.successRate(), good.successSeconds(2.5), good.failureSeconds(6.0)));
        assertEquals(20, reload(g).method("etherwarp_leg").successes);
    }

    @Test
    void goblinSlayerIsNotTiedToTheScoreboardZoneButZoneBoundHuntsStayTied() {
        CombatTarget goblin = CombatTarget.of("Goblin Slayer");
        assertFalse(goblin.zoneBound());
        assertEquals("Goblin Burrows", goblin.area());
        assertTrue(goblin.mobs().contains("Goblin"));
        assertTrue(CombatTarget.of("Glacite Walker Slayer").zoneBound());
    }

    @Test
    void aPlaceWhereGoblinsWereKilledBeatsAPlaceOnlyLabelledForThem() {
        SpawnMemory s = new SpawnMemory();
        long now = 10_000_000L;
        BlockPos from = new BlockPos(-100, 148, 100);
        BlockPos labelled = new BlockPos(-110, 148, 104);
        BlockPos proven = new BlockPos(-140, 145, 130);
        s.seed("Goblin", labelled);
        assertTrue(s.killed("Goblin", proven, now));
        assertFalse(s.killed("Goblin", proven, now + 1000));
        s.killed("Knifethrower", proven, now + 2000);
        BlockPos best = s.best(GOBLINS, from, now + 3000, 0L, Set.of());
        assertTrue(best.distSqr(proven) < 50, "picked " + best);
        // kills are kept across restarts
        SpawnMemory r = new SpawnMemory();
        r.load(JsonParser.parseString(s.toJson().toString()).getAsJsonObject());
        assertEquals(2, r.productive(from, GOBLINS, now + 3000, 3).get(0).kills);
    }

    @Test
    void aSpawnFoundEmptyIsSkippedForAWhileAndKeepsLosingRankIfItStaysEmpty() {
        SpawnMemory s = new SpawnMemory();
        long now = 10_000_000L;
        BlockPos from = new BlockPos(0, 100, 0);
        BlockPos near = new BlockPos(6, 100, 6), far = new BlockPos(30, 100, 30);
        s.sighted("Goblin", near, now);
        s.sighted("Goblin", far, now);
        assertTrue(s.best(GOBLINS, from, now, 0L, Set.of()).distSqr(near) < 50);
        s.empty(near, GOBLINS, now);
        // within the recheck window it isn't offered at all
        assertTrue(s.best(GOBLINS, from, now + 1000, 180_000L, Set.of()).distSqr(far) < 50);
        // after it, repeated empty visits still rank it below the place that had goblins
        s.empty(near, GOBLINS, now + 200_000);
        s.empty(near, GOBLINS, now + 400_000);
        assertTrue(s.best(GOBLINS, from, now + 600_000, 180_000L, Set.of()).distSqr(far) < 50);
        // and a kill there clears its empty record
        s.killed("Goblin", near, now + 700_000);
        assertTrue(s.best(GOBLINS, from, now + 800_000, 0L, Set.of()).distSqr(near) < 50);
    }

    @Test
    void aRouteThatOnlyReachesKnownGroundStillLeadsTowardTheGoal() {
        Hop hop = new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 40.5, 100, 0.5);
        TrailMemory t = shortcut(hop);
        BlockPos goal = new BlockPos(90, 100, 0);
        Plan p = RoutePlanner.plan(t, new BlockPos(0, 100, 0), goal, 24, HOPS, Set.of(), Set.of(), true);
        assertEquals(RoutePlanner.Kind.PARTIAL, p.kind());
        Waypoint end = p.legs().get(p.legs().size() - 1);
        assertTrue(end.pos().distSqr(goal) < new BlockPos(0, 100, 0).distSqr(goal));
    }
}
