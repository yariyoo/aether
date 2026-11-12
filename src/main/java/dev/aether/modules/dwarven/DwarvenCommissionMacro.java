package dev.aether.modules.dwarven;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.dwarven.RetryBudget.Failure;
import dev.aether.modules.dwarven.claim.ClaimManager;
import dev.aether.modules.dwarven.commission.CombatTarget;
import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.dwarven.commission.CommissionScheduler;
import dev.aether.modules.dwarven.commission.DwarvenCommission;
import dev.aether.modules.dwarven.commission.Objective;
import dev.aether.modules.dwarven.diag.Diag;
import dev.aether.modules.dwarven.diag.DiagLog;
import dev.aether.modules.dwarven.diag.NavReport;
import dev.aether.modules.dwarven.diag.Ops;
import dev.aether.modules.dwarven.diag.Sampler;
import dev.aether.modules.dwarven.event.GoblinRaid;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.dwarven.fight.DwarvenFighter;
import dev.aether.modules.dwarven.fight.Weapons;
import dev.aether.modules.dwarven.memory.AreaMemory;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.dwarven.memory.HuntPoints;
import dev.aether.modules.dwarven.memory.SpotMemory;
import dev.aether.modules.dwarven.mine.BlockEfficiency;
import dev.aether.modules.dwarven.mine.DwarvenMiner;
import dev.aether.modules.dwarven.nav.AreaNavigator;
import dev.aether.modules.dwarven.nav.NavFailures;
import dev.aether.modules.dwarven.world.OreKind;
import dev.aether.modules.dwarven.world.OreScanner;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// runs dwarven mines commissions end to end: read them, pick one, get there, mine, claim, repeat
public final class DwarvenCommissionMacro {
    public enum Stage { OFF, VALIDATE, OBSERVE, CLAIM, TRAVEL, MINE, FIGHT, WAIT }

    private static final long AUTO_START_DELAY_MS = 5000L;
    private static final long LEARN_EVERY_MS = 500L;
    private static final long SAVE_EVERY_MS = 30_000L;
    private static final long UNREADABLE_LIMIT_MS = 10_000L;
    private static final long OUTSIDE_LIMIT_MS = 15_000L;
    private static final long STALL_LIMIT_MS = 45_000L;
    private static final int STALL_MIN_BLOCKS = 15;
    private static final long TAB_LAG_MS = 6000L;
    private static final long CLAIM_REFRESH_MS = 400L;
    // a 40-block leg or a stand walk needs a few thousand nodes; this many means the stop can't be reached
    private static final int WALK_SEARCH_LIMIT = 60_000;
    private static final long INPUT_PAUSE_MS = 3000L;
    private static final double ARRIVE_WITHIN = 2.5;
    private static final double PRIOR_RATE = 60.0;

    private static final OreScanner scanner = new OreScanner();
    private static final DwarvenMiner miner = new DwarvenMiner(scanner);
    private static final DwarvenFighter fighter = new DwarvenFighter();
    // a fight that moves the commission along keeps going; this long with no progress means the hunting ground is wrong
    private static final long FIGHT_STALL_MS = 90_000L;
    private static final double NEAR_HUNT_POINT = 24.0;
    private static final double AT_HUNT_SPOT = 8.0;
    private static final long EMPTY_SPOT_RECHECK_MS = 3 * 60_000L;
    private static final double RAID_SIGHT = 40.0;
    // mobs this close, in sight or nearly touching, end a combat trip early: the hunt is already here
    private static final double ENGAGE_RADIUS = 20.0;
    private static final double ENGAGE_UNSEEN = 8.0;
    private static final long ENGAGE_SCAN_MS = 250L;
    // a trip that failed this close to its goal, in a place where the work counts, works from where it stopped
    private static final double WORK_WHERE_STOPPED = 24.0;
    private static long lastEngageScanAt;
    private static final long WRONG_ZONE_MS = 4000L;
    private static final Map<String, String> lastFailure = new HashMap<>();
    private static long wrongZoneSince;
    private static final int FIGHT_STALL_HITS = 8;
    private static final long SWITCH_WHILE_WAITING_MS = 20_000L;
    private static int hitsAtLastProgress;
    private static long waitingSince;
    private static long lastWaitCheckAt;
    private static final AreaNavigator navigator = new AreaNavigator();
    private static final ClaimManager claims = new ClaimManager();
    private static RetryBudget budget = new RetryBudget(4, 1500L);

    private static Stage stage = Stage.OFF;
    private static long stageSince;
    private static boolean pendingStart;
    private static List<DwarvenCommission> commissions = List.of();
    private static DwarvenCommission current;
    private static Objective objective;
    private static final Map<String, Long> skipped = new HashMap<>();
    private static final Set<String> unknownAreasTold = new HashSet<>();
    private static final Set<BlockPos> triedSpots = new HashSet<>();
    private static long lastObserveAt;
    private static long lastLearnAt;
    private static long lastSaveAt;
    private static String lastNothingReason = "";
    private static boolean claimTravel;
    private static dev.aether.modules.dwarven.claim.Rewards.Tally rewards = dev.aether.modules.dwarven.claim.Rewards.NONE;
    private static BlockPos claimEmissary;
    private static long unreadableSince;
    private static long outsideSince;
    private static long waitUntil;
    private static long pausedUntil;
    private static long lastOverrideSeen;
    private static long lastClaimAt;
    private static long mineStartedAt;
    private static double mineStartProgress;
    private static double lastProgress;
    private static long lastProgressAt;
    private static int minedAtLastProgress;
    private static int levelId;
    private static boolean keyWasDown;
    private static boolean manuallyStopped;
    private static boolean resumePending;
    private static long inMinesSince;

    private static long sessionStartedAt;
    private static int completed;
    private static int minedBeforeThisRun;
    private static int titaniumBeforeThisRun;
    private static String zone = "";
    static final ZoneReading ZONE = new ZoneReading();
    private static long confirmingSince;
    // trips per destination for the current commission, to count the time spent travelling somewhere twice
    private static final Map<Long, Integer> tripsTo = new HashMap<>();
    private static long repeatedTripMs;

    private DwarvenCommissionMacro() {}

    public static boolean isRunning() {
        return stage != Stage.OFF || pendingStart;
    }

    public static void toggle(Minecraft client) {
        if (isRunning()) {
            // a manual stop also holds off auto start until the player leaves and comes back
            manuallyStopped = true;
            resumePending = false;
            stop(client, "toggled off", true);
        } else {
            requestStart();
        }
    }

    private static long restMs() {
        return AetherConfig.DWARVEN_REST_MINUTES.get() * 60_000L;
    }

    public static void requestStart() {
        pendingStart = true;
    }

    public static void stop(Minecraft client, String reason, boolean stopMacroState) {
        pendingStart = false;
        if (stage == Stage.OFF) return;
        if (client == null) client = Minecraft.getInstance();
        stopWork(client);
        navigator.stop(client);
        claims.reset();
        RotationManager.cancelRotation();
        stage = Stage.OFF;
        PathfindingManager.quiet(false);
        PathfindingManager.limitWalkSearch(0);
        DwarvenStore.save();
        DwarvenLog.log("stopped: " + reason);
        Ops.cancelAll("macro stopped");
        reportSession("session report (" + reason + ")");
        if (stopMacroState && MacroStateManager.getCurrentState() == MacroState.State.DWARVEN_COMMISSIONS) {
            MacroStateManager.stopMacro(client, "Dwarven commissions stopped: " + reason, false);
        }
    }

    private static void halt(Minecraft client, String reason) {
        ClientUtils.sendMessage("§cDwarven Commissions stopped: " + reason, false);
        stop(client, reason, true);
    }

    // a disconnect or lobby swap takes us out of the mines; come back to work once we're in them again
    private static void haltAndResumeLater(Minecraft client, String reason) {
        boolean resume = AetherConfig.DWARVEN_AUTO_RESUME.get();
        halt(client, reason + (resume ? ", will resume when back in the Dwarven Mines" : ""));
        resumePending = resume;
    }

    private static void autoStart(Minecraft client, boolean inMines, long now) {
        if (!inMines) {
            inMinesSince = 0;
            manuallyStopped = false;
            return;
        }
        if (inMinesSince == 0) inMinesSince = now;
        if (isRunning() || now - inMinesSince < AUTO_START_DELAY_MS || MacroStateManager.isMacroRunning()) return;
        if (resumePending) {
            resumePending = false;
            DwarvenLog.log("back in the Dwarven Mines, resuming");
            requestStart();
        } else if (AetherConfig.DWARVEN_AUTO_START.get() && !manuallyStopped) {
            DwarvenLog.log("auto start: in the Dwarven Mines");
            requestStart();
        }
    }

