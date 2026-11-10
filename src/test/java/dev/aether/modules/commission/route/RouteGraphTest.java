package dev.aether.modules.commission.route;

import dev.aether.modules.commission.CommissionTask;
import dev.aether.modules.commission.route.RouteGraph.EdgeType;
import dev.aether.modules.commission.route.RouteGraph.Plan;
import dev.aether.modules.commission.route.RouteGraph.Point;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteGraphTest {
    private static final Set<EdgeType> ALL = EnumSet.allOf(EdgeType.class);
    private static final Set<EdgeType> WALK_ONLY = EnumSet.of(EdgeType.WALK);

    // 0 -walk- 1 -aotv-> 2 -walk- 3, and a long walk 1 - 4 - 3 around the cliff the hop skips
    private static RouteGraph sample() {
        String json = """
                {"nodes":[[0,100,0],[3,100,0],[11,104,0],[14,104,0],[8,100,20]],
                 "forge":[0],
                 "edges":[
                  {"a":0,"b":1,"type":"WALK"},{"a":1,"b":0,"type":"WALK"},
                  {"a":1,"b":2,"type":"AOTV","from":[3,100,0],"to":[11,104,0]},
                  {"a":2,"b":3,"type":"WALK"},{"a":3,"b":2,"type":"WALK"},
                  {"a":1,"b":4,"type":"WALK"},{"a":4,"b":1,"type":"WALK"},
                  {"a":4,"b":3,"type":"WALK"},{"a":3,"b":4,"type":"WALK"}]}
                """;
        return RouteGraph.parse(new StringReader(json));
    }

    @Test
    void takesTheHopWhenItIsAllowed() {
        Plan plan = sample().plan(new Point(0, 100, 0), new Point(14, 104, 0), 1.5, ALL, Set.of());
        assertNotNull(plan);
        assertTrue(plan.steps().stream().anyMatch(s -> s.type() == EdgeType.AOTV));
    }

    @Test
    void walksAroundWithoutTheHop() {
        Plan plan = sample().plan(new Point(0, 100, 0), new Point(14, 104, 0), 1.5, WALK_ONLY, Set.of());
        assertNotNull(plan);
        assertTrue(plan.steps().stream().allMatch(s -> s.type() == EdgeType.WALK));
        assertTrue(plan.steps().stream().anyMatch(s -> s.to().z() == 20));
    }

    @Test
    void bannedEdgesAreAvoided() {
        RouteGraph graph = sample();
        int hop = graph.edges().stream().filter(e -> e.type() == EdgeType.AOTV).findFirst().orElseThrow().index();
        Plan plan = graph.plan(new Point(0, 100, 0), new Point(14, 104, 0), 1.5, ALL, Set.of(hop));
        assertNotNull(plan);
        assertTrue(plan.steps().stream().noneMatch(s -> s.type() == EdgeType.AOTV));
    }

    @Test
    void hopsAreOneWay() {
        Set<Integer> noDetour = Set.of(5, 6, 7, 8);
        Plan plan = sample().plan(new Point(14, 104, 0), new Point(0, 100, 0), 1.5, EnumSet.of(EdgeType.WALK, EdgeType.AOTV), noDetour);
        assertNull(plan);
    }

    @Test
    void warpsToTheForgeWhenThatIsQuicker() {
        Plan plan = sample().plan(new Point(14, 104, 0), new Point(0, 100, 0), 1.5, ALL, Set.of(5, 6, 7, 8));
        assertNotNull(plan);
        assertEquals(EdgeType.WARP, plan.steps().get(0).type());
    }

    @Test
    void recordedRoutesReachEveryMiningArea() {
        RouteGraph graph = RouteGraph.dwarvenMines();
        assertTrue(graph.nodeCount() > 100);
        Point forge = new Point(0.5, 149, -68.5);
        for (var area : CommissionTask.AREAS) {
            var a = area.anchor();
            Point anchor = new Point(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            Plan plan = graph.plan(forge, anchor, 3.0, ALL, Set.of());
            assertNotNull(plan, "no route to " + area.name());
            assertTrue(plan.seconds() < 40, area.name() + " takes " + plan.seconds() + "s");
        }
    }
}
