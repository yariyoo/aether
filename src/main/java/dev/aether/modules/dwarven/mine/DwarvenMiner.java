package dev.aether.modules.dwarven.mine;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.dwarven.memory.SpotMemory;
import dev.aether.modules.dwarven.world.LiveOreWorld;
import dev.aether.modules.dwarven.world.OreKind;
import dev.aether.modules.dwarven.world.OreScanner;
import dev.aether.modules.dwarven.world.OreWorld;
import dev.aether.modules.dwarven.world.StandPlanner;
import dev.aether.modules.dwarven.world.Vein;
import dev.aether.modules.dwarven.world.VeinFinder;
import dev.aether.modules.dwarven.world.WalkReach;
import dev.aether.modules.dwarven.world.AimPoints;
import dev.aether.modules.dwarven.diag.Ops;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// carries out a mining objective: picks blocks, aims, digs, confirms each break and moves along the vein
public final class DwarvenMiner {
    public enum Phase { IDLE, PICK, AIM, DIG, ABILITY, PLAN_MOVE, WALK, WAIT_REGEN }

    public enum Outcome { RUNNING, NO_ORE, TOOL_MISSING }

    private static final long DIG_START_GRACE_MS = 600L;
    private static final int STAND_FAIL_RADIUS = 2;
    private static final int REGION_FAIL_RADIUS = 5;
    private static final int REGION_FAILS = 2;
    private boolean restartedDig;
    private boolean digStartSeen;
    private long aimStartedAt;
    private String lastProblem;
    private Ops.Op walkOp;
    private final ApproachMemory approaches = new ApproachMemory();
    private int rejectedApproach;
    private final Map<Long, Integer> regionFails = new HashMap<>();
    private static final String[] TOOL_WORDS = {"drill", "pickaxe", "gauntlet", "pickonimbus", "mithril pick"};
    private static final long AIM_LIMIT_MS = 1500L;
    private static final long REGEN_WAIT_MS = 6000L;
    private static final long ABILITY_SETTLE_MS = 350L;
    private static final long ABILITY_RETRY_MS = 20_000L;
    private static final long MISS_SKIP_MS = 3000L;
    private static final long DRY_SPOT_MS = 15_000L;
    private static final int SCAN = 5;
    private static final int VEINS_PLANNED = 3;
    private static final int MIN_REACHABLE_TO_MOVE = 2;
    private static final double TITANIUM_WEIGHT = 4.0;
    private static final double PRIOR_RATE = 60.0;
    private static final int WALK_CHECK_RADIUS = 20;
    private static final int WALK_CHECK_NODES = 6000;
    private static final int WALK_CHECK_MIN = 20;
    // with the pathfinder already moving, this long without getting closer means it won't get there
    private static final long EARLY_STUCK_MS = 2500L;

    private final OreScanner scanner;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.RUNNING;
    private Set<OreKind> wanted = Set.of();
    private boolean preferTitanium;
    private BlockEfficiency.Job job = new BlockEfficiency.Job(false, false, true);
    // learned across commissions, since the tool and the ore don't change between them
    private final BlockEfficiency efficiency = new BlockEfficiency();
    private long lastBreakAt;
    private String area;
    private String zone = "";
    private long phaseSince;
    private BlockPos target;
    private OreKind targetKind;
    private Vec3 aimPoint;
    private boolean releaseTick;
    private final Map<BlockPos, Long> skip = new HashMap<>();
    private final Map<BlockPos, Long> badStands = new HashMap<>();
    private final ArrayDeque<Long> breakTimes = new ArrayDeque<>();
    private int timeoutsInARow;
    private int moveFailures;
    private boolean movedSinceDry;
    private SpotMemory.Spot spot;
    private long spotIdleSince;
    private BlockPos walkGoal;
    private AtomicInteger walkResult = new AtomicInteger();
    private double walkBest;
    private boolean walkStarted;
    private BlockPos walkFrom;
    private long walkProgressAt;
    private boolean abilityReady = true;
    private long abilityBlockedUntil;
    private boolean sneaking;
    private int mined;
    private int titaniumMined;
    private int mithrilSinceTitanium;

    public DwarvenMiner(OreScanner scanner) {
        this.scanner = scanner;
    }

    public void zone(String zone) {
        this.zone = zone == null ? "" : zone;
    }

    public Phase phase() {
        return phase;
    }

    public Outcome outcome() {
        return outcome;
    }

