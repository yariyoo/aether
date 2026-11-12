package dev.aether.modules.dwarven.fight;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// hunts the mobs a slayer or puncher commission asks for: finds them by their name tag, walks over and hits them.
// a hit can move straight on to the next mob while the first one's death is still being confirmed
public final class DwarvenFighter {
    public enum Outcome { RUNNING, WAITING_FOR_RESPAWN, NO_TARGETS, NO_WEAPON }

    private enum Phase { IDLE, SEARCHING, ENGAGING, WAITING_FOR_RESPAWN, REPOSITIONING }

    // hypixel name tags read "[Lv23] Goblin 800/800❤", with font icons between the parts on 26.2
    private static final Pattern TAG = Pattern.compile("\\[Lv\\d+][^A-Za-z]*([A-Za-z' ]+?)\\s+([\\d,.]+)([kKmM]?)/");
    private static final double HIT_REACH = 3.0;
    // wither impact hits around where it lands; looking straight down it lands where we stand
    private static final double WITHER_IMPACT_RADIUS = 6.0;
    private static final double CLUSTER_RADIUS = 6.0;
    private static final long CAST_GAP_MS = 350L;
    private static final double RANGED_REACH = 24.0;
    private static final long SHOT_GAP_MS = 300L;
    private static final float SHOT_AIM_DEGREES = 2.0f;
    private static final long WEAPON_RECHECK_MS = 2000L;
    // a shot or cast is checked for damage this long after it, and a weapon that keeps doing none is dropped
    private static final long DAMAGE_CHECK_MS = 800L;
    private static final int NO_DAMAGE_DROP = 4;
    private static final long REPLAN_MS = 1200L;
    // one swing every few ticks is what a player clicking quickly manages; never faster
    private static final long HIT_GAP_MS = 140L;
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
    private long lastShotAt;
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
    private final Map<Integer, String> names = new HashMap<>();
    private final KillCredit credit = new KillCredit();
    private String area;
    private String zone = "";
    private boolean autoWeapons;
    private Weapons.Choice weapon;
    private long weaponCheckedAt;
    private long lastWeaponLogAt;
    private static final long WEAPON_LOG_MS = 15_000L;
    // weapons found not to work stay dropped for the session
    private static final Set<Weapons.Kind> disabled = EnumSet.noneOf(Weapons.Kind.class);
    private long lastCastAt;
    private Map<Integer, Double> damageSnapshot = Map.of();
    private long snapshotAt;
    private int noDamageStreak;
    private long engagedSince;
    private long engagedMs;

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

    public Weapons.Choice weapon() {
        return weapon;
    }

    public void zone(String zone) {
        this.zone = zone == null ? "" : zone;
    }

    public void progressed(long now) {
        credit.progressed(now);
    }

    public long engagedMs() {
        return engagedMs + (engagedSince > 0 ? System.currentTimeMillis() - engagedSince : 0);
    }

