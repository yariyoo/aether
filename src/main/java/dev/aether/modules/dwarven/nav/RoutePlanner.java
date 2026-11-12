package dev.aether.modules.dwarven.nav;

import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.Hop;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// picks how to get from wherever the player stands to the goal, trying each recovery level in turn
public final class RoutePlanner {
    public enum Kind {
        KNOWN, REATTACHED, PARTIAL, DIRECT, STEP, NONE;

        // whether reaching the last leg means arriving, rather than planning again from there
        public boolean reachesGoal() {
            return this == KNOWN || this == REATTACHED || this == DIRECT;
        }
    }

    public record Plan(Kind kind, List<Waypoint> legs, BlockPos attach, double attachDistance, String reason) {}

    public static final double JOIN = 12.0;
    public static final double REATTACH = 24.0;
    // one pathfinder walk; anything longer is crossed as several walks between known points
    public static final double MAX_WALK = 40.0;
    public static final double STEP_GAIN = 10.0;

    private RoutePlanner() {
    }

    // reattach widens the search for a way onto known ground; walkFallback allows pathfinding where nothing is known
    public record Options(boolean reattach, boolean walkFallback) {}

    public static final Options DEFAULT = new Options(true, true);

    public static Options options(boolean reattach, boolean walkFallback) {
        return new Options(reattach, walkFallback);
    }

    public static Plan plan(TrailMemory trails, BlockPos feet, BlockPos goal, double stride, Set<HopKind> kinds,
                            Set<Hop> banned, Set<BlockPos> unreachable, boolean useTrails) {
        return plan(trails, feet, goal, stride, kinds, banned, unreachable, useTrails, DEFAULT);
    }

    public static Plan plan(TrailMemory trails, BlockPos feet, BlockPos goal, double stride, Set<HopKind> kinds,
                            Set<Hop> banned, Set<BlockPos> unreachable, boolean useTrails, Options opt) {
        double direct = Math.sqrt(feet.distSqr(goal));
        if (useTrails) {
            for (double radius : opt.reattach() ? new double[]{JOIN, REATTACH, MAX_WALK} : new double[]{JOIN}) {
                TrailMemory.Route r = trails.routeFrom(feet, goal, radius, JOIN, stride, kinds, banned, unreachable, false);
                if (r.isEmpty() && !r.complete()) continue;
                Kind kind = r.attachDistance() > JOIN ? Kind.REATTACHED : Kind.KNOWN;
                return new Plan(kind, withGoal(r.legs(), goal), r.attach(), r.attachDistance(),
                        String.format("%s known ground, joined %.0f blocks away", kind == Kind.KNOWN ? "along" : "reattached to", r.attachDistance()));
            }
            TrailMemory.Route toward = trails.routeFrom(feet, goal, REATTACH, JOIN, stride, kinds, banned, unreachable, true);
            if (!toward.isEmpty()) {
                return new Plan(Kind.PARTIAL, toward.legs(), toward.attach(), toward.attachDistance(), "as far as known ground goes");
            }
        }
        if (direct <= MAX_WALK && opt.walkFallback()) return new Plan(Kind.DIRECT, List.of(new Waypoint(goal, null)), null, 0, "short enough to pathfind");
        if (useTrails && opt.walkFallback()) {
            List<BlockPos> steps = trails.stepsToward(feet, goal, MAX_WALK, STEP_GAIN, unreachable);
            if (!steps.isEmpty()) {
                BlockPos step = steps.get(0);
                return new Plan(Kind.STEP, List.of(new Waypoint(step, null)), step, Math.sqrt(step.distSqr(feet)),
                        String.format("no connected route; walking to known ground %.0f blocks closer", direct - Math.sqrt(step.distSqr(goal))));
            }
        }
        int nearby = useTrails ? trails.crumbsWithin(feet, MAX_WALK) : 0;
        String why = nearby == 0
                ? String.format("unexplored: nothing walked within %.0f blocks and the goal is %.0f away", MAX_WALK, direct)
                : String.format("blocked: %d known points nearby, none reachable or closer, and the goal is %.0f away", nearby, direct);
        return new Plan(Kind.NONE, List.of(), null, 0, why);
    }

    public enum Warp { NO_FORGE, AT_FORGE, FORGE_FAILED, AFTER_FAILURE_HERE, NO_ROUTE_HERE, QUICKER, NOT_QUICKER }

    // whether to /warp forge before walking; a trip that already failed from the forge isn't started from there again
    public static Warp forgeDecision(boolean enabled, BlockPos forge, BlockPos feet, double atForge, double hereSeconds,
                                     double fromForgeSeconds, boolean failedFromForge, boolean failedFromHere) {
        return forgeDecision(enabled, forge, feet, atForge, hereSeconds, fromForgeSeconds, failedFromForge, failedFromHere, true);
    }

