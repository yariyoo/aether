package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import dev.aether.modules.dwarven.nav.HopLanding;
import dev.aether.modules.dwarven.nav.HopLanding.Verdict;
import dev.aether.modules.dwarven.nav.NavFailures;
import dev.aether.modules.dwarven.nav.RoutePlanner;
import dev.aether.modules.dwarven.nav.RoutePlanner.Kind;
import dev.aether.modules.dwarven.nav.RoutePlanner.Plan;
import dev.aether.modules.dwarven.nav.RoutePlanner.Warp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// what happens after a teleport lands somewhere other than where it was recorded, and how far the route survives it
class NavigationRecoveryTests {
    private static final Set<HopKind> ALL = EnumSet.allOf(HopKind.class);
    private static final BlockPos FORGE = new BlockPos(0, 149, -69);
    private static final BlockPos UPPER_MINES = new BlockPos(-110, 168, -70);
    private static final Hop FORGE_HOP = new Hop(HopKind.ETHERWARP, 0.52, 149.0, -66.98, -5.62, 147.05, -30.41);
    private static final Hop WEST_HOP = new Hop(HopKind.ETHERWARP, -60.5, 147.0, -30.5, -90.5, 164.0, -60.5);

    @BeforeEach
    void reset() {
        NavFailures.clear();
    }