    public String phaseName() {
        return phase.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public void start(Minecraft client, Set<String> mobNames) {
        start(client, mobNames, null, false);
    }

    // area gates which spawns are learned and visited; auto picks the weapon by priority instead of the mining tool
    public void start(Minecraft client, Set<String> mobNames, String area, boolean auto) {
        stop(client);
        mobs = mobNames;
        this.area = area;
        this.autoWeapons = auto;
        weapon = null;
        weaponCheckedAt = 0;
        names.clear();
        credit.reset();
        engagedMs = 0;
        engagedSince = System.currentTimeMillis();
        outcome = Outcome.RUNNING;
        oldStyleSpots = 0;
        announcedMoveOn = false;
        ignored.clear();
        emptyThisRun.clear();
        attacks.reset();
        phase = Phase.SEARCHING;
        DwarvenLog.log("combat: hunting " + mobNames + (area == null ? "" : " in " + area) + ", mode " + mode());
    }

    // weapon choice is redone every couple of seconds since mana comes and goes
    public Weapons.Choice chooseWeapon(Minecraft client, long now) {
        if (weapon != null && now - weaponCheckedAt < WEAPON_RECHECK_MS) return weapon;
        weaponCheckedAt = now;
        int tool = dev.aether.modules.dwarven.mine.DwarvenMiner.toolSlot(client);
        List<Weapons.Item> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var lore = stack.get(DataComponents.LORE);
            List<String> lines = lore == null ? List.of() : lore.lines().stream().map(c -> TablistUtils.stripColors(c.getString())).toList();
            hotbar.add(new Weapons.Item(i, TablistUtils.stripColors(stack.getHoverName().getString()), lines));
        }
        String toolName = tool >= 0 ? client.player.getInventory().getItem(tool).getHoverName().getString() : null;
        Weapons.Prefs prefs = new Weapons.Prefs(autoWeapons && AetherConfig.DWARVEN_AUTO_WEAPON.get(), AetherConfig.DWARVEN_HYPERION.get(),
                AetherConfig.DWARVEN_TERMINATOR.get(), AetherConfig.DWARVEN_OTHER_WEAPONS.get(), AetherConfig.DWARVEN_TOOL_FALLBACK.get());
        Weapons.Choice next = Weapons.choose(hotbar, tool, toolName, Mana.current(now), prefs, disabled);
        boolean changed = weapon == null || weapon.kind() != next.kind() || weapon.slot() != next.slot();
        // spending mana and regaining it swaps hyperion and the next weapon back and forth; that's not news every time
        if (changed && (next.kind() == null || now - lastWeaponLogAt > WEAPON_LOG_MS)) {
            lastWeaponLogAt = now;
            DwarvenLog.log("combat: fighting with " + (next.kind() == null ? "nothing" : next.name() + " as " + next.kind().name().toLowerCase(Locale.ROOT))
                    + ": " + next.reason());
        }
        weapon = next;
        return weapon;
    }

    public static void resetDisabledWeapons() {
        disabled.clear();
    }

