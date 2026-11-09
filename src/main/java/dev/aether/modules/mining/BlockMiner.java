package dev.aether.modules.mining;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// stands still and mines the mithril and titanium in reach, one block at a time
public final class BlockMiner {
    private static final String[] TOOL_NAMES = {"drill", "pickaxe", "gauntlet", "pickonimbus"};
    private static final long AIM_TIMEOUT_MS = 1500L;
    private static final long SKIP_BLOCK_MS = 15_000L;
    private static final long NO_TARGET_NOTICE_MS = 5000L;

    private enum Phase { SEARCHING, AIMING, MINING }

    private static boolean enabled;
    private static Phase phase = Phase.SEARCHING;
    private static MiningTargetSelector.Target target;
    private static long phaseStartedAt;
    private static long searchingSince;
    private static boolean noTargetNoticeSent;
    private static int levelIdentity;
    private static int blocksMined;
    private static final Map<BlockPos, Long> skipUntil = new HashMap<>();

    private BlockMiner() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getBlocksMined() {
        return blocksMined;
    }

    public static void toggle(Minecraft client) {
        if (enabled) {
            stop(client, true);
        } else {
            start(client);
        }
    }

    public static void start(Minecraft client) {
        if (enabled || client.player == null || client.level == null) return;
        if (MacroStateManager.getCurrentState() != MacroState.State.OFF) {
            ClientUtils.sendMessage("§cStop the running macro before starting the miner.", false);
            return;
        }
        if (!selectMiningTool(client)) {
            ClientUtils.sendMessage("§cNo pickaxe, drill or gauntlet found in hotbar.", false);
            return;
        }
        if (allowedKinds().isEmpty()) {
            ClientUtils.sendMessage("§cEnable at least one block type in the Block Miner settings.", false);
            return;
        }
        enabled = true;
        blocksMined = 0;
        skipUntil.clear();
        levelIdentity = System.identityHashCode(client.level);
        enterSearching();
        MacroStateManager.setCurrentState(MacroState.State.MINING);
        ClientUtils.sendMessage("§aBlock miner started.", false);
    }

    public static void stopForMacro(Minecraft client) {
        stop(client, false);
    }

    private static void stop(Minecraft client, boolean announce) {
        if (!enabled) return;
        enabled = false;
        target = null;
        skipUntil.clear();
        if (client != null) client.execute(() -> releaseAttack(client));
        RotationManager.cancelRotation();
        if (MacroStateManager.getCurrentState() == MacroState.State.MINING) {
            MacroStateManager.setCurrentState(MacroState.State.OFF);
        }
        if (announce) {
            ClientUtils.sendMessage("§eBlock miner stopped. Mined " + blocksMined + " blocks.", false);
        }
    }

    public static void update() {
        if (!enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        if (System.identityHashCode(client.level) != levelIdentity) {
            stop(client, true);
            return;
        }
        if (client.gui.screen() != null) {
            releaseAttack(client);
            return;
        }

        pruneSkips();
        LiveMiningView view = new LiveMiningView(client.level, client.player);
        switch (phase) {
            case SEARCHING -> tickSearching(client, view);
            case AIMING -> tickAiming(client, view);
            case MINING -> tickMining(client, view);
        }
    }

    private static void tickSearching(Minecraft client, MiningView view) {
        releaseAttack(client);
        MiningTargetSelector.Target found = MiningTargetSelector.select(view,
                client.player.getEyePosition(), client.player.getViewVector(1.0f),
                client.player.blockInteractionRange() - 0.1,
                allowedKinds(), AetherConfig.MINER_PREFER_TITANIUM.get(), skipUntil.keySet());
        if (found == null) {
            long now = System.currentTimeMillis();
            if (!noTargetNoticeSent && now - searchingSince > NO_TARGET_NOTICE_MS) {
                ClientUtils.sendMessage("§eNo minable blocks in reach. Waiting for them to regenerate...", false);
                noTargetNoticeSent = true;
            }
            return;
        }
        noTargetNoticeSent = false;
        target = found;
        RotationManager.cancelRotation();
        RotationManager.initiateRotation(client, found.aim(), 0L);
        setPhase(Phase.AIMING);
    }

    private static void tickAiming(Minecraft client, MiningView view) {
        releaseAttack(client);
        if (!MiningTargetSelector.isStillMineable(view, target, allowedKinds())) {
            enterSearching();
            return;
        }
        if (isCrosshairOnTarget(client)) {
            RotationManager.cancelRotation();
            setPhase(Phase.MINING);
            return;
        }
        if (System.currentTimeMillis() - phaseStartedAt > AIM_TIMEOUT_MS) {
            skip(target.pos());
            enterSearching();
            return;
        }
        if (!RotationManager.isRotating()) {
            RotationManager.initiateRotation(client, target.aim(), 0L);
        }
    }

    private static void tickMining(Minecraft client, MiningView view) {
        if (!MiningTargetSelector.isStillMineable(view, target, allowedKinds())) {
            blocksMined++;
            enterSearching();
            return;
        }
        if (!isCrosshairOnTarget(client)) {
            setPhase(Phase.AIMING);
            return;
        }
        long timeoutMs = Math.round(AetherConfig.MINER_BLOCK_TIMEOUT_SECONDS.get() * 1000.0f);
        if (System.currentTimeMillis() - phaseStartedAt > timeoutMs) {
            ClientUtils.sendDebugMessage("BlockMiner: gave up on " + target.pos().toShortString());
            skip(target.pos());
            enterSearching();
            return;
        }
        MacroInput.setAttack(client.options.keyAttack, true);
    }

    private static boolean isCrosshairOnTarget(Minecraft client) {
        HitResult hit = client.hitResult;
        return hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(target.pos());
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

    private static Set<MiningBlockKind> allowedKinds() {
        Set<MiningBlockKind> kinds = EnumSet.noneOf(MiningBlockKind.class);
        if (AetherConfig.MINER_GRAY_MITHRIL.get()) kinds.add(MiningBlockKind.GRAY_MITHRIL);
        if (AetherConfig.MINER_GREEN_MITHRIL.get()) kinds.add(MiningBlockKind.GREEN_MITHRIL);
        if (AetherConfig.MINER_BLUE_MITHRIL.get()) kinds.add(MiningBlockKind.BLUE_MITHRIL);
        if (AetherConfig.MINER_TITANIUM.get()) kinds.add(MiningBlockKind.TITANIUM);
        return kinds;
    }

    private static void enterSearching() {
        target = null;
        searchingSince = System.currentTimeMillis();
        setPhase(Phase.SEARCHING);
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseStartedAt = System.currentTimeMillis();
    }

    private static void skip(BlockPos pos) {
        skipUntil.put(pos, System.currentTimeMillis() + SKIP_BLOCK_MS);
    }

    private static void pruneSkips() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> it = skipUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) it.remove();
        }
    }

    private static void releaseAttack(Minecraft client) {
        if (client.options != null) MacroInput.setAttack(client.options.keyAttack, false);
    }
}
