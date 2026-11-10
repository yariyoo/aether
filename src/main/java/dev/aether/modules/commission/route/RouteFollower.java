package dev.aether.modules.commission.route;

import dev.aether.modules.commission.CommissionLog;
import dev.aether.modules.commission.route.RouteGraph.EdgeType;
import dev.aether.modules.commission.route.RouteGraph.Plan;
import dev.aether.modules.commission.route.RouteGraph.Point;
import dev.aether.modules.commission.route.RouteGraph.Step;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// follows a RouteGraph plan: steers along recorded walks, replays aotv hops and etherwarps, warps to the forge
public final class RouteFollower {
    public enum Status { IDLE, RUNNING, ARRIVED, FAILED }

    private enum Phase { PATHING, WALKING, HOP_PREP, HOP_WAIT, WARP_WAIT }

    private static final double WAYPOINT_REACHED = 1.0;
    private static final double HOP_START_REACHED = 0.8;
    private static final double SLOW_DOWN_AT = 2.5;
    private static final double STEER_LIMIT = 3.0;
    private static final double LANDING_TOLERANCE = 3.0;
    private static final double TELEPORT_MOVED = 2.5;
    private static final long WALK_STALL_MS = 2500L;
    private static final long PATHING_LIMIT_MS = 20_000L;
    private static final long HOP_WAIT_MS = 1200L;
    private static final long HOP_PREP_LIMIT_MS = 2500L;
    private static final long WARP_WAIT_MS = 6000L;
    // the server only treats us as sneaking a tick or two after the key goes down
    private static final int SNEAK_SETTLE_TICKS = 3;
    private static final int MAX_REPLANS = 8;
    private static final long BAN_MS = 5 * 60_000L;
    private static final double RECORDED_AIM_DISTANCE = 0.6;

    private static final Map<Integer, Long> bannedUntil = new HashMap<>();
    private static long warpBannedUntil;

    private Status status = Status.IDLE;
    private Phase phase;
    private Point goal;
    private double goalRadius;
    private String goalName;
    private List<Step> steps = List.of();
    private int index;
    private long phaseSince;
    private int replans;
    private double bestDistance;
    private long progressAt;
    private Vec3 launchPos;
    private int sneakTicks;
    private int hopTries;
    private boolean rotationStarted;
    private AtomicInteger pathResult = new AtomicInteger();
    private String failure;

    public Status status() {
        return status;
    }

    public String failure() {
        return failure;
    }

    public boolean isActive() {
        return status == Status.RUNNING;
    }

    public boolean start(Minecraft client, Point goal, double goalRadius, String goalName) {
        stop(client);
        this.goal = goal;
        this.goalRadius = goalRadius;
        this.goalName = goalName;
        this.replans = 0;
        this.failure = null;
        if (!plan(client, "start")) {
            status = Status.FAILED;
            return false;
        }
        status = Status.RUNNING;
        return true;
    }

    public void stop(Minecraft client) {
        if (phase == Phase.PATHING) PathfindingManager.stop();
        releaseKeys(client);
        RotationManager.cancelRotation();
        steps = List.of();
        phase = null;
        if (status == Status.RUNNING) status = Status.IDLE;
    }

    // lets go of everything without forgetting the route, for while a menu is open
    public void pause(Minecraft client) {
        if (phase == Phase.WALKING || phase == Phase.HOP_PREP) releaseKeys(client);
    }

