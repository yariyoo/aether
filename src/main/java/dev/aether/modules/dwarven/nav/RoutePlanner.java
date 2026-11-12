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

    public static Plan plan(TrailMemory trails, BlockPos feet, BlockPos goal, double stride, Set<HopKind> kinds,
                            Set<Hop> banned, Set<BlockPos> unreachable, boolean useTrails) {
        double direct = Math.sqrt(feet.distSqr(goal));
        if (useTrails) {
            for (double radius : new double[]{JOIN, REATTACH, MAX_WALK}) {
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
        if (direct <= MAX_WALK) return new Plan(Kind.DIRECT, List.of(new Waypoint(goal, null)), null, 0, "short enough to pathfind");
        if (useTrails) {
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

    public enum Warp { NO_FORGE, AT_FORGE, FORGE_FAILED, AFTER_FAILURE_HERE, QUICKER, NOT_QUICKER }

    // whether to /warp forge before walking; a trip that already failed from the forge isn't started from there again
    public static Warp forgeDecision(boolean enabled, BlockPos forge, BlockPos feet, double atForge, double hereSeconds,
                                     double fromForgeSeconds, boolean failedFromForge, boolean failedFromHere) {
        if (!enabled || forge == null) return Warp.NO_FORGE;
        if (Math.sqrt(forge.distSqr(feet)) < atForge) return Warp.AT_FORGE;
        if (failedFromForge) return Warp.FORGE_FAILED;
        if (failedFromHere) return Warp.AFTER_FAILURE_HERE;
        return fromForgeSeconds + 5 < hereSeconds ? Warp.QUICKER : Warp.NOT_QUICKER;
    }

    public static boolean warps(Warp w) {
        return w == Warp.AFTER_FAILURE_HERE || w == Warp.QUICKER;
    }

    private static List<Waypoint> withGoal(List<Waypoint> legs, BlockPos goal) {
        List<Waypoint> out = new ArrayList<>(legs);
        if (out.isEmpty() || out.get(out.size() - 1).pos().distSqr(goal) > 4) out.add(new Waypoint(goal, null));
        return out;
    }
}