    public int mined() {
        return mined;
    }

    public int titaniumMined() {
        return titaniumMined;
    }

    public BlockPos target() {
        return target;
    }

    public BlockPos walkGoal() {
        return phase == Phase.WALK ? walkGoal : null;
    }

    public boolean running() {
        return phase != Phase.IDLE;
    }

    public void start(Minecraft client, Set<OreKind> wanted, boolean preferTitanium, String area) {
        start(client, wanted, new BlockEfficiency.Job(preferTitanium, preferTitanium, true), area);
    }

    // job says what the commission counts, so a mithril commission doesn't spend its time on titanium
    public void start(Minecraft client, Set<OreKind> wanted, BlockEfficiency.Job job, String area) {
        stop(client);
        this.wanted = wanted;
        this.job = job;
        this.preferTitanium = job.titanium();
        this.area = area;
        this.outcome = Outcome.RUNNING;
        this.timeoutsInARow = 0;
        this.moveFailures = 0;
        this.movedSinceDry = false;
        this.spot = null;
        skip.clear();
        regionFails.clear();
        DwarvenLog.log("miner: start, ore " + wanted + (area == null ? ", anywhere" : ", in " + area) + ", " + (job.titanium() ? "titanium first"
                : job.titaniumAlsoCounts() ? "mithril, titanium also counts" : "mithril only") + ", priority " + mode().name().toLowerCase());
        enter(Phase.PICK);
    }