    public static Set<EdgeType> availableMoves(Minecraft client) {
        Set<EdgeType> moves = EnumSet.of(EdgeType.WALK);
        if (aotvSlot(client) >= 0) moves.add(EdgeType.AOTV);
        if (GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) >= 0) moves.add(EdgeType.ETHERWARP);
        if (System.currentTimeMillis() >= warpBannedUntil) moves.add(EdgeType.WARP);
        return moves;
    }

    public void tick(Minecraft client) {
        if (status != Status.RUNNING) return;
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            fail(client, "player missing");
            return;
        }
        if (index >= steps.size()) {
            arrive(client);
            return;
        }
        switch (phase) {
            case PATHING -> tickPathing(client);
            case WALKING -> tickWalking(client);
            case HOP_PREP -> tickHopPrep(client);
            case HOP_WAIT -> tickHopWait(client);
            case WARP_WAIT -> tickWarpWait(client);
        }
    }

    private boolean plan(Minecraft client, String reason) {
        long now = System.currentTimeMillis();
        bannedUntil.values().removeIf(until -> until <= now);
        Point here = here(client);
        Set<EdgeType> moves = availableMoves(client);
        Plan plan = RouteGraph.dwarvenMines().plan(here, goal, goalRadius, moves, bannedUntil.keySet());
        if (plan == null) {
            failure = "no recorded route to " + goalName + " with " + moves;
            CommissionLog.log("route: " + failure + " from " + fmt(here));
            return false;
        }
        steps = plan.steps();
        index = 0;
        CommissionLog.log(String.format("route (%s) to %s: %d steps, about %.1fs, moves %s: %s", reason, goalName,
                steps.size(), plan.seconds(), moves, summary(steps)));
        beginStep(client);
        return true;
    }

    private void replan(Minecraft client, String why) {
        releaseKeys(client);
        RotationManager.cancelRotation();
        if (phase == Phase.PATHING) PathfindingManager.stop();
        replans++;
        CommissionLog.log("route: " + why + " at " + fmt(here(client)) + ", replanning (" + replans + "/" + MAX_REPLANS + ")");
        if (replans > MAX_REPLANS) {
            fail(client, "replanned " + MAX_REPLANS + " times: " + why);
            return;
        }
        if (!plan(client, "replan")) fail(client, failure);
    }

    private void ban(Step step) {
        if (step.type() == EdgeType.WARP) {
            warpBannedUntil = System.currentTimeMillis() + BAN_MS;
        } else if (step.edge() != null) {
            bannedUntil.put(step.edge().index(), System.currentTimeMillis() + BAN_MS);
        }
    }

    private void beginStep(Minecraft client) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        long now = System.currentTimeMillis();
        phaseSince = now;
        progressAt = now;
        switch (step.type()) {
            case WALK -> {
                Point here = here(client);
                if (step.edge() == null && here.distance(step.to()) > STEER_LIMIT) {
                    startPathing(client, step.to());
                } else {
                    phase = Phase.WALKING;
                    bestDistance = here.horizontal(step.to());
                }
            }
            case AOTV, ETHERWARP -> {
                Point here = here(client);
                if (here.horizontal(step.from()) > HOP_START_REACHED || Math.abs(here.y() - step.from().y()) > 1.2) {
                    // line up on the spot the hop was recorded from first
                    steps = new ArrayList<>(steps);
                    steps.add(index, new Step(EdgeType.WALK, here, step.from(), false, null));
                    beginStep(client);
                    return;
                }
                phase = Phase.HOP_PREP;
                sneakTicks = 0;
                hopTries = 0;
                rotationStarted = false;
                releaseKeys(client);
            }
            case WARP -> {
                releaseKeys(client);
                launchPos = client.player.position();
                ClientUtils.sendCommand("/warp forge");
                phase = Phase.WARP_WAIT;
                CommissionLog.log("route: /warp forge from " + fmt(here(client)));
            }
        }
    }

    private void next(Minecraft client) {
        index++;
        if (index >= steps.size()) {
            arrive(client);
        } else {
            beginStep(client);
        }
    }

    private void startPathing(Minecraft client, Point to) {
        phase = Phase.PATHING;
        AtomicInteger result = new AtomicInteger();
        pathResult = result;
        CommissionLog.log("route: pathfinding " + String.format("%.0f", here(client).distance(to)) + " blocks onto the route at " + fmt(to));
        PathfindingManager.startConfiguredWalk(client, (int) Math.floor(to.x()), (int) Math.floor(to.y()),
                (int) Math.floor(to.z()), () -> result.compareAndSet(0, 1), () -> result.compareAndSet(0, 2),
                true, 1.0, false, true);
    }

    private void tickPathing(Minecraft client) {
        Step step = steps.get(index);
        double distance = here(client).distance(step.to());
        if (pathResult.get() == 1 || distance <= WAYPOINT_REACHED * 1.5) {
            if (pathResult.get() != 1) PathfindingManager.stop();
            next(client);
            return;
        }
        if (pathResult.get() == 2 || System.currentTimeMillis() - phaseSince > PATHING_LIMIT_MS) {
            PathfindingManager.stop();
            fail(client, "could not walk onto the route at " + fmt(step.to()));
        }
    }

    private void tickWalking(Minecraft client) {
        LocalPlayer player = client.player;
        Step step = steps.get(index);
        Point here = here(client);
        double distance = here.horizontal(step.to());
        boolean beforeHop = index + 1 < steps.size() && isHop(steps.get(index + 1).type());
        double reach = beforeHop ? HOP_START_REACHED : WAYPOINT_REACHED;
        if (distance <= reach && Math.abs(here.y() - step.to().y()) <= 2.0) {
            if (beforeHop) releaseKeys(client);
            next(client);
            return;
        }

        long now = System.currentTimeMillis();
        if (distance < bestDistance - 0.3) {
            bestDistance = distance;
            progressAt = now;
        } else if (now - progressAt > WALK_STALL_MS) {
            ban(step);
            replan(client, String.format("walk stalled %.1f blocks from %s", distance, fmt(step.to())));
            return;
        }

        // steer at the point after this one once close, so corners don't stop us dead
        Point aim = step.to();
        if (distance < 1.6 && !beforeHop && index + 1 < steps.size() && steps.get(index + 1).type() == EdgeType.WALK) {
            aim = steps.get(index + 1).to();
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(aim.x() - here.x()), aim.z() - here.z()));
        if (Math.abs(wrap(yaw - player.getYRot())) > 6f && !RotationManager.isRotating()) {
            RotationManager.rotateToYawPitch(client, yaw, 10f, 90L, true);
        }
        double toHop = remainingWalkToHop(here);
        boolean slow = beforeHop && toHop < SLOW_DOWN_AT;
        ClientUtils.setKeyMappingState(client.options.keyUp, Math.abs(wrap(yaw - player.getYRot())) < 60f);
        ClientUtils.setKeyMappingState(client.options.keySprint, !slow);
        boolean climb = step.to().y() > here.y() + 0.5 && distance < 2.5;
        ClientUtils.setKeyMappingState(client.options.keyJump,
                player.onGround() && (player.horizontalCollision || climb));
    }

    private double remainingWalkToHop(Point here) {
        double total = here.horizontal(steps.get(index).to());
        for (int i = index + 1; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.type() != EdgeType.WALK) return isHop(s.type()) ? total : Double.MAX_VALUE;
            total += s.from().horizontal(s.to());
        }
        return Double.MAX_VALUE;
    }

    private void tickHopPrep(Minecraft client) {
        LocalPlayer player = client.player;
        Step step = steps.get(index);
        boolean ether = step.type() == EdgeType.ETHERWARP;
        int slot = ether ? GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) : aotvSlot(client);
        if (slot < 0) {
            replan(client, "no " + (ether ? "etherwarp aotv" : "aotv") + " in the hotbar any more");
            return;
        }
        if (System.currentTimeMillis() - phaseSince > HOP_PREP_LIMIT_MS) {
            ban(step);
            replan(client, step.type() + " never lined up");
            return;
        }
        if (FailsafeManager.getCurrentSelectedSlot(client) != slot) {
            FailsafeManager.selectHotbarSlot(client, slot);
            return;
        }
        if (ether) {
            ClientUtils.setKeyMappingState(client.options.keyShift, true);
            player.setShiftKeyDown(true);
            if (++sneakTicks < SNEAK_SETTLE_TICKS) return;
        }
        if (!rotationStarted) {
            RotationUtils.Rotation aim = hopAim(client, step);
            if (aim == null) {
                ban(step);
                replan(client, "can't see the landing block of the " + step.type() + " to " + fmt(step.to()));
                return;
            }
            RotationManager.cancelRotation();
            RotationManager.rotateToExactYawPitch(client, aim.yaw, aim.pitch, 100L);
            rotationStarted = true;
            return;
        }
        if (RotationManager.isRotating()) return;
        launchPos = player.position();
        ClientUtils.performUseClickInstant();
        hopTries++;
        phase = Phase.HOP_WAIT;
        phaseSince = System.currentTimeMillis();
    }

    private void tickHopWait(Minecraft client) {
        Step step = steps.get(index);
        Vec3 pos = client.player.position();
        if (pos.distanceTo(launchPos) > TELEPORT_MOVED) {
            releaseSneak(client);
            Point here = here(client);
            double off = here.distance(step.to());
            if (off > LANDING_TOLERANCE) {
                CommissionLog.log(String.format("route: %s landed at %s, %.1f off the recorded %s", step.type(),
                        fmt(here), off, fmt(step.to())));
                ban(step);
                replan(client, "landed off the route");
                return;
            }
            next(client);
            return;
        }
        if (System.currentTimeMillis() - phaseSince > HOP_WAIT_MS) {
            if (hopTries < 2) {
                CommissionLog.log("route: " + step.type() + " didn't teleport, trying once more");
                phase = Phase.HOP_PREP;
                phaseSince = System.currentTimeMillis();
                rotationStarted = false;
                return;
            }
            releaseSneak(client);
            ban(step);
            replan(client, step.type() + " didn't teleport (out of mana or blocked?)");
        }
    }

    private void tickWarpWait(Minecraft client) {
        Step step = steps.get(index);
        if (client.player.position().distanceTo(launchPos) > 5.0 || here(client).distance(step.to()) < 3.0) {
            next(client);
            return;
        }
        if (System.currentTimeMillis() - phaseSince > WARP_WAIT_MS) {
            ban(step);
            replan(client, "/warp forge didn't move us");
        }
    }

    // aim from where we stand rather than replaying the recorded angles, which only fit the exact recorded spot
    private RotationUtils.Rotation hopAim(Minecraft client, Step step) {
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition();
        if (step.type() == EdgeType.AOTV) {
            Vec3 landingEye = new Vec3(step.to().x(), step.to().y() + player.getEyeHeight(), step.to().z());
            return RotationUtils.calculateLookAt(eye, landingEye);
        }
        BlockPos target = BlockPos.containing(step.to().x(), Math.floor(step.to().y()) - 1, step.to().z());
        double[][] offsets = {{0.5, 0.5, 0.5}, {0.5, 0.9, 0.5}, {0.2, 0.8, 0.2}, {0.8, 0.8, 0.8}, {0.2, 0.8, 0.8}, {0.8, 0.8, 0.2}};
        for (double[] o : offsets) {
            Vec3 point = new Vec3(target.getX() + o[0], target.getY() + o[1], target.getZ() + o[2]);
            if (hits(client, eye, point, target)) return RotationUtils.calculateLookAt(eye, point);
        }
        // the recording proves the warp works from its start, even if our ray clips some outline on the way
        if (here(client).horizontal(step.from()) <= RECORDED_AIM_DISTANCE) {
            return RotationUtils.calculateLookAt(eye, Vec3.atCenterOf(target));
        }
        return null;
    }

    private static boolean hits(Minecraft client, Vec3 eye, Vec3 point, BlockPos target) {
        Vec3 dir = point.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(61.0));
        BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private void arrive(Minecraft client) {
        releaseKeys(client);
        RotationManager.cancelRotation();
        phase = null;
        status = Status.ARRIVED;
        CommissionLog.log("route: reached " + goalName + " at " + fmt(here(client)));
    }

    private void fail(Minecraft client, String why) {
        releaseKeys(client);
        RotationManager.cancelRotation();
        phase = null;
        failure = why;
        status = Status.FAILED;
        CommissionLog.log("route: gave up, " + why);
    }

    private static void releaseKeys(Minecraft client) {
        if (client.options == null) return;
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        releaseSneak(client);
    }

    private static void releaseSneak(Minecraft client) {
        if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyShift, false);
        if (client.player != null) client.player.setShiftKeyDown(false);
    }

    private static int aotvSlot(Minecraft client) {
        int slot = GearManager.findHotbarItemSlot(client, "Aspect of the Void");
        return slot >= 0 ? slot : GearManager.findHotbarItemSlot(client, "Aspect of the End");
    }

    private static boolean isHop(EdgeType type) {
        return type == EdgeType.AOTV || type == EdgeType.ETHERWARP;
    }

    private static Point here(Minecraft client) {
        Vec3 p = client.player.position();
        return new Point(p.x, p.y, p.z);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static String fmt(Point p) {
        return String.format("%.1f %.1f %.1f", p.x(), p.y(), p.z());
    }

    private static String summary(List<Step> steps) {
        StringBuilder out = new StringBuilder();
        EdgeType last = null;
        int run = 0;
        for (Step s : steps) {
            if (s.type() == last) {
                run++;
                continue;
            }
            if (last != null) out.append(last.name().toLowerCase()).append(run > 1 ? " x" + run : "").append(", ");
            last = s.type();
            run = 1;
        }
        if (last != null) out.append(last.name().toLowerCase()).append(run > 1 ? " x" + run : "");
        return out.toString();
    }
}
