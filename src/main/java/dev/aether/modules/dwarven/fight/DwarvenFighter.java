package dev.aether.modules.dwarven.fight;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// hunts the mobs a slayer or puncher commission asks for: finds them by their name tag, walks over and hits them.
// a hit can move straight on to the next mob while the first one's death is still being confirmed
public final class DwarvenFighter {
    public enum Outcome { RUNNING, WAITING_FOR_RESPAWN, NO_TARGETS }

    private enum Phase { IDLE, SEARCHING, ENGAGING, WAITING_FOR_RESPAWN, REPOSITIONING }

    // hypixel name tags read "[Lv23] Goblin 800/800❤", with font icons between the parts on 26.2
    private static final Pattern TAG = Pattern.compile("\\[Lv\\d+][^A-Za-z]*([A-Za-z' ]+?)\\s+([\\d,.]+)([kKmM]?)/");
    private static final double HIT_REACH = 3.0;
    private static final double SIGHT = 48.0;
    private static final long REPLAN_MS = 1200L;
    // one swing every few ticks is what a player clicking quickly manages; never faster
    private static final long HIT_GAP_MS = 140L;
    private static final long TARGET_GIVE_UP_MS = 12_000L;
    private static final long IGNORE_TARGET_MS = 15_000L;
    private static final double AT_SPAWN = 6.0;
    private static final long SPAWN_EMPTY_RECHECK_MS = 60_000L;
    private static final int OLD_STYLE_SPOTS = 4;

    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.RUNNING;
    private Set<String> mobs = Set.of();
    private final AttackLog attacks = new AttackLog();
    private LivingEntity target;
    private long targetSince;
    private long lastHitAt;
    private long lastSwitchAt;
    private long lastPlanAt;
    private long lastScanAt;
    private long waitSince;
    private BlockPos waitSpot;
    private BlockPos walkGoal;
    private double walkBest;
    private long walkProgressAt;
    private AtomicInteger walkResult = new AtomicInteger();
    private int hits;
    private int oldStyleSpots;
    private boolean announcedMoveOn;
    private final Map<Integer, Long> ignored = new HashMap<>();
    private final Set<BlockPos> emptyThisRun = new HashSet<>();

    public Outcome outcome() {
        return outcome;
    }

    public boolean running() {
        return phase != Phase.IDLE;
    }

    public int hits() {
        return hits;
    }

    public int kills() {
        return attacks.kills();
    }

    public Entity target() {
        return target;
    }

