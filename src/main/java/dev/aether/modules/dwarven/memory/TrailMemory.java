package dev.aether.modules.dwarven.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

// ground the player has actually walked, kept as linked breadcrumbs so later trips can follow proven paths
public final class TrailMemory {
    private static final double SPACING = 4.0;
    private static final double MERGE = 2.5;
    private static final double MAX_LINK = 7.0;
    private static final double MAX_CLIMB = 1.3;

    // a teleport between two crumbs costs about as much time as walking this far
    private static final double HOP_COST = 3.0;

    public enum HopKind { AOTV, ETHERWARP }

    // where a recorded aotv or etherwarp started and landed, exact enough to aim the same hop again
    public record Hop(HopKind kind, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {}

    // a stop on a route; when hop is set, this point is reached by teleporting from the stop before it
    public record Waypoint(BlockPos pos, Hop hop) {}

    private final Map<Long, Hop> hops = new HashMap<>();
    private final List<BlockPos> crumbs = new ArrayList<>();
    private final List<Set<Integer>> links = new ArrayList<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private int last = -1;
    private boolean dirty;

    public int size() {
        return crumbs.size();
    }

    public boolean dirty() {
        return dirty;
    }

    // call while the player is walking on the ground; a teleport or fall breaks the chain
    public void walked(BlockPos feet, boolean onGround) {
        if (!onGround) return;
        if (last >= 0) {
            BlockPos prev = crumbs.get(last);
            double d = Math.sqrt(prev.distSqr(feet));
            if (d > MAX_LINK) {
                last = -1;
            } else if (d < SPACING) {
                return;
            }
        }
        int here = nearest(feet, MERGE);
        if (here < 0) {
            here = crumbs.size();
            crumbs.add(feet.immutable());
            links.add(new HashSet<>());
            grid.computeIfAbsent(cell(feet), k -> new ArrayList<>()).add(here);
            dirty = true;
        }
        if (last >= 0 && last != here) link(last, here);
        last = here;
    }

    public void interrupted() {
        last = -1;
    }

    // recorded ground from the bundled run: consecutive points were walked between
    public void seedPath(List<BlockPos> path) {
        int prev = -1;
        for (BlockPos p : path) {
            int here = crumbAt(p);
            if (prev >= 0 && prev != here && Math.sqrt(crumbs.get(prev).distSqr(p)) <= MAX_LINK) link(prev, here);
            prev = here;
        }
    }

    public void seedHop(Hop hop) {
        int a = crumbAt(BlockPos.containing(hop.fromX(), hop.fromY() + 0.01, hop.fromZ()));
        int b = crumbAt(BlockPos.containing(hop.toX(), hop.toY() + 0.01, hop.toZ()));
        if (a == b) return;
        // teleports only go one way, whatever the height difference
        links.get(a).add(b);
        hops.put(edge(a, b), hop);
    }

    private int crumbAt(BlockPos p) {
        int here = nearest(p, MERGE);
        if (here >= 0) return here;
        here = crumbs.size();
        crumbs.add(p.immutable());
        links.add(new HashSet<>());
        grid.computeIfAbsent(cell(p), k -> new ArrayList<>()).add(here);
        return here;
    }

    private static long edge(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private void link(int a, int b) {
        int rise = crumbs.get(b).getY() - crumbs.get(a).getY();
        // a drop can be walked down but not back up
        if (rise <= MAX_CLIMB && links.get(a).add(b)) dirty = true;
        if (-rise <= MAX_CLIMB && links.get(b).add(a)) dirty = true;
    }

    public int nearest(BlockPos pos, double within) {
        int best = -1;
        double bestDistance = within * within;
        int cx = pos.getX() >> 3, cz = pos.getZ() >> 3;
        int span = (int) Math.ceil(within / 8.0);
        for (int dx = -span; dx <= span; dx++) for (int dz = -span; dz <= span; dz++) {
            List<Integer> in = grid.get(BlockPos.asLong(cx + dx, 0, cz + dz));
            if (in == null) continue;
            for (int i : in) {
                double d = crumbs.get(i).distSqr(pos);
                if (d <= bestDistance) {
                    bestDistance = d;
                    best = i;
                }
            }
        }
        return best;
    }

    // waypoints along proven ground from near one point to near another, about every stride blocks; empty when unknown
    public List<BlockPos> route(BlockPos from, BlockPos to, double joinWithin, double stride) {
        List<BlockPos> out = new ArrayList<>();
        for (Waypoint w : waypoints(from, to, joinWithin, stride, Set.of())) out.add(w.pos());
        return out;
    }

    // like route, but may use recorded teleports; a hop's start is always kept as its own stop so it can be lined up
    public List<Waypoint> waypoints(BlockPos from, BlockPos to, double joinWithin, double stride, Set<HopKind> hopKinds) {
        return waypoints(from, to, joinWithin, stride, hopKinds, Set.of());
    }

    // bannedHops are teleports that failed recently and must not be planned again yet
    public List<Waypoint> waypoints(BlockPos from, BlockPos to, double joinWithin, double stride, Set<HopKind> hopKinds,
                                    Set<Hop> bannedHops) {
        int start = nearest(from, joinWithin);
        int goal = nearest(to, joinWithin);
        if (start < 0 || goal < 0 || start == goal) return List.of();
        int[] prev = search(start, hopKinds, bannedHops);
        if (prev[goal] < 0) return List.of();
        return thin(pathTo(prev, goal), stride);
    }

    // when known ground doesn't reach the goal, go as far toward it as it does; empty when that's no closer than here
    public List<Waypoint> waypointsToward(BlockPos from, BlockPos to, double joinWithin, double stride, Set<HopKind> hopKinds,
                                          Set<Hop> bannedHops) {
        int start = nearest(from, joinWithin);
        if (start < 0) return List.of();
        int[] prev = search(start, hopKinds, bannedHops);
        int best = start;
        double bestDistance = crumbs.get(start).distSqr(to);
        for (int i = 0; i < crumbs.size(); i++) {
            if (prev[i] < 0) continue;
            double d = crumbs.get(i).distSqr(to);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        if (best == start || crumbs.get(best).distSqr(to) >= from.distSqr(to) - 16) return List.of();
        return thin(pathTo(prev, best), stride);
    }

    private int[] search(int start, Set<HopKind> hopKinds, Set<Hop> bannedHops) {
        double[] cost = new double[crumbs.size()];
        int[] prev = new int[crumbs.size()];
        Arrays.fill(cost, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        cost[start] = 0;
        open.add(new double[]{0, start});
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int at = (int) top[1];
            if (top[0] > cost[at]) continue;
            for (int next : links.get(at)) {
                Hop hop = hops.get(edge(at, next));
                if (hop != null && (!hopKinds.contains(hop.kind()) || bannedHops.contains(hop))) continue;
                double step = hop != null ? HOP_COST : Math.sqrt(crumbs.get(at).distSqr(crumbs.get(next)));
                double c = cost[at] + step;
                if (c < cost[next]) {
                    cost[next] = c;
                    prev[next] = at;
                    open.add(new double[]{c, next});
                }
            }
        }
        return prev;
    }

    private static List<Integer> pathTo(int[] prev, int goal) {
        List<Integer> path = new ArrayList<>();
        for (int at = goal; at >= 0; at = prev[at]) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private List<Waypoint> thin(List<Integer> path, double stride) {
        List<Waypoint> thinned = new ArrayList<>();
        double since = 0;
        for (int i = 1; i < path.size(); i++) {
            int a = path.get(i - 1), b = path.get(i);
            Hop hop = hops.get(edge(a, b));
            if (hop != null) {
                boolean startKept = !thinned.isEmpty() && thinned.get(thinned.size() - 1).pos().equals(crumbs.get(a));
                // the hop's start is kept as a stop even when it's the first one, so we walk onto it before teleporting
                if (!startKept) thinned.add(new Waypoint(crumbs.get(a), null));
                thinned.add(new Waypoint(crumbs.get(b), hop));
                since = 0;
                continue;
            }
            since += Math.sqrt(crumbs.get(b).distSqr(crumbs.get(a)));
            boolean beforeHop = i + 1 < path.size() && hops.containsKey(edge(b, path.get(i + 1)));
            if (since >= stride || i == path.size() - 1 || beforeHop) {
                thinned.add(new Waypoint(crumbs.get(b), null));
                since = 0;
            }
        }
        return thinned;
    }

    public double routeLength(BlockPos from, BlockPos to, double joinWithin, Set<HopKind> hopKinds) {
        List<Waypoint> r = waypoints(from, to, joinWithin, 1.0, hopKinds);
        if (r.isEmpty()) return -1;
        double total = Math.sqrt(from.distSqr(r.get(0).pos()));
        for (int i = 1; i < r.size(); i++) {
            total += r.get(i).hop() != null ? HOP_COST : Math.sqrt(r.get(i).pos().distSqr(r.get(i - 1).pos()));
        }
        return total;
    }

    private static long cell(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 3, 0, p.getZ() >> 3);
    }

    public JsonObject toJson() {
        JsonArray points = new JsonArray();
        JsonArray edges = new JsonArray();
        for (int i = 0; i < crumbs.size(); i++) {
            points.add(crumbs.get(i).asLong());
            for (int j : links.get(i)) edges.add(((long) i << 32) | j);
        }
        JsonObject root = new JsonObject();
        root.add("points", points);
        root.add("links", edges);
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        crumbs.clear();
        links.clear();
        grid.clear();
        last = -1;
        if (!root.has("points")) return;
        for (JsonElement el : root.getAsJsonArray("points")) {
            BlockPos p = BlockPos.of(el.getAsLong());
            grid.computeIfAbsent(cell(p), k -> new ArrayList<>()).add(crumbs.size());
            crumbs.add(p);
            links.add(new HashSet<>());
        }
        for (JsonElement el : root.getAsJsonArray("links")) {
            long packed = el.getAsLong();
            int a = (int) (packed >> 32), b = (int) packed;
            if (a >= crumbs.size() || b >= crumbs.size()) continue;
            // teleport links come back from the bundled seed with their hop attached; a bare long one isn't walkable
            if (Math.sqrt(crumbs.get(a).distSqr(crumbs.get(b))) > MAX_LINK + 1) continue;
            links.get(a).add(b);
        }
    }
}
