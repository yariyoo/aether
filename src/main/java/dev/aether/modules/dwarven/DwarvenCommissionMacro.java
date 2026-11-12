package dev.aether.modules.dwarven;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.dwarven.RetryBudget.Failure;
import dev.aether.modules.dwarven.claim.ClaimManager;
import dev.aether.modules.dwarven.commission.CombatTarget;
import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.dwarven.commission.CommissionScheduler;
import dev.aether.modules.dwarven.commission.DwarvenCommission;
import dev.aether.modules.dwarven.commission.Objective;
import dev.aether.modules.dwarven.fight.DwarvenFighter;
import dev.aether.modules.dwarven.memory.DwarvenStore;
import dev.aether.modules.dwarven.memory.HuntPoints;
import dev.aether.modules.dwarven.memory.SpotMemory;
import dev.aether.modules.dwarven.mine.DwarvenMiner;
import dev.aether.modules.dwarven.nav.AreaNavigator;
import dev.aether.modules.dwarven.world.OreKind;
import dev.aether.modules.dwarven.world.OreScanner;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// runs dwarven mines commissions end to end: read them, pick one, get there, mine, claim, repeat
public final class DwarvenCommissionMacro {
    public enum Stage { OFF, VALIDATE, OBSERVE, CLAIM, TRAVEL, MINE, FIGHT, WAIT }

    private static final long OBSERVE_EVERY_MS = 1000L;
    private static final long LEARN_EVERY_MS = 500L;
    private static final long SAVE_EVERY_MS = 30_000L;
    private static final long UNREADABLE_LIMIT_MS = 10_000L;
    private static final long OUTSIDE_LIMIT_MS = 15_000L;
    private static final long STALL_LIMIT_MS = 45_000L;
    private static final int STALL_MIN_BLOCKS = 15;
    private static final long TAB_LAG_MS = 6000L;
    private static final long INPUT_PAUSE_MS = 3000L;
    private static final long SKIP_COMMISSION_MS = 5 * 60_000L;
    private static final double ARRIVE_WITHIN = 2.5;
    private static final double PRIOR_RATE = 60.0;

    private static final OreScanner scanner = new OreScanner();
    private static final DwarvenMiner miner = new DwarvenMiner(scanner);
    private static final DwarvenFighter fighter = new DwarvenFighter();
    // a fight that moves the commission along keeps going; this long with no progress means the hunting ground is wrong
    private static final long FIGHT_STALL_MS = 90_000L;
    private static final double NEAR_HUNT_POINT = 24.0;
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

    private static long sessionStartedAt;
    private static int completed;
    private static int minedBeforeThisRun;
    private static int titaniumBeforeThisRun;
    private static String zone = "";

    private DwarvenCommissionMacro() {}

    public static boolean isRunning() {
        return stage != Stage.OFF || pendingStart;
    }

    public static void toggle(Minecraft client) {
        if (isRunning()) stop(client, "toggled off", true);
        else requestStart();
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
        DwarvenStore.save();
        DwarvenLog.log("stopped: " + reason);
        if (stopMacroState && MacroStateManager.getCurrentState() == MacroState.State.DWARVEN_COMMISSIONS) {
            MacroStateManager.stopMacro(client, "Dwarven commissions stopped: " + reason, false);
        }
    }

    private static void halt(Minecraft client, String reason) {
        ClientUtils.sendMessage("§cDwarven Commissions stopped: " + reason, false);
        stop(client, reason, true);
    }