    public void stop(Minecraft client) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.WALK) PathfindingManager.stop();
        releaseAttack(client);
        sneak(client, false);
        RotationManager.cancelRotation();
        phase = Phase.IDLE;
        target = null;
    }

    // lets go of the controls without losing the plan, for menus and when the player takes over
    public void pause(Minecraft client) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.WALK) {
            PathfindingManager.stop();
            enter(Phase.PLAN_MOVE);
        } else if (phase == Phase.AIM || phase == Phase.DIG || phase == Phase.ABILITY) {
            enter(Phase.PICK);
        }
        releaseAttack(client);
        sneak(client, false);
        RotationManager.cancelRotation();
    }

    public void onChat(String message) {
        String text = message.strip().toLowerCase(Locale.ROOT);
        if (text.endsWith("is now available!")) {
            abilityReady = true;
        } else if (text.startsWith("you used your") && text.contains("pickaxe ability")) {
            abilityReady = false;
            abilityBlockedUntil = 0;
        } else if (text.contains("ability is on cooldown") || text.startsWith("this ability is on cooldown")) {
            abilityReady = false;
        }
    }

    public void tick(Minecraft client) {
        if (phase == Phase.IDLE || client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        skip.values().removeIf(until -> until <= now);
        badStands.values().removeIf(until -> until <= now);
        OreWorld world = new LiveOreWorld(client.level, client.player);
        sneak(client, AetherConfig.DWARVEN_SNEAK.get() && phase != Phase.WALK && phase != Phase.PLAN_MOVE);
        switch (phase) {
            case PICK -> pick(client, world, now);
            case AIM -> aim(client, world, now);
            case DIG -> dig(client, world, now);
            case ABILITY -> ability(client, now);
            case PLAN_MOVE -> planMove(client, world, now);
            case WALK -> walk(client, now);
            case WAIT_REGEN -> waitRegen(client, world, now);
            default -> { }
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseSince = System.currentTimeMillis();
        digStartSeen = false;
    }

    // what the miner is doing right now, for the diagnostic snapshot
    public void describe(StringBuilder b, Minecraft client, long now) {
        b.append(" miner=").append(phase.name().toLowerCase());
        if (phase == Phase.IDLE) return;
        if (target != null) {
            b.append(" block=").append(target.toShortString()).append(' ').append(targetKind);
            b.append(String.format(" dblock=%.1f", Math.sqrt(target.distToCenterSqr(client.player.getEyePosition()))));
            b.append(" ray=").append(crosshairOn(client, target));
        }
        if (phase == Phase.WALK && walkGoal != null) {
            b.append(" stand=").append(walkGoal.toShortString()).append(String.format(" dstand=%.1f", walkBest));
            b.append(" noProgressMs=").append(now - walkProgressAt).append(walkStarted ? "" : "(starting)");
        }
        boolean held = client.options.keyAttack.isDown();
        boolean destroying = client.gameMode != null && client.gameMode.isDestroying();
        b.append(" attack=").append(held ? "held" : "up").append(destroying ? "/destroying" : "");
        if (phase == Phase.DIG) b.append(" digMs=").append(now - phaseSince).append(" timeoutLeftMs=").append(Math.max(0, blockTimeoutMs() - (now - phaseSince)));
        if (lastProblem != null) b.append(" lastProblem=\"").append(lastProblem).append('"');
    }

    private void pick(Minecraft client, OreWorld world, long now) {
        int tool = toolSlot(client);
        if (tool < 0) {
            outcome = Outcome.TOOL_MISSING;
            return;
        }
        if (FailsafeManager.getCurrentSelectedSlot(client) != tool) {
            // wait for the swap to land before clicking anything, or the click goes to whatever was held
            releaseAttack(client);
            FailsafeManager.selectHotbarSlot(client, tool);
            return;
        }
        if (spot == null) {
            // a spot is filed under the zone the scoreboard shows, which is what commissions go by
            spot = DwarvenStore.SPOTS.visit(client.player.blockPosition(), zone.isEmpty() ? area : zone, now);
            spotIdleSince = now;
        }
        if (AetherConfig.DWARVEN_PICKAXE_ABILITY.get() && abilityReady && now >= abilityBlockedUntil) {
            releaseAttack(client);
            ClientUtils.performUseClickInstant();
            // if the game never confirms it in chat, the tool has no ability set up; don't keep clicking
            abilityBlockedUntil = now + ABILITY_RETRY_MS;
            DwarvenLog.log("miner: using the pickaxe ability");
            enter(Phase.ABILITY);
            return;
        }
        Choice choice = choose(client, world);
        if (choice == null) {
            releaseAttack(client);
            DwarvenStore.SPOTS.empty(spot, now);
            if (AetherConfig.DWARVEN_REPOSITION.get()) {
                badStands.put(client.player.blockPosition(), now + DRY_SPOT_MS);
                DwarvenLog.log("miner: nothing in reach at " + client.player.blockPosition().toShortString() + ", looking for another spot");
                enter(Phase.PLAN_MOVE);
            } else {
                enter(Phase.WAIT_REGEN);
            }
            return;
        }
        target = choice.pos;
        targetKind = choice.kind;
        aimPoint = choice.point;
        aimStartedAt = now;
        RotationManager.steer(client, aimPoint, DwarvenCamera.profile(DwarvenCamera.Activity.MINING));
        enter(Phase.AIM);
    }

    private record Choice(BlockPos pos, OreKind kind, Vec3 point) {}

    private Choice choose(Minecraft client, OreWorld world) {
        long t0 = System.nanoTime();
        try {
            return chooseInner(client, world);
        } finally {
            Ops.recordNanos("mine.select", System.nanoTime() - t0);
        }
    }

    private BlockEfficiency.Mode mode() {
        return BlockEfficiency.Mode.values()[Math.max(0, Math.min(BlockEfficiency.Mode.values().length - 1, AetherConfig.DWARVEN_MITHRIL_MODE.get()))];
    }

    private static Map<OreKind, Double> weights() {
        Map<OreKind, Double> w = new java.util.EnumMap<>(OreKind.class);
        w.put(OreKind.MITHRIL_GRAY, (double) AetherConfig.DWARVEN_GRAY_WEIGHT.get());
        w.put(OreKind.MITHRIL_GREEN, (double) AetherConfig.DWARVEN_GREEN_WEIGHT.get());
        w.put(OreKind.MITHRIL_BLUE, (double) AetherConfig.DWARVEN_BLUE_WEIGHT.get());
        w.put(OreKind.TITANIUM, 1.0);
        return w;
    }

    // how fast the mining camera turns, used to price the turn toward a block
    private static double turnSpeedNow() {
        return Math.max(30.0, DwarvenCamera.profile(DwarvenCamera.Activity.MINING).maxSpeed() * 0.5);
    }

    private double turnSpeed = 200.0;

    // what one stand is worth: the useful ore in reach, each block counted by how much it advances the job per second
    private double standValue(OreWorld world, StandPlanner.Stand stand) {
        if (!AetherConfig.DWARVEN_COMMISSION_AWARE.get() || !AetherConfig.DWARVEN_CLUSTER_MINING.get()) {
            return stand.reachable().size() + (preferTitanium ? TITANIUM_WEIGHT * stand.titanium() : 0);
        }
        boolean learning = AetherConfig.DWARVEN_BLOCK_LEARNING.get();
        double perSecond = 0;
        for (BlockPos p : stand.reachable()) {
            OreKind k = world.oreAt(p);
            if (k == null || !wanted.contains(k)) continue;
            perSecond += efficiency.contribution(k, job) * 1000.0 / efficiency.breakMs(k, learning);
        }
        // scaled so a stand full of plain mithril scores about what its block count used to
        return perSecond * efficiency.breakMs(OreKind.MITHRIL_GRAY, learning) / 1000.0;
    }

    // one seed per game session, so a block's aim spot stays put but differs between sessions
    private static final long AIM_SEED = System.nanoTime();
    private double aimVariation;

    private Choice chooseInner(Minecraft client, OreWorld world) {
        turnSpeed = turnSpeedNow();
        aimVariation = AetherConfig.DWARVEN_CAM_VARIATION_ON.get() ? AetherConfig.DWARVEN_AIM_VARIATION.get() : 0.0;
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        double reach = client.player.blockInteractionRange();
        BlockPos centre = BlockPos.containing(eye);
        Choice best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-SCAN, -SCAN, -SCAN), centre.offset(SCAN, SCAN, SCAN))) {
            OreKind kind = world.oreAt(p);
            if (kind == null || !wanted.contains(kind) || skip.containsKey(p)) continue;
            Vec3 point = AimPoints.pick(world, eye, p, reach, aimVariation, AIM_SEED);
            if (point == null) continue;
            Vec3 to = point.subtract(eye);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
            double cost;
            if (AetherConfig.DWARVEN_COMMISSION_AWARE.get()) {
                // the time this block costs per unit of commission progress: turning to it plus breaking it
                double aimMs = angle / turnSpeed * 1000.0;
                cost = efficiency.cost(kind, job, mode(), aimMs, to.length() * 20.0, AetherConfig.DWARVEN_BLOCK_LEARNING.get(), weights());
                if (cost == Double.MAX_VALUE) continue;
            } else {
                // turning costs time, so nearby in view wins; titanium jumps the queue when it is what we're after
                cost = angle + to.length() * 4.0;
                if (kind == OreKind.TITANIUM && preferTitanium) cost -= 1000.0;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = new Choice(p.immutable(), kind, point);
            }
        }
        return best;
    }

    private void aim(Minecraft client, OreWorld world, long now) {
        if (world.oreAt(target) != targetKind) {
            enter(Phase.PICK);
            return;
        }
        if (crosshairOn(client, target)) {
            // the dig starts as soon as the ray is on the block; the last fraction of a degree isn't waited for
            if (aimStartedAt > 0) Ops.record("rot.mine", now - aimStartedAt);
            aimStartedAt = 0;
            // the macro input also tells the game to keep digging when the window doesn't have the mouse captured
            MacroInput.setAttack(client.options.keyAttack, true);
            restartedDig = false;
            enter(Phase.DIG);
            return;
        }
        if (now - phaseSince > AIM_LIMIT_MS) {
            skip.put(target, now + MISS_SKIP_MS);
            enter(Phase.PICK);
            return;
        }
        if (!RotationManager.isRotating()) {
            // the first point can end up hidden once we stop moving; take a fresh visible one
            Vec3 point = StandPlanner.visiblePoint(world, client.player.getEyePosition(), target, client.player.blockInteractionRange());
            if (point == null) {
                skip.put(target, now + MISS_SKIP_MS);
                enter(Phase.PICK);
                return;
            }
            aimPoint = point;
            RotationManager.steer(client, aimPoint, DwarvenCamera.profile(DwarvenCamera.Activity.MINING));
        }
    }

    private void dig(Minecraft client, OreWorld world, long now) {
        if (releaseTick) {
            // holding the key again on the same block only continues the old client-side dig and never tells the server;
            // a real click is what starts a fresh one
            releaseTick = false;
            MacroInput.setAttack(client.options.keyAttack, true);
            ClientUtils.clickKeyMapping(client.options.keyAttack);
            return;
        }
        OreKind nowKind = world.oreAt(target);
        if (nowKind != targetKind) {
            recordBreak(now);
            if (nowKind == OreKind.TITANIUM && targetKind.isMithril()) efficiency.titaniumSpawned();
            boolean counts = !AetherConfig.DWARVEN_COMMISSION_AWARE.get() || efficiency.contribution(nowKind == null ? targetKind : nowKind, job) > 0;
            if (nowKind != null && wanted.contains(nowKind) && counts) {
                // the block changed into another ore we want (mithril turning into titanium) and is still solid
                DwarvenLog.log("miner: " + targetKind + " at " + target.toShortString() + " became " + nowKind + ", mining it too");
                targetKind = nowKind;
                phaseSince = now;
                digStartSeen = false;
                releaseTick = true;
                MacroInput.setAttack(client.options.keyAttack, false);
                return;
            }
            // keep the button down and swing straight to the next block
            enter(Phase.PICK);
            pick(client, world, now);
            return;
        }
        if (!crosshairOn(client, target)) {
            releaseAttack(client);
            enter(Phase.AIM);
            return;
        }
        boolean destroying = client.gameMode != null && client.gameMode.isDestroying();
        if (destroying && !digStartSeen) {
            digStartSeen = true;
            Ops.record("mine.dig.start", now - phaseSince);
        }
        if (!destroying && !restartedDig && now - phaseSince > DIG_START_GRACE_MS) {
            // the button is down but the game never began breaking the block; press again instead of waiting out the timeout
            restartedDig = true;
            lastProblem = digState(client);
            Ops.record("mine.dig.restart", now - phaseSince);
            DwarvenLog.log("miner: " + lastProblem + " on " + targetKind + " at " + target.toShortString() + ", restarting the dig");
            MacroInput.setAttack(client.options.keyAttack, false);
            releaseTick = true;
            phaseSince = now;
            return;
        }
        if (now - phaseSince > blockTimeoutMs()) {
            String why = destroying ? "dig ran but the block didn't break" : digState(client);
            lastProblem = why;
            Ops.record("mine.dig.timeout", now - phaseSince);
            releaseAttack(client);
            skip.put(target, now + AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L);
            timeoutsInARow++;
            DwarvenLog.log("miner: " + targetKind + " at " + target.toShortString() + " didn't break in " + (now - phaseSince) + "ms (" + why
                    + "), skipping it (" + timeoutsInARow + " in a row)");
            if (timeoutsInARow >= 3 && AetherConfig.DWARVEN_REPOSITION.get()) {
                timeoutsInARow = 0;
                badStands.put(client.player.blockPosition(), now + AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L);
                enter(Phase.PLAN_MOVE);
            } else {
                enter(Phase.PICK);
            }
        }
    }

    // which part of the dig chain is missing, so a block that won't break says why
    private String digState(Minecraft client) {
        boolean held = client.options.keyAttack.isDown();
        boolean grabbed = client.mouseHandler.isMouseGrabbed();
        int tool = toolSlot(client);
        boolean toolHeld = tool >= 0 && FailsafeManager.getCurrentSelectedSlot(client) == tool;
        if (!toolHeld) return "mining tool not held";
        if (!held) return "attack input not held";
        if (!crosshairOn(client, target)) return "crosshair off the block";
        return "mining never started (attack held, mouse " + (grabbed ? "captured" : "not captured")
                + ", attack suppressed " + dev.aether.bootstrap.AetherBootstrapHooks.isAttackSuppressed() + ")";
    }

    private void recordBreak(long now) {
        long took = now - phaseSince;
        Ops.record("mine.break", took);
        Ops.record("mine.break." + targetKind.name().toLowerCase(), took);
        if (lastBreakAt > 0 && now - lastBreakAt < 30_000) Ops.record("mine.between", now - lastBreakAt);
        lastBreakAt = now;
        efficiency.broke(targetKind, took);
        mined++;
        timeoutsInARow = 0;
        moveFailures = 0;
        movedSinceDry = false;
        if (targetKind == OreKind.TITANIUM) {
            titaniumMined++;
            mithrilSinceTitanium = 0;
        } else {
            mithrilSinceTitanium++;
        }
        breakTimes.addLast(took);
        if (breakTimes.size() > 20) breakTimes.removeFirst();
        if (spot != null) {
            DwarvenStore.SPOTS.mined(spot, targetKind == OreKind.TITANIUM, now - spotIdleSince);
            spotIdleSince = now;
        }
    }

    // the setting is the ceiling; once a few breaks are timed it tightens to what this tool really needs
    private long blockTimeoutMs() {
        long configured = Math.round(AetherConfig.DWARVEN_BLOCK_TIMEOUT.get() * 1000.0f);
        if (!AetherConfig.DWARVEN_ADAPTIVE_TIMEOUT.get() || breakTimes.size() < 5) return configured;
        List<Long> sorted = new ArrayList<>(breakTimes);
        sorted.sort(null);
        long usual = sorted.get(sorted.size() / 2);
        return Math.min(configured, Math.max(1500L, usual * 4L));
    }

    public long usualBreakMs() {
        if (breakTimes.isEmpty()) return 600L;
        List<Long> sorted = new ArrayList<>(breakTimes);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private void ability(Minecraft client, long now) {
        if (now - phaseSince >= ABILITY_SETTLE_MS) {
            abilityReady = false;
            enter(Phase.PICK);
        }
    }

    private void planMove(Minecraft client, OreWorld world, long now) {
        BlockPos feet = client.player.blockPosition();
        List<BlockPos> ore = liveOre(world, feet, AetherConfig.DWARVEN_LOCAL_RADIUS.get());
        if (ore.isEmpty()) ore = liveOre(world, feet, AetherConfig.DWARVEN_EXTENDED_RADIUS.get());
        List<Vein> veins = new ArrayList<>(VeinFinder.find(ore, world, AetherConfig.DWARVEN_MIN_VEIN.get()));
        veins.sort((a, b) -> Double.compare(veinScore(b, feet), veinScore(a, feet)));
        double reach = client.player.blockInteractionRange();
        boolean checkWalk = AetherConfig.DWARVEN_PREFER_REACHABLE.get();
        Set<BlockPos> walkable = checkWalk ? WalkReach.from(world, feet, WALK_CHECK_RADIUS, WALK_CHECK_NODES) : Set.of();
        // a tiny flood means we're on blocks the check can't model (a slab, a ledge edge); then it isn't trusted
        boolean trustWalk = walkable.size() >= WALK_CHECK_MIN;
        long t0 = System.nanoTime();
        StandPlanner.Stand best = pickStand(world, veins, ore, reach, feet, trustWalk ? walkable : null);
        if (best == null && trustWalk) {
            // nothing passed the walk check; the old behaviour of trying the best stand anyway beats giving up
            best = pickStand(world, veins, ore, reach, feet, null);
            if (best != null) DwarvenLog.log("miner: no stand passed the walk check, trying " + best.feet().toShortString() + " anyway");
        } else if (best != null && rejectedUnwalkable > 0) {
            DwarvenLog.log("miner: skipped " + rejectedUnwalkable + " stands there's no walk to from here");
        }
        if (rejectedApproach > 0) DwarvenLog.log("miner: skipped " + rejectedApproach + " stands behind a spot a walk already got stuck at");
        Ops.recordNanos("mine.stand.select", System.nanoTime() - t0);
        planMoveDone(client, world, now, feet, ore, veins, best);
    }

    private int rejectedUnwalkable;

    private StandPlanner.Stand pickStand(OreWorld world, List<Vein> veins, List<BlockPos> ore, double reach, BlockPos feet, Set<BlockPos> walkable) {
        rejectedUnwalkable = 0;
        rejectedApproach = 0;
        StandPlanner.Stand best = null;
        double bestScore = 0;
        for (int i = 0; i < Math.min(VEINS_PLANNED, veins.size()); i++) {
            for (StandPlanner.Stand stand : StandPlanner.plan(world, veins.get(i), ore, reach, badStands.keySet())) {
                if (stand.reachable().size() < MIN_REACHABLE_TO_MOVE) break;
                // a stand known to sit in another zone wouldn't count for this commission, wherever its ore is
                if (area != null && (!inArea(stand.feet(), feet) || !DwarvenStore.AREAS.allows(area, stand.feet()))) continue;
                boolean insideCheck = Math.abs(stand.feet().getX() - feet.getX()) < WALK_CHECK_RADIUS - 1
                        && Math.abs(stand.feet().getZ() - feet.getZ()) < WALK_CHECK_RADIUS - 1;
                if (walkable != null && insideCheck && !walkable.contains(stand.feet())) {
                    rejectedUnwalkable++;
                    continue;
                }
                if (approaches.blocks(feet, stand.feet(), System.currentTimeMillis())) {
                    rejectedApproach++;
                    continue;
                }
                double value = standValue(world, stand);
                if (value <= 0) continue;
                SpotMemory.Spot known = AetherConfig.DWARVEN_VEIN_MEMORY.get() ? DwarvenStore.SPOTS.at(stand.feet()) : null;
                // spots that paid off before get the benefit of the doubt, ones that didn't lose it
                if (known != null) value *= Math.max(0.5, Math.min(2.0, known.rate(PRIOR_RATE) / PRIOR_RATE));
                // climbs and drops usually mean a detour, so height costs more than flat distance
                double rise = Math.abs(stand.feet().getY() - feet.getY());
                // stands near ones that failed before are less likely to work; the weight sets how much that matters
                int fails = regionFails.getOrDefault(BlockPos.asLong(stand.feet().getX() >> 3, stand.feet().getY() >> 3, stand.feet().getZ() >> 3), 0);
                double reliability = 1.0 / (1.0 + AetherConfig.DWARVEN_SPOT_RELIABILITY.get() * fails);
                double score = value * reliability / (8.0 + Math.sqrt(stand.feet().distSqr(feet)) + rise * 2.0);
                if (score > bestScore) {
                    bestScore = score;
                    best = stand;
                }
            }
        }
        return best;
    }

    private void planMoveDone(Minecraft client, OreWorld world, long now, BlockPos feet, List<BlockPos> ore, List<Vein> veins, StandPlanner.Stand best) {
        if (best == null) {
            if (!movedSinceDry && spot != null && spot.blocks > 0) {
                DwarvenLog.log("miner: no other spot reaches ore (" + ore.size() + " ore, " + veins.size() + " veins nearby), waiting for this one to come back");
                enter(Phase.WAIT_REGEN);
            } else {
                DwarvenLog.log("miner: no reachable ore around " + feet.toShortString() + " (" + ore.size() + " ore, " + veins.size() + " veins)");
                releaseAttack(client);
                outcome = Outcome.NO_ORE;
            }
            return;
        }
        if (best.feet().distSqr(feet) <= 2) {
            // we're effectively standing there already
            spot = null;
            enter(Phase.PICK);
            return;
        }
        DwarvenLog.log("miner: moving to " + best.feet().toShortString() + ", " + best.reachable().size() + " ore in reach there (" + best.titanium() + " titanium)");
        startWalk(client, best.feet(), now);
    }

    // titanium only adds to a vein's worth when the job counts it
    private double veinScore(Vein vein, BlockPos feet) {
        boolean titaniumCounts = !AetherConfig.DWARVEN_COMMISSION_AWARE.get() ? preferTitanium : job.titanium() || job.titaniumAlsoCounts();
        double value = vein.size() + (titaniumCounts ? TITANIUM_WEIGHT * vein.titanium() : 0);
        return value / (10.0 + Math.sqrt(vein.centre().distSqr(feet)));
    }

    private List<BlockPos> liveOre(OreWorld world, BlockPos centre, int radius) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> skipping = new HashSet<>(skip.keySet());
        for (BlockPos p : scanner.near(centre, radius, skipping)) {
            OreKind kind = world.oreAt(p);
            if (kind != null && wanted.contains(kind)) out.add(p);
        }
        return out;
    }

    // a stand counts as in the area when the zone was seen there, or it is close to where we already are in it
    private boolean inArea(BlockPos stand, BlockPos feet) {
        if (DwarvenStore.AREAS.contains(area, stand)) return true;
        String other = DwarvenStore.AREAS.areaAt(stand);
        return other == null && stand.distSqr(feet) <= 20 * 20 && DwarvenStore.AREAS.contains(area, feet);
    }

    private void blockAround(BlockPos centre, int r, long until) {
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) badStands.put(p.immutable(), until);
    }

    private void startWalk(Minecraft client, BlockPos goal, long now) {
        releaseAttack(client);
        RotationManager.cancelRotation();
        sneak(client, false);
        walkGoal = goal;
        walkBest = Math.sqrt(goal.distSqr(client.player.blockPosition()));
        walkProgressAt = now;
        walkStarted = false;
        walkFrom = client.player.blockPosition();
        AtomicInteger result = new AtomicInteger();
        walkResult = result;
        walkOp = Ops.begin("mine.walk", (long) (walkBest / 4.3 * 1000) + 1500);
        walkOp.detail("to stand " + goal.toShortString());
        PathfindingManager.startConfiguredWalk(client, goal.getX(), goal.getY(), goal.getZ(),
                () -> result.compareAndSet(0, 1), () -> result.compareAndSet(0, 2), true, 0.6, false, true);
        enter(Phase.WALK);
    }

    private void walk(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        double left = Math.sqrt(walkGoal.distSqr(feet));
        if (walkResult.get() == 1 || left <= 1.2) {
            if (walkResult.get() != 1) PathfindingManager.stop();
            if (walkOp != null) walkOp.end("reached");
            spot = null;
            movedSinceDry = true;
            enter(Phase.PICK);
            return;
        }
        if (left < walkBest - 0.5) {
            walkBest = left;
            walkProgressAt = now;
            if (walkOp != null) walkOp.progress();
        }
        // until the first steps, the pathfinder may still be searching, which isn't being stuck
        if (!walkStarted && walkFrom != null && Math.sqrt(walkFrom.distSqr(feet)) >= 1) {
            walkStarted = true;
            walkProgressAt = now;
        }
        long stuckMs = AetherConfig.DWARVEN_PREDICTIVE_STUCK.get() && walkStarted ? EARLY_STUCK_MS
                : AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L;
        boolean stuck = now - walkProgressAt > stuckMs;
        if (walkResult.get() == 2 || stuck) {
            PathfindingManager.stop();
            long block = AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L;
            if (stuck) Ops.record("mine.stuck", now - walkProgressAt);
            if (walkOp != null) Ops.record("mine.walk.failed", walkOp.end(stuck ? "stuck" : "no path"));
            lastProblem = "couldn't reach " + walkGoal.toShortString();
            // stands past the place we got stuck, approached from here, are behind the same obstruction
            if (walkFrom != null) approaches.stuck(walkFrom, feet, now + block);
            // the stands around one we couldn't reach are usually just as unreachable; drop the patch, not one block
            blockAround(walkGoal, STAND_FAIL_RADIUS, now + block);
            long region = BlockPos.asLong(walkGoal.getX() >> 3, walkGoal.getY() >> 3, walkGoal.getZ() >> 3);
            if (regionFails.merge(region, 1, Integer::sum) >= REGION_FAILS) {
                DwarvenLog.log("miner: stands around " + walkGoal.toShortString() + " failed repeatedly, leaving that part of the vein");
                blockAround(walkGoal, REGION_FAIL_RADIUS, now + block);
            }
            DwarvenStore.SPOTS.unreachable(walkGoal, now, 3, 600_000L);
            moveFailures++;
            DwarvenLog.log("miner: couldn't reach " + walkGoal.toShortString() + " (" + (stuck ? "stuck" : "no path") + "), try " + moveFailures);
            if (moveFailures > AetherConfig.DWARVEN_NAV_RETRIES.get()) {
                moveFailures = 0;
                outcome = Outcome.NO_ORE;
                enter(Phase.PICK);
            } else {
                enter(Phase.PLAN_MOVE);
            }
        }
    }

    private void waitRegen(Minecraft client, OreWorld world, long now) {
        if (choose(client, world) != null) {
            enter(Phase.PICK);
            return;
        }
        if (now - phaseSince > REGEN_WAIT_MS) {
            movedSinceDry = true;
            badStands.remove(client.player.blockPosition());
            enter(AetherConfig.DWARVEN_REPOSITION.get() ? Phase.PLAN_MOVE : Phase.PICK);
            if (!AetherConfig.DWARVEN_REPOSITION.get()) outcome = Outcome.NO_ORE;
        }
    }

    // the caller handled NO_ORE (moved us somewhere else) and wants mining to carry on
    public void resume() {
        outcome = Outcome.RUNNING;
        spot = null;
        movedSinceDry = false;
        if (phase != Phase.IDLE) enter(Phase.PICK);
    }

    public static int toolSlot(Minecraft client) {
        String wanted = AetherConfig.DWARVEN_TOOL_NAME.get().strip().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty()) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(wanted)) return i;
            }
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            for (String word : TOOL_WORDS) {
                if (name.contains(word)) return i;
            }
        }
        return -1;
    }

    private static boolean crosshairOn(Minecraft client, BlockPos pos) {
        HitResult hit = client.hitResult;
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(pos);
    }

    private static void releaseAttack(Minecraft client) {
        if (client.options != null) MacroInput.setAttack(client.options.keyAttack, false);
    }

    private void sneak(Minecraft client, boolean down) {
        if (down == sneaking || client.options == null) return;
        sneaking = down;
        ClientUtils.setKeyMappingState(client.options.keyShift, down);
    }

    public int mithrilSinceTitanium() {
        return mithrilSinceTitanium;
    }
}
