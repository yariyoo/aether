package dev.aether.modules.dwarven.nav;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.diag.Diag;
import dev.aether.modules.dwarven.diag.Ops;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.dwarven.memory.RouteHistory;
import dev.aether.modules.dwarven.memory.TrailMemory;
import dev.aether.modules.dwarven.memory.TrailMemory.HopKind;
import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// gets the player to a far destination with aether's pathfinder, in short legs along ground it has walked before
public final class AreaNavigator {
    public enum Status { IDLE, RUNNING, ARRIVED, FAILED }

    public static final String FORGE_WARP = "@forge warp";
    private static final double JOIN = RoutePlanner.JOIN;
    private static final double LEG_REACHED = 3.0;
    private static final long PLAN_GRACE_MS = 8000L;
    private static final double WARP_MOVED = 20.0;
    private static final long WARP_OVERHEAD_SECONDS = 4;
    private static final double WALK_SPEED = 5.0;
    private static final double UNKNOWN_GROUND_FACTOR = 1.5;
    private static final double ETHERWARP_MIN_DISTANCE = 30.0;
    private static final double AT_FORGE = 12.0;
    private static final double HOP_MOVED = HopLanding.MOVED;
    // the server can correct the landing a tick later, so it is judged only once the position stops changing
    private static final int HOP_SETTLE_TICKS = 2;
    private static final int HOP_SETTLE_LIMIT = 10;
    // a ray blocked this close to the landing block still lands next to it
    private static final double AIM_NEAR_MISS = 1.5;
    private static final int MAX_REPLANS = 12;
    private static final int MAX_STEPS = 6;
    private static final double HOP_MISS_COST = 8.0;
    private static final long EARLY_STUCK_MS = 3000L;
    private static final long HOP_WAIT_MS = 1500L;
    private static final long HOP_AIM_LIMIT_MS = 2500L;
    private static final float HOP_AIM_DEGREES = 1.2f;
    // an aotv throws a fixed distance along the view, so a couple of degrees moves the landing well under a block;
    // etherwarp lands on the exact block aimed at and keeps the tight tolerance
    private static final float AOTV_AIM_DEGREES = 2.5f;
    // what a teleport costs when nothing has been measured yet: lining up, the throw, and the server confirming it
    private static final double DEFAULT_HOP_SECONDS = 1.0;
    // the server only sees the sneak a tick or two after the key goes down
    private static final int SNEAK_SETTLE_TICKS = 3;
    private static final double HOP_START_REACHED = 1.0;
    private static final long HOP_ALIGN_LIMIT_MS = 8000L;

    private Status status = Status.IDLE;
    private BlockPos goal;
    private String label;
    private double arriveWithin;
    private List<Waypoint> legs = List.of();
    private boolean hopping;
    private boolean hopClicked;
    private Vec3 hopAim;
    private Vec3 hopLaunch;
    private long hopSince;
    private int sneakTicks;
    private boolean hopAligned;
    private boolean partialRoute;
    private BlockPos tripStart;
    private boolean hopWalking;
    private int settleTicks;
    private int settleWaited;
    private Vec3 lastHopPos;
    private RoutePlanner.Kind planKind = RoutePlanner.Kind.NONE;
    // the point a route joins known ground at, or a step target; a failed walk there marks it unreachable
    private BlockPos connection;
    private int replans;
    private int steps;
    private String lastPlanReason;
    private String aimProblem;
    private long planningNanos;
    private Ops.Op tripOp;
    private Ops.Op legOp;
    private Ops.Op hopPrepOp;
    private Ops.Op hopExecOp;
    private Ops.Op hopConfirmOp;
    private Ops.Op aimOp;
    private Ops.Op warpOp;
    private long legEndedAt;
    private long hopStartedAt;
    private int tripId;
    private int pathfinderCalls;
    private int plans;
    private String mode = "IDLE";
    private long lastTripMs;
    // a merged leg that fails turns merging off for the rest of the trip, so the original stops are walked again
    private boolean simplifyOff;
    private final Set<Integer> mergedLegs = new java.util.HashSet<>();
    private Vec3 learnFrom;
    private Vec3 lastLegPos;
    private long learnArmedAt;
    // a dynamic etherwarp leg's expected cost, before any have been timed: searching, lining up, the throw
    private static final double DEFAULT_ETHER_LEG_SECONDS = 2.5;
    // below this, walking is never worth handing to the etherwarp pathfinder
    private static final double MIN_ETHER_LEG = 12.0;
    // a teleport that failed costs about this much walking to recover from
    private static final double FAILURE_COST_BLOCKS = 20.0;
    // what a failed etherwarp-pathfinder leg costs before anything has been measured: the search, a stall, the give-up
    private static final double DEFAULT_ETHER_FAIL_SECONDS = 6.0;
    private static final String ETHER_LEG = "etherwarp_leg";
    // a gap this long between two legs of one trip isn't standing still between them, it's a trip that never closed
    private static final long MAX_IDLE_SAMPLE_MS = 15_000L;
    // failing this close to the goal is a local problem; a forge warp would only bring us back to the same spot
    private static final double NEAR_GOAL = 24.0;
    private static final long HOP_ALIGN_STALL_MS = 3000L;
    private static final int MAX_PRECHECKS = 6;
    private static final double REVALIDATE_WITHIN = 48.0;
    private static final String FORGE_REGION = "Forge";
    private String region = "";
    private String journeyFrom = "";
    private String journeyTo = "";
    private long journeyStartedAt;
    private int journeyHops;
    private int recoveries;
    private int prechecks;
    private boolean pathEtherLeg;
    private double hopAlignBest;
    private long hopAlignProgressAt;

    // the zone the player is in when a trip starts, as the scoreboard showed it; trips are learned per start zone
    public void region(String zone) {
        region = zone == null ? "" : zone;
    }

    private String regionOf(BlockPos feet) {
        BlockPos forge = forgeLanding(feet);
        if (forge != null && Math.sqrt(forge.distSqr(feet)) < AT_FORGE) return FORGE_REGION;
        if (!region.isBlank()) return region;
        return "near " + (feet.getX() >> 5 << 5) + "," + (feet.getZ() >> 5 << 5);
    }

    public long lastTripMs() {
        return lastTripMs;
    }
    private int legAt;
    private int attempts;
    private boolean etherwarpLeg;
    private boolean warpTried;
    private boolean warping;
    private BlockPos warpFrom;
    private long warpAt;
    private AtomicInteger result = new AtomicInteger();
    private double best;
    private long progressAt;
    private long legStartedAt;
    private boolean moved;
    private String failure;

    public Status status() {
        return status;
    }

    public String failure() {
        return failure;
    }

    public BlockPos goal() {
        return goal;
    }

    // seconds to walk somewhere: along known trails when they connect, otherwise a padded straight line
    public static double walkSeconds(BlockPos from, BlockPos to) {
        double trail = AetherConfig.DWARVEN_LEARN_TRAILS.get() ? DwarvenStore.TRAILS.routeLength(from, to, JOIN, EnumSet.allOf(HopKind.class)) : -1;
        double blocks = trail >= 0 ? trail : Math.sqrt(from.distSqr(to)) * UNKNOWN_GROUND_FACTOR;
        return blocks / WALK_SPEED;
    }