    public static void onChat(String message) {
        miner.onChat(message);
        claims.onChat(message);
        if (stage == Stage.OFF) return;
        String done = CommissionReader.completedInChat(message);
        if (done != null) {
            completed++;
            DwarvenLog.log("chat: " + done + " commission complete (" + completed + " this session)");
        }
    }

    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        pollKeybind(client);
        boolean inMines = ClientUtils.getCurrentLocation() == MacroState.Location.DWARVEN_MINES;
        if (inMines) learn(client, now);
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
            halt(client, unsafe);
            return;
        }
        if (userTookOver(client, now)) return;
        scanner.tick(client, AetherConfig.DWARVEN_EXTENDED_RADIUS.get(), AetherConfig.DWARVEN_SCAN_BUDGET.get());
        if (now - lastObserveAt >= OBSERVE_EVERY_MS) {
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
        zone = readZone();
        BlockPos feet = client.player.blockPosition();
        if (!zone.isEmpty() && client.player.onGround()) DwarvenStore.AREAS.observe(zone, feet);
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
        minedBeforeThisRun = miner.mined();
        titaniumBeforeThisRun = miner.titaniumMined();
        sessionStartedAt = now;
        outsideSince = 0;
        unreadableSince = 0;
        lastOverrideSeen = RotationManager.lastManualOverrideAt();
        levelId = System.identityHashCode(client.level);
        MacroStateManager.setCurrentState(MacroState.State.DWARVEN_COMMISSIONS);
        DwarvenLog.log("started at " + client.player.blockPosition().toShortString() + ", zone '" + zone + "'");
        enter(Stage.VALIDATE, "start");
    }

    private static void enter(Stage next, String why) {
        if (stage != next) DwarvenLog.log(stage + " -> " + next + " (" + why + ")");
        stage = next;
        stageSince = System.currentTimeMillis();
    }

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
            DwarvenLog.log("you moved the camera, pausing for " + INPUT_PAUSE_MS / 1000 + "s");
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
            claims.start(client);
            enter(Stage.CLAIM, "a commission is done");
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
            if (o.kind() == Objective.Kind.MINING && o.area() != null && !DwarvenStore.AREAS.knows(o.area())) {
                unknown.add(o.area());
                continue;
            }
            open.add(c);
        }
        CommissionScheduler.Rules rules = new CommissionScheduler.Rules(
                CommissionScheduler.Strategy.values()[AetherConfig.DWARVEN_STRATEGY.get()], AetherConfig.DWARVEN_MITHRIL.get(),
                AetherConfig.DWARVEN_TITANIUM.get(), AetherConfig.DWARVEN_AREA_MINING.get(), AetherConfig.DWARVEN_OVERLAP.get(),
                AetherConfig.DWARVEN_SWITCH_GAIN.get(), AetherConfig.DWARVEN_COMBAT.get());
        CommissionScheduler.Choice choice = CommissionScheduler.choose(open, rules, current == null ? null : current.name(),
                o -> travelSeconds(o, feet, now), c -> DwarvenStore.WORK.secondsFor(c.name()));
        if (choice == null) {
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
            triedSpots.clear();
            DwarvenLog.log(String.format("chose %s (%.0f%%), about %.0fs%s; all: %s", current.name(), current.progress() * 100, choice.seconds(),
                    choice.alsoAdvanced().isEmpty() ? "" : ", also advances " + choice.alsoAdvanced().stream().map(DwarvenCommission::name).toList(),
                    describe(commissions)));
        }
        goWork(client, now);
    }

    private static String whyNothing(List<DwarvenCommission> list, List<String> unknown) {
        List<String> reasons = new ArrayList<>();
        for (DwarvenCommission c : list) {
            if (c.done()) continue;
            Objective o = Objective.of(c.name());
            String reason = switch (o.kind()) {
                case COMBAT -> skipped.containsKey(c.name()) ? "resting after failures" : "combat is turned off in settings";
                case EVENT -> "events aren't supported yet";
                case UNKNOWN -> "not a commission it knows yet";
                case MINING -> skipped.containsKey(c.name()) ? "resting after failures"
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

    private static double travelSeconds(Objective o, BlockPos feet, long now) {
        BlockPos target = destination(o, feet, now);
        if (target == null) return o.anywhere() ? 0 : 600;
        return AreaNavigator.travelSeconds(feet, target);
    }

    // where to go for an objective: the best remembered spot, else the nearest ground seen in that zone
    private static BlockPos destination(Objective o, BlockPos feet, long now) {
        if (o.kind() == Objective.Kind.COMBAT) {
            CombatTarget combat = current == null ? null : CombatTarget.of(current.name());
            BlockPos hunt = combat == null ? null : HuntPoints.nearest(combat.mobs(), feet);
            if (hunt != null) return hunt;
            return o.area() == null ? null : DwarvenStore.AREAS.nearestPoint(o.area(), feet);
        }
        if (AetherConfig.DWARVEN_VEIN_MEMORY.get()) {
            SpotMemory.Spot spot = DwarvenStore.SPOTS.best(o.area(), now, AetherConfig.DWARVEN_RECHECK_SECONDS.get() * 1000L,
                    PRIOR_RATE, triedSpots, p -> AreaNavigator.walkSeconds(feet, p));
            if (spot != null && spot.blocks > 0) return spot.pos;
        }
        if (o.area() == null) return null;
        return DwarvenStore.AREAS.nearestPoint(o.area(), feet);
    }

    private static boolean inArea(Objective o, BlockPos feet) {
        if (o.kind() == Objective.Kind.COMBAT) {
            if (o.area() != null && zone.equalsIgnoreCase(o.area())) return true;
            CombatTarget combat = CombatTarget.of(current.name());
            BlockPos hunt = combat == null ? null : HuntPoints.nearest(combat.mobs(), feet);
            return hunt != null && Math.sqrt(hunt.distSqr(feet)) < NEAR_HUNT_POINT;
        }
        if (o.area() == null) return true;
        return zone.equalsIgnoreCase(o.area()) || (zone.isEmpty() && DwarvenStore.AREAS.contains(o.area(), feet));
    }

    private static void goWork(Minecraft client, long now) {
        BlockPos feet = client.player.blockPosition();
        if (inArea(objective, feet)) {
            startWork(client, now);
            return;
        }
        BlockPos target = destination(objective, feet, now);
        if (target == null) {
            startWork(client, now);
            return;
        }
        navigator.start(client, target, objective.area() == null ? "a remembered spot" : objective.area(), ARRIVE_WITHIN);
        enter(Stage.TRAVEL, "heading to " + (objective.area() == null ? "ore" : objective.area()));
    }

    private static void travel(Minecraft client, long now) {
        if (commissionChanged(client)) return;
        navigator.tick(client);
        BlockPos feet = client.player.blockPosition();
        // being in the right zone is what matters, not the exact point we aimed for
        boolean there = navigator.status() == AreaNavigator.Status.ARRIVED
                || (objective.area() != null && zone.equalsIgnoreCase(objective.area()) && Math.sqrt(feet.distSqr(navigator.goal())) < 12);
        if (there) {
            navigator.stop(client);
            budget.succeeded(Failure.NAVIGATION);
            startWork(client, now);
            return;
        }
        if (navigator.status() == AreaNavigator.Status.FAILED) {
            RetryBudget.Verdict v = budget.fail(Failure.NAVIGATION);
            triedSpots.add(navigator.goal());
            if (!v.retry()) {
                budget.succeeded(Failure.NAVIGATION);
                skipped.put(current.name(), now + SKIP_COMMISSION_MS);
                DwarvenLog.log("can't reach " + current.name() + " (" + navigator.failure() + "), leaving it for " + SKIP_COMMISSION_MS / 60_000 + " minutes");
                current = null;
                enter(Stage.OBSERVE, "navigation gave up");
            } else {
                pause(v.waitMs(), "navigation failed (" + navigator.failure() + "), try " + v.attempt());
            }
        }
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
        if (objective.kind() == Objective.Kind.COMBAT) startFighting(client, now);
        else startMining(client, now);
    }

    private static void startFighting(Minecraft client, long now) {
        CombatTarget combat = CombatTarget.of(current.name());
        if (combat == null) {
            skipped.put(current.name(), now + SKIP_COMMISSION_MS);
            current = null;
            enter(Stage.OBSERVE, "unknown combat commission");
            return;
        }
        if (DwarvenFighter.weaponSlot(client) < 0) {
            DwarvenLog.log("no weapon found in the hotbar, fighting with whatever is held");
        }
        fighter.start(client, combat.mobs());
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
        fighter.tick(client);
        if (current.progress() > lastProgress + 1e-6) {
            lastProgress = current.progress();
            lastProgressAt = now;
            budget.succeeded(Failure.NO_ORE);
        }
        boolean stalled = now - lastProgressAt > FIGHT_STALL_MS;
        if (fighter.outcome() == DwarvenFighter.Outcome.NO_TARGETS || stalled) {
            DwarvenLog.log((stalled ? "no progress on " + current.name() + " for " + FIGHT_STALL_MS / 1000 + "s"
                    : "no mobs for " + current.name() + " around") + ", leaving it for " + SKIP_COMMISSION_MS / 60_000 + " minutes");
            fighter.stop(client);
            skipped.put(current.name(), now + SKIP_COMMISSION_MS);
            current = null;
            enter(Stage.OBSERVE, "giving this commission a rest");
        }
    }

    private static void startMining(Minecraft client, long now) {
        Set<OreKind> wanted = EnumSet.of(OreKind.MITHRIL_GRAY, OreKind.MITHRIL_GREEN, OreKind.MITHRIL_BLUE);
        boolean titaniumJob = objective.ore() == Objective.Ore.TITANIUM;
        // titanium appears where mithril is mined, so a titanium job digs mithril until some shows up
        if (titaniumJob || AetherConfig.DWARVEN_TITANIUM_PRIORITY.get()) wanted.add(OreKind.TITANIUM);
        if (titaniumJob && !AetherConfig.DWARVEN_MITHRIL_FOR_TITANIUM.get()) wanted = EnumSet.of(OreKind.TITANIUM);
        miner.start(client, wanted, titaniumJob || AetherConfig.DWARVEN_TITANIUM_PRIORITY.get(), objective.area());
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
        miner.zone(zone);
        miner.tick(client);
        if (current.progress() > lastProgress + 1e-6) {
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
            skipped.put(current.name(), now + SKIP_COMMISSION_MS);
            current = null;
            enter(Stage.OBSERVE, "giving this commission a rest");
            return;
        }
        stopWork(client);
        navigator.start(client, next, "another spot", ARRIVE_WITHIN);
        enter(Stage.TRAVEL, "relocating");
    }

    private static void claim(Minecraft client, long now) {
        claims.tick(client);
        switch (claims.status()) {
            case RUNNING -> { }
            case CLAIMED, NOTHING_TO_CLAIM -> {
                DwarvenLog.log("claim: " + claims.status() + ", " + claims.claimed() + " claimed");
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
            case FIGHT -> fighter.target() == null ? "fighting (looking)" : "fighting";
            case WAIT -> "waiting";
        };
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