    public String phaseName() {
        return phase.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public void start(Minecraft client, Set<String> mobNames) {
        stop(client);
        mobs = mobNames;
        outcome = Outcome.RUNNING;
        oldStyleSpots = 0;
        announcedMoveOn = false;
        ignored.clear();
        emptyThisRun.clear();
        attacks.reset();
        phase = Phase.SEARCHING;
        DwarvenLog.log("combat: hunting " + mobNames + ", mode " + mode());
    }

    public void stop(Minecraft client) {
        if (walkGoal != null) PathfindingManager.stop();
        if (phase != Phase.IDLE) RotationManager.cancelRotation();
        phase = Phase.IDLE;
        target = null;
        walkGoal = null;
    }

    public void pause(Minecraft client) {
        if (walkGoal != null) PathfindingManager.stop();
        walkGoal = null;
        target = null;
        if (phase != Phase.IDLE) phase = Phase.SEARCHING;
    }

    private static AttackLog.Mode mode() {
        return AttackLog.Mode.values()[AetherConfig.DWARVEN_COMBAT_MODE.get()];
    }

    public void tick(Minecraft client) {
        if (phase == Phase.IDLE || client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        ignored.values().removeIf(until -> until <= now);
        attacks.forgetOld(now);
        confirmDeaths(client);
        if (target != null && (!target.isAlive() || target.isRemoved() || dead(client, target))) {
            target = null;
            lastSwitchAt = now;
        }
        if (target == null) {
            boolean waiting = phase == Phase.WAITING_FOR_RESPAWN || phase == Phase.REPOSITIONING;
            long scanEvery = waiting ? AetherConfig.DWARVEN_MOB_SCAN_MS.get() : 0L;
            if (now - lastSwitchAt < AetherConfig.DWARVEN_SWITCH_MS.get() || now - lastScanAt < scanEvery) {
                if (phase == Phase.REPOSITIONING) tickReposition(client, now);
                return;
            }
            lastScanAt = now;
            Found found = findTarget(client, now);
            if (found == null) {
                noTargets(client, now);
                return;
            }
            if (waiting) {
                DwarvenLog.log("combat: " + found.name + " spawned; resuming hunt");
                if (walkGoal != null) PathfindingManager.stop();
                walkGoal = null;
            }
            if (AetherConfig.DWARVEN_SPAWN_MEMORY.get()) DwarvenStore.SPAWNS.sighted(found.name, found.mob.blockPosition(), now);
            target = found.mob;
            targetSince = now;
            oldStyleSpots = 0;
            outcome = Outcome.RUNNING;
            phase = Phase.ENGAGING;
        }
        if (now - targetSince > TARGET_GIVE_UP_MS) {
            DwarvenLog.log("combat: couldn't finish the mob at " + target.blockPosition().toShortString() + " in time, ignoring it a while");
            ignored.put(target.getId(), now + IGNORE_TARGET_MS);
            if (walkGoal != null) PathfindingManager.stop();
            walkGoal = null;
            target = null;
            return;
        }
        Vec3 body = target.getBoundingBox().getCenter();
        double reach = client.player.getEyePosition().distanceTo(body);
        if (reach <= HIT_REACH && visible(client, body)) {
            if (walkGoal != null) {
                PathfindingManager.stop();
                walkGoal = null;
            }
            hit(client, body, now);
        } else {
            chase(client, now);
        }
    }

    // deaths are confirmed on their own schedule, separately from where the fighter is looking now
    private void confirmDeaths(Minecraft client) {
        for (AttackLog.Entry e : attacks.unresolved()) {
            Entity mob = client.level.getEntity(e.id);
            boolean gone = mob == null || !mob.isAlive() || mob.isRemoved() || dead(client, mob);
            if (gone && attacks.recordDeath(e.id)) {
                DwarvenLog.log("combat: kill confirmed after " + e.hits + (e.hits == 1 ? " hit" : " hits")
                        + String.format(" (one-hit kills %.0f%%, %d kills)", attacks.oneHitRate() * 100, attacks.kills()));
            }
        }
    }

    private void hit(Minecraft client, Vec3 body, long now) {
        int weapon = weaponSlot(client);
        if (weapon >= 0 && FailsafeManager.getCurrentSelectedSlot(client) != weapon) {
            FailsafeManager.selectHotbarSlot(client, weapon);
            return;
        }
        RotationManager.steer(client, body, DwarvenCamera.profile());
        HitResult hit = client.hitResult;
        boolean onTarget = hit instanceof EntityHitResult e && e.getEntity() == target;
        if (!onTarget || now - lastHitAt < HIT_GAP_MS) return;
        ClientUtils.performAttackClick();
        lastHitAt = now;
        hits++;
        attacks.recordHit(target.getId(), now);
        boolean moveOn = attacks.moveOnAfterHit(mode(), AetherConfig.DWARVEN_COMBAT_LEARNING.get())
                && attacks.pendingCount(now, AetherConfig.DWARVEN_CONFIRM_MS.get()) <= AetherConfig.DWARVEN_MAX_PENDING.get();
        if (moveOn) {
            if (!announcedMoveOn) {
                announcedMoveOn = true;
                DwarvenLog.log("combat: attacked; outcome pending, selecting the next target without waiting");
            }
            target = null;
            lastSwitchAt = now;
        }
    }

    private void chase(Minecraft client, long now) {
        BlockPos at = target.blockPosition();
        boolean moved = walkGoal == null || walkGoal.distSqr(at) > 9;
        if ((moved && now - lastPlanAt > REPLAN_MS) || walkResult.get() == 2) walkTo(client, at, now);
    }

    private void noTargets(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        if (!AetherConfig.DWARVEN_RESPAWN_SEARCH.get()) {
            oldStyleNoTargets(client, now, feet);
            return;
        }
        outcome = Outcome.WAITING_FOR_RESPAWN;
        if (phase == Phase.REPOSITIONING) {
            tickReposition(client, now);
            return;
        }
        if (phase != Phase.WAITING_FOR_RESPAWN) {
            DwarvenLog.log("combat: no mobs available; entering respawn wait");
            BlockPos spot = AetherConfig.DWARVEN_REPOSITION_SPAWNS.get()
                    ? DwarvenStore.SPAWNS.best(mobs, feet, now, SPAWN_EMPTY_RECHECK_MS, emptyThisRun) : null;
            if (spot != null && Math.sqrt(spot.distSqr(feet)) > AT_SPAWN) {
                reposition(client, spot, now, "heading to the busiest known spawn at ");
                return;
            }
            phase = Phase.WAITING_FOR_RESPAWN;
            waitSince = now;
            waitSpot = feet;
            return;
        }
        long maxEmpty = AetherConfig.DWARVEN_MAX_EMPTY_SECONDS.get() * 1000L;
        if (now - waitSince < maxEmpty || !AetherConfig.DWARVEN_REPOSITION_SPAWNS.get()) return;
        // this spot stayed empty unusually long; try another known one instead of camping here forever
        DwarvenStore.SPAWNS.empty(waitSpot, mobs, now);
        emptyThisRun.add(nearestCell(waitSpot));
        BlockPos next = DwarvenStore.SPAWNS.best(mobs, feet, now, SPAWN_EMPTY_RECHECK_MS, emptyThisRun);
        if (next == null || Math.sqrt(next.distSqr(feet)) <= AT_SPAWN) {
            emptyThisRun.clear();
            waitSince = now;
            return;
        }
        reposition(client, next, now, "empty for " + maxEmpty / 1000 + "s, trying the spawn at ");
    }

    private BlockPos nearestCell(BlockPos p) {
        BlockPos best = p;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos c : DwarvenStore.SPAWNS.all(mobs)) {
            double d = c.distSqr(p);
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    private void reposition(Minecraft client, BlockPos spot, long now, String why) {
        DwarvenLog.log("combat: " + why + spot.toShortString());
        walkTo(client, spot, now);
        phase = Phase.REPOSITIONING;
    }

    private void tickReposition(Minecraft client, long now) {
        if (walkGoal == null) {
            phase = Phase.WAITING_FOR_RESPAWN;
            waitSince = now;
            waitSpot = client.player.blockPosition();
            return;
        }
        double left = Math.sqrt(walkGoal.distSqr(client.player.blockPosition()));
        if (left < walkBest - 0.5) {
            walkBest = left;
            walkProgressAt = now;
        }
        boolean stuck = now - walkProgressAt > AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L;
        if (walkResult.get() != 0 || left <= 2.5 || stuck) {
            PathfindingManager.stop();
            if (stuck) DwarvenLog.log("combat: stuck walking to " + walkGoal.toShortString() + ", waiting here instead");
            walkGoal = null;
            phase = Phase.WAITING_FOR_RESPAWN;
            waitSince = now;
            waitSpot = client.player.blockPosition();
        }
    }

    // with persistent search off: look at a few known spots, then report that there is nothing here
    private void oldStyleNoTargets(Minecraft client, long now, BlockPos feet) {
        if (phase == Phase.REPOSITIONING) {
            tickReposition(client, now);
            return;
        }
        if (oldStyleSpots >= OLD_STYLE_SPOTS) {
            outcome = Outcome.NO_TARGETS;
            return;
        }
        BlockPos spot = DwarvenStore.SPAWNS.best(mobs, feet, now, 0L, emptyThisRun);
        if (spot == null) {
            outcome = Outcome.NO_TARGETS;
            return;
        }
        oldStyleSpots++;
        emptyThisRun.add(spot);
        reposition(client, spot, now, "none in sight, checking ");
    }

    private void walkTo(Minecraft client, BlockPos goal, long now) {
        PathfindingManager.stop();
        walkGoal = goal;
        walkBest = Math.sqrt(goal.distSqr(client.player.blockPosition()));
        walkProgressAt = now;
        lastPlanAt = now;
        AtomicInteger result = new AtomicInteger();
        walkResult = result;
        PathfindingManager.startConfiguredWalk(client, goal.getX(), goal.getY(), goal.getZ(),
                () -> result.compareAndSet(0, 1), () -> result.compareAndSet(0, 2), true, 1.5, false, true);
    }

    private record Found(LivingEntity mob, String name) {}

    // nearby mobs first; ones we just hit are passed over while their outcome is pending, unless nothing else is left
    private Found findTarget(Minecraft client, long now) {
        Vec3 eye = client.player.getEyePosition();
        Found best = null;
        Found bestPending = null;
        double bestCost = Double.MAX_VALUE;
        double bestPendingCost = Double.MAX_VALUE;
        long revisit = AetherConfig.DWARVEN_REVISIT_MS.get();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            Tag tag = parse(stand.getCustomName());
            if (tag == null || tag.health <= 0 || !wanted(tag.name)) continue;
            LivingEntity mob = bodyUnder(client, stand);
            if (mob == null || ignored.containsKey(mob.getId())) continue;
            double distance = mob.position().distanceTo(eye);
            if (distance > SIGHT) continue;
            // height changes cost more than flat ground since they usually mean a detour
            double cost = distance + Math.abs(mob.getY() - client.player.getY()) * 2.0;
            if (attacks.pending(mob.getId(), now, revisit)) {
                if (cost < bestPendingCost) {
                    bestPendingCost = cost;
                    bestPending = new Found(mob, tag.name);
                }
            } else if (cost < bestCost) {
                bestCost = cost;
                best = new Found(mob, tag.name);
            }
        }
        return best != null ? best : bestPending;
    }

    // whether any living mob with one of these tag names is within range, used to tell if an event is happening here
    public static boolean anyVisible(Minecraft client, Set<String> names, double range) {
        if (client.level == null || client.player == null) return false;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName() || stand.distanceTo(client.player) > range) continue;
            Tag tag = parse(stand.getCustomName());
            if (tag == null || tag.health <= 0) continue;
            for (String n : names) {
                if (n.equalsIgnoreCase(tag.name)) return true;
            }
        }
        return false;
    }