    public static double travelSeconds(BlockPos from, BlockPos to) {
        double direct = walkSeconds(from, to);
        BlockPos forge = forgeLanding(from);
        if (forge == null || !AetherConfig.DWARVEN_FORGE_WARP.get()) return direct;
        return Math.min(direct, WARP_OVERHEAD_SECONDS + walkSeconds(forge, to));
    }

    private static BlockPos forgeLanding(BlockPos from) {
        return DwarvenStore.AREAS.nearestPoint(FORGE_WARP, from);
    }

    public void start(Minecraft client, BlockPos goal, String label, double arriveWithin) {
        stop(client);
        this.goal = goal;
        this.label = label;
        this.arriveWithin = arriveWithin;
        this.attempts = 0;
        this.replans = 0;
        this.simplifyOff = false;
        this.steps = 0;
        this.warpTried = false;
        this.failure = null;
        this.status = Status.RUNNING;
        this.legEndedAt = 0;
        this.recoveries = 0;
        this.prechecks = 0;
        this.journeyHops = 0;
        BlockPos feet = client.player.blockPosition();
        tripStart = feet;
        journeyFrom = regionOf(feet);
        // a named area or emissary is one destination wherever in it the goal lies; an unnamed spot is its map cell
        journeyTo = label.startsWith("a remembered") || label.equals("another spot")
                ? "spot " + (goal.getX() >> 4 << 4) + "," + (goal.getZ() >> 4 << 4) : label;
        journeyStartedAt = System.currentTimeMillis();
        BlockPos forge = forgeLanding(feet);
        long now = System.currentTimeMillis();
        boolean failedFromForge = forge != null && NavFailures.lastFor(goal, forge, now) != null;
        NavFailures.Failure here = NavFailures.lastFor(goal, feet, now);
        tripId++;
        tripOp = Ops.begin("nav.trip", 0);
        tripOp.detail(label + " at " + goal.toShortString());
        Diag.event(Diag.ROUTE, "route requested #" + tripId + " to " + label + " at " + goal.toShortString() + " from " + feet.toShortString());
        long t0 = System.nanoTime();
        RoutePlanner.Plan fromHere = planFrom(client, feet);
        RoutePlanner.Plan fromForge = forge == null ? null : planFrom(client, forge);
        long took = System.nanoTime() - t0;
        planningNanos += took;
        Ops.recordNanos("nav.plan", took);
        // compare what each start is expected to cost in time: walking, each teleport at its measured cost, and the warp
        boolean intelligent = AetherConfig.DWARVEN_SMART_FORGE.get();
        // the plain comparison is distance along known ground; the intelligent one prices each teleport and the warp
        // at what they have measured, and counts a route's failure history
        double hereSeconds = intelligent ? estimateSeconds(feet, fromHere) : walkSeconds(feet, goal);
        double forgeSeconds = fromForge == null ? 0 : (intelligent ? warpSeconds() : WARP_OVERHEAD_SECONDS)
                + (intelligent ? estimateSeconds(forge, fromForge) : walkSeconds(forge, goal));
        if (intelligent) {
            // once the same trip has finished a few times, what it took beats any estimate
            hereSeconds = DwarvenStore.ROUTES.expectedSeconds(journeyFrom, journeyTo, hereSeconds);
            if (fromForge != null) forgeSeconds = warpSeconds() + DwarvenStore.ROUTES.expectedSeconds(FORGE_REGION, journeyTo, forgeSeconds - warpSeconds());
        }
        Diag.event(Diag.PATH, String.format("start options: from here %.1fs (%s), from the forge %.1fs (%s)", hereSeconds,
                fromHere.kind(), forgeSeconds, fromForge == null ? "no forge" : fromForge.kind()), took / 1e6);
        RoutePlanner.Warp w = RoutePlanner.forgeDecision(AetherConfig.DWARVEN_FORGE_WARP.get(), forge, feet, AT_FORGE,
                hereSeconds, forgeSeconds, failedFromForge, here != null, fromHere.kind().reachesGoal(),
                fromForge != null && fromForge.kind().reachesGoal());
        String chosenFrom = RoutePlanner.warps(w) ? FORGE_REGION : journeyFrom;
        RouteHistory.Journey known = DwarvenStore.ROUTES.journey(chosenFrom, journeyTo);
        if (known != null && DwarvenStore.ROUTES.trusted(chosenFrom, journeyTo)) {
            DwarvenLog.log(String.format("[LEARNING] Reusing successful historical route: %s -> %s, %d of %d trips arrived, %.1fs on average, best %.1fs",
                    chosenFrom, journeyTo, known.successes, known.trips, known.meanSeconds(), known.bestMs / 1000.0));
        }
        switch (w) {
            case QUICKER -> DwarvenLog.log("[NAV] the forge is the quicker way to " + label + ", warping");
            case NOT_QUICKER -> {
                if (!fromHere.kind().reachesGoal()) DwarvenLog.log(String.format("[NAV] no complete route from the forge either; "
                        + "continuing from here (%.0fs vs %.0fs from the forge)", hereSeconds, forgeSeconds));
            }
            case NO_ROUTE_HERE -> DwarvenLog.log("[NAV] no connected route to " + label + " from here (" + fromHere.reason() + "), starting from the forge");
            case AFTER_FAILURE_HERE -> DwarvenLog.log("[NAV] the last trip to " + label + " from here failed (" + here.reason() + "), coming at it from the forge");
            case FORGE_FAILED -> DwarvenLog.log("[NAV] not warping: the trip to " + label + " from the forge failed recently, planning from here");
            default -> { }
        }
        if (RoutePlanner.warps(w)) {
            warp(client, w != RoutePlanner.Warp.QUICKER);
            return;
        }
        plan(client);
    }

    // a partial plan still has the unknown part after it, walked at the padded straight-line rate
    private double estimateSeconds(BlockPos from, RoutePlanner.Plan p) {
        if (p.kind() == RoutePlanner.Kind.NONE) return walkSeconds(from, goal);
        double seconds = expectedSeconds(from, p);
        if (!p.kind().reachesGoal()) {
            BlockPos end = p.legs().isEmpty() ? from : p.legs().get(p.legs().size() - 1).pos();
            seconds += Math.sqrt(end.distSqr(goal)) * UNKNOWN_GROUND_FACTOR / WALK_SPEED;
        }
        return seconds;
    }

    // seconds this plan should take from here: walking between stops, plus every teleport at its measured cost
    public static double expectedSeconds(BlockPos from, RoutePlanner.Plan p) {
        double walk = 0;
        int hops = 0;
        BlockPos at = from;
        for (Waypoint w : p.legs()) {
            if (w.hop() != null) {
                hops++;
                at = BlockPos.containing(w.hop().toX(), w.hop().toY() + 0.01, w.hop().toZ());
                continue;
            }
            walk += Math.sqrt(at.distSqr(w.pos()));
            at = w.pos();
        }
        return walk / WALK_SPEED + hops * hopSeconds() + p.legs().size() * pathStartSeconds();
    }

    public static double hopSeconds() {
        double aotv = Ops.meanMs("hop.aotv.prep", -1) + Ops.meanMs("hop.aotv.exec", -1) + Ops.meanMs("hop.confirm", -1);
        return aotv > 0 && Ops.summary("hop.aotv.exec").count() >= 3 ? aotv / 1000.0 : DEFAULT_HOP_SECONDS;
    }