    private static List<BlockPos> line(int x0, int y0, int z0, int x1, int y1, int z1, int step) {
        List<BlockPos> out = new ArrayList<>();
        int n = (int) Math.ceil(Math.sqrt(Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2) + Math.pow(z1 - z0, 2)) / step);
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            out.add(new BlockPos((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(y0 + (y1 - y0) * t), (int) Math.round(z0 + (z1 - z0) * t)));
        }
        return out;
    }

    // the recorded failure: forge, etherwarp down to the basin, walk west, etherwarp up, walk into upper mines,
    // plus a short dead-end trail right beside where the etherwarp actually landed
    private static TrailMemory forgeToUpperMines() {
        TrailMemory t = new TrailMemory();
        t.seedPath(line(0, 149, -69, 0, 149, -67, 2));
        t.seedHop(FORGE_HOP);
        t.seedPath(line(-6, 147, -31, -60, 147, -30, 4));
        t.seedHop(WEST_HOP);
        t.seedPath(line(-91, 164, -61, -110, 168, -70, 3));
        t.seedPath(List.of(new BlockPos(-1, 147, -18), new BlockPos(3, 147, -15), new BlockPos(6, 147, -12)));
        return t;
    }

    private static Plan plan(TrailMemory t, BlockPos from, BlockPos to, Set<Hop> banned, Set<BlockPos> unreachable) {
        return RoutePlanner.plan(t, from, to, 24, ALL, banned, unreachable, true);
    }

    private static int hopIndex(List<Waypoint> legs, Hop hop) {
        for (int i = 0; i < legs.size(); i++) if (hop.equals(legs.get(i).hop())) return i;
        return -1;
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static Vec3 landing(Hop h) {
        return v(h.toX(), h.toY(), h.toZ());
    }

    private static Vec3 launch(Hop h) {
        return v(h.fromX(), h.fromY(), h.fromZ());
    }

    @Test
    void forgeToUpperMinesSurvivesTheEtherwarpThatLandedTenBlocksOff() {
        TrailMemory t = forgeToUpperMines();
        Plan first = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        assertEquals(Kind.KNOWN, first.kind());
        int hop = hopIndex(first.legs(), FORGE_HOP);
        assertTrue(hop > 0);
        assertEquals(UPPER_MINES, first.legs().get(first.legs().size() - 1).pos());

        // where it really landed: about 10.5 blocks from the recording, beside a dead-end trail, and no closer by straight line
        Vec3 actual = v(-1.0, 147.0, -21.0);
        assertTrue(actual.distanceTo(landing(FORGE_HOP)) > 10 && actual.distanceTo(landing(FORGE_HOP)) < 11);
        HopLanding.Result r = HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), actual, first.legs(), hop);
        assertEquals(Verdict.CONTINUE, r.verdict());
        assertTrue(r.continueAt() >= hop);

        // even with the hop thrown out, planning from the real landing finds the rest of the route instead of the dead end
        BlockPos at = BlockPos.containing(actual.x, actual.y, actual.z);
        Plan again = plan(t, at, UPPER_MINES, Set.of(FORGE_HOP), Set.of());
        assertTrue(again.kind() == Kind.KNOWN || again.kind() == Kind.REATTACHED);
        assertEquals(UPPER_MINES, again.legs().get(again.legs().size() - 1).pos());
        assertTrue(again.legs().stream().noneMatch(w -> w.pos().getZ() > -20));
        assertTrue(again.legs().stream().anyMatch(w -> WEST_HOP.equals(w.hop())));
    }

    @Test
    void afterTheForgeRouteFailedTheNextTripDoesNotWarpThereAgain() {
        NavFailures.record(UPPER_MINES, FORGE, "no known route", 1_000L);
        Warp w = RoutePlanner.forgeDecision(true, FORGE, new BlockPos(-1, 147, -21), 12, 40, 30,
                NavFailures.lastFor(UPPER_MINES, FORGE, 2_000L) != null, false);
        assertEquals(Warp.FORGE_FAILED, w);
        assertFalse(RoutePlanner.warps(w));
    }

    // 1
    @Test
    void landingOnTheRecordedSpotGoesStraightOn() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        int hop = hopIndex(p.legs(), FORGE_HOP);
        HopLanding.Result r = HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), landing(FORGE_HOP), p.legs(), hop);
        assertEquals(Verdict.ON_TARGET, r.verdict());
        assertEquals(hop + 1, r.continueAt());
    }

    // 2
    @Test
    void landingThreeBlocksOffStillCountsAsOnTarget() {
        Vec3 off = v(FORGE_HOP.toX() + 3, FORGE_HOP.toY(), FORGE_HOP.toZ());
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        assertEquals(Verdict.ON_TARGET, HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), off, p.legs(), hopIndex(p.legs(), FORGE_HOP)).verdict());
    }

    // 3 and 18
    @Test
    void landingTenBlocksOffNextToTheRouteContinuesAlongIt() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        int hop = hopIndex(p.legs(), FORGE_HOP);
        Vec3 off = v(FORGE_HOP.toX() - 2, 147, FORGE_HOP.toZ() + 9.8);
        HopLanding.Result r = HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), off, p.legs(), hop);
        assertEquals(Verdict.CONTINUE, r.verdict());
        assertTrue(r.deviation() > 9);
        // the rest of the route from that stop is untouched and still ends at the goal
        List<Waypoint> rest = p.legs().subList(r.continueAt(), p.legs().size());
        assertEquals(UPPER_MINES, rest.get(rest.size() - 1).pos());
        assertTrue(rest.stream().anyMatch(w -> WEST_HOP.equals(w.hop())));
    }

    // 4
    @Test
    void aLandingCloserToTheGoalButAwayFromTheRouteIsReplannedFromThere() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        Vec3 off = v(-24, 147, -48);
        HopLanding.Result r = HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), off, p.legs(), hopIndex(p.legs(), FORGE_HOP));
        assertEquals(Verdict.REPLAN, r.verdict());
        Plan again = plan(t, new BlockPos(-24, 147, -48), UPPER_MINES, Set.of(FORGE_HOP), Set.of());
        assertEquals(Kind.REATTACHED, again.kind());
        assertEquals(UPPER_MINES, again.legs().get(again.legs().size() - 1).pos());
    }

    // 5
    @Test
    void aLandingFurtherAwayWithNothingKnownAroundFailsSafelyAsUnexplored() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        Vec3 far = v(60, 150, 20);
        assertEquals(Verdict.REPLAN, HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), far, p.legs(), hopIndex(p.legs(), FORGE_HOP)).verdict());
        Plan again = plan(t, new BlockPos(60, 150, 20), UPPER_MINES, Set.of(FORGE_HOP), Set.of());
        assertEquals(Kind.NONE, again.kind());
        assertTrue(again.reason().startsWith("unexplored"));
    }

    // 6
    @Test
    void aTeleportThatNeverHappenedIsReportedAsSuch() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        Vec3 stayed = v(FORGE_HOP.fromX() + 0.3, FORGE_HOP.fromY(), FORGE_HOP.fromZ());
        assertEquals(Verdict.NO_TELEPORT, HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), stayed, p.legs(), hopIndex(p.legs(), FORGE_HOP)).verdict());
    }

    // 7
    @Test
    void aLandingPastTheRecordedSpotRejoinsAtALaterStop() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        int hop = hopIndex(p.legs(), FORGE_HOP);
        BlockPos later = p.legs().get(hop + 2).pos();
        Vec3 there = v(later.getX() + 1.5, later.getY(), later.getZ() + 1.5);
        HopLanding.Result r = HopLanding.judge(launch(FORGE_HOP), landing(FORGE_HOP), there, p.legs(), hop);
        assertEquals(Verdict.CONTINUE, r.verdict());
        assertTrue(r.continueAt() >= hop + 2);
    }

    // 8
    @Test
    void aLandingBackNearAnEarlierStopReconnectsThroughTheGraph() {
        TrailMemory t = forgeToUpperMines();
        Hop second = new Hop(HopKind.ETHERWARP, 0.5, 149, -68.5, -30.5, 147, -30.5);
        t.seedHop(second);
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(), Set.of());
        int hop = hopIndex(p.legs(), WEST_HOP);
        // the west etherwarp bounced us back to the start of the basin walk
        Vec3 back = v(-8, 147, -31);
        HopLanding.Result r = HopLanding.judge(launch(WEST_HOP), landing(WEST_HOP), back, p.legs(), hop);
        assertEquals(Verdict.REPLAN, r.verdict());
        Plan again = plan(t, new BlockPos(-8, 147, -31), UPPER_MINES, Set.of(), Set.of());
        assertEquals(Kind.KNOWN, again.kind());
        assertTrue(again.legs().stream().anyMatch(w -> WEST_HOP.equals(w.hop())));
    }

    // 9
    @Test
    void whenNoNearbyConnectionIsReachableItStepsElsewhereOrFails() {
        TrailMemory t = forgeToUpperMines();
        BlockPos at = new BlockPos(-24, 147, -48);
        List<BlockPos> walk = line(-6, 147, -31, -60, 147, -30, 4);
        Plan p = plan(t, at, UPPER_MINES, Set.of(FORGE_HOP), Set.copyOf(walk));
        assertFalse(p.kind() == Kind.KNOWN || p.kind() == Kind.REATTACHED);
        if (p.attach() != null) assertFalse(walk.contains(p.attach()));
    }

    // 10
    @Test
    void invalidatingOneEdgeKeepsTheGroundAroundIt() {
        TrailMemory t = forgeToUpperMines();
        Set<Hop> banned = Set.of(FORGE_HOP);
        assertFalse(t.waypoints(new BlockPos(-6, 147, -31), new BlockPos(-60, 147, -30), 12, 24, ALL, banned).isEmpty());
        assertFalse(t.waypoints(new BlockPos(-6, 147, -31), UPPER_MINES, 12, 24, ALL, banned).isEmpty());
        assertTrue(t.waypoints(FORGE, UPPER_MINES, 12, 24, ALL, banned).isEmpty());
    }

    // 11
    @Test
    void anAlternativeTeleportIsUsedWhenOneIsBanned() {
        TrailMemory t = forgeToUpperMines();
        Hop other = new Hop(HopKind.ETHERWARP, 0.5, 149, -68.5, -30.5, 147, -30.5);
        t.seedHop(other);
        Plan p = plan(t, FORGE, UPPER_MINES, Set.of(FORGE_HOP), Set.of());
        assertEquals(Kind.KNOWN, p.kind());
        assertTrue(p.legs().stream().anyMatch(w -> other.equals(w.hop())));
        assertTrue(p.legs().stream().noneMatch(w -> FORGE_HOP.equals(w.hop())));
    }

    // 12
    @Test
    void withNoAlternativeItGoesAsFarAsKnownGroundDoes() {
        TrailMemory t = forgeToUpperMines();
        Plan p = plan(t, new BlockPos(-6, 147, -31), UPPER_MINES, Set.of(WEST_HOP), Set.of());
        assertEquals(Kind.PARTIAL, p.kind());
        assertFalse(p.kind().reachesGoal());
        assertTrue(p.legs().get(p.legs().size() - 1).pos().getX() <= -55);
    }

    // 13
    @Test
    void aGoalPastOnePathfinderWalkIsNeverWalkedBlind() {
        TrailMemory empty = new TrailMemory();
        Plan p = plan(empty, FORGE, UPPER_MINES, Set.of(), Set.of());
        assertEquals(Kind.NONE, p.kind());
        assertTrue(p.reason().startsWith("unexplored"));
        Plan near = plan(empty, FORGE, FORGE.offset(-30, 0, 10), Set.of(), Set.of());
        assertEquals(Kind.DIRECT, near.kind());
    }

    // 14
    @Test
    void aLongTripAcrossUnlinkedGroundIsCrossedInSeveralBoundedWalks() {
        TrailMemory t = new TrailMemory();
        // separate patches of walked ground with nothing linking them
        for (int x = -20; x >= -100; x -= 25) t.seedPath(List.of(new BlockPos(x, 150, -69), new BlockPos(x - 3, 150, -69)));
        BlockPos at = FORGE, goal = new BlockPos(-128, 150, -69);
        int steps = 0;
        Set<BlockPos> tried = Set.of();
        Plan p = plan(t, at, goal, Set.of(), tried);
        while (p.kind() == Kind.STEP || p.kind() == Kind.PARTIAL) {
            assertTrue(Math.sqrt(p.legs().get(0).pos().distSqr(at)) <= RoutePlanner.MAX_WALK);
            at = p.legs().get(p.legs().size() - 1).pos();
            steps++;
            assertTrue(steps < 10);
            p = plan(t, at, goal, Set.of(), tried);
        }
        assertTrue(steps >= 3);
        assertEquals(Kind.DIRECT, p.kind());
    }

    // 15
    @Test
    void anUnrecordedStartingPointJoinsTheNearestUsefulTrail() {
        TrailMemory t = forgeToUpperMines();
        BlockPos off = new BlockPos(-30, 147, -48);
        assertTrue(t.waypoints(off, UPPER_MINES, 12, 24, ALL, Set.of()).isEmpty());
        Plan p = plan(t, off, UPPER_MINES, Set.of(), Set.of());
        assertEquals(Kind.REATTACHED, p.kind());
        assertTrue(p.attachDistance() > RoutePlanner.JOIN && p.attachDistance() <= RoutePlanner.REATTACH);
        assertEquals(p.attach(), p.legs().get(0).pos());
    }

    // 16
    @Test
    void failuresOutliveTheStageThatHadThem() {
        long now = 5_000L;
        NavFailures.banHop(FORGE_HOP, now + 300_000L);
        NavFailures.unreachable(new BlockPos(-1, 147, -18), now + 300_000L);
        NavFailures.record(UPPER_MINES, FORGE, "no known route", now);
        // a later observe and travel start from a fresh navigator but the same static memory
        long later = now + 60_000L;
        assertTrue(NavFailures.bannedHops(later).contains(FORGE_HOP));
        assertTrue(NavFailures.unreachable(later).contains(new BlockPos(-1, 147, -18)));
        Plan p = plan(forgeToUpperMines(), FORGE, UPPER_MINES, NavFailures.bannedHops(later), NavFailures.unreachable(later));
        assertNotEquals(Kind.KNOWN, p.kind());
        assertEquals(Warp.FORGE_FAILED, RoutePlanner.forgeDecision(true, FORGE, new BlockPos(-40, 150, -30), 12, 60, 20,
                NavFailures.lastFor(UPPER_MINES, FORGE, later) != null, false));
        // a hop that missed once is trusted again; a second miss bans it
        Hop h = new Hop(HopKind.ETHERWARP, 1, 1, 1, 2, 2, 2);
        assertEquals(1, NavFailures.hopMissed(h));
        assertEquals(2, NavFailures.hopMissed(h));
        assertTrue(NavFailures.bannedHops(later + 400_000L).isEmpty());
    }

    // 17
    @Test
    void theForgeWarpComesBeforeWalkingWhenItIsQuicker() {
        assertEquals(Warp.QUICKER, RoutePlanner.forgeDecision(true, FORGE, new BlockPos(100, 150, 50), 12, 60, 20, false, false));
        assertEquals(Warp.AT_FORGE, RoutePlanner.forgeDecision(true, FORGE, FORGE.offset(0, 0, 2), 12, 60, 20, false, false));
        assertEquals(Warp.NOT_QUICKER, RoutePlanner.forgeDecision(true, FORGE, new BlockPos(100, 150, 50), 12, 22, 20, false, false));
        assertEquals(Warp.AFTER_FAILURE_HERE, RoutePlanner.forgeDecision(true, FORGE, new BlockPos(100, 150, 50), 12, 22, 20, false, true));
        assertEquals(Warp.NO_FORGE, RoutePlanner.forgeDecision(false, FORGE, new BlockPos(100, 150, 50), 12, 60, 20, false, false));
    }
}
