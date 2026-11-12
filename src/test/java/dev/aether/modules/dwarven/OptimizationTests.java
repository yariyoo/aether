package dev.aether.modules.dwarven;

import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.camera.CameraMotion;
import dev.aether.modules.dwarven.camera.CameraProfile;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import dev.aether.modules.dwarven.mine.BlockEfficiency;
import dev.aether.modules.dwarven.mine.BlockEfficiency.Job;
import dev.aether.modules.dwarven.mine.BlockEfficiency.Mode;
import dev.aether.modules.dwarven.nav.RoutePlanner;
import dev.aether.modules.dwarven.world.AimPoints;
import dev.aether.modules.dwarven.world.OreKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the camera's motion, which ore a commission is worth mining, and the navigation shortcuts, all without the game
class OptimizationTests {
    private static final Set<HopKind> HOPS = EnumSet.allOf(HopKind.class);
    private static final Map<OreKind, Double> EVEN = Map.of();
    private static final Job MITHRIL = new Job(false, false, true);
    private static final Job TITANIUM = new Job(true, true, true);

    private static CameraProfile smooth(float jerk, float variation) {
        return new CameraProfile(CameraProfile.Mode.ADAPTIVE, CameraProfile.Easing.SMOOTHERSTEP, 25f, 420f, 1800f, 1500f, 0.35f, 0.25f, 0.6f,
                true, jerk, variation);
    }

    // runs a move at 60 frames a second and returns the largest single-frame change in angular speed
    private static float run(CameraMotion m, int frames) {
        float worst = 0;
        float prev = m.speed();
        for (int i = 0; i < frames && !m.arrived(); i++) {
            m.step(1f / 60f);
            worst = Math.max(worst, Math.abs(m.speed() - prev));
            prev = m.speed();
        }
        return worst;
    }

    @Test
    void movingBetweenNeighbouringBlocksCarriesSpeedInsteadOfRestarting() {
        CameraMotion m = new CameraMotion(1);
        m.reset(0, 30);
        m.aim(0, 30, 20, 34, smooth(12000, 0));
        for (int i = 0; i < 6; i++) m.step(1f / 60f);
        float midway = m.speed();
        assertTrue(midway > 30, "speed " + midway);
        // the next block is picked before this turn finishes; the motion bends toward it without dropping to rest
        m.aim(m.yaw(), m.pitch(), 24, 36, smooth(12000, 0));
        m.step(1f / 60f);
        assertTrue(m.speed() > midway * 0.5, "speed after retarget " + m.speed());
        run(m, 600);
        assertTrue(m.arrived());
        assertEquals(24f, m.yaw(), 1e-3);
        assertEquals(36f, m.pitch(), 1e-3);
    }

    @Test
    void aLargeTurnFinishesQuicklyAndExactlyAndTheJerkLimitSoftensItsStart() {
        CameraMotion limited = new CameraMotion(1);
        limited.reset(0, 0);
        limited.aim(0, 0, 150, -10, smooth(12000, 0));
        float limitedWorst = run(limited, 600);
        CameraMotion free = new CameraMotion(1);
        free.reset(0, 0);
        free.aim(0, 0, 150, -10, smooth(0, 0));
        float freeWorst = run(free, 600);
        assertTrue(limited.arrived() && free.arrived());
        assertEquals(150f, limited.yaw(), 1e-3);
        // under the jerk limit, speed changes less from one frame to the next
        assertTrue(limitedWorst < freeWorst, limitedWorst + " vs " + freeWorst);
        // and a 150 degree turn still takes well under a second
        CameraMotion timed = new CameraMotion(1);
        timed.reset(0, 0);
        timed.aim(0, 0, 150, -10, smooth(12000, 0));
        int frames = 0;
        while (!timed.arrived() && frames < 600) {
            timed.step(1f / 60f);
            frames++;
        }
        assertTrue(frames < 60, frames + " frames");
    }

    @Test
    void etherwarpAimEndsExactlyOnTheTargetAngle() {
        CameraProfile precise = new CameraProfile(CameraProfile.Mode.PRECISION, CameraProfile.Easing.SMOOTHERSTEP, 25f, 460f, 1980f, 1650f, 0.35f, 0f,
                0.5f, true, 13200f, 0f);
        CameraMotion m = new CameraMotion(3);
        m.reset(10, 5);
        m.aim(10, 5, -73.25f, 27.5f, precise);
        run(m, 900);
        assertTrue(m.arrived());
        assertEquals(-73.25f, m.yaw(), 1e-3);
        assertEquals(27.5f, m.pitch(), 1e-3);
    }