    private boolean wanted(String name) {
        for (String m : mobs) {
            if (name.equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    // the name tag floats above the mob; the mob is the living entity right under it
    private static LivingEntity bodyUnder(Minecraft client, ArmorStand stand) {
        AABB below = new AABB(stand.getX() - 1.0, stand.getY() - 3.5, stand.getZ() - 1.0, stand.getX() + 1.0, stand.getY() + 0.5, stand.getZ() + 1.0);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : client.level.getEntities(stand, below)) {
            if (!(e instanceof LivingEntity living) || e instanceof ArmorStand || e == client.player || !living.isAlive()) continue;
            double d = e.distanceToSqr(stand);
            if (d < bestDistance) {
                bestDistance = d;
                best = living;
            }
        }
        return best;
    }

    private boolean dead(Minecraft client, Entity mob) {
        AABB above = mob.getBoundingBox().inflate(0.5, 1.5, 0.5);
        for (Entity e : client.level.getEntities(mob, above)) {
            if (e instanceof ArmorStand stand && stand.hasCustomName()) {
                Tag tag = parse(stand.getCustomName());
                if (tag != null && wanted(tag.name)) return tag.health <= 0;
            }
        }
        return false;
    }

    private static boolean visible(Minecraft client, Vec3 point) {
        Vec3 eye = client.player.getEyePosition();
        HitResult hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(eye) >= point.distanceTo(eye) - 0.6;
    }

    public record Tag(String name, double health) {}

    static Tag parse(Component name) {
        return parse(TablistUtils.stripColors(name.getString()));
    }

    public static Tag parse(String text) {
        Matcher m = TAG.matcher(text);
        if (!m.find()) return null;
        double health;
        try {
            health = Double.parseDouble(m.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        if (unit.equals("k")) health *= 1_000;
        if (unit.equals("m")) health *= 1_000_000;
        return new Tag(m.group(1).strip(), health);
    }

    // commission mobs are fought with the mining tool, like a player doing commissions would
    public static int weaponSlot(Minecraft client) {
        return dev.aether.modules.dwarven.mine.DwarvenMiner.toolSlot(client);
    }
}
