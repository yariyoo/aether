package dev.aether.modules.commission.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

// travel graph built from recorded runs (tools/build_dwarven_routes.py): walks, aotv hops, etherwarps and /warp forge
public final class RouteGraph {
    public enum EdgeType { WALK, AOTV, ETHERWARP, WARP }

    public record Point(double x, double y, double z) {
        public double distance(Point o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double horizontal(Point o) {
            double dx = x - o.x, dz = z - o.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    public record Edge(int index, int a, int b, EdgeType type, boolean jump, Point from, Point to) {}

    // edge is null for the legs that join the graph from where the player stands
    public record Step(EdgeType type, Point from, Point to, boolean jump, Edge edge) {}

    public record Plan(List<Step> steps, double seconds) {}

    // sprinting covers about 5.6 blocks a second; a hop costs its swap, aim and server round trip
    static final double SPRINT_SPEED = 5.6;
    static final double APPROACH_SPEED = 4.0;
    static final double AOTV_COST = 0.45;
    static final double ETHERWARP_COST = 0.8;
    static final double WARP_COST = 1.5;
    static final double JUMP_COST = 0.15;
    static final double ENTRY_RADIUS = 6.0;
    static final double ENTRY_HEIGHT = 2.5;
    static final double FAR_ENTRY_RADIUS = 40.0;

    private static RouteGraph dwarvenMines;

    private final Point[] nodes;
    private final List<List<Edge>> outgoing;
    private final List<Edge> edges;
    private final int[] forge;

    RouteGraph(Point[] nodes, List<Edge> edges, int[] forge) {
        this.nodes = nodes;
        this.edges = List.copyOf(edges);
        this.forge = forge;
        this.outgoing = new ArrayList<>(nodes.length);
        for (int i = 0; i < nodes.length; i++) outgoing.add(new ArrayList<>());
        for (Edge e : edges) outgoing.get(e.a()).add(e);
    }

    public static synchronized RouteGraph dwarvenMines() {
        if (dwarvenMines == null) {
            try (InputStream in = RouteGraph.class.getResourceAsStream("/assets/aether/routes/dwarven_mines.json")) {
                if (in == null) throw new IllegalStateException("dwarven mines route file is missing");
                dwarvenMines = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not read the dwarven mines routes", e);
            }
        }
        return dwarvenMines;
    }

    public static RouteGraph parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonArray rawNodes = root.getAsJsonArray("nodes");
        Point[] nodes = new Point[rawNodes.size()];
        for (int i = 0; i < nodes.length; i++) nodes[i] = point(rawNodes.get(i).getAsJsonArray());
        List<Edge> edges = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("edges")) {
            JsonObject o = el.getAsJsonObject();
            int a = o.get("a").getAsInt(), b = o.get("b").getAsInt();
            EdgeType type = EdgeType.valueOf(o.get("type").getAsString());
            boolean jump = o.has("jump") && o.get("jump").getAsBoolean();
            Point from = o.has("from") ? point(o.getAsJsonArray("from")) : nodes[a];
            Point to = o.has("to") ? point(o.getAsJsonArray("to")) : nodes[b];
            edges.add(new Edge(edges.size(), a, b, type, jump, from, to));
        }
        JsonArray rawForge = root.getAsJsonArray("forge");
        int[] forge = new int[rawForge.size()];
        for (int i = 0; i < forge.length; i++) forge[i] = rawForge.get(i).getAsInt();
        return new RouteGraph(nodes, edges, forge);
    }

    private static Point point(JsonArray a) {
        return new Point(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    public int nodeCount() {
        return nodes.length;
    }

    public List<Edge> edges() {
        return edges;
    }

    // cheapest way from start to within goalRadius of goal; null when the allowed moves don't connect them
    public Plan plan(Point start, Point goal, double goalRadius, Set<EdgeType> allowed, Set<Integer> banned) {
        int n = nodes.length;
        int source = n, target = n + 1;
        double[] cost = new double[n + 2];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Step[] via = new Step[n + 2];
        int[] prev = new int[n + 2];
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));

        cost[source] = 0;
        queue.add(new double[]{0, source});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > cost[u]) continue;
            if (u == target) break;
            for (Candidate c : neighbours(u, source, target, start, goal, goalRadius, allowed, banned)) {
                double next = cost[u] + c.seconds;
                if (next < cost[c.node]) {
                    cost[c.node] = next;
                    prev[c.node] = u;
                    via[c.node] = c.step;
                    queue.add(new double[]{next, c.node});
                }
            }
        }
        if (Double.isInfinite(cost[target])) return null;
        List<Step> steps = new ArrayList<>();
        for (int at = target; at != source; at = prev[at]) {
            if (via[at] != null) steps.add(via[at]);
        }
        Collections.reverse(steps);
        return new Plan(steps, cost[target]);
    }

    private record Candidate(int node, double seconds, Step step) {}

    private List<Candidate> neighbours(int u, int source, int target, Point start, Point goal, double goalRadius,
                                       Set<EdgeType> allowed, Set<Integer> banned) {
        List<Candidate> out = new ArrayList<>();
        if (u == source) {
            boolean near = false;
            for (int i = 0; i < nodes.length; i++) {
                if (isNear(start, nodes[i], ENTRY_RADIUS)) {
                    near = true;
                    out.add(new Candidate(i, start.distance(nodes[i]) / APPROACH_SPEED,
                            new Step(EdgeType.WALK, start, nodes[i], false, null)));
                }
            }
            if (!near) {
                // off the recorded routes: walk to the closest node and join there
                int closest = closest(start, FAR_ENTRY_RADIUS);
                if (closest >= 0) {
                    out.add(new Candidate(closest, start.distance(nodes[closest]) / APPROACH_SPEED * 1.5,
                            new Step(EdgeType.WALK, start, nodes[closest], false, null)));
                }
            }
            if (allowed.contains(EdgeType.WARP)) {
                // the warp works from anywhere, so taking it first is never worse than walking somewhere and then warping
                for (int f : forge) {
                    out.add(new Candidate(f, WARP_COST, new Step(EdgeType.WARP, start, nodes[f], false, null)));
                }
            }
            if (start.distance(goal) <= goalRadius) {
                out.add(new Candidate(target, 0, null));
            }
            return out;
        }
        if (u == target) return out;
        for (Edge e : outgoing.get(u)) {
            if (!allowed.contains(e.type()) || banned.contains(e.index())) continue;
            out.add(new Candidate(e.b(), seconds(e), new Step(e.type(), e.from(), e.to(), e.jump(), e)));
        }
        double toGoal = nodes[u].distance(goal);
        if (toGoal <= goalRadius) {
            out.add(new Candidate(target, toGoal / APPROACH_SPEED,
                    new Step(EdgeType.WALK, nodes[u], goal, false, null)));
        }
        return out;
    }

    private double seconds(Edge e) {
        return switch (e.type()) {
            case WALK -> nodes[e.a()].distance(nodes[e.b()]) / SPRINT_SPEED + (e.jump() ? JUMP_COST : 0);
            case AOTV -> AOTV_COST;
            case ETHERWARP -> ETHERWARP_COST;
            case WARP -> WARP_COST;
        };
    }

    private static boolean isNear(Point a, Point b, double radius) {
        return a.horizontal(b) <= radius && Math.abs(a.y() - b.y()) <= ENTRY_HEIGHT;
    }

    private int closest(Point p, double radius) {
        int best = -1;
        double bestDistance = radius;
        for (int i = 0; i < nodes.length; i++) {
            double d = p.distance(nodes[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