    // routeHere is whether a plan from where we stand actually reaches the goal; a straight-line guess isn't a route
    public static Warp forgeDecision(boolean enabled, BlockPos forge, BlockPos feet, double atForge, double hereSeconds,
                                     double fromForgeSeconds, boolean failedFromForge, boolean failedFromHere, boolean routeHere) {
        if (!enabled || forge == null) return Warp.NO_FORGE;
        if (Math.sqrt(forge.distSqr(feet)) < atForge) return Warp.AT_FORGE;
        if (failedFromForge) return Warp.FORGE_FAILED;
        if (failedFromHere) return Warp.AFTER_FAILURE_HERE;
        if (!routeHere) return Warp.NO_ROUTE_HERE;
        return fromForgeSeconds + 5 < hereSeconds ? Warp.QUICKER : Warp.NOT_QUICKER;
    }

    // like forgeDecision, but when neither start has a complete route the two partial routes are compared by their
    // estimated time instead of warping just because the one from here is partial
    public static Warp forgeDecision(boolean enabled, BlockPos forge, BlockPos feet, double atForge, double hereSeconds,
                                     double fromForgeSeconds, boolean failedFromForge, boolean failedFromHere, boolean routeHere,
                                     boolean routeFromForge) {
        Warp w = forgeDecision(enabled, forge, feet, atForge, hereSeconds, fromForgeSeconds, failedFromForge, failedFromHere, routeHere);
        if (w != Warp.NO_ROUTE_HERE || routeFromForge) return w;
        return fromForgeSeconds + 5 < hereSeconds ? Warp.QUICKER : Warp.NOT_QUICKER;
    }

    public static boolean warps(Warp w) {
        return w == Warp.AFTER_FAILURE_HERE || w == Warp.NO_ROUTE_HERE || w == Warp.QUICKER;
    }

    // a walking leg goes to the etherwarp pathfinder when its expected time, with half a second of margin, beats
    // walking it, and never for a short hop
    public static boolean etherwarpBeatsWalking(double distance, double minDistance, double walkSpeed, double pathStartSeconds,
                                                double etherLegSeconds) {
        if (distance < minDistance) return false;
        return etherLegSeconds + 0.5 < distance / walkSpeed + pathStartSeconds;
    }

    // the expected time of handing a leg to the etherwarp pathfinder, failures included: a failed one costs its own
    // time and then the walk anyway. successRate comes from what such legs have done before
    public static double etherwarpLegSeconds(double successRate, double successSeconds, double failureSeconds, double walkSeconds) {
        return successRate * successSeconds + (1 - successRate) * (failureSeconds + walkSeconds);
    }

    public static boolean etherwarpLegWorthIt(double distance, double minDistance, double walkSpeed, double pathStartSeconds,
                                              double successRate, double successSeconds, double failureSeconds) {
        if (distance < minDistance) return false;
        double walk = distance / walkSpeed + pathStartSeconds;
        return etherwarpLegSeconds(successRate, successSeconds, failureSeconds, walk) + 0.5 < walk;
    }

    // longest single walk a merged leg may cover; inside one pathfinder search's comfortable range
    public static final double MERGE_SPAN = 36.0;
    // a skipped stop may sit this far off the straight line, sideways or in height, and still count as on the way
    public static final double MERGE_CORRIDOR = 2.5;

    // drops walking stops the straight line already passes by, so a run of short legs becomes one longer walk and
    // the pathfinder restarts less often. a teleport's start and anything after it stay exactly as planned
    public static List<Waypoint> simplify(BlockPos from, List<Waypoint> legs, double maxSpan, double corridor) {
        List<Waypoint> out = new ArrayList<>();
        BlockPos anchor = from;
        int i = 0;
        while (i < legs.size()) {
            Waypoint w = legs.get(i);
            if (w.hop() != null) {
                out.add(w);
                anchor = BlockPos.containing(w.hop().toX(), w.hop().toY() + 0.01, w.hop().toZ());
                i++;
                continue;
            }
            int best = i;
            for (int j = i + 1; j < legs.size(); j++) {
                if (legs.get(j).hop() != null) break;
                if (Math.sqrt(anchor.distSqr(legs.get(j).pos())) > maxSpan) break;
                boolean straight = true;
                for (int k = i; k < j && straight; k++) straight = nearSegment(legs.get(k).pos(), anchor, legs.get(j).pos(), corridor);
                if (!straight) break;
                best = j;
                // the stop a teleport leaves from is never walked past
                if (j + 1 < legs.size() && legs.get(j + 1).hop() != null) break;
            }
            out.add(legs.get(best));
            anchor = legs.get(best).pos();
            i = best + 1;
        }
        return out;
    }

    static boolean nearSegment(BlockPos p, BlockPos a, BlockPos b, double corridor) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / len2;
        if (t < 0 || t > 1) return false;
        double cx = a.getX() + t * dx - p.getX(), cz = a.getZ() + t * dz - p.getZ();
        double y = a.getY() + t * (b.getY() - a.getY());
        return Math.sqrt(cx * cx + cz * cz) <= corridor && Math.abs(y - p.getY()) <= corridor;
    }

    private static List<Waypoint> withGoal(List<Waypoint> legs, BlockPos goal) {
        List<Waypoint> out = new ArrayList<>(legs);
        if (out.isEmpty() || out.get(out.size() - 1).pos().distSqr(goal) > 4) out.add(new Waypoint(goal, null));
        return out;
    }
}
