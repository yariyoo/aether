package dev.aether.modules.mining;

import dev.aether.modules.commission.CommissionClaimer;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Random;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// mines the chosen blocks in reach, then walks to the nearest vein and repeats
public final class BlockMiner {
    private static final String[] TOOL_NAMES = {"drill", "pickaxe", "gauntlet", "pickonimbus"};
    private static final long AIM_TIMEOUT_MS = 1500L;
    private static final long PATH_TIMEOUT_MS = 30_000L;
    private static final long RESCAN_INTERVAL_MS = 1000L;
    private static final long SKIP_BLOCK_MS = 15_000L;
    private static final long SKIP_VEIN_MS = 30_000L;
    private static final long SKIP_DECORATION_MS = 300_000L;
    private static final long PATH_PROGRESS_LOG_MS = 2000L;
    private static final long STUCK_AFTER_MS = 3000L;
    private static final int MAX_PLANS_KEPT = 6;
    private static final int TIMEOUTS_BEFORE_LEAVING_SPOT = 2;
    private static final long MINING_LOG_MS = 1000L;
    private static final double STUCK_PROGRESS = 1.0;
    private static final double CLOSE_ENOUGH = 1.6;
    private static final long SKIP_SPOT_MS = 120_000L;
    private static final int OPPORTUNISTIC_CHECK_TICKS = 5;
    private static final int REGEN_MIN_MINED = 5;
    private static final long REGEN_WAIT_MS = 8000L;
    // spots scoring within this share of the best are treated as equal and picked between at random
    private static final double TIE_SHARE = 0.9;
    private static final double CONTINUE_VEIN_SHARE = 0.4;
    private static final long NO_VEIN_NOTICE_MS = 5000L;
    private static final int CANDIDATE_LIMIT = 4000;
    private static final int MAX_VEINS_RANKED = 16;
    private static final int DIAGNOSTIC_RADIUS = 6;

    private enum Phase { SEARCHING, AIMING, MINING, SCANNING, PATHING, REGEN_WAIT, PICKOBULUS }

    private static final int PATH_PENDING = 0;
    private static final int PATH_DONE = 1;
    private static final int PATH_FAILED = 2;

    private static boolean enabled;
    private static Phase phase = Phase.SEARCHING;
    private static MiningTargetSelector.Target target;
    private static long phaseStartedAt;
    private static long lastScanAt;
    private static long noVeinSince;
    private static boolean noVeinNoticeSent;
    private static int levelIdentity;
    private static int blocksMined;
    private static long totalMined;
    private static List<BlockPos> pathVein = List.of();
    private static BlockPos pathGoal;
    private static BlockPos pathFor;
    private static boolean justArrived;
    private static double bestGoalDistance;
    private static long lastProgressAt;
    private static int pathTicks;
    private static int minedHere;
    private static BlockPos lastMined;
    private static int timeoutsHere;
    private static long lastMiningLogAt;
    private static final Map<BlockPos, Long> badSpots = new HashMap<>();
    private static List<MiningSpotPlanner.Plan> fallbackPlans = new ArrayList<>();
    private static final Random random = new Random();
    private static long lastPathLogAt;
    private static AtomicInteger pathResult = new AtomicInteger(PATH_PENDING);
    private static final Map<BlockPos, Long> skipUntil = new HashMap<>();
    private static final MiningBlockIndex index = new MiningBlockIndex();
    private static Set<MiningBlockKind> kindsOverride;
    private static MiningArea area;
    private static final long SKIP_UNBREAKABLE_MS = 600_000L;
    private static final int UNBREAKABLE_RADIUS = 2;
    private static final long PICKOBULUS_CONFIRM_MS = 1500L;
    private static final long PICKOBULUS_BACKOFF_MS = 60_000L;
    private static final int TAB_CHECK_TICKS = 20;
    // once enough breaks are timed, a block taking this many times the usual is treated as not breaking
    private static final int BREAK_SAMPLES = 20;
    private static final int BREAK_SAMPLES_NEEDED = 5;
    private static final double SLOW_BREAK_FACTOR = 4.0;
    private static final long MIN_ADAPTIVE_TIMEOUT_MS = 1500L;
    private static final double TITANIUM_SPOT_WEIGHT = 4.0;
    private static final java.util.ArrayDeque<Long> breakTimes = new java.util.ArrayDeque<>();
    private static MiningPickobulus.Blast blast;
    private static boolean pickobulusClicked;
    private static int ticks;
    private static boolean sneakHeld;
    private static boolean redigNextTick;
    // set when the current spot ran dry: try another spot on the vein first, and only wait for regen if none
    private static boolean regenFallback;
    private static final long LEAVE_DRY_SPOT_MS = 15_000L;
    private static final int MIN_ORE_TO_MOVE = 2;
    private static BlockPos exploringSpot;
    private static final int SAVE_SPOTS_EVERY = 25;

    private BlockMiner() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getBlocksMined() {
        return blocksMined;
    }

