package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.memory.AreaMemory;
import dev.aether.modules.dwarven.memory.SpotMemory;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.world.OreKind;
import dev.aether.modules.dwarven.world.StandPlanner;
import dev.aether.modules.dwarven.world.Vein;
import dev.aether.modules.dwarven.world.VeinFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAndMemoryTests {
    // a floor at y=0 with a 3x3 ore wall standing on it at z=-4, one titanium in it, and a lone ore far away
    private static FakeOreWorld wallWorld() {
        FakeOreWorld world = new FakeOreWorld().floor(-8, -8, 30, 8, 0);
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) world.ore(x, y, -4, OreKind.MITHRIL_GRAY);
        world.ore(0, 2, -4, OreKind.TITANIUM);
        world.ore(25, 1, 0, OreKind.MITHRIL_BLUE);
        return world;
    }

    @Test
    void groupsOreIntoVeinsAndCountsTitanium() {
        FakeOreWorld world = wallWorld();
        List<Vein> veins = VeinFinder.find(world.orePositions(), world, 1);
        assertEquals(2, veins.size());
        Vein wall = veins.stream().filter(v -> v.size() == 9).findFirst().orElseThrow();
        assertEquals(1, wall.titanium());
        assertEquals(1, VeinFinder.find(world.orePositions(), world, 3).size());
    }

    @Test
    void findsAStandThatReachesTheWholeWall() {
        FakeOreWorld world = wallWorld();
        Vein wall = VeinFinder.find(world.orePositions(), world, 3).get(0);
        List<StandPlanner.Stand> stands = StandPlanner.plan(world, wall, world.orePositions(), 4.5, Set.of());
        assertFalse(stands.isEmpty());
        StandPlanner.Stand best = stands.get(0);
        assertEquals(9, best.reachable().size());
        assertEquals(1, best.titanium());
        assertEquals(1, best.feet().getY());
        assertTrue(StandPlanner.standable(world, best.feet()));
    }

    @Test
    void oreBehindAWallIsNotReachable() {
        FakeOreWorld world = new FakeOreWorld().floor(-5, -5, 5, 5, 0).ore(0, 2, -4, OreKind.MITHRIL_GRAY);
        Vec3 eye = new Vec3(0.5, 1 + StandPlanner.EYE_HEIGHT, 0.5);
        assertNotNull(StandPlanner.visiblePoint(world, eye, new BlockPos(0, 2, -4), 4.5));
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) world.stone(x, y, -2);
        assertNull(StandPlanner.visiblePoint(world, eye, new BlockPos(0, 2, -4), 4.5));
        assertNull(StandPlanner.visiblePoint(world, eye, new BlockPos(0, 2, -4), 2.0));
    }

    @Test
    void avoidedStandsAreNotOffered() {
        FakeOreWorld world = wallWorld();
        Vein wall = VeinFinder.find(world.orePositions(), world, 3).get(0);
        BlockPos first = StandPlanner.plan(world, wall, world.orePositions(), 4.5, Set.of()).get(0).feet();
        assertTrue(StandPlanner.plan(world, wall, world.orePositions(), 4.5, Set.of(first)).stream().noneMatch(s -> s.feet().equals(first)));
    }

    @Test
    void spotMemoryPrefersWhatPaidOffAndRechecksEmptySpots() {
        SpotMemory memory = new SpotMemory();
        long now = 1_000_000L;
        SpotMemory.Spot rich = memory.visit(new BlockPos(10, 100, 10), "Royal Mines", now);
        SpotMemory.Spot poor = memory.visit(new BlockPos(12, 100, 30), "Royal Mines", now);
        memory.visit(new BlockPos(50, 100, 50), "Lava Springs", now);
        for (int i = 0; i < 60; i++) memory.mined(rich, i % 10 == 0, 500);
        for (int i = 0; i < 5; i++) memory.mined(poor, false, 5000);
        assertEquals(rich, memory.best("royal mines", now, 30_000, 60, Set.of(), p -> 10));
        memory.empty(rich, now);
        assertEquals(poor, memory.best("Royal Mines", now + 1000, 30_000, 60, Set.of(), p -> 10));
        assertEquals(rich, memory.best("Royal Mines", now + 31_000, 30_000, 60, Set.of(), p -> 10));
        assertEquals(poor, memory.best("Royal Mines", now + 31_000, 30_000, 60, Set.of(rich.pos), p -> 10));
        // standing one block off still counts as the same spot
        assertEquals(rich, memory.at(new BlockPos(11, 100, 10)));
    }

    @Test
    void spotMemorySurvivesSavingButForgetsEmptiness() {
        SpotMemory memory = new SpotMemory();
        SpotMemory.Spot spot = memory.visit(new BlockPos(1, 2, 3), "Royal Mines", 5);
        memory.mined(spot, true, 400);
        memory.empty(spot, 99);
        SpotMemory reloaded = new SpotMemory();
        reloaded.load(memory.toJson());
        SpotMemory.Spot back = reloaded.at(new BlockPos(1, 2, 3));
        assertEquals(1, back.blocks);
        assertEquals(1, back.titanium);
        assertEquals("Royal Mines", back.area);
        assertEquals(0, back.emptyAt);
    }

    @Test
    void unreachableSpotsGetBlockedAfterRepeatedFailures() {
        SpotMemory memory = new SpotMemory();
        SpotMemory.Spot spot = memory.visit(new BlockPos(0, 0, 0), "Royal Mines", 0);
        memory.mined(spot, false, 400);
        for (int i = 0; i < 3; i++) memory.unreachable(spot.pos, 1000, 3, 60_000);
        assertNull(memory.best("Royal Mines", 2000, 0, 60, Set.of(), p -> 0));
        assertEquals(spot, memory.best("Royal Mines", 70_000, 0, 60, Set.of(), p -> 0));
    }

    @Test
    void areasAreLearnedFromWhereTheZoneWasSeen() {
        AreaMemory areas = new AreaMemory();
        assertFalse(areas.knows("Royal Mines"));
        assertNull(areas.nearestPoint("Royal Mines", BlockPos.ZERO));
        for (int x = 100; x <= 140; x += 2) areas.observe("Royal Mines", new BlockPos(x, 150, 20));
        areas.observe("Lava Springs", new BlockPos(40, 200, -10));
        assertTrue(areas.knows("royal mines"));
        assertTrue(areas.contains("Royal Mines", new BlockPos(120, 151, 22)));
        assertFalse(areas.contains("Royal Mines", new BlockPos(40, 200, -10)));
        assertEquals("royal mines", areas.areaAt(new BlockPos(120, 150, 20)));
        assertTrue(areas.nearestPoint("Royal Mines", new BlockPos(0, 150, 20)).getX() < 106);
        AreaMemory reloaded = new AreaMemory();
        reloaded.load(areas.toJson());
        assertTrue(reloaded.contains("Royal Mines", new BlockPos(120, 151, 22)));
    }

    @Test
    void trailsRememberWalkedGroundAndRouteAlongIt() {
        TrailMemory trails = new TrailMemory();
        // walk an L: east along z=0, then south along x=40
        for (int x = 0; x <= 40; x++) trails.walked(new BlockPos(x, 100, 0), true);
        for (int z = 1; z <= 40; z++) trails.walked(new BlockPos(40, 100, z), true);
        assertTrue(trails.size() > 10);
        List<BlockPos> route = trails.route(new BlockPos(1, 100, 1), new BlockPos(41, 100, 39), 8, 20);
        assertFalse(route.isEmpty());
        // the route follows the corner rather than cutting across unknown ground
        assertTrue(route.stream().anyMatch(p -> p.getX() >= 36 && p.getZ() <= 8), route.toString());
        double length = trails.routeLength(new BlockPos(1, 100, 1), new BlockPos(41, 100, 39), 8, java.util.Set.of());
        assertTrue(length > 70 && length < 95, "length " + length);
        assertTrue(trails.route(new BlockPos(1, 100, 1), new BlockPos(300, 100, 300), 8, 20).isEmpty());
        TrailMemory reloaded = new TrailMemory();
        reloaded.load(trails.toJson());
        assertEquals(route, reloaded.route(new BlockPos(1, 100, 1), new BlockPos(41, 100, 39), 8, 20));
    }

    @Test
    void teleportsBreakTheTrailAndDropsAreOneWay() {
        TrailMemory trails = new TrailMemory();
        for (int x = 0; x <= 12; x++) trails.walked(new BlockPos(x, 100, 0), true);
        for (int x = 200; x <= 212; x++) trails.walked(new BlockPos(x, 100, 0), true);
        assertTrue(trails.route(new BlockPos(0, 100, 0), new BlockPos(212, 100, 0), 6, 20).isEmpty());

        TrailMemory ledge = new TrailMemory();
        for (int x = 0; x <= 8; x++) ledge.walked(new BlockPos(x, 100, 0), true);
        for (int x = 12; x <= 20; x++) ledge.walked(new BlockPos(x, 96, 0), true);
        assertFalse(ledge.route(new BlockPos(0, 100, 0), new BlockPos(20, 96, 0), 4, 50).isEmpty());
        assertTrue(ledge.route(new BlockPos(20, 96, 0), new BlockPos(0, 100, 0), 4, 50).isEmpty());
    }
}
