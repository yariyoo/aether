package dev.aether.modules.dwarven.fight;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.memory.HuntPoints;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// hunts the mobs a slayer or puncher commission asks for: finds them by their name tag, walks over and hits them
public final class DwarvenFighter {
    public enum Outcome { RUNNING, NO_TARGETS }

    private enum Phase { IDLE, SEEK, CHASE, HIT, ROAM }

    // hypixel name tags read "[Lv23] Goblin 800/800❤", with font icons between the parts on 26.2
    private static final Pattern TAG = Pattern.compile("\\[Lv\\d+][^A-Za-z]*([A-Za-z' ]+?)\\s+([\\d,.]+)([kKmM]?)/");
    private static final double HIT_REACH = 3.0;
    private static final double SIGHT = 48.0;
    private static final long REPLAN_MS = 1200L;
    private static final long HIT_GAP_MS = 140L;
    private static final long TARGET_GIVE_UP_MS = 12_000L;
    private static final long IGNORE_TARGET_MS = 15_000L;
    private static final long ROAM_POINT_RECHECK_MS = 60_000L;
    private static final int ROAM_POINTS_BEFORE_GIVING_UP = 4;

    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.RUNNING;
    private Set<String> mobs = Set.of();
    private Entity target;
    private long targetSince;
    private long lastHitAt;
    private long lastPlanAt;
    private BlockPos walkGoal;
    private double walkBest;
    private long walkProgressAt;
    private AtomicInteger walkResult = new AtomicInteger();
    private int hits;
    private int roamed;
    private final Map<Integer, Long> ignored = new HashMap<>();
    private final Map<BlockPos, Long> roamedAt = new HashMap<>();

    public Outcome outcome() {
        return outcome;
    }

    public boolean running() {
        return phase != Phase.IDLE;
    }

    public int hits() {
        return hits;
    }

    public Entity target() {
        return target;
    }

    public void start(Minecraft client, Set<String> mobNames) {
        stop(client);
        mobs = mobNames;
        outcome = Outcome.RUNNING;
        roamed = 0;
        ignored.clear();
        phase = Phase.SEEK;
        DwarvenLog.log("fighter: hunting " + mobNames);
    }

    public void stop(Minecraft client) {
        if (phase == Phase.CHASE || phase == Phase.ROAM) PathfindingManager.stop();
        if (phase != Phase.IDLE) RotationManager.cancelRotation();
        phase = Phase.IDLE;
        target = null;
        walkGoal = null;
    }

    public void pause(Minecraft client) {
        if (phase == Phase.CHASE || phase == Phase.ROAM) PathfindingManager.stop();
        walkGoal = null;
        if (phase != Phase.IDLE) phase = Phase.SEEK;
    }

    public void tick(Minecraft client) {
        if (phase == Phase.IDLE || client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        ignored.values().removeIf(until -> until <= now);
        if (target != null && (!target.isAlive() || target.isRemoved() || dead(client, target))) {
            DwarvenLog.log("fighter: target down after " + (now - targetSince) + "ms");
            target = null;
            phase = Phase.SEEK;
        }
        if (target == null || phase == Phase.ROAM) {
            Entity found = findTarget(client);
            if (found != null) {
                if (phase == Phase.ROAM) PathfindingManager.stop();
                target = found;
                targetSince = now;
                roamed = 0;
                walkGoal = null;
                phase = Phase.CHASE;
                DwarvenLog.log("fighter: target at " + found.blockPosition().toShortString() + ", "
                        + String.format("%.1f", found.distanceTo(client.player)) + " blocks");
            } else if (phase != Phase.ROAM) {
                roam(client, now);
                return;
            }
        }
        if (phase == Phase.ROAM) {
            tickRoam(client, now);
            return;
        }
        if (now - targetSince > TARGET_GIVE_UP_MS) {
            DwarvenLog.log("fighter: couldn't finish the target at " + target.blockPosition().toShortString() + " in time, ignoring it a while");
            ignored.put(target.getId(), now + IGNORE_TARGET_MS);
            PathfindingManager.stop();
            target = null;
            phase = Phase.SEEK;
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

    private void hit(Minecraft client, Vec3 body, long now) {
        phase = Phase.HIT;
        int weapon = weaponSlot(client);
        if (weapon >= 0 && FailsafeManager.getCurrentSelectedSlot(client) != weapon) {
            FailsafeManager.selectHotbarSlot(client, weapon);
            return;
        }
        RotationManager.steer(client, body, DwarvenCamera.profile());
        HitResult hit = client.hitResult;
        boolean onTarget = hit instanceof EntityHitResult e && e.getEntity() == target;
        if (onTarget && now - lastHitAt >= HIT_GAP_MS) {
            ClientUtils.performAttackClick();
            lastHitAt = now;
            hits++;
        }
    }

    private void chase(Minecraft client, long now) {
        BlockPos at = target.blockPosition();
        boolean moved = walkGoal == null || walkGoal.distSqr(at) > 9;
        if (phase != Phase.CHASE || (moved && now - lastPlanAt > REPLAN_MS) || walkResult.get() == 2) {
            phase = Phase.CHASE;
            walkTo(client, at, now);
        }
        // keep looking where we're going so the turn to hit is short once in reach
        if (client.player.distanceTo(target) < 8) RotationManager.steer(client, target.getBoundingBox().getCenter(), DwarvenCamera.profile());
    }

    private void roam(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        roamedAt.values().removeIf(at -> now - at > ROAM_POINT_RECHECK_MS);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos p : HuntPoints.points(mobs)) {
            if (roamedAt.containsKey(p) || p.distSqr(feet) < 16) continue;
            double d = p.distSqr(feet);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        if (best == null || roamed >= ROAM_POINTS_BEFORE_GIVING_UP) {
            if (outcome != Outcome.NO_TARGETS) DwarvenLog.log("fighter: no " + mobs + " found after checking " + roamed + " spots");
            outcome = Outcome.NO_TARGETS;
            return;
        }
        roamed++;
        roamedAt.put(best, now);
        DwarvenLog.log("fighter: none in sight, checking " + best.toShortString() + " where they were fought before");
        walkTo(client, best, now);
        phase = Phase.ROAM;
    }

    private void tickRoam(Minecraft client, long now) {
        double left = Math.sqrt(walkGoal.distSqr(client.player.blockPosition()));
        if (left < walkBest - 0.5) {
            walkBest = left;
            walkProgressAt = now;
        }
        boolean stuck = now - walkProgressAt > AetherConfig.DWARVEN_STUCK_SECONDS.get() * 1000L;
        if (walkResult.get() != 0 || left <= 2.5 || stuck) {
            PathfindingManager.stop();
            if (stuck) DwarvenLog.log("fighter: stuck walking to " + walkGoal.toShortString());
            walkGoal = null;
            phase = Phase.SEEK;
        }
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

    private Entity findTarget(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Entity best = null;
        double bestCost = Double.MAX_VALUE;
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
            if (cost < bestCost) {
                bestCost = cost;
                best = mob;
            }
        }
        return best;
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
