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
        int start = nearest(from, joinWithin);
        int goal = nearest(to, joinWithin);
        if (start < 0 || goal < 0 || start == goal) return List.of();
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
            if (at == goal) break;
            for (int next : links.get(at)) {
                double c = cost[at] + Math.sqrt(crumbs.get(at).distSqr(crumbs.get(next)));
                if (c < cost[next]) {
                    cost[next] = c;
                    prev[next] = at;
                    open.add(new double[]{c, next});
                }
            }
        }
        if (prev[goal] < 0) return List.of();
        List<BlockPos> path = new ArrayList<>();
        for (int at = goal; at >= 0; at = prev[at]) path.add(crumbs.get(at));
        Collections.reverse(path);
        List<BlockPos> thinned = new ArrayList<>();
        double since = 0;
        for (int i = 1; i < path.size(); i++) {
            since += Math.sqrt(path.get(i).distSqr(path.get(i - 1)));
            if (since >= stride || i == path.size() - 1) {
                thinned.add(path.get(i));
                since = 0;
            }
        }
        return thinned;
    }

    public double routeLength(BlockPos from, BlockPos to, double joinWithin) {
        List<BlockPos> r = route(from, to, joinWithin, 1.0);
        if (r.isEmpty()) return -1;
        double total = Math.sqrt(from.distSqr(r.get(0)));
        for (int i = 1; i < r.size(); i++) total += Math.sqrt(r.get(i).distSqr(r.get(i - 1)));
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
            if (a < crumbs.size() && b < crumbs.size()) links.get(a).add(b);
        }
    }
}