    @Test
    void variationChangesEachMoveALittleAndNeverItsEndPoint() {
        CameraMotion m = new CameraMotion(42);
        m.reset(0, 0);
        float lastK = 1f;
        float target = 0;
        for (int move = 0; move < 40; move++) {
            target += move % 2 == 0 ? 35 : -20;
            m.aim(m.yaw(), m.pitch(), target, 10, smooth(12000, 1f));
            float k = m.speedFactor();
            assertTrue(k >= 0.8f - 1e-4f && k <= 1.2f + 1e-4f, "factor " + k);
            // the random walk moves gradually, not to an unrelated value each time
            assertTrue(Math.abs(k - lastK) <= 0.2f * 1.3f + 1e-4f, "jump " + lastK + " -> " + k);
            lastK = k;
            run(m, 900);
            assertTrue(m.arrived());
            assertEquals(target, m.yaw(), 1e-3);
            assertEquals(10f, m.pitch(), 1e-3);
        }
    }

    @Test
    void aimPointsVaryOnTheFaceButAlwaysHitTheBlockAndStayPutPerBlock() {
        FakeOreWorld w = new FakeOreWorld();
        BlockPos ore = new BlockPos(3, 101, 0);
        w.stone(ore.getX(), ore.getY(), ore.getZ());
        Vec3 eye = new Vec3(0.5, 101.62, 0.5);
        Vec3 a = AimPoints.pick(w, eye, ore, 4.5, 1.0, 99);
        Vec3 b = AimPoints.pick(w, eye, ore, 4.5, 1.0, 99);
        assertNotNull(a);
        assertEquals(a.x, b.x, 0);
        assertEquals(a.y, b.y, 0);
        assertEquals(a.z, b.z, 0);
        assertEquals(ore, w.rayHit(eye, a.add(a.subtract(eye).normalize().scale(0.1))));
        // a different session seed moves it, still onto the same block
        boolean moved = false;
        for (long seed = 1; seed < 20 && !moved; seed++) {
            Vec3 c = AimPoints.pick(w, eye, ore, 4.5, 1.0, seed);
            assertEquals(ore, w.rayHit(eye, c.add(c.subtract(eye).normalize().scale(0.1))));
            moved = c.distanceTo(a) > 0.05;
        }
        assertTrue(moved);
    }

    @Test
    void titaniumCommissionsGoForTitaniumFirstAndMineMithrilOnlyToSpawnIt() {
        BlockEfficiency e = new BlockEfficiency();
        double ti = e.cost(OreKind.TITANIUM, TITANIUM, Mode.FASTEST, 200, 40, true, EVEN);
        double gray = e.cost(OreKind.MITHRIL_GRAY, TITANIUM, Mode.FASTEST, 50, 40, true, EVEN);
        assertTrue(ti < gray, ti + " vs " + gray);
        assertTrue(gray < Double.MAX_VALUE);
        // with mithril-for-titanium off, mithril isn't worth anything to a titanium job
        assertEquals(Double.MAX_VALUE, e.cost(OreKind.MITHRIL_GRAY, new Job(true, true, false), Mode.FASTEST, 50, 40, true, EVEN));
    }

    @Test
    void mithrilCommissionsIgnoreTitaniumUnlessAnotherCommissionCountsIt() {
        BlockEfficiency e = new BlockEfficiency();
        assertEquals(Double.MAX_VALUE, e.cost(OreKind.TITANIUM, MITHRIL, Mode.FASTEST, 10, 10, true, EVEN));
        assertTrue(e.cost(OreKind.TITANIUM, new Job(false, true, true), Mode.FASTEST, 10, 10, true, EVEN) < Double.MAX_VALUE);
    }

    @Test
    void blueFirstPutsBlueMithrilAheadOfCheaperGray() {
        BlockEfficiency e = new BlockEfficiency();
        double blue = e.cost(OreKind.MITHRIL_BLUE, MITHRIL, Mode.BLUE_FIRST, 100, 40, true, EVEN);
        double gray = e.cost(OreKind.MITHRIL_GRAY, MITHRIL, Mode.BLUE_FIRST, 100, 40, true, EVEN);
        assertTrue(blue < gray, blue + " vs " + gray);
        // fastest progress, unmeasured, goes by block strength: gray breaks quickest
        assertTrue(e.cost(OreKind.MITHRIL_GRAY, MITHRIL, Mode.FASTEST, 100, 40, true, EVEN)
                < e.cost(OreKind.MITHRIL_BLUE, MITHRIL, Mode.FASTEST, 100, 40, true, EVEN));
    }

