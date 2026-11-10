package dev.aether.modules.commission;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.mining.BlockMiner;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// does dwarven commissions: picks the quickest one, gets to its area, mines it, claims it, repeats
public final class CommissionMacro {
    private static final long REVIEW_INTERVAL_MS = 1000L;
    private static final long TRAVEL_TIMEOUT_MS = 90_000L;
    private static final long SKIP_AFTER_FAILURE_MS = 120_000L;
    private static final long UNSUPPORTED_NOTICE_MS = 30_000L;

    private enum Step { CHOOSING, TRAVELING, WORKING }

    private static final int WALK_PENDING = 0;
    private static final int WALK_DONE = 1;
    private static final int WALK_FAILED = 2;

    private static boolean running;
    private static boolean pendingStart;
    private static Step step = Step.CHOOSING;
    private static CommissionTask current;
    private static long stepStartedAt;
    private static long lastReviewAt;
    private static long lastUnsupportedNoticeAt;
    private static int levelIdentity;
    private static AtomicInteger walkResult = new AtomicInteger(WALK_PENDING);
    private static final Map<String, Long> skipUntil = new HashMap<>();

    private CommissionMacro() {}

    public static boolean isRunning() {
        return running;
    }

    public static boolean isRunningOrPending() {
        return running || pendingStart;
    }

    public static void toggle() {
        if (isRunningOrPending()) {
            stop(Minecraft.getInstance(), "toggled off", true);
        } else {
            requestStart();
        }
    }

    // starts once no screen is open, so turning it on from the aether menu or chat doesn't stop it straight away
    public static void requestStart() {
        if (running) return;
        if (MacroStateManager.getCurrentState() != MacroState.State.OFF) {
            ClientUtils.sendMessage("§cStop the running macro before starting Dwarven Commissions.", false);
            return;
        }
        pendingStart = true;
        CommissionLog.log("start requested, waiting for screens to close");
        ClientUtils.sendMessage("§aDwarven Commissions will start when you close this screen.", false);
    }

    public static void stopForMacro(Minecraft client) {
        stop(client, "macro stop", false);
    }

    private static void stop(Minecraft client, String reason, boolean announce) {
        boolean wasRunning = running;
        pendingStart = false;
        if (!wasRunning) return;
        running = false;
        CommissionLog.log("stopped (" + reason + ")");
        if (step == Step.TRAVELING) PathfindingManager.stop();
        BlockMiner.stopManaged(client);
        current = null;
        skipUntil.clear();
        if (MacroStateManager.getCurrentState() == MacroState.State.COMMISSIONS) {
            MacroStateManager.setCurrentState(MacroState.State.OFF);
        }
        if (announce) ClientUtils.sendMessage("§eDwarven Commissions stopped.", false);
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        if (pendingStart) {
            if (client.gui.screen() != null) return;
            pendingStart = false;
            begin(client);
            return;
        }
        if (!running) return;

        if (System.identityHashCode(client.level) != levelIdentity) {
            // a warp or server swap moves us to a new level; carry on there rather than stopping
            CommissionLog.log("world changed, choosing again");
            levelIdentity = System.identityHashCode(client.level);
            if (step == Step.TRAVELING) PathfindingManager.stop();
            BlockMiner.stopManaged(client);
            enter(Step.CHOOSING, "world changed");
        }
        if (CommissionClaimer.isBusy()) return;

        skipUntil.values().removeIf(until -> until <= System.currentTimeMillis());
        switch (step) {
            case CHOOSING -> choose(client);
            case TRAVELING -> tickTraveling(client);
            case WORKING -> tickWorking(client);
        }
    }

    private static void begin(Minecraft client) {
        if (MacroStateManager.getCurrentState() != MacroState.State.OFF) {
            ClientUtils.sendMessage("§cStop the running macro before starting Dwarven Commissions.", false);
            return;
        }
        running = true;
        levelIdentity = System.identityHashCode(client.level);
        skipUntil.clear();
        MacroStateManager.setCurrentState(MacroState.State.COMMISSIONS);
        CommissionLog.log("started at " + client.player.blockPosition().toShortString()
                + ", location " + ClientUtils.getCurrentLocation());
        ClientUtils.sendMessage("§aDwarven Commissions started.", false);
        enter(Step.CHOOSING, "start");
    }

    private static void choose(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastReviewAt < REVIEW_INTERVAL_MS) return;
        lastReviewAt = now;

        List<Commission> commissions = CommissionClaimer.currentCommissions(client);
        if (commissions.isEmpty()) {
            CommissionLog.log("no commissions in the tab list yet (location " + ClientUtils.getCurrentLocation() + ")");
            return;
        }
        if (commissions.stream().anyMatch(Commission::done)) {
            CommissionClaimer.requestClaim("a commission is DONE");
            return;
        }

