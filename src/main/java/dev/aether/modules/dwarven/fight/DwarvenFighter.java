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
import dev.aether.modules.dwarven.camera.CameraProfile;
import dev.aether.modules.dwarven.diag.Diag;
import dev.aether.modules.dwarven.world.LiveOreWorld;
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
    // wither impact hits around where it lands; looking straight down it lands where we stand
    private static final double WITHER_IMPACT_RADIUS = 6.0;
    private static final double CLUSTER_RADIUS = 6.0;
    private static final long CAST_GAP_MS = 350L;
    private static final float IMPACT_AIM_DEGREES = 3.0f;
    // the teleport and the mana drop both show up within a few ticks of the click
    private static final long CAST_VERIFY_MS = 700L;
    private static final long COOLDOWN_SUSPEND_MS = 1000L;
    // once mana runs out, melee for at least this long so the two modes don't flicker
    private static final long MIN_SUSPEND_MS = 1500L;
    private static final long SHOT_GAP_MS = 300L;
    private static final float SHOT_AIM_DEGREES = 2.0f;
    // vanilla arrow physics per tick; a shortbow shot leaves at about full bow speed
    private static final double ARROW_SPEED = 3.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static final long SHOT_VERIFY_MS = 400L;
    private static final int NO_ARROW_LIMIT = 3;
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
    private final Map<Integer, BlockPos> lastPos = new HashMap<>();
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
    private long abilitySuspendedUntil;
    private boolean manaSuspended;
    private long manaSuspendedAt;
    private String abilityState = "ready";
    private boolean castPending;
    private Vec3 castFrom = Vec3.ZERO;
    private int castMana = -1;
    private boolean castMoves;
    private int casts;
    private int castFailures;
    private boolean shotPending;
    private int shots;
    private Set<Integer> arrowsBefore = Set.of();
    private int arrowlessShots;
    private boolean arrowsSeen;
    private boolean triedOtherInput;
    private boolean autoLeft;
    private String combatMode;
    private String lastFailure;
    private String scanNote = "-";
    private long scanNoteAt;
    private long offTargetSince;
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
        lastPos.clear();
        credit.reset();
        engagedMs = 0;
        engagedSince = System.currentTimeMillis();
        combatMode = null;
        lastFailure = null;
        scanNote = "-";
        manaSuspended = false;
        abilitySuspendedUntil = 0;
        abilityState = "ready";
        castPending = false;
        shotPending = false;
        castFailures = 0;
        arrowlessShots = 0;
        casts = 0;
        shots = 0;
        offTargetSince = 0;
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
            hotbar.add(new Weapons.Item(i, skyblockId(stack), TablistUtils.stripColors(stack.getHoverName().getString()), lines));
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

    // hypixel's own item id, which identifies a hyperion or terminator whatever it's been renamed or reforged to
    private static String skyblockId(ItemStack stack) {
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return "";
        var tag = custom.copyTag();
        return tag.getString("id").orElseGet(() -> tag.getCompound("ExtraAttributes").flatMap(a -> a.getString("id")).orElse(""));
    }

    // what the fighter is doing right now, for the diagnostic snapshot
    public void describe(StringBuilder b, Minecraft client, long now) {
        b.append(" fighter=").append(phase.name().toLowerCase(Locale.ROOT)).append(" combatMode=").append(combatMode);
        if (weapon != null) b.append(" weapon=\"").append(weapon.kind() == null ? "none" : weapon.name()).append('"');
        if (target != null) {
            b.append(" target=").append(names.getOrDefault(target.getId(), "?")).append('@').append(target.blockPosition().toShortString());
            b.append(String.format(" dtarget=%.1f", target.position().distanceTo(client.player.position())));
        }
        b.append(" ability=").append(abilityState);
        int mana = Mana.current(now);
        if (mana >= 0) b.append(" mana=").append(mana).append('/').append(Mana.max());
        b.append(" scan=\"").append(scanNote).append('"');
        if (lastFailure != null) b.append(" attackProblem=\"").append(lastFailure).append('"');
        b.append(" kills=").append(attacks.kills()).append(" casts=").append(casts).append(" shots=").append(shots);
    }

    // label and value pairs for the hud's combat debug rows; each row is switched on by its own setting
    public List<String[]> debugRows(Minecraft client, long now) {
        List<String[]> rows = new ArrayList<>();
        if (phase == Phase.IDLE) return rows;
        if (AetherConfig.DWARVEN_DEBUG_WEAPON.get() && weapon != null)
            rows.add(new String[]{"Weapon", weapon.kind() == null ? "none" : weapon.name()});
        if (AetherConfig.DWARVEN_DEBUG_MODE.get()) rows.add(new String[]{"Mode", combatMode == null ? "-" : combatMode.toLowerCase(Locale.ROOT).replace('_', ' ')});
        if (AetherConfig.DWARVEN_DEBUG_TARGET.get()) rows.add(new String[]{"Target", target == null ? scanNote : names.getOrDefault(target.getId(), "?")});
        if (AetherConfig.DWARVEN_DEBUG_DISTANCE.get() && target != null)
            rows.add(new String[]{"Distance", String.format("%.1f", target.position().distanceTo(client.player.position()))});
        if (AetherConfig.DWARVEN_DEBUG_ABILITY.get() && weapon != null && weapon.impact()) rows.add(new String[]{"Ability", abilityState});
        if (AetherConfig.DWARVEN_DEBUG_MANA.get() && weapon != null && weapon.impact()) {
            int mana = Mana.current(now);
            rows.add(new String[]{"Mana", (mana < 0 ? "?" : mana + "/" + Mana.max()) + (manaSuspended ? ", melee until it recovers" : "")});
        }
        if (AetherConfig.DWARVEN_DEBUG_INPUT.get()) {
            String input = combatMode != null && (combatMode.equals("TERMINATOR") || combatMode.equals("SHORTBOW")) ? (fireWithLeft() ? "left click" : "right click")
                    : combatMode != null && combatMode.equals("HYPERION_IMPACT") ? "right click" : "left click";
            rows.add(new String[]{"Input", input + (dev.aether.bootstrap.AetherBootstrapHooks.isAttackSuppressed() ? " (suppressed)" : "")});
        }
        if (AetherConfig.DWARVEN_DEBUG_FAILURE.get() && lastFailure != null) rows.add(new String[]{"Attack problem", lastFailure});
        if (AetherConfig.DWARVEN_DEBUG_KILLS.get()) rows.add(new String[]{"Confirmed kills", String.valueOf(attacks.kills())});
        return rows;
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
        verifyCast(client, now);
        verifyShot(client, now);
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
            case HYPERION -> hyperion(client, w, body, reach, now);
            case TERMINATOR, SHORTBOW -> ranged(client, w, body, reach, now);
            default -> melee(client, w, body, reach, now, "MELEE");
        }
    }

    private void melee(Minecraft client, Weapons.Choice w, Vec3 body, double reach, long now, String mode) {
        setMode(mode);
        if (reach <= AetherConfig.DWARVEN_MELEE_RANGE.get() && visible(client, body)) {
            stopWalking();
            hit(client, w.slot(), body, now);
        } else {
            lastFailure = null;
            chase(client, now);
        }
    }

    // wither impact while it can fire; left clicks with the same blade while mana recovers; walking only when nothing
    // is within one teleport
    private void hyperion(Minecraft client, Weapons.Choice w, Vec3 body, double reach, long now) {
        if (w.impact() && AetherConfig.DWARVEN_WITHER_IMPACT.get() && impactReady(w, now)) {
            ImpactPlanner.Plan plan = planImpact(client);
            if (plan != null) {
                setMode("HYPERION_IMPACT");
                stopWalking();
                castAt(client, w, plan, now);
                return;
            }
            setMode("HYPERION_APPROACH");
            chase(client, now);
            return;
        }
        if (!w.impact() || AetherConfig.DWARVEN_MELEE_FALLBACK.get()) {
            melee(client, w, body, reach, now, w.impact() ? "HYPERION_MELEE" : "MELEE");
            return;
        }
        setMode("HYPERION_WAITING_FOR_MANA");
        stopWalking();
    }

    // whether wither impact may be used now; low mana is held until it reaches the recovery mark, so the two modes
    // don't swap every tick
    private boolean impactReady(Weapons.Choice w, long now) {
        if (now < abilitySuspendedUntil) return false;
        int mana = Mana.current(now);
        int min = AetherConfig.DWARVEN_MIN_MANA.get() > 0 ? AetherConfig.DWARVEN_MIN_MANA.get() : w.manaCost();
        if (manaSuspended) {
            int recover = AetherConfig.DWARVEN_MANA_RECOVERY.get() > 0 ? AetherConfig.DWARVEN_MANA_RECOVERY.get() : min * 2;
            if (Mana.max() > 0) recover = Math.min(recover, Mana.max());
            boolean back = mana >= recover
                    || mana < 0 && now - manaSuspendedAt > AetherConfig.DWARVEN_ABILITY_FAIL_COOLDOWN.get() * 1000L;
            if (!AetherConfig.DWARVEN_REENABLE_ABILITY.get() || !back || now - manaSuspendedAt < MIN_SUSPEND_MS) return false;
            manaSuspended = false;
            abilityState = "ready";
            DwarvenLog.log("[HYPERION] mana recovered" + (mana >= 0 ? " (" + mana + ")" : "") + "; resuming Wither Impact");
        }
        if (mana >= 0 && mana < min) {
            suspendForMana(now, "mana " + mana + " is below " + min);
            return false;
        }
        return true;
    }

    private void suspendForMana(long now, String why) {
        if (manaSuspended) return;
        manaSuspended = true;
        manaSuspendedAt = now;
        abilityState = "no mana";
        DwarvenLog.log("[HYPERION] insufficient mana (" + why + "); "
                + (AetherConfig.DWARVEN_MELEE_FALLBACK.get() ? "switching to left-click melee" : "waiting for it to recover"));
    }

    private ImpactPlanner.Plan planImpact(Minecraft client) {
        List<ImpactPlanner.Target> near = new ArrayList<>();
        for (LivingEntity m : eligibleNear(client, client.player.position(), ImpactPlanner.RANGE + WITHER_IMPACT_RADIUS)) {
            near.add(new ImpactPlanner.Target(m.getId(), m.position()));
        }
        if (near.isEmpty()) return null;
        LiveOreWorld world = new LiveOreWorld(client.level, client.player);
        return ImpactPlanner.best(world, client.player.position(), client.player.getEyePosition(), client.player.getYRot(), near,
                WITHER_IMPACT_RADIUS, AetherConfig.DWARVEN_PREFER_CLUSTERS.get());
    }

    private void castAt(Minecraft client, Weapons.Choice w, ImpactPlanner.Plan plan, long now) {
        if (FailsafeManager.getCurrentSelectedSlot(client) != w.slot()) {
            FailsafeManager.selectHotbarSlot(client, w.slot());
            return;
        }
        RotationManager.steerAngles(client, plan.yaw(), plan.pitch(), DwarvenCamera.profile(DwarvenCamera.Activity.COMBAT));
        float yawOff = plan.moves() ? Math.abs(net.minecraft.util.Mth.wrapDegrees(plan.yaw() - client.player.getYRot())) : 0;
        float pitchOff = Math.abs(plan.pitch() - client.player.getXRot());
        if (yawOff > IMPACT_AIM_DEGREES || pitchOff > (plan.moves() ? IMPACT_AIM_DEGREES : 5.0f) || now - lastCastAt < CAST_GAP_MS) return;
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        ClientUtils.performUseClickInstant();
        lastCastAt = now;
        castPending = true;
        castFrom = client.player.position();
        castMana = Mana.current(now);
        castMoves = plan.moves();
        casts++;
        hits++;
        for (int id : plan.hits()) attacks.recordHit(id, now);
        List<LivingEntity> hitBy = new ArrayList<>();
        for (int id : plan.hits()) if (client.level.getEntity(id) instanceof LivingEntity l) hitBy.add(l);
        snapshot(client, hitBy, now);
        Diag.event(Diag.COMBAT, String.format("[HYPERION] ability activation requested: %s, %d goblins in the blast", plan.moves()
                ? "teleport to " + fmt(plan.landing()) : "in place", plan.hits().size()));
        // the next target or cluster is picked straight away; deaths are confirmed on their own
        target = null;
        lastSwitchAt = now;
    }

    // after a cast: a teleport, a mana drop or a refusal from the server says what happened. silence repeated means
    // the ability isn't working right now, which is not the same as having no mana
    private void verifyCast(Minecraft client, long now) {
        if (!castPending || now - lastCastAt < CAST_VERIFY_MS) return;
        castPending = false;
        int mana = Mana.current(now);
        boolean moved = client.player.position().distanceTo(castFrom) >= 2.0;
        boolean spent = castMana >= 0 && mana >= 0 && mana < castMana;
        if (Mana.noManaAt() >= lastCastAt) {
            castFailures = 0;
            suspendForMana(now, "the server said not enough mana");
            return;
        }
        if (Mana.cooldownAt() >= lastCastAt) {
            abilitySuspendedUntil = now + COOLDOWN_SUSPEND_MS;
            abilityState = "on cooldown";
            Diag.event(Diag.COMBAT, "[HYPERION] ability on cooldown; swinging meanwhile");
            return;
        }
        if (moved || spent || !castMoves && mana < 0) {
            castFailures = 0;
            abilityState = "ready";
            Diag.event(Diag.COMBAT, "[HYPERION] ability executed (" + (moved ? "teleported" : spent ? "mana spent" : "no mana reading to check") + ")");
            return;
        }
        castFailures++;
        if (castFailures >= AetherConfig.DWARVEN_MAX_ABILITY_FAILURES.get()) {
            castFailures = 0;
            abilitySuspendedUntil = now + AetherConfig.DWARVEN_ABILITY_FAIL_COOLDOWN.get() * 1000L;
            boolean held = weapon != null && FailsafeManager.getCurrentSelectedSlot(client) == weapon.slot();
            abilityState = held ? "no response" : "item not held";
            DwarvenLog.log("[HYPERION] " + AetherConfig.DWARVEN_MAX_ABILITY_FAILURES.get() + " casts showed no teleport and no mana spent ("
                    + (held ? "no response from the server" : "the Hyperion wasn't in hand") + "); using melee for "
                    + AetherConfig.DWARVEN_ABILITY_FAIL_COOLDOWN.get() + "s");
        }
    }

    // a shot from where we stand when the goblin is visible and in range; otherwise walk until it is
    private void ranged(Minecraft client, Weapons.Choice w, Vec3 body, double reach, long now) {
        setMode(w.kind() == Weapons.Kind.TERMINATOR ? "TERMINATOR" : "SHORTBOW");
        boolean clear = !AetherConfig.DWARVEN_RANGED_LOS.get() || visible(client, body);
        if (reach <= AetherConfig.DWARVEN_RANGED_DISTANCE.get() && clear) {
            stopWalking();
            shoot(client, w, body, reach, now);
        } else {
            lastFailure = reach > AetherConfig.DWARVEN_RANGED_DISTANCE.get() ? "target out of range" : "no clear shot";
            chase(client, now);
        }
    }

    private void shoot(Minecraft client, Weapons.Choice w, Vec3 body, double distance, long now) {
        if (FailsafeManager.getCurrentSelectedSlot(client) != w.slot()) {
            FailsafeManager.selectHotbarSlot(client, w.slot());
            return;
        }
        Vec3 aim = body;
        double ticks = distance / ARROW_SPEED;
        if (AetherConfig.DWARVEN_RANGED_PREDICTION.get()) {
            // lead a moving goblin by where it will be when the arrow gets there
            aim = aim.add((target.getX() - target.xo) * ticks, 0, (target.getZ() - target.zo) * ticks);
        }
        // arrows drop on the way; aim that much higher
        aim = aim.add(0, 0.5 * ARROW_GRAVITY * ticks * ticks, 0);
        CameraProfile profile = AetherConfig.DWARVEN_RANGED_SMOOTH.get() ? DwarvenCamera.profile(DwarvenCamera.Activity.COMBAT) : DwarvenCamera.quick();
        RotationManager.steer(client, aim, profile);
        RotationUtils.Rotation want = RotationUtils.calculateLookAt(client.player.getEyePosition(), aim);
        float yawOff = Math.abs(net.minecraft.util.Mth.wrapDegrees(want.yaw - client.player.getYRot()));
        float pitchOff = Math.abs(want.pitch - client.player.getXRot());
        if (yawOff > SHOT_AIM_DEGREES || pitchOff > SHOT_AIM_DEGREES || now - lastShotAt < SHOT_GAP_MS) return;
        arrowsBefore = arrowsNear(client);
        if (fireWithLeft()) ClientUtils.performAttackClick();
        else ClientUtils.performUseClickInstant();
        lastShotAt = now;
        shotPending = true;
        shots++;
        hits++;
        attacks.recordHit(target.getId(), now);
        snapshot(client, List.of(target), now);
        if (shots == 1 || shots % 25 == 0) Diag.event(Diag.COMBAT, "[TERMINATOR] firing interaction executed (" + (fireWithLeft() ? "left" : "right") + " click)");
        if (attacks.moveOnAfterHit(mode(), AetherConfig.DWARVEN_COMBAT_LEARNING.get())) {
            target = null;
            lastSwitchAt = now;
        }
    }

    private boolean fireWithLeft() {
        int input = AetherConfig.DWARVEN_TERMINATOR_INPUT.get();
        return input == 2 || input == 0 && autoLeft;
    }

    private Set<Integer> arrowsNear(Minecraft client) {
        Set<Integer> out = new HashSet<>();
        Vec3 eye = client.player.getEyePosition();
        for (Entity e : client.level.entitiesForRendering()) {
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            boolean arrow = type.equals("arrow") || type.equals("spectral_arrow");
            if (arrow && e.position().distanceTo(eye) < 5) out.add(e.getId());
        }
        return out;
    }

    // a shot is only real if an arrow left the bow; with the automatic input, a click that never produces arrows is
    // swapped for the other one before the weapon is given up on
    private void verifyShot(Minecraft client, long now) {
        if (!shotPending || now - lastShotAt < SHOT_VERIFY_MS) return;
        shotPending = false;
        boolean fired = false;
        for (Integer id : arrowsNear(client)) if (!arrowsBefore.contains(id)) fired = true;
        if (fired) {
            arrowlessShots = 0;
            arrowsSeen = true;
            return;
        }
        if (++arrowlessShots < NO_ARROW_LIMIT) return;
        arrowlessShots = 0;
        if (AetherConfig.DWARVEN_TERMINATOR_INPUT.get() == 0 && !triedOtherInput) {
            triedOtherInput = true;
            autoLeft = !autoLeft;
            DwarvenLog.log("[TERMINATOR] " + NO_ARROW_LIMIT + " shots produced no arrows; trying " + (autoLeft ? "left" : "right") + " click instead");
            return;
        }
        if (weapon != null && weapon.kind() != null) {
            disabled.add(weapon.kind());
            weaponCheckedAt = 0;
            DwarvenLog.log("[TERMINATOR] shots aren't producing arrows with " + (triedOtherInput ? "either click" : "this click") + "; falling back to the next weapon");
        }
    }

    private void setMode(String mode) {
        if (!mode.equals(combatMode)) {
            Diag.event(Diag.COMBAT, "combat mode " + combatMode + " -> " + mode);
            if (combatMode != null && (mode.startsWith("HYPERION") || combatMode.startsWith("HYPERION"))) {
                DwarvenLog.log("combat: mode " + mode.toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            combatMode = mode;
        }
    }

    private static String fmt(Vec3 v) {
        return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
    }

    private void stopWalking() {
        if (walkGoal != null) {
            PathfindingManager.stop();
            walkGoal = null;
        }
    }

    // an unknown or not yet refreshed zone isn't evidence either way, so nothing is learned from it
    private boolean inHuntArea() {
        return area == null || zone.equalsIgnoreCase(area);
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
                long at = System.currentTimeMillis();
                if (type != null) credit.killed(type, at);
                BlockPos where = mob != null ? mob.blockPosition() : lastPos.get(e.id);
                if (type != null && where != null && AetherConfig.DWARVEN_SPAWN_MEMORY.get() && inHuntArea()
                        && DwarvenStore.SPAWNS.killed(type, where, at)) {
                    DwarvenLog.log("[LEARNING] " + (mobs.contains("Goblin") ? "Goblin hunting" : "Hunting") + " location verified by successful kill at "
                            + where.toShortString());
                }
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
        RotationManager.steer(client, body, DwarvenCamera.profile(DwarvenCamera.Activity.COMBAT));
        HitResult hit = client.hitResult;
        boolean onTarget = hit instanceof EntityHitResult e && e.getEntity() == target;
        if (!onTarget) {
            if (offTargetSince == 0) offTargetSince = now;
            if (now - offTargetSince > 800) {
                lastFailure = hit instanceof EntityHitResult other ? "crosshair on another entity (" + other.getEntity().getName().getString() + ")"
                        : "crosshair not on the goblin yet";
            }
            return;
        }
        offTargetSince = 0;
        if (now - lastHitAt < HIT_GAP_MS) return;
        if (dev.aether.bootstrap.AetherBootstrapHooks.isAttackSuppressed()) {
            lastFailure = "attack input suppressed by another module";
            return;
        }
        lastFailure = null;
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
        int tags = 0, eligible = 0, noBody = 0, far = 0, skipped = 0, pending = 0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            Tag tag = parse(stand.getCustomName());
            if (tag == null || tag.health <= 0) continue;
            tags++;
            if (!wanted(tag.name)) continue;
            eligible++;
            LivingEntity mob = bodyUnder(client, stand);
            if (mob == null) {
                noBody++;
                continue;
            }
            if (ignored.containsKey(mob.getId())) {
                skipped++;
                continue;
            }
            double distance = mob.position().distanceTo(eye);
            if (distance > AetherConfig.DWARVEN_TARGET_RADIUS.get()) {
                far++;
                continue;
            }
            // a goblin in view is fought wherever the zone map puts it; kill credit sorts out ones that don't count
            names.put(mob.getId(), tag.name);
            lastPos.put(mob.getId(), mob.blockPosition());
            if (attacks.pending(mob.getId(), now, revisit)) pending++;
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
        Found chosen = best != null ? best : bestPending;
        noteScan(now, chosen, tags, eligible, noBody, far, skipped, pending);
        return chosen;
    }

    // why a scan came up empty, so "no goblins" is told apart from goblins that were all filtered out
    private void noteScan(long now, Found chosen, int tags, int eligible, int noBody, int far, int skipped, int pending) {
        String note;
        if (chosen != null) note = "target " + chosen.name;
        else if (tags == 0) note = "no mobs in view";
        else if (eligible == 0) note = tags + " mobs in view, none of them " + mobs;
        else note = String.format("%d eligible but none usable: %d without a body under the tag, %d past %d blocks, %d ignored for now",
                eligible, noBody, far, AetherConfig.DWARVEN_TARGET_RADIUS.get(), skipped);
        if (pending > 0 && chosen != null) note += " (" + pending + " hit and awaiting outcome)";
        boolean changed = !note.equals(scanNote);
        scanNote = note;
        if (chosen == null && eligible > 0 && changed && now - scanNoteAt > 5000) {
            scanNoteAt = now;
            DwarvenLog.log("[GOBLIN] " + note);
        } else if (changed) {
            Diag.event(Diag.COMBAT, "[GOBLIN] " + note);
        }
    }

    // what it takes to get this mob with the weapon in hand: a shot from here, a walk into a crowd, or a walk up to it
    private double targetCost(Minecraft client, LivingEntity mob, double distance, Vec3 eye) {
        // height changes cost more than flat ground since they usually mean a detour
        double cost = distance + Math.abs(mob.getY() - client.player.getY()) * 2.0;
        Weapons.Kind kind = weapon == null ? Weapons.Kind.TOOL : weapon.kind();
        if (kind == Weapons.Kind.TERMINATOR || kind == Weapons.Kind.SHORTBOW) {
            boolean shootable = distance <= AetherConfig.DWARVEN_RANGED_DISTANCE.get() && visible(client, mob.getBoundingBox().getCenter());
            return shootable ? distance * 0.25 : cost + 20;
        }
        if (kind == Weapons.Kind.HYPERION && AetherConfig.DWARVEN_PREFER_CLUSTERS.get()) {
            cost -= 4.0 * (eligibleNear(client, mob.position(), CLUSTER_RADIUS).size() - 1);
        }
        return cost;
    }

    // mobs with these names that can be gone after from where the player stands: alive, with a body under the tag, in
    // range, and either in sight or close; used to stop travelling and fight when they are already here
    public static int engageable(Minecraft client, Set<String> names, double range, double closeWithoutSight) {
        if (client.level == null || client.player == null) return 0;
        int n = 0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName() || stand.distanceTo(client.player) > range + 2.5) continue;
            Tag tag = parse(stand.getCustomName());
            if (tag == null || tag.health <= 0) continue;
            boolean named = false;
            for (String m : names) named |= m.equalsIgnoreCase(tag.name);
            if (!named) continue;
            LivingEntity mob = bodyUnder(client, stand);
            if (mob == null || Math.abs(mob.getY() - client.player.getY()) > 10) continue;
            double d = mob.distanceTo(client.player);
            if (d <= range && (d <= closeWithoutSight || visible(client, mob.getBoundingBox().getCenter()))) n++;
        }
        return n;
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
