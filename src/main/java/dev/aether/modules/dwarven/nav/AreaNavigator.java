package dev.aether.modules.dwarven.nav;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.memory.DwarvenStore;
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
        this.steps = 0;
        this.warpTried = false;
        this.failure = null;
        this.status = Status.RUNNING;
        BlockPos feet = client.player.blockPosition();
        tripStart = feet;
        BlockPos forge = forgeLanding(feet);
        long now = System.currentTimeMillis();
        boolean failedFromForge = forge != null && NavFailures.lastFor(goal, forge, now) != null;
        NavFailures.Failure here = NavFailures.lastFor(goal, feet, now);
        long t0 = System.nanoTime();
        RoutePlanner.Plan fromHere = planFrom(client, feet);
        planningNanos += System.nanoTime() - t0;
        RoutePlanner.Warp w = RoutePlanner.forgeDecision(AetherConfig.DWARVEN_FORGE_WARP.get(), forge, feet, AT_FORGE,
                walkSeconds(feet, goal), forge == null ? 0 : WARP_OVERHEAD_SECONDS + walkSeconds(forge, goal), failedFromForge, here != null,
                fromHere.kind().reachesGoal());
        switch (w) {
            case QUICKER -> DwarvenLog.log("[NAV] the forge is the quicker way to " + label + ", warping");
            case NO_ROUTE_HERE -> DwarvenLog.log("[NAV] no connected route to " + label + " from here (" + fromHere.reason() + "), starting from the forge");
            case AFTER_FAILURE_HERE -> DwarvenLog.log("[NAV] the last trip to " + label + " from here failed (" + here.reason() + "), coming at it from the forge");
            case FORGE_FAILED -> DwarvenLog.log("[NAV] not warping: the trip to " + label + " from the forge failed recently, planning from here");
            default -> { }
        }
        if (RoutePlanner.warps(w)) {
            warp(client);
            return;
        }
        plan(client);
    }

    public void stop(Minecraft client) {
        if (status == Status.RUNNING && !warping && !hopping) PathfindingManager.stop();
        if (hopping) endHop(client);
        warping = false;
        if (status == Status.RUNNING) status = Status.IDLE;
    }

    private void plan(Minecraft client) {
        if (++replans > MAX_REPLANS) {
            routeFailed(client, "gave up after " + MAX_REPLANS + " replans (last: " + lastPlanReason + ")");
            return;
        }
        BlockPos feet = client.player.blockPosition();
        long t0 = System.nanoTime();
        RoutePlanner.Plan p = planFrom(client, feet);
        long took = System.nanoTime() - t0;
        planningNanos += took;
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
        legAt = 0;
        long hopCount = legs.stream().filter(w -> w.hop() != null).count();
        DwarvenLog.log(String.format("[NAV] route to %s at %s, %.0f blocks: %s, %d legs, %d teleports (planned in %.1fms)", label,
                goal.toShortString(), direct, p.reason(), legs.size(), hopCount, took / 1e6));
        if (p.kind() == RoutePlanner.Kind.REATTACHED || p.kind() == RoutePlanner.Kind.STEP) {
            DwarvenLog.log("[NAV] first leg is a local walk to " + p.attach().toShortString() + "; it proves the connection is reachable");
        }
        startLeg(client);
    }

    private RoutePlanner.Plan planFrom(Minecraft client, BlockPos feet) {
        long nowMs = System.currentTimeMillis();
        boolean avoid = AetherConfig.DWARVEN_AVOID_FAILED_WAYPOINTS.get();
        double weight = AetherConfig.DWARVEN_ROUTE_RELIABILITY.get();
        // each recent miss costs about as much as walking a few extra blocks
        DwarvenStore.TRAILS.hopPenalty(h -> weight * HOP_MISS_COST * NavFailures.misses(h));
        return RoutePlanner.plan(DwarvenStore.TRAILS, feet, goal, AetherConfig.DWARVEN_LEG_LENGTH.get(), hopKinds(client),
                NavFailures.bannedHops(nowMs), avoid ? NavFailures.unreachable(nowMs) : Set.of(), AetherConfig.DWARVEN_LEARN_TRAILS.get(),
                RoutePlanner.options(AetherConfig.DWARVEN_ROUTE_REATTACH.get(), AetherConfig.DWARVEN_WALK_FALLBACK.get()));
    }

    // known ground ran out here; the forge is a fixed, reliable starting point unless the trip already failed from it
    private boolean forgeFallback(Minecraft client, BlockPos feet) {
        BlockPos forge = forgeLanding(feet);
        if (warpTried || forge == null || !AetherConfig.DWARVEN_FORGE_WARP.get()) return false;
        if (Math.sqrt(forge.distSqr(feet)) < AT_FORGE) return false;
        if (NavFailures.lastFor(goal, forge, System.currentTimeMillis()) != null) return false;
        DwarvenLog.log("[NAV] no way on from " + feet.toShortString() + " (" + lastPlanReason + "); warping to the forge to start from known ground");
        warp(client);
        return true;
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
        etherwarpLeg = canEtherwarp && distance >= ETHERWARP_MIN_DISTANCE && attempts % 2 == 0;
        AtomicInteger r = new AtomicInteger();
        result = r;
        best = distance;
        long now = System.currentTimeMillis();
        progressAt = now;
        legStartedAt = now;
        moved = false;
        if (etherwarpLeg) {
            PathfindingManager.startConfiguredEtherwarp(client, to.getX(), to.getY(), to.getZ(), () -> r.compareAndSet(0, 1), () -> r.compareAndSet(0, 2));
        } else {
            PathfindingManager.startConfiguredWalk(client, to.getX(), to.getY(), to.getZ(), () -> r.compareAndSet(0, 1), () -> r.compareAndSet(0, 2), true, 1.0, false, true);
        }
    }

    public void tick(Minecraft client) {
        if (status != Status.RUNNING || client.player == null) return;
        long now = System.currentTimeMillis();
        BlockPos feet = client.player.blockPosition();
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
            DwarvenLog.log("[NAV] reached " + label + " at " + feet.toShortString());
            return;
        }
        BlockPos to = legs.get(legAt).pos();
        double left = Math.sqrt(feet.distSqr(to));
        boolean lastLeg = legAt == legs.size() - 1;
        if (lastLeg && partialRoute && (result.get() == 1 || left <= LEG_REACHED)) {
            PathfindingManager.stop();
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
            if (result.get() != 1) PathfindingManager.stop();
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
            best = left;
            progressAt = now;
            moved = true;
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

    private void legFailed(Minecraft client, String why) {
        PathfindingManager.stop();
        BlockPos target = legs.get(legAt).pos();
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
        if (!warpTried && !atForge && !forgeFailed && AetherConfig.DWARVEN_FORGE_WARP.get()) {
            DwarvenLog.log("[NAV] out of retries, warping to the forge to come at it from there");
            warp(client);
            return;
        }
        routeFailed(client, why);
    }

    private void routeFailed(Minecraft client, String why) {
        PathfindingManager.stop();
        failure = why;
        status = Status.FAILED;
        NavFailures.Failure f = NavFailures.record(goal, tripStart == null ? client.player.blockPosition() : tripStart, why, System.currentTimeMillis());
        DwarvenLog.log("[NAV] route to " + label + " failed: " + why + " (" + f.count() + " time" + (f.count() == 1 ? "" : "s") + " from here)");
    }

    private void warp(Minecraft client) {
        warpTried = true;
        warping = true;
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
            DwarvenLog.log("[NAV] forge arrival confirmed at " + feet.toShortString() + ", planning from here");
            DwarvenStore.AREAS.observe(FORGE_WARP, feet);
            DwarvenStore.TRAILS.interrupted();
            // failures from here on belong to the trip from the forge, so the next try knows the forge didn't help
            tripStart = feet;
            attempts = 0;
            plan(client);
            return;
        }
        if (now - warpAt > AetherConfig.DWARVEN_WARP_TIMEOUT_MS.get()) {
            warping = false;
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
        etherwarpLeg = hop.kind() == HopKind.ETHERWARP;
        PathfindingManager.stop();
    }

    private void tickHop(Minecraft client, long now) {
        TrailMemory.Hop hop = legs.get(legAt).hop();
        boolean ether = hop.kind() == HopKind.ETHERWARP;
        Vec3 pos = client.player.position();
        if (hopClicked) {
            if (pos.distanceTo(hopLaunch) > HOP_MOVED) {
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
                if (result.get() == 2 || now - hopSince > HOP_ALIGN_LIMIT_MS) {
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
        // hypixel traces etherwarp from its own sneaking eye height, not the modern client's
        Vec3 eye = ether ? EtherwarpHelper.getEyePosition(client, pos) : client.player.getEyePosition();
        RotationUtils.Rotation want = RotationUtils.calculateLookAt(eye, hopAim);
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        RotationManager.steerAngles(client, want.yaw, want.pitch, DwarvenCamera.profile());
        float yawOff = Math.abs(Mth.wrapDegrees(want.yaw - client.player.getYRot()));
        float pitchOff = Math.abs(want.pitch - client.player.getXRot());
        if (yawOff > HOP_AIM_DEGREES || pitchOff > HOP_AIM_DEGREES) return;
        hopLaunch = pos;
        hopClicked = true;
        hopSince = now;
        ClientUtils.performUseClickInstant();
    }

    private void landed(Minecraft client, TrailMemory.Hop hop, Vec3 pos) {
        endHop(client);
        DwarvenStore.TRAILS.interrupted();
        Vec3 intended = new Vec3(hop.toX(), hop.toY(), hop.toZ());
        HopLanding.Result r = HopLanding.judge(hopLaunch, intended, pos, legs, legAt);
        String kind = hop.kind().name().toLowerCase();
        DwarvenLog.log(String.format("[NAV] %s completed: launch %s, expected %s, actual %s, deviation %.1f (measured after the position %s)",
                kind, fmt(hopLaunch), fmt(intended), fmt(pos), r.deviation(), settleTicks >= HOP_SETTLE_TICKS ? "settled" : "kept changing"));
        attempts = 0;
        switch (r.verdict()) {
            case ON_TARGET -> {
                NavFailures.hopLanded(hop);
                legAt = r.continueAt();
                if (legAt >= legs.size()) {
                    if (Math.sqrt(client.player.blockPosition().distSqr(goal)) <= arriveWithin) status = Status.ARRIVED;
                    else plan(client);
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
        if (client.level.getBlockState(target).getCollisionShape(client.level, target).isEmpty()) {
            aimProblem = "its landing block at " + target.toShortString() + " is gone";
            return null;
        }
        if (!client.level.getBlockState(target.above()).getCollisionShape(client.level, target.above()).isEmpty()) {
            aimProblem = "there's no room to stand on its landing block";
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