    @Test
    void measuredBreakTimesDecideWhichMithrilIsFastestNotAssumptions() {
        BlockEfficiency e = new BlockEfficiency();
        // with this tool blue happens to break quicker than gray; once measured, that's what the ranking follows
        for (int i = 0; i < 6; i++) {
            e.broke(OreKind.MITHRIL_BLUE, 300);
            e.broke(OreKind.MITHRIL_GRAY, 900);
        }
        assertTrue(e.cost(OreKind.MITHRIL_BLUE, MITHRIL, Mode.FASTEST, 100, 40, true, EVEN)
                < e.cost(OreKind.MITHRIL_GRAY, MITHRIL, Mode.FASTEST, 100, 40, true, EVEN));
        // green has no measurements yet and is scaled from the measured ones by its strength
        double green = e.breakMs(OreKind.MITHRIL_GREEN, true);
        assertTrue(green > 0 && green < 2000, "green " + green);
        // without learning it goes back to strengths
        assertTrue(e.breakMs(OreKind.MITHRIL_GRAY, false) < e.breakMs(OreKind.MITHRIL_BLUE, false));
    }

    @Test
    void closestModeTurnsLeastWhateverTheVariantAndCustomFollowsTheWeights() {
        BlockEfficiency e = new BlockEfficiency();
        assertTrue(e.cost(OreKind.MITHRIL_BLUE, MITHRIL, Mode.CLOSEST, 20, 10, true, EVEN)
                < e.cost(OreKind.MITHRIL_GRAY, MITHRIL, Mode.CLOSEST, 80, 10, true, EVEN));
        Map<OreKind, Double> greenFavoured = Map.of(OreKind.MITHRIL_GREEN, 5.0);
        assertTrue(e.cost(OreKind.MITHRIL_GREEN, MITHRIL, Mode.CUSTOM, 100, 40, true, greenFavoured)
                < e.cost(OreKind.MITHRIL_GRAY, MITHRIL, Mode.CUSTOM, 100, 40, true, greenFavoured));
    }

    @Test
    void theTitaniumSpawnRateIsLearnedFromBreaks() {
        BlockEfficiency e = new BlockEfficiency();
        assertEquals(0.05, e.spawnRate(), 1e-9);
        for (int i = 0; i < 40; i++) e.broke(OreKind.MITHRIL_GRAY, 500);
        for (int i = 0; i < 8; i++) e.titaniumSpawned();
        assertEquals(0.2, e.spawnRate(), 1e-9);
    }

    @Test
    void anEtherwarpLegIsUsedOnlyWhenItBeatsWalking() {
        // 30 blocks takes about 6.3s on foot; a 2.5s etherwarp leg wins
        assertTrue(RoutePlanner.etherwarpBeatsWalking(30, 12, 5.0, 0.3, 2.5));
        // 13 blocks is 2.9s on foot, not worth a 2.5s search and throw plus margin
        assertFalse(RoutePlanner.etherwarpBeatsWalking(13, 12, 5.0, 0.3, 2.5));
        // and if etherwarp legs turn out slow in practice, the measured time keeps walking in charge
        assertFalse(RoutePlanner.etherwarpBeatsWalking(30, 12, 5.0, 0.3, 7.0));
        assertFalse(RoutePlanner.etherwarpBeatsWalking(10, 12, 5.0, 0.3, 0.1));
    }