    public static void onChat(String message) {
        miner.onChat(message);
        dev.aether.modules.dwarven.fight.Mana.onChat(message);
        claims.onChat(message);
        if (stage == Stage.OFF) return;
        String done = CommissionReader.completedInChat(message);
        if (done != null) {
            completed++;
            DwarvenLog.log("chat: " + done + " commission complete (" + completed + " this session)");
        }
    }

    public static void tick(Minecraft client) {
        long t0 = System.nanoTime();
        tickInner(client);
        lastTickNs = System.nanoTime() - t0;
        diagnostics(client);
    }

    // per-frame hook from the hud, so a snapshot can still go out on time when a client tick runs late
    public static void onFrame(Minecraft client) {
        if (stage != Stage.OFF && client.player != null) sample(client);
    }

    private static void tickInner(Minecraft client) {
        if (client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        pollKeybind(client);
        boolean inMines = ClientUtils.getCurrentLocation() == MacroState.Location.DWARVEN_MINES;
        if (inMines) {
            // every tick, so a teleport is told apart from walking and the zone line isn't trusted until it catches up
            ZONE.tick(client.player.getX(), client.player.getY(), client.player.getZ(), readZone(), now);
            zone = ZONE.zone();
            learn(client, now);
        }
        autoStart(client, inMines, now);
        if (pendingStart && client.gui.screen() == null) {
            pendingStart = false;
            begin(client, now);
        }
        if (stage == Stage.OFF) return;
        if (MacroStateManager.getCurrentState() != MacroState.State.DWARVEN_COMMISSIONS) {
            stop(client, "another macro state took over", false);
            return;
        }
        if (System.identityHashCode(client.level) != levelId) {
            levelId = System.identityHashCode(client.level);
            stopWork(client);
            navigator.stop(client);
            DwarvenStore.TRAILS.interrupted();
            enter(Stage.VALIDATE, "world changed");
        }
        String unsafe = unsafe(client, inMines, now);
        if (unsafe != null) {
            if (!inMines) haltAndResumeLater(client, unsafe);
            else halt(client, unsafe);
            return;
        }
        if (userTookOver(client, now)) return;
        scanner.tick(client, AetherConfig.DWARVEN_EXTENDED_RADIUS.get(), AetherConfig.DWARVEN_SCAN_BUDGET.get());
        if (now - lastObserveAt >= AetherConfig.DWARVEN_OBSERVE_MS.get()) {
            lastObserveAt = now;
            commissions = CommissionReader.fromTab(TablistUtils.getTabLines(client));
        }
        switch (stage) {
            case VALIDATE -> validate(client, inMines, now);
            case OBSERVE -> observe(client, now);
            case CLAIM -> claim(client, now);
            case TRAVEL -> travel(client, now);
            case MINE -> mine(client, now);
            case FIGHT -> fight(client, now);
            case WAIT -> {
                if (now >= waitUntil) enter(Stage.OBSERVE, "wait over");
            }
            default -> { }
        }
    }

    private static void pollKeybind(Minecraft client) {
        // read the physical key so the toggle works even when the mapping wasn't registered with the options
        boolean down = client.gui.screen() == null && dev.aether.bootstrap.AetherKeybindRegistry.isDown(client,
                dev.aether.bootstrap.AetherKeybindRegistry.getDwarvenCommissionsKey());
        if (down && !keyWasDown) toggle(client);
        keyWasDown = down;
    }

    // runs whenever the player is in the mines, macro on or off, so playing by hand teaches it too
    private static void learn(Minecraft client, long now) {
        if (now - lastLearnAt < LEARN_EVERY_MS) return;
        lastLearnAt = now;
        DwarvenStore.load();
        BlockPos feet = client.player.blockPosition();
        // a stale zone line right after a teleport would teach the old zone's name for the new place
        if (ZONE.fresh(now) && client.player.onGround()) DwarvenStore.AREAS.observe(zone, feet);
        if (ZONE.fresh(now)) navigator.region(zone);
        DwarvenStore.EMISSARIES.observe(client);
        if (AetherConfig.DWARVEN_LEARN_TRAILS.get()) DwarvenStore.TRAILS.walked(feet, client.player.onGround());
        if (now - lastSaveAt > SAVE_EVERY_MS) {
            lastSaveAt = now;
            DwarvenStore.save();
        }
    }

    private static String readZone() {
        for (String raw : ClientUtils.getSidebarLines()) {
            String zone = zoneFromSidebarLine(TablistUtils.stripColors(raw));
            if (!zone.isEmpty()) return zone;
        }
        return "";
    }

    // the location line starts with an icon: '⏣' on older clients, a private-use font glyph on 26.2
    static String zoneFromSidebarLine(String line) {
        String text = line.strip();
        if (text.isEmpty()) return "";
        int first = text.codePointAt(0);
        boolean icon = first == '⏣' || first == 'ൠ' || Character.getType(first) == Character.PRIVATE_USE;
        if (!icon) return "";
        return text.substring(Character.charCount(first)).replaceAll("[^\\p{L}\\p{N}' ]", "").strip();
    }

    private static void begin(Minecraft client, long now) {
        if (ClientUtils.getCurrentLocation() != MacroState.Location.DWARVEN_MINES) {
            ClientUtils.sendMessage("§cDwarven Commissions only runs in the Dwarven Mines.", false);
            return;
        }
        if (MacroStateManager.isMacroRunning()) {
            ClientUtils.sendMessage("§cStop the running macro before starting Dwarven Commissions.", false);
            return;
        }
        DwarvenStore.load();
        budget = new RetryBudget(AetherConfig.DWARVEN_MAX_RECOVERY.get(), 1500L);
        skipped.clear();
        lastNothingReason = "";
        unknownAreasTold.clear();
        current = null;
        objective = null;
        completed = 0;
        STATS.reset();
        Ops.reset();
        repeatedTripMs = 0;
        tripsTo.clear();
        rewards = dev.aether.modules.dwarven.claim.Rewards.NONE;
        minedBeforeThisRun = miner.mined();
        titaniumBeforeThisRun = miner.titaniumMined();
        sessionStartedAt = now;
        outsideSince = 0;
        unreadableSince = 0;
        lastOverrideSeen = RotationManager.lastManualOverrideAt();
        levelId = System.identityHashCode(client.level);
        MacroStateManager.setCurrentState(MacroState.State.DWARVEN_COMMISSIONS);
        DwarvenLog.log("started at " + client.player.blockPosition().toShortString() + ", zone '" + zone + "'");
        // every leg and stand walk would otherwise put four lines in chat; a stop the pathfinder can't reach is
        // given up on after a fraction of a second rather than the 3.5s search seen in the 22:03 video
        PathfindingManager.quiet(true);
        PathfindingManager.limitWalkSearch(WALK_SEARCH_LIMIT);
        enter(Stage.VALIDATE, "start");
    }

    private static void enter(Stage next, String why) {
        long now = System.currentTimeMillis();
        long spent = stageSince > 0 ? now - stageSince : 0;
        if (stage != Stage.OFF && stageSince > 0) STATS.stageTime(stage, spent);
        if (stage == Stage.WAIT) Ops.record("recovery.wait", spent);
        if (stage != Stage.OFF) Ops.record("stage." + stage.name().toLowerCase(Locale.ROOT), spent);
        Diag.event(Diag.STATE, stage + " -> " + next + " (" + why + ") after " + spent + "ms in " + stage, spent);
        // each stage's subsystem owns the camera; a turn the last one started must not carry into the next
        if (stage != next && RotationManager.isRotating()) RotationManager.cancelRotation();
        if (stage != next) DwarvenLog.log(stage + " -> " + next + " (" + why + ")");
        stage = next;
        stageSince = now;
    }

    public static final DwarvenStats STATS = new DwarvenStats();
    private static long commissionSince;

    private static String unsafe(Minecraft client, boolean inMines, long now) {
        if (client.player.isDeadOrDying() || client.gui.screen() instanceof DeathScreen) return "you died";
        if (!inMines) {
            if (outsideSince == 0) outsideSince = now;
            // warps and lobby swaps show another place for a moment; only a lasting one means we really left
            if (now - outsideSince > OUTSIDE_LIMIT_MS) return "left the Dwarven Mines";
        } else {
            outsideSince = 0;
        }
        int stopAfter = AetherConfig.DWARVEN_STOP_AFTER.get();
        if (stopAfter > 0 && completed >= stopAfter) return "finished " + completed + " commissions";
        int minutes = AetherConfig.DWARVEN_SESSION_MINUTES.get();
        if (minutes > 0 && now - sessionStartedAt > minutes * 60_000L) return "session limit of " + minutes + " minutes reached";
        return null;
    }

    private static boolean userTookOver(Minecraft client, long now) {
        long override = RotationManager.lastManualOverrideAt();
        if (override != lastOverrideSeen && AetherConfig.DWARVEN_PAUSE_ON_INPUT.get()) {
            lastOverrideSeen = override;
            pausedUntil = now + INPUT_PAUSE_MS;
            pauseWork(client);
            DwarvenLog.log("camera: manual mouse input detected, pausing for " + INPUT_PAUSE_MS / 1000 + "s");
        }
        lastOverrideSeen = override;
        return now < pausedUntil;
    }

    private static void validate(Minecraft client, boolean inMines, long now) {
        if (!inMines) return;
        if (DwarvenMiner.toolSlot(client) < 0) {
            halt(client, "no drill, pickaxe or gauntlet in the hotbar");
            return;
        }
        enter(Stage.OBSERVE, "ready");
    }

    private static void observe(Minecraft client, long now) {
        if (client.gui.screen() != null) return;
        if (commissions.isEmpty()) {
            if (unreadableSince == 0) unreadableSince = now;
            if (now - unreadableSince > UNREADABLE_LIMIT_MS) {
                unreadableSince = 0;
                RetryBudget.Verdict v = budget.fail(Failure.COMMISSIONS_UNREADABLE);
                if (!v.retry()) halt(client, "can't read commissions from the tab list (enable the Commissions widget with /tablist)");
                else pause(v.waitMs(), "commissions unreadable, try " + v.attempt());
            }
            return;
        }
        unreadableSince = 0;
        budget.succeeded(Failure.COMMISSIONS_UNREADABLE);
        boolean anyDone = commissions.stream().anyMatch(DwarvenCommission::done);
        // the tab list keeps saying DONE for a few seconds after a claim
        if (anyDone && now - lastClaimAt > TAB_LAG_MS) {
            startClaim(client);
            return;
        }
        choose(client, now);
    }

    private static void choose(Minecraft client, long now) {
        skipped.values().removeIf(until -> until <= now);
        BlockPos feet = client.player.blockPosition();
        List<DwarvenCommission> open = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (DwarvenCommission c : commissions) {
            if (c.done() || skipped.containsKey(c.name())) continue;
            Objective o = Objective.of(c.name());
            // events are never hunted for; a raid is joined only while it runs right where we are
            if (o.kind() == Objective.Kind.EVENT && (!GoblinRaid.isRaidCommission(c.name()) || !raidHere(client))) continue;
            if (o.kind() == Objective.Kind.MINING && o.area() != null && !DwarvenStore.AREAS.knows(o.area())) {
                unknown.add(o.area());
                continue;
            }
            if (isGoblinSlayer(c.name()) && !AetherConfig.DWARVEN_GOBLIN_SLAYER.get()) continue;
            open.add(c);
        }
        CommissionScheduler.Rules rules = new CommissionScheduler.Rules(
                CommissionScheduler.Strategy.values()[AetherConfig.DWARVEN_STRATEGY.get()], AetherConfig.DWARVEN_MITHRIL.get(),
                AetherConfig.DWARVEN_TITANIUM.get(), AetherConfig.DWARVEN_AREA_MINING.get(), AetherConfig.DWARVEN_OVERLAP.get(),
                AetherConfig.DWARVEN_SWITCH_GAIN.get(), AetherConfig.DWARVEN_COMBAT.get(), true);
        CommissionScheduler.Choice choice = CommissionScheduler.choose(open, rules, current == null ? null : current.name(),
                o -> travelSeconds(o, feet, now), c -> DwarvenStore.WORK.secondsFor(c.name()));
        if (choice == null) {
            if (!unknown.isEmpty() && AetherConfig.DWARVEN_STOP_UNKNOWN_AREA.get()) {
                halt(client, "no known way to " + String.join(", ", unknown));
                return;
            }
            // right after a claim the tab list still shows the old commission as done and the new one isn't listed
            // yet; that isn't "nothing to do", so check again shortly instead of waiting five seconds (22:03 video)
            boolean refreshing = now - lastClaimAt < TAB_LAG_MS || commissions.stream().anyMatch(DwarvenCommission::done);
            if (refreshing) {
                pause(CLAIM_REFRESH_MS, "waiting for the new commission to show up in the tab list");
                return;
            }
            String why = whyNothing(commissions, unknown);
            if (!why.equals(lastNothingReason)) {
                lastNothingReason = why;
                ClientUtils.sendMessage("§eDwarven Commissions is waiting: " + why, false);
            }
            for (String area : unknown) {
                if (unknownAreasTold.add(area)) {
                    ClientUtils.sendMessage("§eAether hasn't been to " + area + " yet. Walk there once and it will remember the way.", false);
                }
            }
            if (stage != Stage.WAIT) DwarvenLog.log("nothing to do right now: " + describe(commissions) + (unknown.isEmpty() ? "" : ", unknown areas " + unknown));
            pause(5000L, "no commission it can do");
            return;
        }
        lastNothingReason = "";
        boolean changed = current == null || !current.name().equals(choice.commission().name());
        current = choice.commission();
        objective = choice.objective();
        if (changed) {
            commissionSince = now;
            tripsTo.clear();
            triedSpots.clear();
            DwarvenLog.log(String.format("chose %s (%.0f%%), about %.0fs%s; all: %s", current.name(), current.progress() * 100, choice.seconds(),
                    choice.alsoAdvanced().isEmpty() ? "" : ", also advances " + choice.alsoAdvanced().stream().map(DwarvenCommission::name).toList(),
                    describe(commissions)));
        }
        goWork(client, now);
    }

    private static String lastFailureFor(DwarvenCommission c) {
        String why = lastFailure.get(c.name());
        return why == null ? "unknown" : why;
    }

    private static String whyNothing(List<DwarvenCommission> list, List<String> unknown) {
        List<String> reasons = new ArrayList<>();
        for (DwarvenCommission c : list) {
            if (c.done()) continue;
            Objective o = Objective.of(c.name());
            String reason = switch (o.kind()) {
                case COMBAT -> skipped.containsKey(c.name()) ? "resting after failures" : "combat is turned off in settings";
                case EVENT -> GoblinRaid.isRaidCommission(c.name())
                        ? (AetherConfig.DWARVEN_EVENTS.get() ? "only joined while a raid runs where it is" : "events are turned off in settings")
                        : "this event isn't supported yet";
                case UNKNOWN -> "not a commission it knows yet";
                case MINING -> skipped.containsKey(c.name()) ? "resting after failures (" + lastFailureFor(c) + ")"
                        : o.area() != null && unknown.contains(o.area()) ? "hasn't seen " + o.area() + " yet" : "turned off in settings";
            };
            reasons.add(c.name() + " (" + reason + ")");
        }
        return reasons.isEmpty() ? "no open commissions" : String.join(", ", reasons);
    }

    private static String describe(List<DwarvenCommission> list) {
        StringBuilder out = new StringBuilder();
        for (DwarvenCommission c : list) out.append(c.name()).append(c.done() ? " DONE" : String.format(" %.0f%%", c.progress() * 100)).append("; ");
        return out.toString();
    }

    // only a zone line that has caught up with the last teleport counts as evidence of being in the wrong place
    // whether the current combat commission counts kills anywhere, so the zone line is no reason to leave or skip a place
    private static boolean zoneFree(Objective o) {
        if (o.kind() != Objective.Kind.COMBAT || current == null) return false;
        CombatTarget combat = CombatTarget.of(current.name());
        return combat != null && !combat.zoneBound();
    }

    private static boolean wrongZone(Objective o) {
        if (zoneFree(o)) return false;
        boolean placed = o.kind() == Objective.Kind.MINING || o.kind() == Objective.Kind.COMBAT;
        return placed && o.area() != null && ZONE.fresh(System.currentTimeMillis()) && !zone.equalsIgnoreCase(o.area());
    }

    private static boolean rightZone(Objective o) {
        return o.area() != null && ZONE.fresh(System.currentTimeMillis()) && zone.equalsIgnoreCase(o.area());
    }

    private static boolean isGoblinSlayer(String name) {
        return name.strip().equalsIgnoreCase("Goblin Slayer");
    }

    private static boolean raidHere(Minecraft client) {
        if (!AetherConfig.DWARVEN_EVENTS.get() || !GoblinRaid.active(ClientUtils.getSidebarLines())) return false;
        return DwarvenFighter.anyVisible(client, GoblinRaid.MOBS, RAID_SIGHT);
    }

    private static double travelSeconds(Objective o, BlockPos feet, long now) {
        if (o.kind() == Objective.Kind.EVENT) return 0;
        BlockPos target = destination(o, feet, now);
        if (target == null) return o.anywhere() ? 0 : 600;
        // a destination whose route keeps failing from here costs a lot, so the scheduler prefers something reachable
        if (NavFailures.blocked(target, feet, now)) return 900 + AreaNavigator.travelSeconds(feet, target);
        return AreaNavigator.travelSeconds(feet, target);
    }

    // where to go for an objective: the best remembered spot, else the nearest ground seen in that zone
    private static BlockPos destination(Objective o, BlockPos feet, long now) {
        if (o.kind() == Objective.Kind.COMBAT) {
            CombatTarget combat = current == null ? null : CombatTarget.of(current.name());
            if (combat == null) return o.area() == null ? null : DwarvenStore.AREAS.bestPoint(o.area(), feet, List.of(), 0);
            // a recorded fight spot only counts if it lies in the commission's area; a raid elsewhere leaves misleading ones.
            // a hunt that counts anywhere goes by what each place has produced instead, and passes over ones just found empty
            java.util.function.Predicate<BlockPos> inIt = combat.zoneBound() ? p -> DwarvenStore.AREAS.allows(combat.area(), p) : p -> true;
            long recheck = combat.zoneBound() ? 0L : EMPTY_SPOT_RECHECK_MS;
            BlockPos busiest = DwarvenStore.SPAWNS.best(combat.mobs(), feet, now, recheck, triedSpots, inIt);
            if (busiest != null) return busiest;
            BlockPos hunt = HuntPoints.nearest(combat.mobs(), feet, inIt);
            if (hunt != null) return hunt;
            return o.area() == null ? null : DwarvenStore.AREAS.bestPoint(o.area(), feet, List.of(), 0);
        }
        if (AetherConfig.DWARVEN_VEIN_MEMORY.get()) {
            SpotMemory.Spot spot = DwarvenStore.SPOTS.best(o.area(), now, AetherConfig.DWARVEN_RECHECK_SECONDS.get() * 1000L,
                    PRIOR_RATE, triedSpots, p -> AreaNavigator.walkSeconds(feet, p), p -> DwarvenStore.AREAS.allows(o.area(), p));
            boolean inRange = spot != null && Math.sqrt(spot.pos.distSqr(feet)) <= AetherConfig.DWARVEN_MAX_EXPLORE.get();
            if (spot != null && spot.blocks > 0 && (inRange || o.area() != null)) return spot.pos;
        }
        if (o.area() == null) return null;
        // a goal that keeps failing from here is swapped for another part of the same area; border points come last
        return DwarvenStore.AREAS.bestPoint(o.area(), feet, NavFailures.failedGoalsFrom(feet, now), 16);
    }

    private static boolean inArea(Objective o, BlockPos feet) {
        if (o.kind() == Objective.Kind.COMBAT) {
            CombatTarget combat = CombatTarget.of(current.name());
            if (combat != null && !combat.zoneBound()) {
                // here is good enough when the mobs are here, or a place they've been found is close
                if (DwarvenFighter.engageable(Minecraft.getInstance(), combat.mobs(), ENGAGE_RADIUS, ENGAGE_UNSEEN) > 0) return true;
                BlockPos spot = DwarvenStore.SPAWNS.best(combat.mobs(), feet, System.currentTimeMillis(), 0L, Set.of());
                return spot != null && Math.sqrt(spot.distSqr(feet)) < AT_HUNT_SPOT;
            }
            if (o.area() != null && ZONE.fresh(System.currentTimeMillis())) return zone.equalsIgnoreCase(o.area());
            if (combat == null) return false;
            BlockPos hunt = HuntPoints.nearest(combat.mobs(), feet, p -> DwarvenStore.AREAS.allows(combat.area(), p));
            return hunt != null && Math.sqrt(hunt.distSqr(feet)) < NEAR_HUNT_POINT;
        }
        if (o.area() == null) return true;
        if (ZONE.fresh(System.currentTimeMillis())) return zone.equalsIgnoreCase(o.area());
        // right after a teleport the zone line may still name where we came from; go by what's known about this spot
        return DwarvenStore.AREAS.confidence(o.area(), feet) == AreaMemory.Confidence.VERIFIED_CORRECT_AREA;
    }

    private static void goWork(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        if (objective.kind() == Objective.Kind.EVENT || inArea(objective, feet)) {
            startWork(client, now);
            return;
        }
        BlockPos target = destination(objective, feet, now);
        if (target == null) {
            if (objective.area() != null && objective.kind() != Objective.Kind.EVENT) {
                // working here wouldn't count, and there's nowhere known to go; rest it instead of mining the wrong area
                skipped.put(current.name(), now + restMs());
                lastFailure.put(current.name(), "no known place in " + objective.area() + " to go to");
                DwarvenLog.log("no known way into " + objective.area() + " for " + current.name() + "; resting it");
                current = null;
                enter(Stage.OBSERVE, "nowhere known to go");
                return;
            }
            startWork(client, now);
            return;
        }
        int before = tripsTo.merge(target.asLong(), 1, Integer::sum) - 1;
        if (before > 0) Diag.event(Diag.ROUTE, "trip " + (before + 1) + " to " + target.toShortString() + " for this commission");
        navigator.start(client, target, objective.area() == null ? "a remembered spot" : objective.area(), ARRIVE_WITHIN);
        enter(Stage.TRAVEL, "heading to " + (objective.area() == null ? "ore" : objective.area()));
    }

    private static void travel(Minecraft client, long now) {
        if (commissionChanged(client)) return;
        if (engageOnTheWay(client, now)) return;
        navigator.tick(client);
        BlockPos feet = client.player.blockPosition();
        // being in the right zone is what matters, not the exact point we aimed for
        boolean arrived = navigator.status() == AreaNavigator.Status.ARRIVED;
        boolean there = arrived || (rightZone(objective) && Math.sqrt(feet.distSqr(navigator.goal())) < 12);
        boolean placed = objective.kind() == Objective.Kind.MINING || objective.kind() == Objective.Kind.COMBAT;
        if (arrived && placed && objective.area() != null && !ZONE.fresh(now)) {
            // the last hop usually lands moments before the sidebar is refreshed; judge the zone once it has caught up
            if (confirmingSince == 0) {
                confirmingSince = now;
                Diag.event("AREA", "arrived at " + feet.toShortString() + ", waiting for the zone line to refresh (shows " + zone + ")");
            }
            return;
        }
        if (confirmingSince != 0) {
            long took = now - confirmingSince;
            confirmingSince = 0;
            Ops.record("area.confirm", took);
            Diag.event("AREA", "zone line refreshed after " + took + "ms: " + zone, took);
        }
        if (there && wrongZone(objective)) {
            wrongArea(client, feet, now);
            return;
        }
        if (there) {
            if (tripsTo.getOrDefault(navigator.goal().asLong(), 0) > 1) repeatedTripMs += navigator.lastTripMs();
            navigator.stop(client);
            STATS.trip(true);
            budget.succeeded(Failure.NAVIGATION);
            budget.succeeded(Failure.WRONG_AREA);
            startWork(client, now);
            return;
        }
        if (navigator.status() == AreaNavigator.Status.FAILED && navigator.goal() != null
                && Math.sqrt(feet.distSqr(navigator.goal())) <= WORK_WHERE_STOPPED && (rightZone(objective) || zoneFree(objective))) {
            DwarvenLog.log(String.format("[NAV] the last few blocks to %s failed (%s), but %.0f blocks from it is close enough to work from",
                    navigator.goal().toShortString(), navigator.failure(), Math.sqrt(feet.distSqr(navigator.goal()))));
            STATS.trip(true);
            startWork(client, now);
            return;
        }
        if (navigator.status() == AreaNavigator.Status.FAILED) {
            STATS.trip(false);
            RetryBudget.Verdict v = budget.fail(Failure.NAVIGATION);
            triedSpots.add(navigator.goal());
            if (!v.retry()) {
                budget.succeeded(Failure.NAVIGATION);
                skipped.put(current.name(), now + restMs());
                lastFailure.put(current.name(), "can't reach it: " + navigator.failure());
                DwarvenLog.log("can't reach " + current.name() + " (" + navigator.failure() + "), leaving it for " + AetherConfig.DWARVEN_REST_MINUTES.get() + " minutes");
                current = null;
                enter(Stage.OBSERVE, "navigation gave up");
            } else {
                pause(v.waitMs(), "navigation failed (" + navigator.failure() + "), try " + v.attempt());
            }
        }
    }

    // a combat trip ends as soon as the mobs it's going for are already in reach; walking on to the planned point
    // would only walk away from them
    private static boolean engageOnTheWay(Minecraft client, long now) {
        if (objective.kind() != Objective.Kind.COMBAT || now - lastEngageScanAt < ENGAGE_SCAN_MS) return false;
        lastEngageScanAt = now;
        CombatTarget combat = CombatTarget.of(current.name());
        if (combat == null || combat.zoneBound() && !rightZone(objective)) return false;
        int n = DwarvenFighter.engageable(client, combat.mobs(), ENGAGE_RADIUS, ENGAGE_UNSEEN);
        if (n == 0) return false;
        BlockPos goal = navigator.goal();
        DwarvenLog.log("[GOBLIN] " + n + " eligible " + (n == 1 ? "entity" : "entities") + " detected on the way" + (goal == null ? ""
                : String.format(", %.0f blocks before %s", Math.sqrt(client.player.blockPosition().distSqr(goal)), goal.toShortString()))
                + "; engaging them instead of walking on");
        navigator.stop(client);
        STATS.trip(true);
        startWork(client, now);
        return true;
    }

    // reaching a destination and finding, once the scoreboard caught up, that it isn't in the area. that destination is
    // ruled out for this area for good (until standing there shows the right zone), and the next pick starts from here
    private static void wrongArea(Minecraft client, BlockPos feet, long now) {
        BlockPos goal = navigator.goal();
        navigator.stop(client);
        STATS.wrongArea();
        Ops.record("nav.trip.wrong_area", navigator.lastTripMs());
        DwarvenLog.log("[AREA] reached " + feet.toShortString() + " for " + objective.area() + " but the scoreboard says " + zone
                + " (" + ZONE.sinceJump(now) + "ms after the last teleport); destination " + goal.toShortString() + " ruled out for " + objective.area());
        DwarvenStore.AREAS.markWrongGoal(objective.area(), goal);
        DwarvenStore.AREAS.markWrongGoal(objective.area(), feet);
        triedSpots.add(goal);
        if (objective.kind() == Objective.Kind.COMBAT) {
            // the fight spot that brought us here is not in the hunt's area; forget it so it isn't picked again
            CombatTarget combat = CombatTarget.of(current.name());
            HuntPoints.forget(goal, 8);
            if (combat != null) DwarvenStore.SPAWNS.forget(goal, combat.mobs(), 8);
        }
        RetryBudget.Verdict v = budget.fail(Failure.WRONG_AREA);
        if (!v.retry()) {
            budget.succeeded(Failure.WRONG_AREA);
            skipped.put(current.name(), now + restMs());
            lastFailure.put(current.name(), "every known destination in " + objective.area() + " turned out to be elsewhere");
            current = null;
            enter(Stage.OBSERVE, "couldn't find the real " + objective.area());
            return;
        }
        BlockPos next = destination(objective, feet, now);
        if (next != null) {
            DwarvenLog.log(String.format("[AREA] next %s destination %s, %.0f blocks from here (%s)", objective.area(), next.toShortString(),
                    Math.sqrt(next.distSqr(feet)), DwarvenStore.AREAS.confidence(objective.area(), next).name().toLowerCase(Locale.ROOT)));
        }
        // no navigation failure is recorded: the route worked, the destination was wrong, so nothing pushes a forge reset
        goWork(client, now);
    }

    private static void stopWork(Minecraft client) {
        miner.stop(client);
        fighter.stop(client);
    }

    private static void pauseWork(Minecraft client) {
        miner.pause(client);
        fighter.pause(client);
    }

    private static void startWork(Minecraft client, long now) {
        if (objective.kind() == Objective.Kind.COMBAT || objective.kind() == Objective.Kind.EVENT) startFighting(client, now);
        else startMining(client, now);
    }

    private static void startFighting(Minecraft client, long now) {
        CombatTarget combat = CombatTarget.of(current.name());
        if (combat == null) {
            skipped.put(current.name(), now + restMs());
            current = null;
            enter(Stage.OBSERVE, "unknown combat commission");
            return;
        }
        boolean auto = isGoblinSlayer(current.name());
        // a hunt that counts anywhere learns and revisits spawns anywhere; the zone line is no filter for it
        fighter.start(client, combat.mobs(), combat.zoneBound() ? combat.area() : null, auto);
        Weapons.Choice w = fighter.chooseWeapon(client, now);
        if (w.kind() == null) {
            // other commissions may still be doable, so this one is rested rather than stopping everything
            fighter.stop(client);
            skipped.put(current.name(), now + restMs());
            lastFailure.put(current.name(), w.reason());
            DwarvenLog.log("combat: " + w.reason() + "; leaving " + current.name() + " for " + AetherConfig.DWARVEN_REST_MINUTES.get() + " minutes");
            current = null;
            enter(Stage.OBSERVE, "no weapon");
            return;
        }
        hitsAtLastProgress = 0;
        waitingSince = 0;
        lastProgress = current.progress();
        lastProgressAt = now;
        enter(Stage.FIGHT, "fighting for " + current.name());
    }

    private static void fight(Minecraft client, long now) {
        if (commissionChanged(client)) return;
        if (client.gui.screen() != null) {
            pauseWork(client);
            return;
        }
        if (objective.kind() == Objective.Kind.EVENT && !GoblinRaid.active(ClientUtils.getSidebarLines())) {
            DwarvenLog.log("the raid ended, going back to commissions");
            fighter.stop(client);
            current = null;
            enter(Stage.OBSERVE, "raid over");
            return;
        }
        if (wrongZone(objective)) {
            if (wrongZoneSince == 0) wrongZoneSince = now;
            if (now - wrongZoneSince > WRONG_ZONE_MS) {
                wrongZoneSince = 0;
                DwarvenLog.log("combat: " + client.player.blockPosition().toShortString() + " is in " + zone + ", not " + objective.area()
                        + "; kills here wouldn't count, heading back in");
                fighter.stop(client);
                goWork(client, now);
                return;
            }
        } else {
            wrongZoneSince = 0;
        }
        fighter.zone(ZONE.fresh(now) ? zone : "");
        fighter.tick(client);
        if (fighter.outcome() == DwarvenFighter.Outcome.NO_WEAPON) {
            String why = fighter.weapon() == null ? "no usable combat equipment" : fighter.weapon().reason();
            fighter.stop(client);
            skipped.put(current.name(), now + restMs());
            lastFailure.put(current.name(), why);
            DwarvenLog.log("combat: " + why + "; leaving " + current.name() + " for " + AetherConfig.DWARVEN_REST_MINUTES.get() + " minutes");
            current = null;
            enter(Stage.OBSERVE, "no weapon");
            return;
        }
        if (current.progress() > lastProgress + 1e-6) {
            STATS.progress(current.progress() - lastProgress);
            fighter.progressed(now);
            DwarvenLog.log(String.format("%s commission progress increased: %.0f%% -> %.0f%%", isGoblinSlayer(current.name()) ? "[GOBLIN]" : "combat:",
                    lastProgress * 100, current.progress() * 100));
            lastProgress = current.progress();
            lastProgressAt = now;
            hitsAtLastProgress = fighter.hits();
            budget.succeeded(Failure.NO_ORE);
        }
        // waiting out a respawn isn't a stall; swinging for a long time with nothing to show for it is
        boolean stalled = now - lastProgressAt > FIGHT_STALL_MS && fighter.hits() - hitsAtLastProgress >= FIGHT_STALL_HITS;
        if (fighter.outcome() == DwarvenFighter.Outcome.WAITING_FOR_RESPAWN) {
            if (betterThanWaiting(client, now)) return;
        } else {
            waitingSince = 0;
        }
        if (fighter.outcome() == DwarvenFighter.Outcome.NO_TARGETS || stalled) {
            DwarvenLog.log((stalled ? "hits on " + current.name() + " stopped counting for " + FIGHT_STALL_MS / 1000 + "s"
                    : "no mobs for " + current.name() + " around") + ", leaving it for " + AetherConfig.DWARVEN_REST_MINUTES.get() + " minutes");
            fighter.stop(client);
            skipped.put(current.name(), now + restMs());
            current = null;
            enter(Stage.OBSERVE, "giving this commission a rest");
        }
    }

    // while mobs respawn, another commission may be the better use of the time; the hunt itself stays eligible
    private static boolean betterThanWaiting(Minecraft client, long now) {
        if (waitingSince == 0) waitingSince = now;
        if (now - waitingSince < SWITCH_WHILE_WAITING_MS || now - lastWaitCheckAt < SWITCH_WHILE_WAITING_MS) return false;
        lastWaitCheckAt = now;
        BlockPos feet = client.player.blockPosition();
        List<DwarvenCommission> others = new ArrayList<>();
        for (DwarvenCommission c : commissions) {
            if (c.done() || c.name().equals(current.name()) || skipped.containsKey(c.name())) continue;
            Objective o = Objective.of(c.name());
            if (o.kind() == Objective.Kind.EVENT) continue;
            if (o.kind() == Objective.Kind.MINING && o.area() != null && !DwarvenStore.AREAS.knows(o.area())) continue;
            others.add(c);
        }
        CommissionScheduler.Choice other = CommissionScheduler.choose(others, rules(), null, o -> travelSeconds(o, feet, now),
                c -> DwarvenStore.WORK.secondsFor(c.name()));
        if (other == null) return false;
        DwarvenLog.log("combat: no " + current.name() + " mobs for " + (now - waitingSince) / 1000 + "s, doing " + other.commission().name() + " meanwhile");
        fighter.stop(client);
        waitingSince = 0;
        current = other.commission();
        objective = other.objective();
        triedSpots.clear();
        goWork(client, now);
        return true;
    }

    private static CommissionScheduler.Rules rules() {
        return new CommissionScheduler.Rules(
                CommissionScheduler.Strategy.values()[AetherConfig.DWARVEN_STRATEGY.get()], AetherConfig.DWARVEN_MITHRIL.get(),
                AetherConfig.DWARVEN_TITANIUM.get(), AetherConfig.DWARVEN_AREA_MINING.get(), AetherConfig.DWARVEN_OVERLAP.get(),
                AetherConfig.DWARVEN_SWITCH_GAIN.get(), AetherConfig.DWARVEN_COMBAT.get(), true);
    }

    private static void startMining(Minecraft client, long now) {
        Set<OreKind> wanted = EnumSet.of(OreKind.MITHRIL_GRAY, OreKind.MITHRIL_GREEN, OreKind.MITHRIL_BLUE);
        boolean titaniumJob = objective.ore() == Objective.Ore.TITANIUM;
        if (!AetherConfig.DWARVEN_COMMISSION_AWARE.get()) {
            // titanium appears where mithril is mined, so a titanium job digs mithril until some shows up
            if (titaniumJob || AetherConfig.DWARVEN_TITANIUM_PRIORITY.get()) wanted.add(OreKind.TITANIUM);
            if (titaniumJob && !AetherConfig.DWARVEN_MITHRIL_FOR_TITANIUM.get()) wanted = EnumSet.of(OreKind.TITANIUM);
            miner.start(client, wanted, titaniumJob || AetherConfig.DWARVEN_TITANIUM_PRIORITY.get(), objective.area());
        } else {
            // a mithril job only spends time on titanium when something counts it: another open titanium commission
            // here, or the user asking for it
            boolean otherTitanium = false;
            for (DwarvenCommission c : commissions) {
                if (c.done() || c.name().equals(current.name())) continue;
                Objective o = Objective.of(c.name());
                if (o.kind() == Objective.Kind.MINING && o.ore() == Objective.Ore.TITANIUM
                        && (o.area() == null || o.area().equalsIgnoreCase(objective.area()))) otherTitanium = true;
            }
            boolean titaniumCounts = titaniumJob || otherTitanium || !AetherConfig.DWARVEN_AVOID_TITANIUM_IN_MITHRIL.get();
            if (titaniumCounts) wanted.add(OreKind.TITANIUM);
            boolean mithrilForTitanium = AetherConfig.DWARVEN_MITHRIL_FOR_TITANIUM.get();
            if (titaniumJob && !mithrilForTitanium) wanted = EnumSet.of(OreKind.TITANIUM);
            // titanium first off: every ore the job can use counts the same, nearest first
            BlockEfficiency.Job job = titaniumJob && !AetherConfig.DWARVEN_TITANIUM_PRIORITY.get()
                    ? new BlockEfficiency.Job(false, true, true) : new BlockEfficiency.Job(titaniumJob, titaniumCounts, mithrilForTitanium);
            if (otherTitanium && !titaniumJob) DwarvenLog.log("miner: an open titanium commission here also counts titanium, so it's mined too");
            miner.start(client, wanted, job, objective.area());
        }
        mineStartedAt = now;
        mineStartProgress = current.progress();
        lastProgress = current.progress();
        lastProgressAt = now;
        minedAtLastProgress = miner.mined();
        enter(Stage.MINE, "mining for " + current.name());
    }

    private static boolean commissionChanged(Minecraft client) {
        // an empty read is the tab list flickering, not every commission vanishing at once
        if (commissions.isEmpty()) return false;
        DwarvenCommission mine = find(current.name());
        long now = System.currentTimeMillis();
        if (mine != null && !mine.done()) {
            current = mine;
            return false;
        }
        stopWork(client);
        navigator.stop(client);
        double gained = (mine == null ? 1.0 : mine.progress()) - mineStartProgress;
        if (stage == Stage.MINE) {
            DwarvenStore.WORK.record(current.name(), (now - mineStartedAt) / 1000.0, gained);
            DwarvenStore.workChanged();
        }
        DwarvenLog.log(current.name() + (mine == null ? " is gone from the list" : " is DONE") + String.format(" after %.0fs", (now - stageSince) / 1000.0));
        if (mine != null && commissionSince > 0) {
            STATS.commissionDone(now - commissionSince);
            DwarvenLog.log(String.format("stats: %s took %.0fs from choosing it; session average %.0fs, travel %.0fs per commission",
                    current.name(), (now - commissionSince) / 1000.0, STATS.averageCommissionMs() / 1000.0, STATS.averageTravelMs() / 1000.0));
        }
        commissionSince = 0;
        current = null;
        enter(Stage.OBSERVE, "commission finished");
        return true;
    }

    private static DwarvenCommission find(String name) {
        for (DwarvenCommission c : commissions) if (c.name().equals(name)) return c;
        return null;
    }

    private static void mine(Minecraft client, long now) {
        if (commissionChanged(client)) return;
        if (client.gui.screen() != null) {
            pauseWork(client);
            return;
        }
        if (wrongZone(objective)) {
            if (wrongZoneSince == 0) wrongZoneSince = now;
            if (now - wrongZoneSince > WRONG_ZONE_MS) {
                wrongZoneSince = 0;
                // mining here wouldn't count for this commission; drop these cells from the area and go back in
                DwarvenLog.log("vein: mining position " + client.player.blockPosition().toShortString() + " is in " + zone + ", not "
                        + objective.area() + "; repositioning");
                // the stand itself is ruled out for this area; the cells around it keep what the scoreboard really said
                DwarvenStore.AREAS.markWrongGoal(objective.area(), client.player.blockPosition());
                stopWork(client);
                goWork(client, now);
                return;
            }
        } else {
            wrongZoneSince = 0;
        }
        miner.zone(ZONE.fresh(now) ? zone : "");
        miner.tick(client);
        if (current.progress() > lastProgress + 1e-6) {
            STATS.progress(current.progress() - lastProgress);
            lastProgress = current.progress();
            lastProgressAt = now;
            minedAtLastProgress = miner.mined();
            budget.succeeded(Failure.MINING_STALLED);
            budget.succeeded(Failure.NO_ORE);
        }
        BlockPos feet = client.player.blockPosition();
        if (miner.outcome() == DwarvenMiner.Outcome.TOOL_MISSING) {
            halt(client, "the mining tool is no longer in the hotbar");
            return;
        }
        boolean stalled = now - lastProgressAt > STALL_LIMIT_MS && miner.mined() - minedAtLastProgress >= STALL_MIN_BLOCKS;
        if (stalled) {
            // blocks are breaking but the commission isn't moving, so this ground doesn't count for it
            lastProgressAt = now;
            minedAtLastProgress = miner.mined();
            relocate(client, now, Failure.MINING_STALLED, "mined " + STALL_MIN_BLOCKS + "+ blocks with no progress at " + feet.toShortString());
            return;
        }
        if (miner.outcome() == DwarvenMiner.Outcome.NO_ORE) {
            relocate(client, now, Failure.NO_ORE, "no reachable ore around " + feet.toShortString());
        }
    }

    // move to another known spot for this objective, or give the commission a rest when there is none left
    private static void relocate(Minecraft client, long now, Failure failure, String why) {
        BlockPos feet = client.player.blockPosition();
        triedSpots.add(feet);
        SpotMemory.Spot here = DwarvenStore.SPOTS.at(feet);
        if (here != null) triedSpots.add(here.pos);
        RetryBudget.Verdict v = budget.fail(failure);
        BlockPos next = AetherConfig.DWARVEN_EXPLORE.get() ? destination(objective, feet, now) : null;
        if (next != null && next.distSqr(feet) < 16) {
            triedSpots.add(next);
            next = destination(objective, feet, now);
        }
        DwarvenLog.log(why + "; " + (next == null ? "no other known spot" : "trying " + next.toShortString()) + " (try " + v.attempt() + ")");
        if (!v.retry() || next == null) {
            budget.succeeded(failure);
            stopWork(client);
            skipped.put(current.name(), now + restMs());
            current = null;
            enter(Stage.OBSERVE, "giving this commission a rest");
            return;
        }
        stopWork(client);
        navigator.start(client, next, "another spot", ARRIVE_WITHIN);
        enter(Stage.TRAVEL, "relocating");
    }

    // 0 = pigeon, else walk to an emissary; 1 = pigeon only; 2 = emissary only
    private static void startClaim(Minecraft client) {
        int method = AetherConfig.DWARVEN_CLAIM_METHOD.get();
        claimTravel = false;
        if (method != 2 && ClaimManager.hasPigeon(client)) {
            claims.start(client);
            enter(Stage.CLAIM, "a commission is done (pigeon)");
            return;
        }
        if (method == 1) {
            halt(client, "a commission is done but there is no Royal Pigeon in the hotbar to claim it");
            return;
        }
        var emissary = DwarvenStore.EMISSARIES.nearest(client.player.blockPosition());
        if (emissary == null) {
            halt(client, "a commission is done, there's no Royal Pigeon, and no Emissary has been seen yet (walk past one once)");
            return;
        }
        claimEmissary = emissary.getValue();
        claimTravel = true;
        navigator.start(client, claimEmissary, emissary.getKey(), 3.0);
        enter(Stage.CLAIM, "a commission is done (walking to " + emissary.getKey() + ")");
    }

    private static void claim(Minecraft client, long now) {
        if (claimTravel) {
            navigator.tick(client);
            var nearNpc = navigator.status() == AreaNavigator.Status.FAILED
                    ? dev.aether.modules.dwarven.claim.Emissaries.npcNear(client, claimEmissary, 6.0) : null;
            if (nearNpc != null && client.player.distanceTo(nearNpc) <= 4.5) {
                // the walk gave up beside the emissary, usually pushed off by the npc itself; it can be clicked from here
                DwarvenLog.log(String.format("claim: walk stopped %.1f blocks from the emissary; claiming from here", client.player.distanceTo(nearNpc)));
                claimTravel = false;
                claims.startEmissary(client, nearNpc);
                return;
            }
            if (navigator.status() == AreaNavigator.Status.FAILED) {
                claimTravel = false;
                RetryBudget.Verdict v = budget.fail(Failure.CLAIM);
                if (!v.retry()) halt(client, "couldn't reach an Emissary to claim (" + navigator.failure() + ")");
                else pause(v.waitMs(), "couldn't reach the emissary, try " + v.attempt());
                return;
            }
            if (navigator.status() != AreaNavigator.Status.ARRIVED) return;
            claimTravel = false;
            var npc = dev.aether.modules.dwarven.claim.Emissaries.npcNear(client, claimEmissary, 6.0);
            if (npc == null) {
                DwarvenLog.log("claim: reached " + claimEmissary.toShortString() + " but no emissary is standing there");
                RetryBudget.Verdict v = budget.fail(Failure.CLAIM);
                if (!v.retry()) halt(client, "the Emissary wasn't where it was last seen");
                else pause(v.waitMs(), "emissary missing, try " + v.attempt());
                return;
            }
            claims.startEmissary(client, npc);
        }
        claims.tick(client);
        switch (claims.status()) {
            case RUNNING -> { }
            case CLAIMED, NOTHING_TO_CLAIM -> {
                rewards = rewards.plus(claims.earned());
                DwarvenLog.log("claim: " + claims.status() + ", " + claims.claimed() + " claimed, earned " + claims.earned());
                budget.succeeded(Failure.CLAIM);
                lastClaimAt = now;
                claims.reset();
                current = null;
                enter(Stage.OBSERVE, "claimed");
            }
            case NO_PIGEON -> halt(client, "a commission is done but there is no Royal Pigeon in the hotbar to claim it");
            default -> {
                claims.reset();
                RetryBudget.Verdict v = budget.fail(Failure.CLAIM);
                if (!v.retry()) halt(client, "couldn't claim commissions after " + (v.attempt() - 1) + " tries");
                else pause(v.waitMs(), "claim failed, try " + v.attempt());
            }
        }
    }

    private static void pause(long ms, String why) {
        waitUntil = System.currentTimeMillis() + ms;
        enter(Stage.WAIT, why);
    }

    public static Stage stage() {
        return stage;
    }

    public static String hudState() {
        if (pendingStart) return "starting";
        if (System.currentTimeMillis() < pausedUntil) return "paused (you moved)";
        return switch (stage) {
            case OFF -> "off";
            case VALIDATE -> "checking";
            case OBSERVE -> "choosing";
            case CLAIM -> "claiming";
            case TRAVEL -> "travelling";
            case MINE -> "mining (" + miner.phase().name().toLowerCase().replace('_', ' ') + ")";
            case FIGHT -> "fighting (" + fighter.phaseName() + ")";
            case WAIT -> "waiting";
        };
    }

    public static dev.aether.modules.dwarven.claim.Rewards.Tally rewards() {
        return rewards;
    }

    public static int mithrilSinceTitanium() {
        return miner.mithrilSinceTitanium();
    }

    public static String hudCommission() {
        return current == null ? "-" : current.name();
    }

    public static double hudProgress() {
        return current == null ? -1 : current.progress();
    }

    public static String hudZone() {
        return zone.isEmpty() ? "-" : zone;
    }

    public static BlockPos hudTarget() {
        return stage == Stage.MINE ? (miner.walkGoal() != null ? miner.walkGoal() : miner.target()) : stage == Stage.TRAVEL ? navigator.goal() : null;
    }

    public static long sessionMs() {
        return stage == Stage.OFF ? 0 : System.currentTimeMillis() - sessionStartedAt;
    }

    private static final Sampler SAMPLER = new Sampler(50);
    private static long lastTickNs;
    private static double lastSnapX, lastSnapY, lastSnapZ;
    private static long lastSnapNs;
    private static float lastSnapYaw, lastSnapPitch;
    private static final Path DIAG_DIR = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("dwarven").resolve("diag");

    private static void diagnostics(Minecraft client) {
        boolean on = AetherConfig.DWARVEN_DIAG.get();
        if (!on && Diag.log() != null) Diag.close();
        Set<String> off = new HashSet<>();
        if (!AetherConfig.DWARVEN_DIAG_TRANSITIONS.get()) off.add(Diag.STATE);
        if (!AetherConfig.DWARVEN_DIAG_PATHING.get()) off.add(Diag.PATH);
        if (!AetherConfig.DWARVEN_DIAG_ROUTE.get()) {
            off.add(Diag.ROUTE);
            off.add(Diag.HOP);
        }
        if (!AetherConfig.DWARVEN_DIAG_ROTATION.get()) off.add(Diag.ROT);
        if (!AetherConfig.DWARVEN_DIAG_MINING.get()) off.add(Diag.MINE);
        if (!AetherConfig.DWARVEN_DIAG_RECOVERY.get()) off.add(Diag.RECOVERY);
        Diag.configure(on, off, DwarvenCommissionMacro::newDiagLog);
        SAMPLER.interval(AetherConfig.DWARVEN_DIAG_INTERVAL_MS.get());
        if (stage != Stage.OFF && client.player != null) sample(client);
        if (stage != Stage.OFF && AetherConfig.DWARVEN_WATCHDOG.get()) {
            for (String w : Ops.watchdog(AetherConfig.DWARVEN_WATCHDOG_SLACK.get())) {
                if (w.contains("WARNING")) DwarvenLog.log(w);
                else Diag.event(Diag.PERF, w);
            }
        }
        Diag.flush(System.currentTimeMillis());
    }

    private static DiagLog newDiagLog() {
        String name = "dwarven-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int buffer = AetherConfig.DWARVEN_DIAG_BUFFER.get();
        long maxBytes = AetherConfig.DWARVEN_DIAG_MAX_FILE_MB.get() * 1024L * 1024L;
        SAMPLER.reset();
        return new DiagLog(DIAG_DIR, name, buffer, buffer, maxBytes, AetherConfig.DWARVEN_DIAG_ROTATIONS.get(),
                r -> MacroWorkerThread.getInstance().submitCritical("dwarven diagnostics", r), System.nanoTime());
    }

    // gathers what can be read on the game thread into one immutable line; the writer never touches the world
    private static void sample(Minecraft client) {
        if (!Diag.enabled()) return;
        long ns = System.nanoTime();
        Sampler.Sample smp = SAMPLER.due(ns);
        if (smp == null) return;
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(640);
        b.append(String.format("dt=%.1fms", smp.intervalNs() / 1e6));
        if (smp.lateNs() > 0) b.append(String.format(" late=%.1fms missed=%d", smp.lateNs() / 1e6, smp.missed()));
        b.append(" state=").append(stage).append(" inStateMs=").append(now - stageSince);
        if (current != null) b.append(" commission=\"").append(current.name()).append(String.format("\" %.0f%%", current.progress() * 100));
        if (objective != null) b.append(" objective=").append(objective.kind()).append(objective.area() == null ? "" : "/" + objective.area());
        b.append(" zone=\"").append(zone).append('"').append(ZONE.fresh(now) ? "" : "(stale " + ZONE.sinceJump(now) + "ms)");
        b.append(" paused=").append(client.gui.screen() != null);
        b.append(" subsystem=").append(switch (stage) {
            case TRAVEL -> "navigator";
            case MINE -> "miner";
            case FIGHT -> "fighter";
            case CLAIM -> "claims";
            default -> "macro";
        });
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        if (AetherConfig.DWARVEN_DIAG_COORDS.get()) {
            b.append(String.format(" pos=%.2f,%.2f,%.2f", x, y, z));
            if (lastSnapNs > 0) {
                double secs = (ns - lastSnapNs) / 1e9;
                double moved = Math.sqrt(Math.pow(x - lastSnapX, 2) + Math.pow(z - lastSnapZ, 2));
                b.append(String.format(" speed=%.2f expected=%.2f", secs > 0 ? moved / secs : 0, client.player.isSprinting() ? 5.61 : 4.32));
            }
        }
        if (AetherConfig.DWARVEN_DIAG_ROTATION.get()) {
            float yaw = client.player.getYRot(), pitch = client.player.getXRot();
            b.append(String.format(" yaw=%.1f pitch=%.1f", yaw, pitch));
            var target = RotationManager.targetRotation();
            if (target != null) {
                float diff = Math.abs(net.minecraft.util.Mth.wrapDegrees(target.yaw - yaw)) + Math.abs(target.pitch - pitch);
                b.append(String.format(" targetYaw=%.1f targetPitch=%.1f diff=%.1f", target.yaw, target.pitch, diff));
            }
            if (lastSnapNs > 0) {
                double secs = (ns - lastSnapNs) / 1e9;
                double turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - lastSnapYaw)) + Math.abs(pitch - lastSnapPitch);
                b.append(String.format(" rotVel=%.0fdeg/s", secs > 0 ? turned / secs : 0));
            }
            b.append(" rotating=").append(RotationManager.isRotating());
            lastSnapYaw = yaw;
            lastSnapPitch = pitch;
        }
        lastSnapX = x;
        lastSnapY = y;
        lastSnapZ = z;
        lastSnapNs = ns;
        navigator.describe(b, client, now);
        if (stage == Stage.MINE) miner.describe(b, client, now);
        if (stage == Stage.FIGHT) fighter.describe(b, client, now);
        b.append(String.format(" tickMs=%.2f", lastTickNs / 1e6));
        b.append(" lastOp=").append(Ops.lastEnded()).append(" pending=").append(Ops.oldestOpen());
        b.append(String.format(" planMsTotal=%.1f", navigator.planningNanos() / 1e6)).append(" pathfinderCalls=").append(navigator.pathfinderCalls())
                .append(" plans=").append(navigator.plans()).append(" recoveryMs=").append(STATS.recoveryMs());
        DiagLog log = Diag.log();
        if (log != null) b.append(" queued=").append(log.queued()).append(" dropped=").append(log.dropped());
        Diag.snapshot(b.toString());
    }

    private static Map<String, Long> extraWaste() {
        Map<String, Long> extra = new LinkedHashMap<>();
        extra.put("repeated trips to the same destination", repeatedTripMs);
        return extra;
    }

    // logs the three biggest time sinks, and the full numbers into the diagnostic file when it's on
    private static void reportSession(String header) {
        List<String> learning = learningLines();
        String report = NavReport.text(extraWaste(), header) + sessionLine() + String.join("\n", learning) + "\n";
        Diag.event(Diag.PERF, report.replace("\n", " | "));
        var waste = NavReport.waste(extraWaste());
        for (int i = 0; i < Math.min(3, waste.size()); i++) {
            DwarvenLog.log(String.format("[PERF] wasted time #%d: %s, %.1fs", i + 1, waste.get(i).what(), waste.get(i).ms() / 1000.0));
        }
        for (String line : learning) DwarvenLog.log(line);
    }

    // what navigation and hunting have learned so far, kept across sessions
    private static List<String> learningLines() {
        List<String> out = new ArrayList<>();
        var routes = DwarvenStore.ROUTES;
        out.add(String.format("[LEARNING] %d learned journeys, %d verified by an arrival; %d teleports unreliable or left out; %+.0fs against the historical average so far",
                routes.count(), routes.verifiedCount(), DwarvenStore.TRAILS.unreliableHopCount(), -routes.savedSeconds()));
        var ether = routes.method("etherwarp_leg");
        out.add(String.format("[LEARNING] etherwarp-pathfinder legs: %d worked, %d failed; used only while their expected time beats walking",
                ether.successes, ether.failures));
        List<String> dest = routes.byDestination(6);
        if (!dest.isEmpty()) out.add("[LEARNING] average trip by destination: " + String.join("; ", dest));
        CombatTarget goblins = CombatTarget.of("Goblin Slayer");
        BlockPos from = Minecraft.getInstance().player == null ? BlockPos.ZERO : Minecraft.getInstance().player.blockPosition();
        var spots = DwarvenStore.SPAWNS.productive(from, goblins.mobs(), System.currentTimeMillis(), 3);
        if (!spots.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (var sp : spots) names.add(sp.pos.toShortString() + " (" + sp.kills + " kills)");
            out.add("[LEARNING] most productive goblin locations: " + String.join("; ", names));
        }
        return out;
    }

    private static String sessionLine() {
        long ms = sessionMs();
        double hours = ms / 3_600_000.0;
        return String.format("session %.1f min, %d commissions (%.1f/h), %d wrong-area arrivals, average commission %.0fs, %.1f%% commission progress per minute%n",
                ms / 60000.0, completed, hours > 0 ? completed / hours : 0, STATS.wrongAreas(), STATS.averageCommissionMs() / 1000.0,
                ms > 0 ? STATS.progressTotal() * 100 / (ms / 60000.0) : 0);
    }

    // writes the ring buffer and the report to a file of its own; works with diagnostics off too, minus the event history
    public static void exportDiagnostics() {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = DIAG_DIR.resolve("export-" + stamp + ".txt");
        String report = NavReport.text(extraWaste(), "diagnostic export") + sessionLine();
        DiagLog log = Diag.log();
        // the writer finishes off the game thread; the chat message goes back onto it
        Runnable done = () -> MacroWorkerThread.runOnClient(Minecraft.getInstance(), () -> ClientUtils.sendMessage("§aDwarven diagnostics exported to " + file));
        if (log != null) {
            log.export(file, report, done);
            return;
        }
        MacroWorkerThread.getInstance().submit("dwarven diagnostics export", () -> {
            try {
                java.nio.file.Files.createDirectories(DIAG_DIR);
                java.nio.file.Files.writeString(file, report);
            } catch (java.io.IOException ignored) {
                // reported as done either way; the file is simply missing
            }
            done.run();
        });
    }

    public static List<String[]> combatDebugRows(Minecraft client) {
        if (stage != Stage.FIGHT || client.player == null) return List.of();
        return fighter.debugRows(client, System.currentTimeMillis());
    }

    public static String hudWeapon() {
        Weapons.Choice w = fighter.weapon();
        if (w == null) return null;
        return (w.kind() == null ? "none" : w.name()) + ": " + w.reason();
    }

    public static double planningMs() {
        return navigator.planningNanos() / 1e6;
    }

    public static int completed() {
        return completed;
    }

    public static int minedThisSession() {
        return miner.mined() - minedBeforeThisRun;
    }

    public static int titaniumThisSession() {
        return miner.titaniumMined() - titaniumBeforeThisRun;
    }

    public static int recoveries() {
        return budget.total();
    }

    public static long usualBreakMs() {
        return miner.usualBreakMs();
    }
}