        BlockPos feet = client.player.blockPosition();
        CommissionTask best = null;
        double bestDistance = Double.MAX_VALUE;
        StringBuilder considered = new StringBuilder();
        boolean unsupported = false;
        for (Commission c : commissions) {
            CommissionTask task = CommissionTask.of(c.name());
            String status = c.name() + " " + Math.round(c.progress() * 100) + "% " + task.kind();
            if (!task.isMining()) {
                unsupported = true;
                considered.append(status).append(" (not supported yet); ");
                continue;
            }
            if (skipUntil.containsKey(c.name())) {
                considered.append(status).append(" (skipped after a failure); ");
                continue;
            }
            double distance = task.area() == null ? 0.0 : Math.sqrt(feet.distSqr(task.area().anchor()));
            considered.append(status).append(String.format(" %.0f away; ", distance));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = task;
            }
        }
        CommissionLog.log("choosing from: " + considered);

        if (best == null) {
            if (unsupported && now - lastUnsupportedNoticeAt > UNSUPPORTED_NOTICE_MS) {
                lastUnsupportedNoticeAt = now;
                ClientUtils.sendMessage("§eOnly combat or event commissions are left, and those aren't supported yet.", false);
            }
            return;
        }
        current = best;
        CommissionLog.log("chose " + best.commission() + (best.area() == null ? " (anywhere)"
                : String.format(" in %s, %.0f blocks away", best.area().name(), bestDistance)));

        if (best.area() != null && bestDistance > best.area().radius()) {
            startTravel(client, best);
        } else {
            startWorking(client, best);
        }
    }

    private static void startTravel(Minecraft client, CommissionTask task) {
        BlockPos goal = task.area().anchor();
        AtomicInteger result = new AtomicInteger(WALK_PENDING);
        walkResult = result;
        CommissionLog.log("walking to " + task.area().name() + " at " + goal.toShortString());
        enter(Step.TRAVELING, "travel to " + task.area().name());
        PathfindingManager.startConfiguredWalk(client, goal.getX(), goal.getY(), goal.getZ(),
                () -> result.compareAndSet(WALK_PENDING, WALK_DONE),
                () -> result.compareAndSet(WALK_PENDING, WALK_FAILED),
                true, 1.5, false, true);
    }

    private static void tickTraveling(Minecraft client) {
        CommissionTask task = current;
        double remaining = Math.sqrt(client.player.blockPosition().distSqr(task.area().anchor()));
        int result = walkResult.get();
        if (result == WALK_DONE || remaining <= task.area().radius() * 0.5) {
            if (result != WALK_DONE) PathfindingManager.stop();
            CommissionLog.log(String.format("reached %s (%.0f from its centre) after %dms",
                    task.area().name(), remaining, System.currentTimeMillis() - stepStartedAt));
            startWorking(client, task);
            return;
        }
        if (result == WALK_FAILED || System.currentTimeMillis() - stepStartedAt > TRAVEL_TIMEOUT_MS) {
            CommissionLog.log("could not get to " + task.area().name() + " (" + (result == WALK_FAILED
                    ? "no walkable path" : "timed out") + String.format(", %.0f away), skipping it for now", remaining));
            if (result != WALK_FAILED) PathfindingManager.stop();
            skipUntil.put(task.commission(), System.currentTimeMillis() + SKIP_AFTER_FAILURE_MS);
            enter(Step.CHOOSING, "travel failed");
        }
    }

    private static void startWorking(Minecraft client, CommissionTask task) {
        CommissionTask.Area area = task.area();
        boolean started = BlockMiner.startManaged(client, task.ores(),
                area == null ? null : area.anchor(), area == null ? 0 : area.radius());
        if (!started) {
            stop(client, "miner could not start", true);
            return;
        }
        enter(Step.WORKING, "mining for " + task.commission());
    }

    private static void tickWorking(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastReviewAt < REVIEW_INTERVAL_MS) return;
        lastReviewAt = now;

        List<Commission> commissions = CommissionClaimer.currentCommissions(client);
        Commission mine = commissions.stream().filter(c -> c.name().equals(current.commission())).findFirst().orElse(null);
        if (mine == null || mine.done()) {
            CommissionLog.log(current.commission() + (mine == null ? " is gone from the tab list" : " is DONE"));
            BlockMiner.stopManaged(client);
            if (mine != null) CommissionClaimer.requestClaim(current.commission() + " finished");
            enter(Step.CHOOSING, "commission finished");
            return;
        }
        if (!BlockMiner.isEnabled()) {
            CommissionLog.log("miner stopped on its own while on " + current.commission() + ", restarting it");
            startWorking(client, current);
        }
    }

    private static void enter(Step next, String reason) {
        CommissionLog.log("-> " + next + " (" + reason + ")");
        step = next;
        stepStartedAt = System.currentTimeMillis();
        lastReviewAt = 0L;
    }
}
