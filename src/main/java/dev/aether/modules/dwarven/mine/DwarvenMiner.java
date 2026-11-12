package dev.aether.modules.dwarven.mine;

import dev.aether.config.AetherConfig;
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

    private final OreScanner scanner;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.RUNNING;
    private Set<OreKind> wanted = Set.of();
    private boolean preferTitanium;
    private String area;
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
        stop(client);
        this.wanted = wanted;
        this.preferTitanium = preferTitanium;
        this.area = area;
        this.outcome = Outcome.RUNNING;
        this.timeoutsInARow = 0;
        this.moveFailures = 0;
        this.movedSinceDry = false;
        this.spot = null;
        skip.clear();
        DwarvenLog.log("miner: start, ore " + wanted + (area == null ? ", anywhere" : ", in " + area) + ", prefer titanium " + preferTitanium);
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
            spot = DwarvenStore.SPOTS.visit(client.player.blockPosition(), area, now);
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
        RotationManager.steer(client, aimPoint, DwarvenCamera.profile());
        enter(Phase.AIM);
    }

    private record Choice(BlockPos pos, OreKind kind, Vec3 point) {}

    private Choice choose(Minecraft client, OreWorld world) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        double reach = client.player.blockInteractionRange();
        BlockPos centre = BlockPos.containing(eye);
        Choice best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-SCAN, -SCAN, -SCAN), centre.offset(SCAN, SCAN, SCAN))) {
            OreKind kind = world.oreAt(p);
            if (kind == null || !wanted.contains(kind) || skip.containsKey(p)) continue;
            Vec3 point = StandPlanner.visiblePoint(world, eye, p, reach);
            if (point == null) continue;
            Vec3 to = point.subtract(eye);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
            // turning costs time, so nearby in view wins; titanium jumps the queue when it is what we're after
            double cost = angle + to.length() * 4.0;
            if (kind == OreKind.TITANIUM && preferTitanium) cost -= 1000.0;
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
            ClientUtils.setKeyMappingState(client.options.keyAttack, true);
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
            RotationManager.steer(client, aimPoint, DwarvenCamera.profile());
        }
    }

    private void dig(Minecraft client, OreWorld world, long now) {
        if (releaseTick) {
            // one tick off the button makes the game start a new dig on the block that replaced the old one
            releaseTick = false;
            ClientUtils.setKeyMappingState(client.options.keyAttack, true);
            return;
        }
        OreKind nowKind = world.oreAt(target);
        if (nowKind != targetKind) {
            recordBreak(now);
            if (nowKind != null && wanted.contains(nowKind)) {
                // the block changed into another ore we want (mithril turning into titanium) and is still solid
                DwarvenLog.log("miner: " + targetKind + " at " + target.toShortString() + " became " + nowKind + ", mining it too");
                targetKind = nowKind;
                phaseSince = now;
                releaseTick = true;
                ClientUtils.setKeyMappingState(client.options.keyAttack, false);
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
        if (now - phaseSince > blockTimeoutMs()) {
            releaseAttack(client);
            skip.put(target, now + AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L);
            timeoutsInARow++;
            DwarvenLog.log("miner: " + targetKind + " at " + target.toShortString() + " didn't break in " + (now - phaseSince) + "ms, skipping it (" + timeoutsInARow + " in a row)");
            if (timeoutsInARow >= 3 && AetherConfig.DWARVEN_REPOSITION.get()) {
                timeoutsInARow = 0;
                badStands.put(client.player.blockPosition(), now + AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L);
                enter(Phase.PLAN_MOVE);
            } else {
                enter(Phase.PICK);
            }
        }
    }

    private void recordBreak(long now) {
        long took = now - phaseSince;
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
        StandPlanner.Stand best = null;
        double bestScore = 0;
        for (int i = 0; i < Math.min(VEINS_PLANNED, veins.size()); i++) {
            for (StandPlanner.Stand stand : StandPlanner.plan(world, veins.get(i), ore, reach, badStands.keySet())) {
                if (stand.reachable().size() < MIN_REACHABLE_TO_MOVE) break;
                if (area != null && !inArea(stand.feet(), feet)) continue;
                double value = stand.reachable().size() + (preferTitanium ? TITANIUM_WEIGHT * stand.titanium() : 0);
                SpotMemory.Spot known = AetherConfig.DWARVEN_VEIN_MEMORY.get() ? DwarvenStore.SPOTS.at(stand.feet()) : null;
                // spots that paid off before get the benefit of the doubt, ones that didn't lose it
                if (known != null) value *= Math.max(0.5, Math.min(2.0, known.rate(PRIOR_RATE) / PRIOR_RATE));
                double score = value / (8.0 + Math.sqrt(stand.feet().distSqr(feet)));
                if (score > bestScore) {
                    bestScore = score;
                    best = stand;
                }
            }
        }
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

    private double veinScore(Vein vein, BlockPos feet) {
        double value = vein.size() + (preferTitanium ? TITANIUM_WEIGHT * vein.titanium() : 0);
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

    private void startWalk(Minecraft client, BlockPos goal, long now) {
        releaseAttack(client);
        RotationManager.cancelRotation();
        sneak(client, false);
        walkGoal = goal;
        walkBest = Math.sqrt(goal.distSqr(client.player.blockPosition()));
        walkProgressAt = now;
        AtomicInteger result = new AtomicInteger();
        walkResult = result;
        PathfindingManager.startConfiguredWalk(client, goal.getX(), goal.getY(), goal.getZ(),
                () -> result.compareAndSet(0, 1), () -> result.compareAndSet(0, 2), true, 0.6, false, true);
        enter(Phase.WALK);
    }

    private void walk(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        double left = Math.sqrt(walkGoal.distSqr(feet));
        if (walkResult.get() == 1 || left <= 1.2) {
            if (walkResult.get() != 1) PathfindingManager.stop();
            spot = null;
            movedSinceDry = true;
            enter(Phase.PICK);
            return;
        }
        if (left < walkBest - 0.5) {
            walkBest = left;
            walkProgressAt = now;
        }
        boolean stuck = now - walkProgressAt > AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L;
        if (walkResult.get() == 2 || stuck) {
            PathfindingManager.stop();
            long block = AetherConfig.DWARVEN_BLACKLIST_SECONDS.get() * 1000L;
            badStands.put(walkGoal, now + block);
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
        if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyAttack, false);
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