    private static double pathStartSeconds() {
        return Ops.meanMs("path.start", 300) / 1000.0;
    }

    private static double warpSeconds() {
        return Ops.summary("nav.warp").count() >= 2 ? Ops.meanMs("nav.warp", 1000) / 1000.0 : WARP_OVERHEAD_SECONDS;
    }

    public void stop(Minecraft client) {
        if (status == Status.RUNNING && !warping && !hopping) PathfindingManager.stop();
        if (hopping) endHop(client);
        warping = false;
        if (status == Status.RUNNING) {
            status = Status.IDLE;
            cancelOps("navigation stopped");
        }
        mode = "IDLE";
    }

    private void cancelOps(String why) {
        for (Ops.Op op : new Ops.Op[]{legOp, hopPrepOp, hopExecOp, hopConfirmOp, aimOp, warpOp, tripOp}) {
            if (op != null && !op.done()) op.cancel(why);
        }
        legEndedAt = 0;
    }

    private void plan(Minecraft client) {
        if (++replans > MAX_REPLANS) {
            routeFailed(client, "gave up after " + MAX_REPLANS + " replans (last: " + lastPlanReason + ")");
            return;
        }
        BlockPos feet = client.player.blockPosition();
        revalidateNearby(client, feet);
        long t0 = System.nanoTime();
        RoutePlanner.Plan p = planFrom(client, feet);
        long took = System.nanoTime() - t0;
        planningNanos += took;
        plans++;
        // a teleport on the plan whose landing can already be seen as blocked is dropped now, not after walking to it
        TrailMemory.Hop broken = p.kind() == RoutePlanner.Kind.NONE ? null : firstBrokenHop(client, p.legs());
        if (broken != null && prechecks < MAX_PRECHECKS) {
            prechecks++;
            replans--;
            String why = landingProblem(client, broken);
            long until = DwarvenStore.TRAILS.invalidate(broken, why);
            DwarvenLog.log(String.format("[LEARNING] Failed waypoint penalized: etherwarp from %s %s; left out of planning for %s (strike %d), replanning before walking to it",
                    BlockPos.containing(broken.fromX(), broken.fromY(), broken.fromZ()).toShortString(), why, span(until - System.currentTimeMillis()),
                    DwarvenStore.TRAILS.hopStats(broken).strikes));
            plan(client);
            return;
        }
        Ops.recordNanos("nav.plan", took);
        lastPlanReason = p.reason();
        double direct = Math.sqrt(feet.distSqr(goal));
        if (p.kind() == RoutePlanner.Kind.NONE) {
            if (forgeFallback(client, feet)) return;
            routeFailed(client, p.reason());
            return;
        }
        if (p.kind() == RoutePlanner.Kind.STEP && ++steps > MAX_STEPS) {
            routeFailed(client, "walked " + MAX_STEPS + " steps toward it without finding a connected route");
            return;
        }
        planKind = p.kind();
        partialRoute = !p.kind().reachesGoal();
        connection = p.attach() != null && p.attachDistance() > 4.0 ? p.attach() : null;
        legs = p.legs();
        mergedLegs.clear();
        if (AetherConfig.DWARVEN_ROUTE_SIMPLIFY.get() && !simplifyOff && legs.size() > 1) {
            // a walk onto known ground stays its own leg, so a failure there still marks that join point unreachable
            boolean keepFirst = connection != null && legs.get(0).pos().equals(connection);
            List<Waypoint> merged = keepFirst ? new ArrayList<>(List.of(legs.get(0))) : new ArrayList<>();
            merged.addAll(RoutePlanner.simplify(keepFirst ? connection : feet, keepFirst ? legs.subList(1, legs.size()) : legs,
                    RoutePlanner.MERGE_SPAN, RoutePlanner.MERGE_CORRIDOR));
            if (merged.size() < legs.size()) {
                // remember which legs now stand for several, so a failure on one can fall back to the original stops
                int at = 0;
                for (int i = 0; i < merged.size(); i++) {
                    int orig = legs.indexOf(merged.get(i));
                    if (orig - at > 0) mergedLegs.add(i);
                    at = orig + 1;
                }
                Diag.event(Diag.PATH, "route simplified from " + legs.size() + " to " + merged.size() + " legs");
                legs = merged;
            }
        }
        legAt = 0;
        long hopCount = legs.stream().filter(w -> w.hop() != null).count();
        DwarvenLog.log(String.format("[NAV] route to %s at %s, %.0f blocks: %s, %d legs, %d teleports, about %.0fs (planned in %.1fms)", label,
                goal.toShortString(), direct, p.reason(), legs.size(), hopCount, expectedSeconds(feet, p), took / 1e6));
        if (p.kind() == RoutePlanner.Kind.REATTACHED || p.kind() == RoutePlanner.Kind.STEP) {
            DwarvenLog.log("[NAV] first leg is a local walk to " + p.attach().toShortString() + "; it proves the connection is reachable");
        }
        startLeg(client);
    }

    private TrailMemory.Hop firstBrokenHop(Minecraft client, List<Waypoint> route) {
        for (Waypoint w : route) {
            if (w.hop() != null && w.hop().kind() == HopKind.ETHERWARP && landingProblem(client, w.hop()) != null) return w.hop();
        }
        return null;
    }

    // a teleport left out because its landing was blocked comes back as soon as its landing is seen clear again
    private void revalidateNearby(Minecraft client, BlockPos feet) {
        for (TrailMemory.Hop h : DwarvenStore.TRAILS.invalidHops()) {
            if (h.kind() != HopKind.ETHERWARP) continue;
            TrailMemory.HopStats st = DwarvenStore.TRAILS.hopStats(h);
            if (st == null || !st.cause.contains("landing")) continue;
            BlockPos land = BlockPos.containing(h.toX(), h.toY(), h.toZ());
            if (Math.sqrt(land.distSqr(feet)) > REVALIDATE_WITHIN || !client.level.hasChunkAt(land)) continue;
            if (landingProblem(client, h) == null) {
                DwarvenStore.TRAILS.revalidate(h);
                DwarvenLog.log("[LEARNING] etherwarp landing at " + land.toShortString() + " is clear again; teleport back in planning");
            }
        }
    }

    // why an etherwarp's landing can't be used right now, or null if it can or can't be checked from here
    private static String landingProblem(Minecraft client, TrailMemory.Hop hop) {
        BlockPos target = BlockPos.containing(hop.toX(), Math.floor(hop.toY() + 0.01) - 1, hop.toZ());
        if (!client.level.hasChunkAt(target)) return null;
        if (client.level.getBlockState(target).getCollisionShape(client.level, target).isEmpty()) {
            return "its landing block at " + target.toShortString() + " is gone";
        }
        if (!client.level.getBlockState(target.above()).getCollisionShape(client.level, target.above()).isEmpty()) {
            return "there's no room to stand on its landing block";
        }
        return null;
    }

    private static String span(long ms) {
        return ms >= 3_600_000L ? (ms / 3_600_000L) + "h" : (ms / 60_000L) + "m";
    }