    @Test
    void aTeleportLearnedInPlayIsUsedAndRemembered() {
        TrailMemory t = new TrailMemory();
        t.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(4, 100, 0)));
        t.seedPath(List.of(new BlockPos(40, 110, 0), new BlockPos(44, 110, 0)));
        BlockPos a = new BlockPos(0, 100, 0), b = new BlockPos(44, 110, 0);
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).isEmpty());
        Hop found = new Hop(HopKind.ETHERWARP, 4.5, 100, 0.5, 40.5, 110, 0.5);
        t.learnHop(found);
        assertTrue(t.knowsHop(found));
        assertTrue(t.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().anyMatch(w -> found.equals(w.hop())));
        TrailMemory reloaded = new TrailMemory();
        reloaded.load(JsonParser.parseString(t.toJson().toString()).getAsJsonObject());
        assertTrue(reloaded.waypoints(a, b, 4, 24, HOPS, Set.of()).stream().anyMatch(w -> found.equals(w.hop())));
        assertEquals(1, reloaded.learnedHopCount());
    }

    @Test
    void teleportHistorySurvivesAReload() {
        TrailMemory t = new TrailMemory();
        Hop h = new Hop(HopKind.AOTV, 0.5, 100, 0.5, 12.5, 100, 0.5);
        t.hopResult(h, true, 1.2, 900);
        t.hopResult(h, true, 0.8, 1100);
        t.hopResult(h, false, 0, 0);
        TrailMemory reloaded = new TrailMemory();
        reloaded.load(JsonParser.parseString(t.toJson().toString()).getAsJsonObject());
        TrailMemory.HopStats st = reloaded.hopStats(h);
        assertEquals(2, st.successes);
        assertEquals(1, st.failures);
        assertEquals(1.0, st.meanDeviation(), 1e-9);
        assertEquals(1000.0, st.meanMillis(), 1e-9);
        assertEquals(1.0 / 3, st.failureRate(), 1e-9);
    }

    @Test
    void straightRunsOfStopsMergeButBendsAndTeleportStartsStay() {
        List<Waypoint> legs = new ArrayList<>();
        for (int x = 4; x <= 24; x += 4) legs.add(new Waypoint(new BlockPos(x, 100, 0), null));
        // a sharp bend at 24
        legs.add(new Waypoint(new BlockPos(24, 100, 20), null));
        Hop hop = new Hop(HopKind.AOTV, 24.5, 100, 20.5, 36.5, 100, 20.5);
        legs.add(new Waypoint(new BlockPos(36, 100, 20), hop));
        legs.add(new Waypoint(new BlockPos(44, 100, 20), null));
        List<Waypoint> merged = RoutePlanner.simplify(new BlockPos(0, 100, 0), legs, 36, 2.5);
        assertEquals(List.of(new BlockPos(24, 100, 0), new BlockPos(24, 100, 20), new BlockPos(36, 100, 20), new BlockPos(44, 100, 20)),
                merged.stream().map(Waypoint::pos).toList());
        assertEquals(hop, merged.get(2).hop());
        // a climb that doesn't follow the straight line keeps its stop
        List<Waypoint> stairs = List.of(new Waypoint(new BlockPos(4, 100, 0), null), new Waypoint(new BlockPos(8, 106, 0), null),
                new Waypoint(new BlockPos(12, 106, 0), null));
        assertEquals(3, RoutePlanner.simplify(new BlockPos(0, 100, 0), stairs, 36, 2.5).size() - 0);
        // and nothing ever spans more than the limit
        List<Waypoint> longRun = new ArrayList<>();
        for (int x = 4; x <= 100; x += 4) longRun.add(new Waypoint(new BlockPos(x, 100, 0), null));
        BlockPos at = new BlockPos(0, 100, 0);
        for (Waypoint w : RoutePlanner.simplify(at, longRun, 36, 2.5)) {
            assertTrue(Math.sqrt(at.distSqr(w.pos())) <= 36);
            at = w.pos();
        }
    }

    @Test
    void aPointThatKeepsGettingStuckIsRoutedAroundAndForgivenLater() {
        TrailMemory t = new TrailMemory();
        long[] now = {0};
        t.clock(() -> now[0]);
        // two ways from a to b: straight through the bad point, or a slightly longer loop
        t.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(5, 100, 0), new BlockPos(10, 100, 0)));
        t.seedPath(List.of(new BlockPos(0, 100, 0), new BlockPos(3, 100, 4), new BlockPos(7, 100, 4), new BlockPos(10, 100, 0)));
        BlockPos a = new BlockPos(0, 100, 0), b = new BlockPos(10, 100, 0), bad = new BlockPos(5, 100, 0);
        assertTrue(t.waypoints(a, b, 1, 1, HOPS, Set.of()).stream().anyMatch(w -> w.pos().equals(bad)));
        t.stuck(bad);
        t.stuck(bad);
        assertTrue(t.waypoints(a, b, 1, 1, HOPS, Set.of()).stream().noneMatch(w -> w.pos().equals(bad)));
        assertTrue(t.stuckPenalty(bad) > 0);
        // it survives a save, and fades over a few hours
        TrailMemory reloaded = new TrailMemory();
        reloaded.clock(() -> now[0]);
        reloaded.load(JsonParser.parseString(t.toJson().toString()).getAsJsonObject());
        assertEquals(t.stuckPenalty(bad), reloaded.stuckPenalty(bad), 1e-9);
        now[0] += 4 * 60 * 60_000L;
        assertTrue(reloaded.stuckPenalty(bad) < 1.0);
        // walking past it clears it at once
        t.passed(bad);
        assertEquals(0.0, t.stuckPenalty(bad), 1e-9);
    }
}
