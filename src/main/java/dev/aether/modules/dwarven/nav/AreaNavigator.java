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
    private static final double JOIN = 12.0;
    private static final double LEG_REACHED = 3.0;
    private static final long PLAN_GRACE_MS = 8000L;
    private static final double WARP_MOVED = 20.0;
    private static final long WARP_OVERHEAD_SECONDS = 4;
    private static final double WALK_SPEED = 5.0;
    private static final double UNKNOWN_GROUND_FACTOR = 1.5;
    private static final double ETHERWARP_MIN_DISTANCE = 30.0;
    private static final double AT_FORGE = 12.0;
    // a hop is aimed from where we stand, so it can land a few blocks from where the recording did
    private static final double HOP_LANDING_TOLERANCE = 4.5;
    private static final double HOP_MOVED = 2.5;
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
    // beyond this, with no known ground to follow, a single pathfinder walk isn't worth trying
    private static final double MAX_UNKNOWN_WALK = 40.0;
    private boolean hopWalking;
    private static final java.util.Map<TrailMemory.Hop, Long> bannedHops = new java.util.HashMap<>();
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
        this.warpTried = false;
        this.failure = null;
        this.status = Status.RUNNING;
        BlockPos feet = client.player.blockPosition();
        tripStart = feet;
        BlockPos forge = forgeLanding(feet);
        boolean atForge = forge != null && Math.sqrt(forge.distSqr(feet)) < AT_FORGE;
        if (forge != null && !atForge && AetherConfig.DWARVEN_FORGE_WARP.get()
                && WARP_OVERHEAD_SECONDS + walkSeconds(forge, goal) + 5 < walkSeconds(feet, goal)) {
            DwarvenLog.log("nav: the forge is the quicker way to " + label + ", warping");
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
        BlockPos feet = client.player.blockPosition();
        List<Waypoint> route = new ArrayList<>();
        Set<HopKind> kinds = hopKinds(client);
        long nowMs = System.currentTimeMillis();
        bannedHops.values().removeIf(until -> until <= nowMs);
        if (AetherConfig.DWARVEN_LEARN_TRAILS.get()) {
            route.addAll(DwarvenStore.TRAILS.waypoints(feet, goal, JOIN, AetherConfig.DWARVEN_LEG_LENGTH.get(), kinds, bannedHops.keySet()));
        }
        boolean known = !route.isEmpty();
        partialRoute = false;
        if (!known && AetherConfig.DWARVEN_LEARN_TRAILS.get()) {
            // a failed teleport can cut the full route; known ground still gets us closer, so use as much of it as works
            route.addAll(DwarvenStore.TRAILS.waypointsToward(feet, goal, JOIN, AetherConfig.DWARVEN_LEG_LENGTH.get(), kinds, bannedHops.keySet()));
            partialRoute = !route.isEmpty();
        }
        double direct = Math.sqrt(feet.distSqr(goal));
        if (route.isEmpty() && direct > MAX_UNKNOWN_WALK) {
            // the pathfinder can't reliably cross areas in one go, and this goal is beyond what's loaded around us anyway
            routeFailed(client, String.format("no known route, and %.0f blocks is too far for one pathfinder walk", direct));
            return;
        }
        if (!partialRoute && (route.isEmpty() || route.get(route.size() - 1).pos().distSqr(goal) > 4)) route.add(new Waypoint(goal, null));
        legs = route;
        legAt = 0;
        long hopCount = route.stream().filter(w -> w.hop() != null).count();
        String how = partialRoute ? legs.size() + " legs as far as known ground goes (" + hopCount + " teleports), then replanning"
                : known ? legs.size() + " legs along known ground, " + hopCount + " of them teleports"
                : "no known trail, short enough to pathfind";
        DwarvenLog.log(String.format("nav: route to %s at %s, %.0f blocks: %s", label, goal.toShortString(), direct, how));
        startLeg(client);
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
            DwarvenLog.log("nav: reached " + label + " at " + feet.toShortString());
            return;
        }
        BlockPos to = legs.get(legAt).pos();
        double left = Math.sqrt(feet.distSqr(to));
        boolean lastLeg = legAt == legs.size() - 1;
        if (lastLeg && partialRoute && (result.get() == 1 || left <= LEG_REACHED)) {
            PathfindingManager.stop();
            attempts = 0;
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
        long allowed = moved ? AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L : PLAN_GRACE_MS;
        if (result.get() == 2) {
            legFailed(client, "no path");
        } else if (now - Math.max(progressAt, legStartedAt) > allowed) {
            legFailed(client, moved ? "stuck" : "never started moving");
        }
    }

    private void legFailed(Minecraft client, String why) {
        PathfindingManager.stop();
        attempts++;
        DwarvenLog.log("nav: leg " + (legAt + 1) + "/" + legs.size() + " " + (etherwarpLeg ? "(etherwarp) " : "") + why + " at "
                + client.player.blockPosition().toShortString() + ", attempt " + attempts);
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
        if (!warpTried && !atForge && AetherConfig.DWARVEN_FORGE_WARP.get()) {
            DwarvenLog.log("nav: out of retries, warping to the forge to come at it from there");
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
        DwarvenLog.log("nav: route to " + label + " failed: " + why + " (" + f.count() + " time" + (f.count() == 1 ? "" : "s") + " from here)");
    }

    private void warp(Minecraft client) {
        warpTried = true;
        warping = true;
        warpFrom = client.player.blockPosition();
        warpAt = System.currentTimeMillis();
        DwarvenStore.TRAILS.interrupted();
        // the warp turns the camera; that's ours, not a staff check
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        DwarvenLog.log("nav: forge warp requested");
        ClientUtils.sendCommand("/warp forge");
    }

    private void tickWarp(Minecraft client, BlockPos feet, long now) {
        if (Math.sqrt(feet.distSqr(warpFrom)) > WARP_MOVED) {
            warping = false;
            DwarvenLog.log("nav: forge arrival confirmed at " + feet.toShortString() + ", planning from here");
            DwarvenStore.AREAS.observe(FORGE_WARP, feet);
            DwarvenStore.TRAILS.interrupted();
            attempts = 0;
            plan(client);
            return;
        }
        if (now - warpAt > AetherConfig.DWARVEN_WARP_TIMEOUT_MS.get()) {
            warping = false;
            // we may already be standing at the forge, in which case the warp moves us nowhere
            DwarvenLog.log("nav: /warp forge didn't move us");
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
                endHop(client);
                double off = pos.distanceTo(new Vec3(hop.toX(), hop.toY(), hop.toZ()));
                DwarvenStore.TRAILS.interrupted();
                if (off > HOP_LANDING_TOLERANCE) {
                    double before = hopLaunch.distanceTo(Vec3.atCenterOf(goal));
                    double after = pos.distanceTo(Vec3.atCenterOf(goal));
                    if (after < before - 2.0) {
                        // off the recorded spot but still progress; plan on from where we actually are
                        DwarvenLog.log(String.format("nav: %s landed %.1f blocks off the recorded spot but %.0f blocks closer, replanning from here",
                                hop.kind().name().toLowerCase(), off, before - after));
                        attempts = 0;
                        plan(client);
                        return;
                    }
                    hopFailed(client, hop, String.format("landed %.1f blocks from the recorded spot and no closer", off));
                    return;
                }
                legAt++;
                attempts = 0;
                if (legAt >= legs.size()) {
                    if (Math.sqrt(client.player.blockPosition().distSqr(goal)) <= arriveWithin) status = Status.ARRIVED;
                    else plan(client);
                    return;
                }
                startLeg(client);
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
                hopFailed(client, hop, "its landing block can't be seen from here");
                return;
            }
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

    // a failed teleport is blacklisted and the route is planned again without it, so the same throw isn't repeated
    private void hopFailed(Minecraft client, TrailMemory.Hop hop, String why) {
        endHop(client);
        if (hopWalking) PathfindingManager.stop();
        hopWalking = false;
        bannedHops.put(hop, System.currentTimeMillis() + AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS.get() * 1000L);
        attempts++;
        DwarvenLog.log("nav: " + hop.kind().name().toLowerCase() + " from " + BlockPos.containing(hop.fromX(), hop.fromY(), hop.fromZ()).toShortString()
                + " " + why + "; leaving it out and recalculating the route (" + attempts + "/" + AetherConfig.DWARVEN_NAV_RETRIES.get() + ")");
        if (attempts <= AetherConfig.DWARVEN_NAV_RETRIES.get()) {
            int keep = attempts;
            plan(client);
            attempts = keep;
            return;
        }
        legFailed(client, "teleports kept failing");
    }

    private Vec3 aim(Minecraft client, TrailMemory.Hop hop) {
        Vec3 pos = client.player.position();
        if (hop.kind() == HopKind.AOTV) {
            return new Vec3(hop.toX(), hop.toY() + client.player.getEyeHeight(), hop.toZ());
        }
        BlockPos target = BlockPos.containing(hop.toX(), Math.floor(hop.toY() + 0.01) - 1, hop.toZ());
        Vec3 eye = EtherwarpHelper.getEyePosition(client, pos);
        double[][] offsets = {{0.5, 0.5, 0.5}, {0.5, 0.95, 0.5}, {0.2, 0.9, 0.2}, {0.8, 0.9, 0.8}, {0.2, 0.9, 0.8}, {0.8, 0.9, 0.2}};
        for (double[] o : offsets) {
            Vec3 point = new Vec3(target.getX() + o[0], target.getY() + o[1], target.getZ() + o[2]);
            Vec3 end = eye.add(point.subtract(eye).normalize().scale(61.0));
            BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return point;
        }
        // the recording proves the warp works from its start, even if our ray clips something on the way
        Vec3 start = new Vec3(hop.fromX(), hop.fromY(), hop.fromZ());
        return pos.distanceTo(start) < 1.5 ? Vec3.atCenterOf(target) : null;
    }

    private void endHop(Minecraft client) {
        hopping = false;
        RotationManager.cancelRotation();
        if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyShift, false);
    }
}
