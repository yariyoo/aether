package dev.aether.modules.dwarven.nav;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// gets the player to a far destination with aether's pathfinder, in short legs along ground it has walked before
public final class AreaNavigator {
    public enum Status { IDLE, RUNNING, ARRIVED, FAILED }

    public static final String FORGE_WARP = "@forge warp";
    private static final double LEG = 24.0;
    private static final double JOIN = 12.0;
    private static final double LEG_REACHED = 3.0;
    private static final long PLAN_GRACE_MS = 8000L;
    private static final long WARP_WAIT_MS = 6000L;
    private static final double WARP_MOVED = 20.0;
    private static final long WARP_OVERHEAD_SECONDS = 4;
    private static final double WALK_SPEED = 5.0;
    private static final double UNKNOWN_GROUND_FACTOR = 1.5;
    private static final double ETHERWARP_MIN_DISTANCE = 30.0;

    private Status status = Status.IDLE;
    private BlockPos goal;
    private String label;
    private double arriveWithin;
    private List<BlockPos> legs = List.of();
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
        double trail = AetherConfig.DWARVEN_LEARN_TRAILS.get() ? DwarvenStore.TRAILS.routeLength(from, to, JOIN) : -1;
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
        BlockPos forge = forgeLanding(feet);
        if (forge != null && AetherConfig.DWARVEN_FORGE_WARP.get()
                && WARP_OVERHEAD_SECONDS + walkSeconds(forge, goal) + 5 < walkSeconds(feet, goal)) {
            DwarvenLog.log("nav: the forge is the quicker way to " + label + ", warping");
            warp(client);
            return;
        }
        plan(client);
    }

    public void stop(Minecraft client) {
        if (status == Status.RUNNING && !warping) PathfindingManager.stop();
        warping = false;
        if (status == Status.RUNNING) status = Status.IDLE;
    }

    private void plan(Minecraft client) {
        BlockPos feet = client.player.blockPosition();
        List<BlockPos> route = new ArrayList<>();
        if (AetherConfig.DWARVEN_LEARN_TRAILS.get()) route.addAll(DwarvenStore.TRAILS.route(feet, goal, JOIN, LEG));
        boolean known = !route.isEmpty();
        if (route.isEmpty() || route.get(route.size() - 1).distSqr(goal) > 4) route.add(goal);
        legs = route;
        legAt = 0;
        DwarvenLog.log(String.format("nav: to %s at %s, %.0f blocks, %s", label, goal.toShortString(),
                Math.sqrt(feet.distSqr(goal)), known ? (legs.size() + " legs along known ground") : "no known trail, pathfinding straight there"));
        startLeg(client);
    }

    private void startLeg(Minecraft client) {
        BlockPos to = legs.get(legAt);
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
        if (Math.sqrt(feet.distSqr(goal)) <= arriveWithin) {
            PathfindingManager.stop();
            status = Status.ARRIVED;
            DwarvenLog.log("nav: reached " + label + " at " + feet.toShortString());
            return;
        }
        BlockPos to = legs.get(legAt);
        double left = Math.sqrt(feet.distSqr(to));
        boolean lastLeg = legAt == legs.size() - 1;
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
        if (!warpTried && AetherConfig.DWARVEN_FORGE_WARP.get()) {
            DwarvenLog.log("nav: out of retries, warping to the forge to come at it from there");
            warp(client);
            return;
        }
        failure = why;
        status = Status.FAILED;
    }

    private void warp(Minecraft client) {
        warpTried = true;
        warping = true;
        warpFrom = client.player.blockPosition();
        warpAt = System.currentTimeMillis();
        DwarvenStore.TRAILS.interrupted();
        ClientUtils.sendCommand("/warp forge");
    }

    private void tickWarp(Minecraft client, BlockPos feet, long now) {
        if (Math.sqrt(feet.distSqr(warpFrom)) > WARP_MOVED) {
            warping = false;
            DwarvenStore.AREAS.observe(FORGE_WARP, feet);
            DwarvenStore.TRAILS.interrupted();
            attempts = 0;
            plan(client);
            return;
        }
        if (now - warpAt > WARP_WAIT_MS) {
            warping = false;
            // we may already be standing at the forge, in which case the warp moves us nowhere
            DwarvenLog.log("nav: /warp forge didn't move us");
            attempts = 0;
            plan(client);
        }
    }
}
