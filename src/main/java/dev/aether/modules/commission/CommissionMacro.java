package dev.aether.modules.commission;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.commission.route.RouteFollower;
import dev.aether.modules.commission.route.RouteGraph;
import dev.aether.modules.mining.BlockMiner;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

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
    private static final long TRAVEL_LOG_MS = 2000L;
    private static final long TRAVEL_STUCK_MS = 5000L;
    // etherwarp travel can stand still while it lines up the next warp
    private static final long ETHERWARP_STUCK_MS = 10_000L;
    private static final long ETHERWARP_PLAN_LIMIT_MS = 20_000L;
    private static final int MAX_TRAVEL_ATTEMPTS = 5;
    private static final double ROUTE_GOAL_RADIUS = 3.0;
    private static final double SPOT_ARRIVAL = 2.0;
    private static final long ZONE_LOG_MS = 30_000L;
    // tab progress refreshes about every 3s, so this many blocks with no gain means they are not counting
    private static final long NO_PROGRESS_MS = 20_000L;
    private static final int NO_PROGRESS_MIN_MINED = 15;

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
    private static double travelBest;
    private static long travelProgressAt;
    private static long travelLogAt;
    private static int travelAttempts;
    private static boolean travelToSpot;
    private static boolean travelByEtherwarp;
    private static boolean travelByRoute;
    private static boolean routeFailedThisTrip;
    private static final RouteFollower route = new RouteFollower();
    private static boolean nextLegWalks;
    private static boolean planReadyLogged;
    private static double lastProgress;
    private static long lastProgressAt;
    private static int minedAtProgress;
    private static long zoneLogAt;
    private static String lastConsidered = "";
    private static long lastConsideredAt;
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
        route.stop(client);
        BlockMiner.stopManaged(client);
        current = null;
        travelAttempts = 0;
        nextLegWalks = false;
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
            route.stop(client);
            BlockMiner.stopManaged(client);
            enter(Step.CHOOSING, "world changed");
        }
        if (CommissionClaimer.isBusy()) {
            route.pause(client);
            return;
        }

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
            if (task.kind() == CommissionTask.Kind.IGNORED) {
                considered.append(status).append(" (ignored); ");
                continue;
            }
            if (!task.isMining()) {
                unsupported = true;
                considered.append(status).append(" (not supported yet); ");
                continue;
            }
            if (skipUntil.containsKey(c.name())) {
                considered.append(status).append(" (skipped after a failure); ");
                continue;
            }
            double distance = task.area() == null || task.area().contains(feet) ? 0.0 : travelSeconds(client, task);
            considered.append(status).append(String.format(" %.0fs away; ", distance));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = task;
            }
        }
        String summary = considered.toString();
        if (!summary.equals(lastConsidered) || now - lastConsideredAt >= ZONE_LOG_MS) {
            lastConsidered = summary;
            lastConsideredAt = now;
            CommissionLog.log("choosing from: " + summary);
        }

        // ore mined inside an area also counts for the anywhere commissions, so one trip does both
        if (best != null && best.area() == null) {
            CommissionTask covering = null;
            double coveringDistance = Double.MAX_VALUE;
            for (Commission c : commissions) {
                CommissionTask task = CommissionTask.of(c.name());
                if (!task.isMining() || task.area() == null || skipUntil.containsKey(c.name())) continue;
                if (task.ores().stream().noneMatch(best.ores()::contains)) continue;
                double distance = task.area().contains(feet) ? 0.0 : travelSeconds(client, task);
                if (distance < coveringDistance) {
                    coveringDistance = distance;
                    covering = task;
                }
            }
            if (covering != null) {
                CommissionLog.log("doing " + covering.commission() + " instead of " + best.commission()
                        + ", mining there counts for both");
                best = covering;
                bestDistance = coveringDistance;
            }
        }

        if (best == null) {
            if (unsupported && now - lastUnsupportedNoticeAt > UNSUPPORTED_NOTICE_MS) {
                lastUnsupportedNoticeAt = now;
                ClientUtils.sendMessage("§eOnly combat or event commissions are left, and those aren't supported yet.", false);
            }
            return;
        }
        current = best;
        CommissionLog.log("chose " + best.commission() + (best.area() == null ? " (anywhere)"
                : String.format(" in %s, about %.0fs away", best.area().name(), bestDistance)));

        if (best.area() != null && !best.area().contains(feet)) {
            routeFailedThisTrip = false;
            startTravel(client, best);
        } else {
            startWorking(client, best);
        }
    }

    // seconds by the recorded routes, or a slow walking guess when none fit
    private static double travelSeconds(Minecraft client, CommissionTask task) {
        BlockPos anchor = task.area().anchor();
        var from = client.player.position();
        RouteGraph.Plan plan = RouteGraph.dwarvenMines().plan(new RouteGraph.Point(from.x, from.y, from.z),
                new RouteGraph.Point(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5), ROUTE_GOAL_RADIUS,
                RouteFollower.availableMoves(client), java.util.Set.of());
        return plan != null ? plan.seconds() : Math.sqrt(client.player.blockPosition().distSqr(anchor)) / 3.0;
    }

    private static void startTravel(Minecraft client, CommissionTask task) {
        BlockPos goal = task.area().anchor();
        if (!routeFailedThisTrip) {
            RouteGraph.Point target = new RouteGraph.Point(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);
            double radius = travelToSpot ? SPOT_ARRIVAL : ROUTE_GOAL_RADIUS;
            travelBest = Math.sqrt(client.player.blockPosition().distSqr(goal));
            travelLogAt = System.currentTimeMillis();
            if (route.start(client, target, radius, task.area().name())) {
                travelByRoute = true;
                enter(Step.TRAVELING, "recorded route to " + task.area().name());
                return;
            }
            routeFailedThisTrip = true;
            CommissionLog.log("no recorded route fits (" + route.failure() + "), using the pathfinder");
        }
        travelByRoute = false;
        AtomicInteger result = new AtomicInteger(WALK_PENDING);
        walkResult = result;
        travelBest = Math.sqrt(client.player.blockPosition().distSqr(goal));
        travelProgressAt = System.currentTimeMillis();
        travelLogAt = travelProgressAt;
        boolean hasEtherwarp = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) >= 0;
        // after a walk stalls, etherwarp from the stall point; after etherwarp planning runs long, walk instead
        travelByEtherwarp = hasEtherwarp && !nextLegWalks;
        nextLegWalks = false;
        planReadyLogged = false;
        CommissionLog.log(String.format("%s to %s at %s, %.0f blocks (attempt %d)",
                travelByEtherwarp ? "etherwarping and walking" : hasEtherwarp
                        ? "walking this leg (etherwarp stalled last time)" : "walking (no etherwarp aotv in the hotbar)",
                task.area().name(), goal.toShortString(), travelBest, travelAttempts + 1));
        enter(Step.TRAVELING, "travel to " + task.area().name());
        Runnable done = () -> result.compareAndSet(WALK_PENDING, WALK_DONE);
        Runnable failed = () -> result.compareAndSet(WALK_PENDING, WALK_FAILED);
        if (travelByEtherwarp) {
            PathfindingManager.startConfiguredEtherwarp(client, goal.getX(), goal.getY(), goal.getZ(), done, failed);
        } else {
            PathfindingManager.startConfiguredWalk(client, goal.getX(), goal.getY(), goal.getZ(), done, failed,
                    true, 1.0, false, true);
        }
    }

    private static void tickTraveling(Minecraft client) {
        if (travelByRoute) {
            tickRoute(client);
            return;
        }
        CommissionTask task = current;
        BlockPos feet = client.player.blockPosition();
        long now = System.currentTimeMillis();
        double remaining = Math.sqrt(feet.distSqr(task.area().anchor()));
        int result = walkResult.get();
        boolean arrived = travelToSpot ? remaining <= SPOT_ARRIVAL : task.area().contains(feet);
        if (result == WALK_DONE || arrived) {
            if (result != WALK_DONE) PathfindingManager.stop();
            CommissionLog.log(String.format("reached %s at %s (%.0f from its spot) after %dms, sidebar zone %s",
                    task.area().name(), feet.toShortString(), remaining, now - stepStartedAt, sidebarZone()));
            travelAttempts = 0;
            travelToSpot = false;
            startWorking(client, task);
            return;
        }
        // the walker also plans while replanning mid-walk, which must still count toward being stuck
        boolean planning = PathfindingManager.isSearching() && !planReadyLogged;
        if (planning) {
            // planning time is not being stuck; restart the stall clock once it moves
            travelProgressAt = now;
            if (travelByEtherwarp && now - stepStartedAt > ETHERWARP_PLAN_LIMIT_MS) {
                CommissionLog.log("etherwarp route to " + task.area().name() + " still planning after "
                        + (now - stepStartedAt) + "ms, walking this leg instead");
                PathfindingManager.stop();
                nextLegWalks = true;
                startTravel(client, task);
                return;
            }
        } else if (!planReadyLogged) {
            planReadyLogged = true;
            CommissionLog.log("route ready after " + (now - stepStartedAt) + "ms, moving");
        }
        if (now - travelLogAt >= TRAVEL_LOG_MS) {
            travelLogAt = now;
            CommissionLog.log(String.format("%s: at %s, %.0f blocks to %s (best %.0f), pathfinder %s, %dms in",
                    travelByEtherwarp ? "etherwarp" : "walking", feet.toShortString(), remaining, task.area().name(),
                    travelBest, planning ? "planning" : PathfindingManager.isNavigating() ? "moving" : "idle",
                    now - stepStartedAt));
        }
        if (remaining <= travelBest - 1.0) {
            travelBest = remaining;
            travelProgressAt = now;
        }
        String problem = null;
        if (result == WALK_FAILED) problem = "no complete walkable path";
        else if (now - travelProgressAt > (travelByEtherwarp ? ETHERWARP_STUCK_MS : TRAVEL_STUCK_MS)) {
            problem = "no progress for " + (now - travelProgressAt) + "ms";
        }
        else if (now - stepStartedAt > TRAVEL_TIMEOUT_MS) problem = "timed out";
        if (problem == null) return;

        if (result != WALK_FAILED) PathfindingManager.stop();
        travelAttempts++;
        CommissionLog.log(String.format("%s to %s failed at %s: %s, %.0f blocks left", travelByEtherwarp
                ? "etherwarp" : "walk", task.area().name(), feet.toShortString(), problem, remaining));
        CommissionLog.log("  around the player: " + describeSurroundings(client));
        if (travelAttempts < MAX_TRAVEL_ATTEMPTS) {
            if (!travelByEtherwarp && GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client) >= 0) {
                CommissionLog.log("trying etherwarp from here, past where walking stalled");
            } else if (travelByEtherwarp) {
                nextLegWalks = true;
            }
            startTravel(client, task);
            return;
        }
        CommissionLog.log("giving up on " + task.commission() + " for now after " + travelAttempts + " attempts");
        travelAttempts = 0;
        travelToSpot = false;
        skipUntil.put(task.commission(), now + SKIP_AFTER_FAILURE_MS);
        enter(Step.CHOOSING, "travel failed");
    }

    private static void tickRoute(Minecraft client) {
        CommissionTask task = current;
        BlockPos feet = client.player.blockPosition();
        long now = System.currentTimeMillis();
        double remaining = Math.sqrt(feet.distSqr(task.area().anchor()));
        boolean arrived = travelToSpot ? remaining <= SPOT_ARRIVAL : task.area().contains(feet);
        if (arrived) {
            route.stop(client);
            CommissionLog.log(String.format("reached %s at %s by recorded route after %dms, sidebar zone %s",
                    task.area().name(), feet.toShortString(), now - stepStartedAt, sidebarZone()));
            travelAttempts = 0;
            travelToSpot = false;
            startWorking(client, task);
            return;
        }
        route.tick(client);
        if (now - travelLogAt >= TRAVEL_LOG_MS) {
            travelLogAt = now;
            CommissionLog.log(String.format("route: at %s, %.0f blocks to %s, %dms in", feet.toShortString(),
                    remaining, task.area().name(), now - stepStartedAt));
        }
        RouteFollower.Status status = route.status();
        if (status == RouteFollower.Status.RUNNING && now - stepStartedAt <= TRAVEL_TIMEOUT_MS) return;
        String why = status == RouteFollower.Status.ARRIVED ? "route ended " + String.format("%.0f", remaining)
                + " blocks short of the area" : status == RouteFollower.Status.FAILED ? route.failure() : "timed out";
        route.stop(client);
        routeFailedThisTrip = true;
        CommissionLog.log("recorded route to " + task.area().name() + " didn't get there (" + why + "), using the pathfinder");
        startTravel(client, task);
    }

    private static void startWorking(Minecraft client, CommissionTask task) {
        boolean started = BlockMiner.startManaged(client, task.ores(), task.area());
        if (!started) {
            stop(client, "miner could not start", true);
            return;
        }
        Commission now = find(CommissionClaimer.currentCommissions(client), task.commission());
        lastProgress = now == null ? 0.0 : now.progress();
        lastProgressAt = System.currentTimeMillis();
        minedAtProgress = BlockMiner.getBlocksMined();
        zoneLogAt = lastProgressAt;
        enter(Step.WORKING, "mining for " + task.commission());
    }

    private static void tickWorking(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastReviewAt < REVIEW_INTERVAL_MS) return;
        lastReviewAt = now;

        Commission mine = find(CommissionClaimer.currentCommissions(client), current.commission());
        if (mine == null || mine.done()) {
            CommissionLog.log(current.commission() + (mine == null ? " is gone from the tab list" : " is DONE"));
            BlockMiner.stopManaged(client);
            if (mine != null) CommissionClaimer.requestClaim(current.commission() + " finished");
            enter(Step.CHOOSING, "commission finished");
            return;
        }
        if (now - zoneLogAt >= ZONE_LOG_MS) {
            zoneLogAt = now;
            CommissionLog.log(String.format("working on %s: %.1f%%, at %s, sidebar zone %s, mined %d",
                    current.commission(), mine.progress() * 100, client.player.blockPosition().toShortString(),
                    sidebarZone(), BlockMiner.getBlocksMined()));
        }
        if (mine.progress() > lastProgress) {
            lastProgress = mine.progress();
            lastProgressAt = now;
            minedAtProgress = BlockMiner.getBlocksMined();
        } else if (now - lastProgressAt > NO_PROGRESS_MS
                && BlockMiner.getBlocksMined() - minedAtProgress >= NO_PROGRESS_MIN_MINED) {
            CommissionLog.log(String.format("%s stuck at %.1f%% for %ds while %d blocks were mined at %s (zone %s), "
                    + "going back to the area's spot", current.commission(), mine.progress() * 100,
                    (now - lastProgressAt) / 1000, BlockMiner.getBlocksMined() - minedAtProgress,
                    client.player.blockPosition().toShortString(), sidebarZone()));
            BlockMiner.stopManaged(client);
            if (current.area() != null) {
                travelToSpot = true;
                routeFailedThisTrip = false;
                startTravel(client, current);
            } else {
                enter(Step.CHOOSING, "anywhere mining was not counting");
            }
            return;
        }
        if (!BlockMiner.isEnabled()) {
            CommissionLog.log("miner stopped on its own while on " + current.commission() + ", restarting it");
            startWorking(client, current);
        }
    }

    // what is in front of the player at foot and head height, to see what the walker ran into
    private static String describeSurroundings(Minecraft client) {
        var player = client.player;
        var level = client.level;
        BlockPos feet = player.blockPosition();
        var facing = player.getDirection();
        BlockPos ahead = feet.relative(facing);
        return String.format("facing %s, onGround %s, horizontalCollision %s, feet %s, head %s, ahead feet %s, "
                        + "ahead head %s, ahead above %s, below %s, ahead below %s",
                facing.getName(), player.onGround(), player.horizontalCollision,
                blockName(level, feet), blockName(level, feet.above()),
                blockName(level, ahead), blockName(level, ahead.above()), blockName(level, ahead.above(2)),
                blockName(level, feet.below()), blockName(level, ahead.below()));
    }

    private static String blockName(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    private static Commission find(List<Commission> commissions, String name) {
        for (Commission c : commissions) {
            if (c.name().equals(name)) return c;
        }
        return null;
    }

    // the sidebar names the part of the mines the player is standing in
    private static String sidebarZone() {
        for (String line : ClientUtils.getSidebarLines()) {
            String text = line.strip();
            for (var area : CommissionTask.AREAS) {
                if (text.endsWith(area.name())) return area.name();
            }
        }
        return "unknown";
    }

    private static void enter(Step next, String reason) {
        CommissionLog.log("-> " + next + " (" + reason + ")");
        step = next;
        stepStartedAt = System.currentTimeMillis();
        lastReviewAt = 0L;
    }
}