    public void stop(Minecraft client) {
        if (engagedSince > 0) engagedMs += System.currentTimeMillis() - engagedSince;
        engagedSince = 0;
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
        String droppedType = credit.tick(now);
        if (droppedType != null) {
            DwarvenLog.log("combat: " + KillCredit.DROP_AFTER + " " + droppedType + " kills never moved the commission; leaving that mob alone");
        }
        checkDamage(client, now);
        Weapons.Choice w = chooseWeapon(client, now);
        if (w.kind() == null) {
            outcome = Outcome.NO_WEAPON;
            return;
        }
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
            // only sightings where the scoreboard shows the hunt's area are learned, so a raid elsewhere can't pollute them
            if (AetherConfig.DWARVEN_SPAWN_MEMORY.get() && inHuntArea()) DwarvenStore.SPAWNS.sighted(found.name, found.mob.blockPosition(), now);
            target = found.mob;
            targetSince = now;
            oldStyleSpots = 0;
            outcome = Outcome.RUNNING;
            phase = Phase.ENGAGING;
        }
        if (now - targetSince > AetherConfig.DWARVEN_MAX_ENGAGE_SECONDS.get() * 1000L) {
            DwarvenLog.log("combat: couldn't finish the mob at " + target.blockPosition().toShortString() + " in time, ignoring it a while");
            ignored.put(target.getId(), now + IGNORE_TARGET_MS);
            if (walkGoal != null) PathfindingManager.stop();
            walkGoal = null;
            target = null;
            return;
        }
        Vec3 body = target.getBoundingBox().getCenter();
        double reach = client.player.getEyePosition().distanceTo(body);
        switch (w.kind()) {
            case HYPERION -> {
                if (inImpactRange(client) > 0) {
                    stopWalking();
                    cast(client, w, now);
                } else {
                    chase(client, now);
                }
            }
            case TERMINATOR, SHORTBOW -> {
                if (reach <= RANGED_REACH && visible(client, body)) {
                    // no need to walk up to something we can shoot from here
                    stopWalking();
                    shoot(client, w, body, now);
                } else {
                    chase(client, now);
                }
            }
            default -> {
                if (reach <= HIT_REACH && visible(client, body)) {
                    stopWalking();
                    hit(client, w.slot(), body, now);
                } else {
                    chase(client, now);
                }
            }
        }
    }

    private void stopWalking() {
        if (walkGoal != null) {
            PathfindingManager.stop();
            walkGoal = null;
        }
    }

    private boolean inHuntArea() {
        return area == null || zone.isEmpty() || zone.equalsIgnoreCase(area);
    }

    private List<LivingEntity> eligibleNear(Minecraft client, Vec3 centre, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName() || stand.position().distanceTo(centre) > radius + 2.5) continue;
            Tag tag = parse(stand.getCustomName());
            if (tag == null || tag.health <= 0 || !wanted(tag.name)) continue;
            LivingEntity mob = bodyUnder(client, stand);
            if (mob != null && mob.position().distanceTo(centre) <= radius) out.add(mob);
        }
        return out;
    }

    private int inImpactRange(Minecraft client) {
        return eligibleNear(client, client.player.position(), WITHER_IMPACT_RADIUS - 0.5).size();
    }

    // looking straight down, wither impact lands where we stand and hits everything around us
    private void cast(Minecraft client, Weapons.Choice w, long now) {
        if (FailsafeManager.getCurrentSelectedSlot(client) != w.slot()) {
            FailsafeManager.selectHotbarSlot(client, w.slot());
            return;
        }
        RotationManager.steerAngles(client, client.player.getYRot(), 89.0f, DwarvenCamera.profile());
        if (client.player.getXRot() < 85.0f || now - lastCastAt < CAST_GAP_MS) return;
        int mana = Mana.current(now);
        if (mana >= 0 && mana < w.manaCost()) {
            weaponCheckedAt = 0;
            return;
        }
        List<LivingEntity> hitBy = eligibleNear(client, client.player.position(), WITHER_IMPACT_RADIUS);
        if (hitBy.isEmpty()) return;
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        ClientUtils.performUseClickInstant();
        lastCastAt = now;
        hits++;
        for (LivingEntity m : hitBy) attacks.recordHit(m.getId(), now);
        snapshot(client, hitBy, now);
        // the next target is picked straight away; deaths are confirmed on their own
        target = null;
        lastSwitchAt = now;
    }

    private void shoot(Minecraft client, Weapons.Choice w, Vec3 body, long now) {
        if (FailsafeManager.getCurrentSelectedSlot(client) != w.slot()) {
            FailsafeManager.selectHotbarSlot(client, w.slot());
            return;
        }
        RotationManager.steer(client, body, DwarvenCamera.profile());
        RotationUtils.Rotation want = RotationUtils.calculateLookAt(client.player.getEyePosition(), body);
        float yawOff = Math.abs(net.minecraft.util.Mth.wrapDegrees(want.yaw - client.player.getYRot()));
        float pitchOff = Math.abs(want.pitch - client.player.getXRot());
        if (yawOff > SHOT_AIM_DEGREES || pitchOff > SHOT_AIM_DEGREES || now - lastShotAt < SHOT_GAP_MS) return;
        ClientUtils.performUseClickInstant();
        lastShotAt = now;
        hits++;
        attacks.recordHit(target.getId(), now);
        snapshot(client, List.of(target), now);
        if (attacks.moveOnAfterHit(mode(), AetherConfig.DWARVEN_COMBAT_LEARNING.get())) {
            target = null;
            lastSwitchAt = now;
        }
    }

    private void snapshot(Minecraft client, List<LivingEntity> mobsHit, long now) {
        if (now - snapshotAt < DAMAGE_CHECK_MS && !damageSnapshot.isEmpty()) return;
        Map<Integer, Double> health = new HashMap<>();
        for (LivingEntity m : mobsHit) health.put(m.getId(), healthOf(client, m));
        damageSnapshot = health;
        snapshotAt = now;
    }

    // a weapon whose hits never take health off or kill anything is dropped for the next one in the priority list
    private void checkDamage(Minecraft client, long now) {
        if (damageSnapshot.isEmpty() || now - snapshotAt < DAMAGE_CHECK_MS || weapon == null || weapon.kind() == null) return;
        boolean landed = false;
        for (var e : damageSnapshot.entrySet()) {
            Entity mob = client.level.getEntity(e.getKey());
            double after = mob instanceof LivingEntity l && l.isAlive() && !l.isRemoved() ? healthOf(client, l) : 0;
            if (after < e.getValue()) landed = true;
        }
        damageSnapshot = Map.of();
        noDamageStreak = landed ? 0 : noDamageStreak + 1;
        if (noDamageStreak >= NO_DAMAGE_DROP && weapon.kind() != Weapons.Kind.TOOL) {
            DwarvenLog.log("combat: " + NO_DAMAGE_DROP + " " + weapon.kind().name().toLowerCase(Locale.ROOT)
                    + " attacks in a row did no damage; falling back to the next weapon");
            disabled.add(weapon.kind());
            noDamageStreak = 0;
            weaponCheckedAt = 0;
        }
    }

    private double healthOf(Minecraft client, Entity mob) {
        AABB above = mob.getBoundingBox().inflate(0.5, 1.5, 0.5);
        for (Entity e : client.level.getEntities(mob, above)) {
            if (e instanceof ArmorStand stand && stand.hasCustomName()) {
                Tag tag = parse(stand.getCustomName());
                if (tag != null && wanted(tag.name)) return tag.health;
            }
        }
        return mob instanceof LivingEntity l ? l.getHealth() : 0;
    }

    // deaths are confirmed on their own schedule, separately from where the fighter is looking now
    private void confirmDeaths(Minecraft client) {
        for (AttackLog.Entry e : attacks.unresolved()) {
            Entity mob = client.level.getEntity(e.id);
            boolean gone = mob == null || !mob.isAlive() || mob.isRemoved() || dead(client, mob);
            if (gone && attacks.recordDeath(e.id)) {
                String type = names.get(e.id);
                if (type != null) credit.killed(type, System.currentTimeMillis());
                dev.aether.modules.dwarven.DwarvenCommissionMacro.STATS.kill();
                DwarvenLog.log("combat: kill confirmed after " + e.hits + (e.hits == 1 ? " hit" : " hits")
                        + String.format(" (one-hit kills %.0f%%, %d kills)", attacks.oneHitRate() * 100, attacks.kills()));
            }
        }
    }

    private void hit(Minecraft client, int slot, Vec3 body, long now) {
        if (slot >= 0 && FailsafeManager.getCurrentSelectedSlot(client) != slot) {
            FailsafeManager.selectHotbarSlot(client, slot);
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
        snapshot(client, List.of(target), now);
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
                    ? DwarvenStore.SPAWNS.best(mobs, feet, now, SPAWN_EMPTY_RECHECK_MS, emptyThisRun, this::spawnInArea) : null;
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
        BlockPos next = DwarvenStore.SPAWNS.best(mobs, feet, now, SPAWN_EMPTY_RECHECK_MS, emptyThisRun, this::spawnInArea);
        if (next == null || Math.sqrt(next.distSqr(feet)) <= AT_SPAWN) {
            emptyThisRun.clear();
            waitSince = now;
            return;
        }
        reposition(client, next, now, "empty for " + maxEmpty / 1000 + "s, trying the spawn at ");
    }

    private boolean spawnInArea(BlockPos p) {
        return DwarvenStore.AREAS.allows(area, p);
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
        BlockPos spot = DwarvenStore.SPAWNS.best(mobs, feet, now, 0L, emptyThisRun, this::spawnInArea);
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
            if (distance > AetherConfig.DWARVEN_TARGET_RADIUS.get()) continue;
            if (area != null && !DwarvenStore.AREAS.allows(area, mob.blockPosition())) continue;
            names.put(mob.getId(), tag.name);
            double cost = targetCost(client, mob, distance, eye);
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

    // what it takes to get this mob with the weapon in hand: a shot from here, a walk into a crowd, or a walk up to it
    private double targetCost(Minecraft client, LivingEntity mob, double distance, Vec3 eye) {
        // height changes cost more than flat ground since they usually mean a detour
        double cost = distance + Math.abs(mob.getY() - client.player.getY()) * 2.0;
        Weapons.Kind kind = weapon == null ? Weapons.Kind.TOOL : weapon.kind();
        if (kind == Weapons.Kind.TERMINATOR || kind == Weapons.Kind.SHORTBOW) {
            boolean shootable = distance <= RANGED_REACH && visible(client, mob.getBoundingBox().getCenter());
            return shootable ? distance * 0.25 : cost + 20;
        }
        if (kind == Weapons.Kind.HYPERION && AetherConfig.DWARVEN_PREFER_CLUSTERS.get()) {
            cost -= 4.0 * (eligibleNear(client, mob.position(), CLUSTER_RADIUS).size() - 1);
        }
        return cost;
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
        if (!credit.counts(name)) return false;
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