    // never reset between runs, so a macro that restarts the miner can still count a whole session
    public static long getTotalMined() {
        return totalMined;
    }

    public static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, true, "toggled off");
        } else {
            start(client);
        }
    }

    // started by another macro: it owns the macro state, picks the ores and keeps the miner inside an area
    public static boolean startManaged(Minecraft client, Set<MiningBlockKind> kinds, MiningArea standArea) {
        if (client.player == null || client.level == null) return false;
        if (enabled) stop(client, false, "restarting for a new target");
        if (!selectMiningTool(client)) {
            MinerLog.log("managed start refused: no mining tool in hotbar");
            ClientUtils.sendMessage("§cNo pickaxe, drill or gauntlet found in hotbar.", false);
            return false;
        }
        kindsOverride = Set.copyOf(kinds);
        area = standArea;
        beginRun(client, "managed: kinds " + kinds + ", area " + (standArea == null ? "anywhere"
                : standArea.name() + " " + MinerLog.pos(standArea.min()) + " to " + MinerLog.pos(standArea.max())));
        return true;
    }

    public static void stopManaged(Minecraft client) {
        stop(client, false, "managed stop");
    }

    public static void start(Minecraft client) {
        if (enabled || client.player == null || client.level == null) return;
        MinerLog.log("start requested at " + MinerLog.pos(client.player.blockPosition())
                + ", location " + ClientUtils.getCurrentLocation()
                + ", state " + MacroStateManager.getCurrentState());
        if (MacroStateManager.getCurrentState() != MacroState.State.OFF) {
            MinerLog.log("start refused: another macro is running");
            ClientUtils.sendMessage("§cStop the running macro before starting the miner.", false);
            return;
        }
        if (!selectMiningTool(client)) {
            MinerLog.log("start refused: no mining tool in hotbar");
            ClientUtils.sendMessage("§cNo pickaxe, drill or gauntlet found in hotbar.", false);
            return;
        }
        if (allowedKinds().isEmpty()) {
            MinerLog.log("start refused: every block type is disabled");
            ClientUtils.sendMessage("§cEnable at least one block type in the Block Miner settings.", false);
            return;
        }
        kindsOverride = null;
        area = null;
        beginRun(client, "manual");
        MacroStateManager.setCurrentState(MacroState.State.MINING);
        ClientUtils.sendMessage("§aBlock miner started.", false);
    }

    private static void beginRun(Minecraft client, String mode) {
        Set<MiningBlockKind> allowed = allowedKinds();
        enabled = true;
        blocksMined = 0;
        minedHere = 0;
        regenFallback = false;
        redigNextTick = false;
        breakTimes.clear();
        exploringSpot = null;
        lastMined = null;
        skipUntil.clear();
        badSpots.clear();
        levelIdentity = System.identityHashCode(client.level);
        index.reset();
        MinerLog.log("started (" + mode + "): allowed " + allowed
                + ", prefer titanium " + preferTitanium()
                + ", sneak " + AetherConfig.MINER_SNEAK.get() + ", pickobulus " + AetherConfig.MINER_PICKOBULUS.get()
                + ", render distance " + client.options.getEffectiveRenderDistance()
                + ", reach " + String.format("%.2f", reach(client))
                + ", tool slot " + FailsafeManager.getCurrentSelectedSlot(client)
                + " (" + client.player.getMainHandItem().getHoverName().getString() + ")");
        logSurroundings(client);
        enterSearching("start");
    }

    public static void stopForMacro(Minecraft client) {
        stop(client, false, "macro stop");
    }

    private static void stop(Minecraft client, boolean announce, String reason) {
        if (!enabled) return;
        enabled = false;
        MinerLog.log("stopped (" + reason + "), mined " + blocksMined + " blocks");
        MiningSpotStore.save();
        if (phase == Phase.PATHING) PathfindingManager.stop();
        target = null;
        pathVein = List.of();
        skipUntil.clear();
        index.reset();
        if (client != null) client.execute(() -> {
            releaseAttack(client);
            holdSneak(client, false);
        });
        RotationManager.cancelRotation();
        if (MacroStateManager.getCurrentState() == MacroState.State.MINING) {
            MacroStateManager.setCurrentState(MacroState.State.OFF);
        }
        kindsOverride = null;
        area = null;
        if (announce) {
            ClientUtils.sendMessage("§eBlock miner stopped. Mined " + blocksMined + " blocks.", false);
        }
    }

    public static void update() {
        if (!enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        if (System.identityHashCode(client.level) != levelIdentity) {
            stop(client, true, "world changed");
            return;
        }
        if (CommissionClaimer.isBusy()) {
            releaseAttack(client);
            return;
        }
        if (client.gui.screen() != null && phase != Phase.PATHING) {
            releaseAttack(client);
            return;
        }

        if (++ticks % TAB_CHECK_TICKS == 0) PickobulusTracker.onTab(TablistUtils.getTabLines(client));
        // sneaking keeps us from stepping off ledges while turning between blocks, like the recorded runs
        holdSneak(client, AetherConfig.MINER_SNEAK.get() && phase != Phase.PATHING && phase != Phase.SCANNING);
        index.tick(client);
        pruneSkips();
        LiveMiningView view = new LiveMiningView(client.level, client.player);
        switch (phase) {
            case SEARCHING -> tickSearching(client, view);
            case AIMING -> tickAiming(client, view);
            case MINING -> tickMining(client, view);
            case SCANNING -> tickScanning(client, view);
            case PATHING -> tickPathing(client, view);
            case REGEN_WAIT -> tickRegenWait(client, view);
            case PICKOBULUS -> tickPickobulus(client, view);
        }
    }

    private static void tickSearching(Minecraft client, MiningView view) {
        releaseAttack(client);
        MiningTargetSelector.Target found = MiningTargetSelector.select(view,
                client.player.getEyePosition(), client.player.getViewVector(1.0f), reach(client),
                allowedKinds(), preferTitanium(), skipUntil.keySet());
        if (found == null) {
            MinerLog.log("nothing visible in reach from " + MinerLog.pos(client.player.blockPosition()));
            if (justArrived && pathFor != null) {
                MinerLog.log("arrived but still cannot reach " + MinerLog.pos(pathFor) + ", skipping it");
                skip(pathFor, SKIP_BLOCK_MS);
            }
            justArrived = false;
            if (exploringSpot != null && exploringSpot.distSqr(client.player.blockPosition()) <= 4) {
                MinerLog.log("remembered spot " + MinerLog.pos(exploringSpot) + " is empty for now");
                MiningSpotStore.get().recordEmpty(exploringSpot, System.currentTimeMillis());
                exploringSpot = null;
            }
            if (minedHere >= REGEN_MIN_MINED) {
                MinerLog.log("spot gave " + minedHere + " blocks and is dry, looking for another spot on the vein");
                regenFallback = true;
                badSpots.put(client.player.blockPosition(), System.currentTimeMillis() + LEAVE_DRY_SPOT_MS);
                enterScanning("spot dry");
                return;
            }
            setPhase(Phase.SCANNING);
            lastScanAt = 0L;
            return;
        }
        justArrived = false;
        if (tryPickobulus(client, view)) return;
        target = found;
        MinerLog.log("target " + found.kind() + " at " + MinerLog.pos(found.pos())
                + String.format(", %.2f blocks away", client.player.getEyePosition().distanceTo(found.aim())));
        RotationManager.cancelRotation();
        RotationManager.initiateRotation(client, found.aim(), 0L);
        setPhase(Phase.AIMING);
    }

    private static void tickAiming(Minecraft client, MiningView view) {
        releaseAttack(client);
        if (!MiningTargetSelector.isStillMineable(view, target, allowedKinds())) {
            enterSearching("target changed while aiming");
            return;
        }
        if (isCrosshairOnTarget(client)) {
            RotationManager.cancelRotation();
            MinerLog.log("on target after " + (System.currentTimeMillis() - phaseStartedAt) + "ms, mining");
            lastMiningLogAt = System.currentTimeMillis();
            setPhase(Phase.MINING);
            return;
        }
        if (System.currentTimeMillis() - phaseStartedAt > AIM_TIMEOUT_MS) {
            MinerLog.log("could not aim at " + MinerLog.pos(target.pos()) + ", crosshair on "
                    + describeCrosshair(client) + ", skipping it");
            skip(target.pos(), SKIP_BLOCK_MS);
            enterSearching("aim timeout");
            return;
        }
        if (!RotationManager.isRotating()) {
            RotationManager.initiateRotation(client, target.aim(), 0L);
        }
    }

    private static void recordBreak(Minecraft client) {
        blocksMined++;
        totalMined++;
        minedHere++;
        lastMined = target.pos();
        timeoutsHere = 0;
        regenFallback = false;
        breakTimes.addLast(System.currentTimeMillis() - phaseStartedAt);
        if (breakTimes.size() > BREAK_SAMPLES) breakTimes.removeFirst();
        if (area != null && area.contains(client.player.blockPosition())) {
            MiningSpotStore.get().recordMined(client.player.blockPosition());
            exploringSpot = null;
            if (blocksMined % SAVE_SPOTS_EVERY == 0) MiningSpotStore.save();
        }
        MinerLog.log("mined " + target.kind() + " at " + MinerLog.pos(target.pos()) + " in "
                + (System.currentTimeMillis() - phaseStartedAt) + "ms, now "
                + blockName(client, target.pos()) + " (total " + blocksMined + ")");
    }

    private static void tickMining(Minecraft client, MiningView view) {
        if (redigNextTick) {
            // one tick with attack up makes the client start a fresh dig instead of continuing the spent one
            redigNextTick = false;
            releaseAttack(client);
            return;
        }
        MiningBlockKind kindNow = view.kindAt(target.pos());
        if (kindNow != null && kindNow != target.kind() && allowedKinds().contains(kindNow)) {
            // mithril can break into titanium and stay solid; that was a break, and the new block needs its own dig
            recordBreak(client);
            MinerLog.log("block turned into " + kindNow + ", mining it too");
            target = new MiningTargetSelector.Target(target.pos(), kindNow, target.aim());
            phaseStartedAt = System.currentTimeMillis();
            redigNextTick = true;
            releaseAttack(client);
            return;
        }
        if (!MiningTargetSelector.isStillMineable(view, target, allowedKinds())) {
            recordBreak(client);
            enterSearching("block broken");
            return;
        }
        if (!isCrosshairOnTarget(client)) {
            MinerLog.log("crosshair slipped to " + describeCrosshair(client) + ", re-aiming");
            setPhase(Phase.AIMING);
            return;
        }
        long now = System.currentTimeMillis();
        long timeoutMs = blockTimeoutMs();
        if (now - phaseStartedAt > timeoutMs) {
            timeoutsHere++;
            MinerLog.log("gave up on " + MinerLog.pos(target.pos()) + " after " + timeoutMs + "ms ("
                    + timeoutsHere + " in a row here), " + miningState(client));
            skip(target.pos(), SKIP_BLOCK_MS);
            if (area == null) {
                // outside commission areas a block that never breaks is usually decoration, so its neighbours are too
                BlockPos centre = target.pos();
                for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-UNBREAKABLE_RADIUS, -UNBREAKABLE_RADIUS, -UNBREAKABLE_RADIUS),
                        centre.offset(UNBREAKABLE_RADIUS, UNBREAKABLE_RADIUS, UNBREAKABLE_RADIUS))) {
                    skip(pos.immutable(), SKIP_UNBREAKABLE_MS);
                }
                MinerLog.log("avoiding blocks around " + MinerLog.pos(centre) + " for " + SKIP_UNBREAKABLE_MS / 1000 + "s");
            }
            if (timeoutsHere >= TIMEOUTS_BEFORE_LEAVING_SPOT) {
                MinerLog.log("blocks here are not breaking, leaving " + MinerLog.pos(client.player.blockPosition()));
                badSpots.put(client.player.blockPosition(), now + SKIP_SPOT_MS);
                timeoutsHere = 0;
                minedHere = 0;
                enterScanning("blocks not breaking");
                return;
            }
            enterSearching("mine timeout");
            return;
        }
        MacroInput.setAttack(client.options.keyAttack, true);
        if (now - lastMiningLogAt >= MINING_LOG_MS) {
            lastMiningLogAt = now;
            MinerLog.log("mining " + MinerLog.pos(target.pos()) + " for " + (now - phaseStartedAt) + "ms, "
                    + miningState(client));
        }
    }

    private static void tickScanning(Minecraft client, MiningView view) {
        releaseAttack(client);
        long now = System.currentTimeMillis();
        if (now - lastScanAt < RESCAN_INTERVAL_MS) return;
        lastScanAt = now;

        if (!index.hasFinishedASweep()) {
            MinerLog.log("waiting for the first index sweep before choosing (" + index.progress() + ")");
            return;
        }
        BlockPos feet = client.player.blockPosition();
        Set<MiningBlockKind> allowed = allowedKinds();
        long started = System.nanoTime();
        List<BlockPos> candidates = index.nearest(feet, allowed, skipUntil.keySet(), CANDIDATE_LIMIT);
        List<BlockPos> live = new ArrayList<>(candidates.size());
        double reachLimit = reach(client) + 1.0;
        for (BlockPos pos : candidates) {
            if (area != null && area.distanceTo(pos) > reachLimit) continue;
            MiningBlockKind kind = view.kindAt(pos);
            if (kind != null && allowed.contains(kind)) live.add(pos);
        }
        List<MiningVeins.Vein> veins = MiningVeins.group(live, feet);
        MinerLog.log(String.format("scan from %s: index %d blocks (sweep %s), %d nearest, %d still ore, %d veins, %d skipped, %.1fms",
                MinerLog.pos(feet), index.blockCount(), index.hasFinishedASweep() ? "done" : index.progress(),
                candidates.size(), live.size(), veins.size(), skipUntil.size(), (System.nanoTime() - started) / 1e6));

        if (veins.isEmpty()) {
            if (waitForRegen("no other ore in the area")) return;
            if (exploreRemembered(client, "no ore in the area is visible")) return;
            if (noVeinSince == 0L) noVeinSince = now;
            if (!noVeinNoticeSent && now - noVeinSince > NO_VEIN_NOTICE_MS) {
                ClientUtils.sendMessage("§eNo minable blocks nearby. Waiting for them to regenerate...", false);
                noVeinNoticeSent = true;
            }
            return;
        }
        noVeinSince = 0L;
        noVeinNoticeSent = false;

        double reach = reach(client);
        List<MiningVeins.Vein> natural = new ArrayList<>();
        for (int v = 0; v < veins.size() && natural.size() < MAX_VEINS_RANKED; v++) {
            List<BlockPos> blocks = veins.get(v).blocks();
            // commission areas are mining zones, so only free mining has to guess what is decoration
            if (area != null) {
                natural.add(veins.get(v));
                continue;
            }
            MiningVeinFilter.Surroundings around = MiningVeinFilter.inspect(view, blocks);
            if (around.looksNatural()) {
                natural.add(veins.get(v));
            } else {
                MinerLog.log(String.format("vein (%d blocks, nearest %s) looks like decoration, skipping it: %s",
                        blocks.size(), MinerLog.pos(blocks.getFirst()), around.describe()));
                for (BlockPos pos : blocks) skip(pos, SKIP_DECORATION_MS);
            }
        }
        long planStarted = System.nanoTime();
        List<MiningSpotPlanner.Plan> plans = MiningSpotPlanner.rank(view, natural, live, feet, reach,
                badSpots.keySet(), area, MAX_VEINS_RANKED, preferTitanium() ? TITANIUM_SPOT_WEIGHT : 1.0);
        MinerLog.log(String.format("ranked %d spots from %d veins in %.1fms", plans.size(), natural.size(),
                (System.nanoTime() - planStarted) / 1e6));
        if (regenFallback) {
            plans = new ArrayList<>(plans);
            plans.removeIf(p -> p.reachable() < MIN_ORE_TO_MOVE);
        }
        for (int i = 0; i < Math.min(3, plans.size()); i++) {
            MiningSpotPlanner.Plan p = plans.get(i);
            MinerLog.log(String.format("  #%d spot %s reaches %d ore, %.1f away, score %.2f",
                    i + 1, MinerLog.pos(p.spot()), p.reachable(), p.distance(), p.score()));
        }
        if (plans.isEmpty() && waitForRegen("no other spot reaches " + MIN_ORE_TO_MOVE + "+ ore")) return;
        if (plans.isEmpty()) {
            MinerLog.log("no standing spot reaches any of the " + natural.size() + " nearest veins, skipping them");
            for (MiningVeins.Vein vein : natural) {
                for (BlockPos pos : vein.blocks()) skip(pos, SKIP_VEIN_MS);
            }
            exploreRemembered(client, "no standing spot reaches the visible ore");
            return;
        }
        MiningSpotPlanner.Plan chosen = continueVein(plans);
        if (chosen != null) {
            MinerLog.log("staying on the current vein: spot " + MinerLog.pos(chosen.spot()) + " reaches "
                    + chosen.reachable() + " more of it");
        } else {
            chosen = pickAmongBest(plans);
        }
        fallbackPlans = new ArrayList<>(plans.subList(0, Math.min(MAX_PLANS_KEPT, plans.size())));
        fallbackPlans.remove(chosen);
        MinerLog.log("chose spot " + MinerLog.pos(chosen.spot()) + " (" + chosen.reachable() + " ore in reach)");
        startPlan(client, chosen);
    }

    private static boolean waitForRegen(String why) {
        if (!regenFallback) return false;
        regenFallback = false;
        MinerLog.log(why + ", waiting up to " + REGEN_WAIT_MS + "ms for the last spot to regenerate");
        setPhase(Phase.REGEN_WAIT);
        return true;
    }

    private static void startPlan(Minecraft client, MiningSpotPlanner.Plan plan) {
        startPath(client, plan.spot(), plan.forBlock(), plan.vein());
    }

    // an unwalkable spot is rejected before moving, so go straight to the next best one instead of rescanning
    private static boolean tryNextPlan(Minecraft client) {
        while (!fallbackPlans.isEmpty()) {
            MiningSpotPlanner.Plan next = fallbackPlans.removeFirst();
            if (badSpots.containsKey(next.spot())) continue;
            MinerLog.log("trying next spot " + MinerLog.pos(next.spot()) + " (" + next.reachable() + " ore, "
                    + String.format("%.1f", next.distance()) + " away)");
            startPlan(client, next);
            return true;
        }
        return false;
    }

    // finish the vein being mined before walking off to another, as long as it is still worth it
    private static MiningSpotPlanner.Plan continueVein(List<MiningSpotPlanner.Plan> plans) {
        if (lastMined == null) return null;
        double best = plans.getFirst().score();
        for (MiningSpotPlanner.Plan plan : plans) {
            if (plan.score() < best * CONTINUE_VEIN_SHARE) break;
            for (BlockPos pos : plan.vein()) {
                if (pos.distSqr(lastMined) <= 4) return plan;
            }
        }
        return null;
    }

    private static MiningSpotPlanner.Plan pickAmongBest(List<MiningSpotPlanner.Plan> plans) {
        double cutoff = plans.getFirst().score() * TIE_SHARE;
        int ties = 0;
        while (ties < plans.size() && plans.get(ties).score() >= cutoff) ties++;
        return plans.get(random.nextInt(Math.max(1, ties)));
    }

    private static void startPath(Minecraft client, BlockPos spot, BlockPos forBlock, List<BlockPos> vein) {
        pathGoal = spot;
        pathFor = forBlock;
        pathVein = vein;
        AtomicInteger result = new AtomicInteger(PATH_PENDING);
        pathResult = result;
        setPhase(Phase.PATHING);
        bestGoalDistance = Math.sqrt(client.player.blockPosition().distSqr(spot));
        lastProgressAt = System.currentTimeMillis();
        pathTicks = 0;
        lastPathLogAt = lastProgressAt;
        holdSneak(client, false);
        MinerLog.log("walking to " + MinerLog.pos(spot) + " from " + MinerLog.pos(client.player.blockPosition()));
        // full paths only: a partial path walks into a wall and stops there
        PathfindingManager.startConfiguredWalk(client, spot.getX(), spot.getY(), spot.getZ(),
                () -> result.compareAndSet(PATH_PENDING, PATH_DONE),
                () -> result.compareAndSet(PATH_PENDING, PATH_FAILED),
                true, 0.5, false, true);
    }

    private static void tickPathing(Minecraft client, MiningView view) {
        releaseAttack(client);
        pathTicks++;
        long now = System.currentTimeMillis();
        long elapsed = now - phaseStartedAt;
        int result = pathResult.get();
        double remaining = Math.sqrt(client.player.blockPosition().distSqr(pathGoal));
        if (result == PATH_DONE || remaining <= CLOSE_ENOUGH) {
            if (result != PATH_DONE) PathfindingManager.stop();
            MinerLog.log(String.format("arrived near %s at %s (%.1f off) after %dms",
                    MinerLog.pos(pathGoal), MinerLog.pos(client.player.blockPosition()), remaining, elapsed));
            arrive();
            return;
        }
        if (result == PATH_FAILED) {
            MinerLog.log("no complete walkable path to " + MinerLog.pos(pathGoal) + " (" + elapsed
                    + "ms), avoiding that spot");
            badSpots.put(pathGoal, System.currentTimeMillis() + SKIP_SPOT_MS);
            forgetExploring();
            if (tryNextPlan(client)) return;
            abandonPath("no walkable path");
            return;
        }
        if (pathTicks % OPPORTUNISTIC_CHECK_TICKS == 0 && tryMineOnTheWay(client, view)) return;

        if (now - lastPathLogAt >= PATH_PROGRESS_LOG_MS) {
            lastPathLogAt = now;
            MinerLog.log(String.format("walking: at %s, %.1f blocks from goal (best %.1f), pathfinder %s, %dms in",
                    MinerLog.pos(client.player.blockPosition()), remaining, bestGoalDistance,
                    PathfindingManager.isNavigating() ? "navigating" : "idle", elapsed));
        }
        // judge progress by distance to the goal, since the walker can jitter in place without getting closer
        if (remaining <= bestGoalDistance - STUCK_PROGRESS) {
            bestGoalDistance = remaining;
            lastProgressAt = now;
        } else if (now - lastProgressAt > STUCK_AFTER_MS) {
            MinerLog.log(String.format("no progress for %dms at %s, %.1f blocks from %s (best %.1f), avoiding that spot",
                    now - lastProgressAt, MinerLog.pos(client.player.blockPosition()), remaining,
                    MinerLog.pos(pathGoal), bestGoalDistance));
            PathfindingManager.stop();
            badSpots.put(pathGoal, System.currentTimeMillis() + SKIP_SPOT_MS);
            forgetExploring();
            if (tryNextPlan(client)) return;
            abandonPath("stuck walking");
            return;
        }
        if (elapsed > PATH_TIMEOUT_MS) {
            MinerLog.log("walk to " + MinerLog.pos(pathGoal) + " timed out at " + MinerLog.pos(client.player.blockPosition()));
            PathfindingManager.stop();
            abandonPath("walk timeout");
        }
    }

    // ore that comes into reach on the way is mined straight away instead of walking past it
    private static boolean tryMineOnTheWay(Minecraft client, MiningView view) {
        MiningTargetSelector.Target found = MiningTargetSelector.select(view,
                client.player.getEyePosition(), client.player.getViewVector(1.0f), reach(client),
                allowedKinds(), preferTitanium(), skipUntil.keySet());
        if (found == null) return false;
        if (area != null && !area.contains(client.player.blockPosition())) return false;
        if (area == null && !MiningVeinFilter.inspect(view, List.of(found.pos())).looksNatural()) return false;
        MinerLog.log("ore came into reach while walking: " + found.kind() + " at " + MinerLog.pos(found.pos())
                + ", stopping to mine it");
        PathfindingManager.stop();
        minedHere = 0;
        target = found;
        RotationManager.cancelRotation();
        RotationManager.initiateRotation(client, found.aim(), 0L);
        setPhase(Phase.AIMING);
        return true;
    }

    private static void arrive() {
        justArrived = true;
        minedHere = 0;
        timeoutsHere = 0;
        enterSearching("arrived");
    }

    private static void abandonPath(String reason) {
        badSpots.put(pathGoal, System.currentTimeMillis() + SKIP_SPOT_MS);
        forgetExploring();
        skipVein();
        enterScanning(reason);
    }

    private static void tickRegenWait(Minecraft client, MiningView view) {
        releaseAttack(client);
        MiningTargetSelector.Target found = MiningTargetSelector.select(view,
                client.player.getEyePosition(), client.player.getViewVector(1.0f), reach(client),
                allowedKinds(), preferTitanium(), skipUntil.keySet());
        long waited = System.currentTimeMillis() - phaseStartedAt;
        if (found != null) {
            MinerLog.log("ore regenerated after " + waited + "ms: " + found.kind() + " at " + MinerLog.pos(found.pos()));
            target = found;
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(client, found.aim(), 0L);
            setPhase(Phase.AIMING);
            return;
        }
        if (waited > REGEN_WAIT_MS) {
            MinerLog.log("nothing regenerated in " + waited + "ms, moving on");
            minedHere = 0;
            enterScanning("regen wait over");
        }
    }

    // with nothing in sight, walk to the best spot that paid off before and look again from there
    private static boolean exploreRemembered(Minecraft client, String why) {
        if (area == null) return false;
        BlockPos feet = client.player.blockPosition();
        BlockPos spot = MiningSpotStore.get().best(area, feet, System.currentTimeMillis(), badSpots.keySet());
        if (spot == null) {
            MinerLog.log(why + ", and no remembered spot in " + area.name() + " is due a look");
            return false;
        }
        MinerLog.log(why + ", checking remembered spot " + MinerLog.pos(spot) + " in " + area.name());
        exploringSpot = spot;
        fallbackPlans = new ArrayList<>();
        startPath(client, spot, null, List.of());
        return true;
    }

    private static void forgetExploring() {
        if (exploringSpot == null || !exploringSpot.equals(pathGoal)) return;
        MiningSpotStore.get().recordUnreachable(exploringSpot, System.currentTimeMillis());
        exploringSpot = null;
    }

    private static boolean tryPickobulus(Minecraft client, MiningView view) {
        if (!AetherConfig.MINER_PICKOBULUS.get() || !PickobulusTracker.isReady(System.currentTimeMillis())) return false;
        MiningPickobulus.Blast found = MiningPickobulus.best(view, client.player.getEyePosition(), reach(client),
                allowedKinds(), skipUntil.keySet());
        if (found == null || found.ore() < MiningPickobulus.MIN_ORE) return false;
        blast = found;
        pickobulusClicked = false;
        MinerLog.log("pickobulus is ready, throwing it at " + MinerLog.pos(found.pos()) + " (" + found.ore() + " ore around it)");
        RotationManager.cancelRotation();
        RotationManager.initiateRotation(client, found.aim(), 0L);
        setPhase(Phase.PICKOBULUS);
        return true;
    }

    private static void tickPickobulus(Minecraft client, MiningView view) {
        releaseAttack(client);
        long now = System.currentTimeMillis();
        if (pickobulusClicked) {
            if (PickobulusTracker.usedAt() >= phaseStartedAt) {
                MinerLog.log("pickobulus went off after " + (now - phaseStartedAt) + "ms");
                minedHere += blast.ore();
                totalMined += blast.ore();
                enterSearching("pickobulus thrown");
            } else if (now - phaseStartedAt > PICKOBULUS_CONFIRM_MS) {
                MinerLog.log("pickobulus didn't fire (no chat line), not trying again for " + PICKOBULUS_BACKOFF_MS / 1000 + "s");
                PickobulusTracker.castFailed(now, PICKOBULUS_BACKOFF_MS);
                enterSearching("pickobulus failed");
            }
            return;
        }
        HitResult hit = client.hitResult;
        boolean onBlast = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(blast.pos());
        if (onBlast) {
            RotationManager.cancelRotation();
            ClientUtils.performUseClickInstant();
            pickobulusClicked = true;
            phaseStartedAt = now;
            return;
        }
        if (now - phaseStartedAt > AIM_TIMEOUT_MS) {
            MinerLog.log("could not aim pickobulus at " + MinerLog.pos(blast.pos()) + ", mining normally");
            PickobulusTracker.castFailed(now, 5000L);
            enterSearching("pickobulus aim timeout");
            return;
        }
        if (!RotationManager.isRotating()) RotationManager.initiateRotation(client, blast.aim(), 0L);
    }

    // the setting is the ceiling; with a few breaks timed it drops to what this tool actually needs
    private static long blockTimeoutMs() {
        long configured = Math.round(AetherConfig.MINER_BLOCK_TIMEOUT_SECONDS.get() * 1000.0f);
        if (breakTimes.size() < BREAK_SAMPLES_NEEDED) return configured;
        List<Long> sorted = new ArrayList<>(breakTimes);
        sorted.sort(null);
        long median = sorted.get(sorted.size() / 2);
        return Math.min(configured, Math.max(MIN_ADAPTIVE_TIMEOUT_MS, Math.round(median * SLOW_BREAK_FACTOR)));
    }

    private static boolean preferTitanium() {
        if (kindsOverride != null) return kindsOverride.contains(MiningBlockKind.TITANIUM);
        return AetherConfig.MINER_PREFER_TITANIUM.get();
    }

    private static void holdSneak(Minecraft client, boolean down) {
        if (client.options == null || down == sneakHeld) return;
        sneakHeld = down;
        ClientUtils.setKeyMappingState(client.options.keyShift, down);
        if (client.player != null) client.player.setShiftKeyDown(down);
    }

    private static void skipVein() {
        for (BlockPos pos : pathVein) skip(pos, SKIP_VEIN_MS);
        pathVein = List.of();
    }

    private static boolean isCrosshairOnTarget(Minecraft client) {
        HitResult hit = client.hitResult;
        return hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(target.pos());
    }

    private static String miningState(Minecraft client) {
        var gameMode = client.gameMode;
        return "attack " + (client.options.keyAttack.isDown() ? "held" : "released")
                + ", crosshair " + describeCrosshair(client)
                + ", destroying " + (gameMode != null && gameMode.isDestroying())
                + " stage " + (gameMode == null ? -1 : gameMode.getDestroyStage());
    }

    private static String describeCrosshair(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockName(client, blockHit.getBlockPos()) + " at " + MinerLog.pos(blockHit.getBlockPos());
        }
        return hit == null ? "nothing" : hit.getType().toString();
    }

    private static boolean selectMiningTool(Minecraft client) {
        for (String name : TOOL_NAMES) {
            int slot = GearManager.findHotbarItemSlot(client, name);
            if (slot < 0) continue;
            if (FailsafeManager.getCurrentSelectedSlot(client) != slot) {
                FailsafeManager.selectHotbarSlot(client, slot);
            }
            return true;
        }
        return false;
    }

    // the block types around the player, so a wrong mithril mapping shows up in the log straight away
    private static void logSurroundings(Minecraft client) {
        Map<String, Integer> counts = new HashMap<>();
        Map<MiningBlockKind, Integer> kinds = new EnumMap<>(MiningBlockKind.class);
        BlockPos feet = client.player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -DIAGNOSTIC_RADIUS; dx <= DIAGNOSTIC_RADIUS; dx++) {
            for (int dy = -DIAGNOSTIC_RADIUS; dy <= DIAGNOSTIC_RADIUS; dy++) {
                for (int dz = -DIAGNOSTIC_RADIUS; dz <= DIAGNOSTIC_RADIUS; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    var state = client.level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    counts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
                    MiningBlockKind kind = MiningBlockKind.fromBlock(state.getBlock());
                    if (kind != null) kinds.merge(kind, 1, Integer::sum);
                }
            }
        }
        String top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        MinerLog.log("blocks within " + DIAGNOSTIC_RADIUS + ": " + top);
        MinerLog.log("recognised as ore within " + DIAGNOSTIC_RADIUS + ": " + (kinds.isEmpty() ? "none" : kinds));
    }

    private static String blockName(Minecraft client, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).getPath();
    }

    private static double reach(Minecraft client) {
        return client.player.blockInteractionRange() - 0.1;
    }

    private static Set<MiningBlockKind> allowedKinds() {
        if (kindsOverride != null) return kindsOverride;
        Set<MiningBlockKind> kinds = EnumSet.noneOf(MiningBlockKind.class);
        if (AetherConfig.MINER_GRAY_MITHRIL.get()) kinds.add(MiningBlockKind.GRAY_MITHRIL);
        if (AetherConfig.MINER_GREEN_MITHRIL.get()) kinds.add(MiningBlockKind.GREEN_MITHRIL);
        if (AetherConfig.MINER_BLUE_MITHRIL.get()) kinds.add(MiningBlockKind.BLUE_MITHRIL);
        if (AetherConfig.MINER_TITANIUM.get()) kinds.add(MiningBlockKind.TITANIUM);
        return kinds;
    }

    private static void enterSearching(String reason) {
        target = null;
        MinerLog.log("-> searching (" + reason + ")");
        setPhase(Phase.SEARCHING);
    }

    private static void enterScanning(String reason) {
        target = null;
        lastScanAt = 0L;
        MinerLog.log("-> scanning (" + reason + ")");
        setPhase(Phase.SCANNING);
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseStartedAt = System.currentTimeMillis();
    }

    private static void skip(BlockPos pos, long durationMs) {
        skipUntil.put(pos, System.currentTimeMillis() + durationMs);
    }

    private static void pruneSkips() {
        long now = System.currentTimeMillis();
        skipUntil.values().removeIf(until -> until <= now);
        badSpots.values().removeIf(until -> until <= now);
    }

    private static void releaseAttack(Minecraft client) {
        if (client.options != null) MacroInput.setAttack(client.options.keyAttack, false);
    }
}