    private RoutePlanner.Plan planFrom(Minecraft client, BlockPos feet) {
        long nowMs = System.currentTimeMillis();
        boolean avoid = AetherConfig.DWARVEN_AVOID_FAILED_WAYPOINTS.get();
        double weight = AetherConfig.DWARVEN_ROUTE_RELIABILITY.get();
        boolean learned = AetherConfig.DWARVEN_TELEPORT_OPTIMIZE.get();
        boolean aotvFirst = AetherConfig.DWARVEN_PREFER_AOTV.get();
        DwarvenStore.TRAILS.avoidStuck(AetherConfig.DWARVEN_STUCK_AVOIDANCE.get());
        // each recent miss costs about as much as walking a few extra blocks; a teleport's whole history adds its
        // failure rate times what a failure costs to recover from
        DwarvenStore.TRAILS.hopPenalty(h -> {
            double pen = weight * HOP_MISS_COST * NavFailures.misses(h);
            TrailMemory.HopStats st = learned ? DwarvenStore.TRAILS.hopStats(h) : null;
            if (st != null) pen += weight * st.failureRate() * FAILURE_COST_BLOCKS;
            if (!aotvFirst && h.kind() == HopKind.AOTV) pen += Math.min(15.0, hopSeconds() * WALK_SPEED);
            return pen;
        });
        // only once a few teleports have been timed; until then the graph keeps its usual price for them
        if (Ops.summary("hop.aotv.exec").count() >= 3) DwarvenStore.TRAILS.hopCost(Math.min(15.0, hopSeconds() * WALK_SPEED));
        return RoutePlanner.plan(DwarvenStore.TRAILS, feet, goal, AetherConfig.DWARVEN_LEG_LENGTH.get(), hopKinds(client),
                NavFailures.bannedHops(nowMs), avoid ? NavFailures.unreachable(nowMs) : Set.of(), AetherConfig.DWARVEN_LEARN_TRAILS.get(),
                RoutePlanner.options(AetherConfig.DWARVEN_ROUTE_REATTACH.get(), AetherConfig.DWARVEN_WALK_FALLBACK.get()));
    }

    // known ground ran out here; the forge is a fixed, reliable starting point unless the trip already failed from it
    // or the forge has no route there either
    private boolean forgeFallback(Minecraft client, BlockPos feet) {
        BlockPos forge = forgeLanding(feet);
        if (warpTried || forge == null || !AetherConfig.DWARVEN_FORGE_WARP.get()) return false;
        if (Math.sqrt(forge.distSqr(feet)) < AT_FORGE) return false;
        if (NavFailures.lastFor(goal, forge, System.currentTimeMillis()) != null) return false;
        RoutePlanner.Plan fromForge = planFrom(client, forge);
        if (fromForge.kind() == RoutePlanner.Kind.NONE) {
            Diag.event(Diag.RECOVERY, "no forge warp: the forge has no route to " + label + " either (" + fromForge.reason() + ")");
            return false;
        }
        DwarvenLog.log("[NAV] no way on from " + feet.toShortString() + " (" + lastPlanReason + "); warping to the forge to start from known ground");
        warp(client, true);
        return true;
    }

    public int tripId() {
        return tripId;
    }

    public int pathfinderCalls() {
        return pathfinderCalls;
    }

    public int plans() {
        return plans;
    }

    public String mode() {
        return mode;
    }

    // what the navigator is doing right now, for the diagnostic snapshot
    public void describe(StringBuilder b, Minecraft client, long now) {
        b.append(" nav=").append(status.name().toLowerCase());
        if (status != Status.RUNNING) return;
        BlockPos feet = client.player.blockPosition();
        b.append(" route=#").append(tripId).append(" goal=").append(goal.toShortString()).append(" mode=").append(mode);
        if (legs != null && !legs.isEmpty() && legAt < legs.size()) {
            BlockPos wp = legs.get(legAt).pos();
            b.append(" leg=").append(legAt + 1).append('/').append(legs.size()).append(" wp=").append(wp.toShortString());
            b.append(String.format(" dwp=%.1f", Math.sqrt(feet.distSqr(wp))));
            double walk = 0;
            int hops = 0;
            BlockPos at = feet;
            for (int i = legAt; i < legs.size(); i++) {
                Waypoint w = legs.get(i);
                if (w.hop() != null) hops++;
                else walk += Math.sqrt(at.distSqr(w.pos()));
                at = w.pos();
            }
            b.append(String.format(" eta=%.1fs", walk / WALK_SPEED + hops * hopSeconds()));
        }
        b.append(String.format(" dgoal=%.1f", Math.sqrt(feet.distSqr(goal))));
        b.append(" plan=").append(planKind.name().toLowerCase()).append(" fails=").append(attempts).append(" replans=").append(replans)
                .append(" steps=").append(steps);
        if (!hopping && !warping) {
            long since = now - Math.max(progressAt, legStartedAt);
            b.append(" noProgressMs=").append(since).append(moved ? "" : "(starting)");
        }
        if (hopping) {
            TrailMemory.Hop h = legs.get(legAt).hop();
            b.append(" hop=").append(h.kind().name().toLowerCase()).append(" expect=").append(fmt(new Vec3(h.toX(), h.toY(), h.toZ())));
            b.append(" hopState=").append(hopClicked ? (settleTicks > 0 || lastHopPos != null ? "confirming" : "requested")
                    : hopAligned ? (hopAim == null ? "checking" : "aiming") : "aligning");
            b.append(" hopWaitMs=").append(now - hopSince);
        }
        if (warping) b.append(" warpWaitMs=").append(now - warpAt);
    }

    public long planningNanos() {
        return planningNanos;
    }

    private static Set<HopKind> hopKinds(Minecraft client) {
        Set<HopKind> kinds = EnumSet.noneOf(HopKind.class);
        if (!AetherConfig.DWARVEN_USE_ETHERWARP.get()) return kinds;
        if (aotvSlot(client) >= 0) kinds.add(HopKind.AOTV);
        if (GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) >= 0) kinds.add(HopKind.ETHERWARP);
        return kinds;
    }

    private static int aotvSlot(Minecraft client) {
        int slot = GearManager.findHotbarItemSlot(client, "Aspect of the Void");
        return slot >= 0 ? slot : GearManager.findHotbarItemSlot(client, "Aspect of the End");
    }

    private void startLeg(Minecraft client) {
        Waypoint w = legs.get(legAt);
        if (w.hop() != null && hopKinds(client).contains(w.hop().kind())) {
            startHop(client, w.hop());
            return;
        }
        BlockPos to = w.pos();
        BlockPos feet = client.player.blockPosition();
        double distance = Math.sqrt(feet.distSqr(to));
        boolean canEtherwarp = AetherConfig.DWARVEN_USE_ETHERWARP.get() && GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) >= 0;
        // alternate on retries so one broken mode can't block the trip
        etherwarpLeg = canEtherwarp && attempts % 2 == 0 && etherwarpWorthIt(distance);
        pathEtherLeg = etherwarpLeg;
        // the pathfinder turns the camera while walking; a steer left over from a teleport aim would fight it
        if (RotationManager.isRotating()) RotationManager.cancelRotation();
        learnFrom = null;
        lastLegPos = client.player.position();
        AtomicInteger r = new AtomicInteger();
        result = r;
        best = distance;
        long now = System.currentTimeMillis();
        progressAt = now;
        legStartedAt = now;
        moved = false;
        pathfinderCalls++;
        mode = etherwarpLeg ? "PATHFINDER_ETHERWARP" : "WALK";
        legOp = Ops.begin("nav.leg", (long) (distance / WALK_SPEED * 1000) + PLAN_GRACE_MS);
        legOp.detail(String.format("leg %d/%d to %s, %.0f blocks", legAt + 1, legs.size(), to.toShortString(), distance));
        Diag.event(Diag.ROUTE, String.format("leg %d/%d %s to %s, %.1f blocks", legAt + 1, legs.size(), mode.toLowerCase(), to.toShortString(), distance));
        if (etherwarpLeg) {
            PathfindingManager.startConfiguredEtherwarp(client, to.getX(), to.getY(), to.getZ(), () -> r.compareAndSet(0, 1), () -> r.compareAndSet(0, 2));
        } else {
            PathfindingManager.startConfiguredWalk(client, to.getX(), to.getY(), to.getZ(), () -> r.compareAndSet(0, 1), () -> r.compareAndSet(0, 2), true, 1.0, false, true);
        }
    }

    // a walking leg is handed to the etherwarp pathfinder when that is expected to be quicker than walking it
    private boolean etherwarpWorthIt(double distance) {
        if (!AetherConfig.DWARVEN_PREFER_ETHERWARP.get() || !AetherConfig.DWARVEN_DYNAMIC_TELEPORTS.get()) return distance >= ETHERWARP_MIN_DISTANCE;
        // priced by what these legs have actually done, failures included; in the 19:56 log they failed more often
        // than they worked, and a failed one costs its own time and then the walk anyway
        RouteHistory.Method m = DwarvenStore.ROUTES.method(ETHER_LEG);
        return RoutePlanner.etherwarpLegWorthIt(distance, MIN_ETHER_LEG, WALK_SPEED, pathStartSeconds(), m.successRate(),
                m.successSeconds(DEFAULT_ETHER_LEG_SECONDS), m.failureSeconds(DEFAULT_ETHER_FAIL_SECONDS));
    }

    // a teleport the etherwarp pathfinder made, seen as a jump between two ticks, becomes a known hop for next time
    private void watchForTeleport(Minecraft client, long now) {
        Vec3 pos = client.player.position();
        if (lastLegPos != null && pos.distanceTo(lastLegPos) > 3.0) {
            learnFrom = lastLegPos;
            learnArmedAt = now;
        }
        lastLegPos = pos;
        if (learnFrom != null && client.player.onGround() && now - learnArmedAt > 150) {
            TrailMemory.Hop hop = new TrailMemory.Hop(HopKind.ETHERWARP, learnFrom.x, learnFrom.y, learnFrom.z, pos.x, pos.y, pos.z);
            if (!DwarvenStore.TRAILS.knowsHop(hop)) {
                DwarvenStore.TRAILS.learnHop(hop);
                Diag.event(Diag.PATH, "learned an etherwarp from " + fmt(learnFrom) + " to " + fmt(pos));
            }
            learnFrom = null;
        }
    }

    public void tick(Minecraft client) {
        if (status != Status.RUNNING || client.player == null) return;
        long now = System.currentTimeMillis();
        BlockPos feet = client.player.blockPosition();
        if (etherwarpLeg && !hopping && !warping && AetherConfig.DWARVEN_DYNAMIC_TELEPORTS.get()) watchForTeleport(client, now);
        if (warping) {
            tickWarp(client, feet, now);
            return;
        }
        if (hopping) {
            tickHop(client, now);
            return;
        }
        if (Math.sqrt(feet.distSqr(goal)) <= arriveWithin) {
            PathfindingManager.stop();
            NavFailures.succeeded(goal);
            status = Status.ARRIVED;
            mode = "IDLE";
            if (legOp != null) legOp.end("reached the goal");
            long tripMs = tripOp == null ? 0 : tripOp.end("arrived");
            lastTripMs = tripMs;
            legEndedAt = 0;
            DwarvenLog.log("[NAV] reached " + label + " at " + feet.toShortString() + " in " + String.format("%.1fs", tripMs / 1000.0));
            learnJourney(true, now);
            return;
        }
        BlockPos to = legs.get(legAt).pos();
        double left = Math.sqrt(feet.distSqr(to));
        boolean lastLeg = legAt == legs.size() - 1;
        if (lastLeg && partialRoute && (result.get() == 1 || left <= LEG_REACHED)) {
            PathfindingManager.stop();
            if (legOp != null) legOp.end("reached the end of known ground");
            legEndedAt = now;
            attempts = 0;
            DwarvenLog.log("[NAV] reached " + to.toShortString() + " (" + planKind.name().toLowerCase() + "), planning on from here");
            plan(client);
            return;
        }
        if (result.get() == 1 || (!lastLeg && left <= LEG_REACHED)) {
            if (lastLeg) {
                // the pathfinder says done but we're not within range; treat it like any other miss
                legFailed(client, "stopped " + String.format("%.0f", left) + " blocks short");
                return;
            }
            // starting the next walk stops this one anyway; stopping first only adds a tick of standing still
            boolean nextWalks = legAt + 1 < legs.size() && legs.get(legAt + 1).hop() == null;
            if (result.get() != 1 && !(nextWalks && AetherConfig.DWARVEN_REDUCE_LEG_DELAY.get())) PathfindingManager.stop();
            if (legOp != null) {
                long legMs = legOp.end("reached");
                Ops.record(etherwarpLeg ? "nav.leg.ether" : "nav.leg.walk", legMs);
                if (pathEtherLeg) DwarvenStore.ROUTES.method(ETHER_LEG, true, legMs);
            }
            pathEtherLeg = false;
            DwarvenStore.TRAILS.passed(to);
            legEndedAt = now;
            if (connection != null && to.equals(connection)) {
                DwarvenLog.log("[NAV] reattached at " + to.toShortString() + ", continuing along the route");
                connection = null;
            }
            legAt++;
            attempts = 0;
            startLeg(client);
            return;
        }
        if (left < best - 0.5) {
            if (!moved) {
                // the first step: until now the pathfinder was searching, and the player was standing still
                Ops.record("path.start", now - legStartedAt);
                if (legEndedAt > 0 && now - legEndedAt <= MAX_IDLE_SAMPLE_MS) Ops.record("nav.idle", now - legEndedAt);
                legEndedAt = 0;
            }
            best = left;
            progressAt = now;
            moved = true;
            if (legOp != null) legOp.progress();
        }
        // route planning can take a few seconds before the first step, which isn't being stuck
        long stuckMs = AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L;
        // once it's been moving, a few seconds without getting closer already means the walk won't make it
        if (AetherConfig.DWARVEN_PREDICTIVE_STUCK.get()) stuckMs = Math.min(stuckMs, EARLY_STUCK_MS);
        long allowed = moved ? stuckMs : PLAN_GRACE_MS;
        if (result.get() == 2) {
            legFailed(client, "no path");
        } else if (now - Math.max(progressAt, legStartedAt) > allowed) {
            legFailed(client, moved ? "stuck" : "never started moving");
        }
    }

    private void learnJourney(boolean arrived, long now) {
        if (journeyFrom.isEmpty() || journeyTo.isEmpty()) return;
        long ms = now - journeyStartedAt;
        RouteHistory.Outcome o = DwarvenStore.ROUTES.record(journeyFrom, journeyTo, arrived, ms, journeyHops, recoveries);
        String route = journeyFrom + " -> " + journeyTo;
        if (!arrived) {
            DwarvenLog.log(String.format("[LEARNING] Route failed: %s after %.1fs, %d recoveries; its expected cost now includes the failure", route, ms / 1000.0, recoveries));
        } else if (o.firstSuccess()) {
            DwarvenLog.log(String.format("[LEARNING] Route completed: %s, %.1fs (%d teleports, %d recoveries); successful route recorded", route, ms / 1000.0,
                    journeyHops, recoveries));
        } else {
            DwarvenLog.log(String.format("[LEARNING] Route completed: %s, %.1fs against %.1fs on average before (%+.1fs)%s", route, ms / 1000.0,
                    o.previousMeanSeconds(), -o.savedSeconds(), o.faster() ? "; faster verified alternative discovered" : ""));
        }
        journeyFrom = "";
    }

    private void legFailed(Minecraft client, String why) {
        PathfindingManager.stop();
        long now = System.currentTimeMillis();
        if (why.equals("stuck")) Ops.record("nav.stuck", now - progressAt);
        if (legOp != null) legOp.end("failed: " + why);
        Ops.record("nav.fail", 0);
        legEndedAt = now;
        recoveries++;
        if (pathEtherLeg) {
            DwarvenStore.ROUTES.method(ETHER_LEG, false, now - legStartedAt);
            pathEtherLeg = false;
        }
        Diag.event(Diag.RECOVERY, "leg " + (legAt + 1) + " failed (" + why + "), recovering");
        BlockPos target = legs.get(legAt).pos();
        // the stop that couldn't be reached costs more in later routes, so a bad one stops being chosen
        // the place the walk stalled is what later routes should avoid; the stop it was heading for may be fine
        BlockPos stall = client.player.blockPosition();
        if (!etherwarpLeg && (why.equals("stuck") || why.equals("no path"))) {
            DwarvenStore.TRAILS.stuck(why.equals("stuck") ? stall : target);
            Diag.event(Diag.PATH, "[LEARNING] Failed waypoint penalized at " + (why.equals("stuck") ? stall : target).toShortString());
        }
        if (mergedLegs.contains(legAt) && !simplifyOff) {
            simplifyOff = true;
            DwarvenLog.log("[NAV] a merged leg failed; walking the original stops for the rest of this trip");
            plan(client);
            return;
        }
        DwarvenLog.log("[NAV] leg " + (legAt + 1) + "/" + legs.size() + " " + (etherwarpLeg ? "(etherwarp) " : "") + why + " at "
                + client.player.blockPosition().toShortString());
        if (connection != null && target.equals(connection)) {
            // the join point looked close but the pathfinder can't get there, so plan without it rather than retrying it
            NavFailures.unreachable(connection, System.currentTimeMillis() + AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS.get() * 1000L);
            DwarvenLog.log("[NAV] " + connection.toShortString() + " isn't reachable from here; searching for another connection");
            connection = null;
            plan(client);
            return;
        }
        attempts++;
        if (attempts <= AetherConfig.DWARVEN_NAV_RETRIES.get()) {
            if (attempts == 1 && legs.size() > 1) {
                // the remembered ground may have changed; replan from here before retrying the same leg
                plan(client);
                attempts = 1;
            } else {
                startLeg(client);
            }
            return;
        }
        BlockPos forge = forgeLanding(client.player.blockPosition());
        boolean atForge = forge != null && Math.sqrt(forge.distSqr(client.player.blockPosition())) < AT_FORGE;
        boolean forgeFailed = forge != null && NavFailures.lastFor(goal, forge, System.currentTimeMillis()) != null;
        double toGoal = Math.sqrt(client.player.blockPosition().distSqr(goal));
        if (toGoal <= NEAR_GOAL) {
            // a warp from here only leads back to this same spot; the caller picks another way in or works from here
            routeFailed(client, why + String.format(" %.0f blocks from the goal; not warping to the forge for a local problem", toGoal));
            return;
        }
        if (!warpTried && !atForge && !forgeFailed && AetherConfig.DWARVEN_FORGE_WARP.get()) {
            DwarvenLog.log("[NAV] out of retries, warping to the forge to come at it from there");
            warp(client, true);
            return;
        }
        routeFailed(client, why);
    }

    private void routeFailed(Minecraft client, String why) {
        PathfindingManager.stop();
        failure = why;
        status = Status.FAILED;
        mode = "IDLE";
        if (tripOp != null) tripOp.end("failed: " + why);
        cancelOps("route failed");
        learnJourney(false, System.currentTimeMillis());
        legEndedAt = 0;
        NavFailures.Failure f = NavFailures.record(goal, tripStart == null ? client.player.blockPosition() : tripStart, why, System.currentTimeMillis());
        DwarvenLog.log("[NAV] route to " + label + " failed: " + why + " (" + f.count() + " time" + (f.count() == 1 ? "" : "s") + " from here)");
    }

    // recovery marks a warp made because something failed, so the report can tell those apart from planned ones
    private void warp(Minecraft client, boolean recovery) {
        warpTried = true;
        warping = true;
        mode = "WARP";
        warpOp = Ops.begin(recovery ? "nav.warp.recovery" : "nav.warp", 2500);
        warpFrom = client.player.blockPosition();
        warpAt = System.currentTimeMillis();
        DwarvenStore.TRAILS.interrupted();
        // the warp turns the camera; that's ours, not a staff check
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        DwarvenLog.log("[NAV] forge warp requested");
        ClientUtils.sendCommand("/warp forge");
    }

    private void tickWarp(Minecraft client, BlockPos feet, long now) {
        if (Math.sqrt(feet.distSqr(warpFrom)) > WARP_MOVED) {
            warping = false;
            if (warpOp != null) {
                long ms = warpOp.end("arrived");
                // every warp counts toward the warp's own cost, whatever the reason for it
                if (warpOp.name.equals("nav.warp.recovery")) Ops.record("nav.warp", ms);
            }
            DwarvenLog.log("[NAV] forge arrival confirmed at " + feet.toShortString() + ", planning from here");
            DwarvenStore.AREAS.observe(FORGE_WARP, feet);
            DwarvenStore.TRAILS.interrupted();
            // failures from here on belong to the trip from the forge, so the next try knows the forge didn't help
            tripStart = feet;
            journeyFrom = FORGE_REGION;
            journeyStartedAt = now;
            journeyHops = 0;
            attempts = 0;
            plan(client);
            return;
        }
        if (now - warpAt > AetherConfig.DWARVEN_WARP_TIMEOUT_MS.get()) {
            warping = false;
            if (warpOp != null) warpOp.end("didn't move us");
            // we may already be standing at the forge, in which case the warp moves us nowhere
            DwarvenLog.log("[NAV] /warp forge didn't move us");
            attempts = 0;
            plan(client);
        }
    }

    private void startHop(Minecraft client, TrailMemory.Hop hop) {
        hopping = true;
        hopClicked = false;
        hopAligned = false;
        hopWalking = false;
        hopSince = System.currentTimeMillis();
        sneakTicks = 0;
        settleTicks = 0;
        settleWaited = 0;
        lastHopPos = null;
        hopAim = null;
        hopAlignBest = Double.MAX_VALUE;
        hopAlignProgressAt = hopSince;
        etherwarpLeg = hop.kind() == HopKind.ETHERWARP;
        PathfindingManager.stop();
        hopStartedAt = hopSince;
        mode = hop.kind().name();
        legEndedAt = 0;
        String k = hop.kind().name().toLowerCase();
        hopPrepOp = Ops.begin("hop." + k + ".prep", 1500);
        hopPrepOp.detail("launch " + fmt(new Vec3(hop.fromX(), hop.fromY(), hop.fromZ())));
        aimOp = null;
        Diag.event(Diag.HOP, k + " preparation started, expected landing " + fmt(new Vec3(hop.toX(), hop.toY(), hop.toZ())));
    }

    private void tickHop(Minecraft client, long now) {
        TrailMemory.Hop hop = legs.get(legAt).hop();
        boolean ether = hop.kind() == HopKind.ETHERWARP;
        Vec3 pos = client.player.position();
        if (hopClicked) {
            if (pos.distanceTo(hopLaunch) > HOP_MOVED) {
                if (hopExecOp != null && !hopExecOp.done()) {
                    hopExecOp.end("moved");
                    hopConfirmOp = Ops.begin("hop.confirm", 400);
                }
                if (hopConfirmOp != null) hopConfirmOp.progress();
                boolean still = lastHopPos != null && pos.distanceTo(lastHopPos) < 0.1;
                lastHopPos = pos;
                settleTicks = still ? settleTicks + 1 : 0;
                if (settleTicks < HOP_SETTLE_TICKS && ++settleWaited < HOP_SETTLE_LIMIT) return;
                landed(client, hop, pos);
            } else if (now - hopSince > HOP_WAIT_MS) {
                hopFailed(client, hop, "didn't teleport (out of mana or blocked?)");
            }
            return;
        }
        Vec3 start = new Vec3(hop.fromX(), hop.fromY(), hop.fromZ());
        if (!hopAligned) {
            // stand where the recording stood before aiming, so the same throw lands in the same place
            double off = Math.hypot(pos.x - start.x, pos.z - start.z);
            if (off <= HOP_START_REACHED && Math.abs(pos.y - start.y) < 1.5) {
                if (hopWalking) PathfindingManager.stop();
                hopWalking = false;
                hopAligned = true;
                hopSince = now;
            } else {
                if (!hopWalking) {
                    hopWalking = true;
                    AtomicInteger r = new AtomicInteger();
                    result = r;
                    BlockPos to = BlockPos.containing(start.x, start.y + 0.01, start.z);
                    PathfindingManager.startConfiguredWalk(client, to.getX(), to.getY(), to.getZ(), () -> r.compareAndSet(0, 1),
                            () -> r.compareAndSet(0, 2), true, 0.4, false, true);
                }
                if (off < hopAlignBest - 0.2) {
                    hopAlignBest = off;
                    hopAlignProgressAt = now;
                }
                // a start the walk has stopped closing in on won't be reached by waiting out the full limit
                if (result.get() == 2 || now - hopSince > HOP_ALIGN_LIMIT_MS || now - hopAlignProgressAt > HOP_ALIGN_STALL_MS) {
                    hopFailed(client, hop, "couldn't stand on its start");
                }
                return;
            }
        }
        int slot = ether ? GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) : aotvSlot(client);
        if (slot < 0) {
            hopFailed(client, hop, "no aotv in the hotbar for it");
            return;
        }
        if (hopAim == null) {
            // check the landing can be aimed at before touching sneak or the hotbar
            hopAim = aim(client, hop);
            if (hopAim == null) {
                hopFailed(client, hop, "checked before the throw: " + aimProblem);
                return;
            }
            DwarvenLog.log("[NAV] planned " + hop.kind().name().toLowerCase() + ": launch " + fmt(pos) + ", expected landing "
                    + fmt(new Vec3(hop.toX(), hop.toY(), hop.toZ())));
        }
        if (now - hopSince > HOP_AIM_LIMIT_MS) {
            hopFailed(client, hop, "never lined up");
            return;
        }
        if (FailsafeManager.getCurrentSelectedSlot(client) != slot) {
            FailsafeManager.selectHotbarSlot(client, slot);
            return;
        }
        if (ether) {
            ClientUtils.setKeyMappingState(client.options.keyShift, true);
            if (++sneakTicks < SNEAK_SETTLE_TICKS) return;
        }
        if (aimOp == null) aimOp = Ops.begin("rot.aim", 500);
        // hypixel traces etherwarp from its own sneaking eye height, not the modern client's
        Vec3 eye = ether ? EtherwarpHelper.getEyePosition(client, pos) : client.player.getEyePosition();
        RotationUtils.Rotation want = RotationUtils.calculateLookAt(eye, hopAim);
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        RotationManager.steerAngles(client, want.yaw, want.pitch, ether ? DwarvenCamera.profile(DwarvenCamera.Activity.ETHERWARP) : DwarvenCamera.profile(DwarvenCamera.Activity.NAVIGATION));
        float yawOff = Math.abs(Mth.wrapDegrees(want.yaw - client.player.getYRot()));
        float pitchOff = Math.abs(want.pitch - client.player.getXRot());
        float tolerance = ether ? HOP_AIM_DEGREES : AOTV_AIM_DEGREES;
        aimOp.progress();
        if (yawOff > tolerance || pitchOff > tolerance) return;
        aimOp.end(String.format("lined up within %.1f degrees", Math.max(yawOff, pitchOff)));
        hopLaunch = pos;
        hopClicked = true;
        hopSince = now;
        if (hopPrepOp != null) hopPrepOp.end("ready");
        hopExecOp = Ops.begin("hop." + hop.kind().name().toLowerCase() + ".exec", 900);
        Diag.event(Diag.HOP, hop.kind().name().toLowerCase() + " requested from " + fmt(pos));
        ClientUtils.performUseClickInstant();
    }

    private void landed(Minecraft client, TrailMemory.Hop hop, Vec3 pos) {
        if (hopConfirmOp != null) hopConfirmOp.end("position settled");
        legEndedAt = System.currentTimeMillis();
        mode = "WALK";
        endHop(client);
        DwarvenStore.TRAILS.interrupted();
        Vec3 intended = new Vec3(hop.toX(), hop.toY(), hop.toZ());
        HopLanding.Result r = HopLanding.judge(hopLaunch, intended, pos, legs, legAt);
        boolean useful = r.verdict() == HopLanding.Verdict.ON_TARGET || r.verdict() == HopLanding.Verdict.CONTINUE;
        DwarvenStore.TRAILS.hopResult(hop, useful, r.deviation(), System.currentTimeMillis() - hopStartedAt);
        journeyHops++;
        if (!useful) {
            recoveries++;
            TrailMemory.HopStats st = DwarvenStore.TRAILS.hopStats(hop);
            Diag.event(Diag.HOP, String.format("[LEARNING] Teleport edge reliability updated: %d of %d landed usefully",
                    st.successes, st.successes + st.failures));
        }
        String kind = hop.kind().name().toLowerCase();
        DwarvenLog.log(String.format("[NAV] %s completed: launch %s, expected %s, actual %s, deviation %.1f (measured after the position %s)",
                kind, fmt(hopLaunch), fmt(intended), fmt(pos), r.deviation(), settleTicks >= HOP_SETTLE_TICKS ? "settled" : "kept changing"));
        attempts = 0;
        switch (r.verdict()) {
            case ON_TARGET -> {
                NavFailures.hopLanded(hop);
                legAt = r.continueAt();
                if (legAt >= legs.size()) {
                    if (Math.sqrt(client.player.blockPosition().distSqr(goal)) <= arriveWithin) {
                        status = Status.ARRIVED;
                        mode = "IDLE";
                        lastTripMs = tripOp == null ? 0 : tripOp.end("arrived");
                        legEndedAt = 0;
                        NavFailures.succeeded(goal);
                        learnJourney(true, System.currentTimeMillis());
                    } else {
                        plan(client);
                    }
                    return;
                }
                startLeg(client);
            }
            case CONTINUE -> {
                int misses = NavFailures.hopMissed(hop);
                if (misses >= 2) {
                    NavFailures.banHop(hop, System.currentTimeMillis() + AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS.get() * 1000L);
                    DwarvenLog.log("[NAV] this " + kind + " has missed twice; failed edge removed from planning");
                }
                // walk to the route stop we're next to instead of the one the hop was meant to reach
                List<Waypoint> rest = new ArrayList<>(legs);
                BlockPos to = rest.get(r.continueAt()).pos();
                rest.set(r.continueAt(), new Waypoint(to, null));
                legs = rest;
                legAt = r.continueAt();
                connection = to;
                DwarvenLog.log(String.format("[NAV] landing deviation %.1f; original route continuation restored by walking to leg %d/%d at %s",
                        r.deviation(), legAt + 1, legs.size(), to.toShortString()));
                startLeg(client);
            }
            case REPLAN -> {
                NavFailures.banHop(hop, System.currentTimeMillis() + AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS.get() * 1000L);
                DwarvenLog.log(String.format("[NAV] landing deviation %.1f and no route stop within reach; failed edge removed, searching for reachable route connections",
                        r.deviation()));
                plan(client);
            }
            case NO_TELEPORT -> hopFailed(client, hop, "didn't move us");
        }
    }

    private static String fmt(Vec3 v) {
        return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
    }

    // a failed teleport is blacklisted and the route is planned again without it, so the same throw isn't repeated
    private void hopFailed(Minecraft client, TrailMemory.Hop hop, String why) {
        Ops.record("hop.failed", System.currentTimeMillis() - hopStartedAt);
        recoveries++;
        // a blocked landing or a start nobody can stand on fails the same way every time; those are left out of
        // planning across restarts instead of only for this trip
        boolean structural = why.contains("no room to stand") || why.contains("landing block at") || why.startsWith("couldn't stand on its start");
        if (structural) {
            long until = DwarvenStore.TRAILS.invalidate(hop, why);
            DwarvenLog.log(String.format("[LEARNING] Teleport edge reliability updated: %s from %s %s; left out of planning for %s (strike %d)",
                    hop.kind().name().toLowerCase(), BlockPos.containing(hop.fromX(), hop.fromY(), hop.fromZ()).toShortString(), why,
                    span(until - System.currentTimeMillis()), DwarvenStore.TRAILS.hopStats(hop).strikes));
        } else {
            DwarvenStore.TRAILS.hopResult(hop, false, 0, 0);
        }
        for (Ops.Op op : new Ops.Op[]{hopPrepOp, aimOp, hopExecOp, hopConfirmOp}) if (op != null && !op.done()) op.cancel(why);
        Diag.event(Diag.RECOVERY, hop.kind().name().toLowerCase() + " failed: " + why);
        endHop(client);
        if (hopWalking) PathfindingManager.stop();
        hopWalking = false;
        NavFailures.banHop(hop, System.currentTimeMillis() + AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS.get() * 1000L);
        attempts++;
        DwarvenLog.log("[NAV] " + hop.kind().name().toLowerCase() + " from " + BlockPos.containing(hop.fromX(), hop.fromY(), hop.fromZ()).toShortString()
                + " " + why + "; leaving it out and recalculating the route (" + attempts + "/" + AetherConfig.DWARVEN_NAV_RETRIES.get() + ")");
        if (attempts <= AetherConfig.DWARVEN_NAV_RETRIES.get()) {
            int keep = attempts;
            plan(client);
            attempts = keep;
            return;
        }
        legFailed(client, "teleports kept failing");
    }

    // checked before the throw: the landing block must still be there, have room above it, be in range and be visible
    private Vec3 aim(Minecraft client, TrailMemory.Hop hop) {
        Vec3 pos = client.player.position();
        if (hop.kind() == HopKind.AOTV) {
            return new Vec3(hop.toX(), hop.toY() + client.player.getEyeHeight(), hop.toZ());
        }
        BlockPos target = BlockPos.containing(hop.toX(), Math.floor(hop.toY() + 0.01) - 1, hop.toZ());
        if (!client.level.hasChunkAt(target)) {
            // past render distance there's nothing to check against, so trust the recording only from its exact start
            Vec3 start = new Vec3(hop.fromX(), hop.fromY(), hop.fromZ());
            if (pos.distanceTo(start) < 1.5) return Vec3.atCenterOf(target).add(0, 0.45, 0);
            aimProblem = "its landing isn't loaded and we're not on the recorded start";
            return null;
        }
        String problem = landingProblem(client, hop);
        if (problem != null) {
            aimProblem = problem;
            return null;
        }
        Vec3 eye = EtherwarpHelper.getEyePosition(client, pos);
        double[][] offsets = {{0.5, 0.95, 0.5}, {0.5, 0.5, 0.5}, {0.2, 0.9, 0.2}, {0.8, 0.9, 0.8}, {0.2, 0.9, 0.8}, {0.8, 0.9, 0.2},
                {0.5, 0.5, 0.05}, {0.5, 0.5, 0.95}, {0.05, 0.5, 0.5}, {0.95, 0.5, 0.5}};
        BlockPos nearMiss = null;
        Vec3 nearMissPoint = null;
        boolean inRange = false;
        for (double[] o : offsets) {
            Vec3 point = new Vec3(target.getX() + o[0], target.getY() + o[1], target.getZ() + o[2]);
            if (eye.distanceTo(point) > EtherwarpHelper.MAX_ETHERWARP_DISTANCE) continue;
            inRange = true;
            Vec3 end = eye.add(point.subtract(eye).normalize().scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE));
            BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            if (hit.getBlockPos().equals(target)) return point;
            if (nearMiss == null && Math.sqrt(hit.getBlockPos().distSqr(target)) <= AIM_NEAR_MISS) {
                nearMiss = hit.getBlockPos();
                nearMissPoint = point;
            }
        }
        if (!inRange) {
            aimProblem = String.format("its landing is %.0f blocks away, past etherwarp range", eye.distanceTo(Vec3.atCenterOf(target)));
            return null;
        }
        // a ray stopped by the block right beside the landing still lands next to it; anything further would land elsewhere
        if (nearMiss != null) return nearMissPoint;
        aimProblem = "its landing block can't be seen from here";
        return null;
    }

    private void endHop(Minecraft client) {
        hopping = false;
        RotationManager.cancelRotation();
        if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyShift, false);
    }
}
