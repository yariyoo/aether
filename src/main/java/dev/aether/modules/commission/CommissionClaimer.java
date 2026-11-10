package dev.aether.modules.commission;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

// claims finished commissions through the royal pigeon, wherever it sits in the hotbar
public final class CommissionClaimer {
    private static final String PIGEON = "Royal Pigeon";
    private static final String MENU_TITLE = "Commissions";
    private static final long MENU_TIMEOUT_MS = 3000L;
    private static final long CLICK_SETTLE_MS = 400L;
    private static final long RETRY_AFTER_MS = 5000L;
    private static final int TAB_CHECK_TICKS = 20;

    private enum Step { IDLE, OPENING, CLAIMING, CLOSING }

    private static Step step = Step.IDLE;
    private static long stepStartedAt;
    private static long lastClickAt;
    private static long lastAttemptAt;
    private static int previousSlot = -1;
    private static int restoreSlotNextTick = -1;
    private static boolean requested;
    private static int claimedThisRun;
    private static final java.util.Map<Integer, Integer> clicksPerSlot = new java.util.HashMap<>();
    // a slot still showing claimable after this many clicks isn't taking them
    private static final int MAX_CLICKS_PER_SLOT = 2;
    private static int failedClaims;
    private static int ticks;

    private CommissionClaimer() {}

    public static boolean isBusy() {
        return step != Step.IDLE;
    }

    public static void requestClaim(String reason) {
        CommissionLog.log("claim requested: " + reason);
        requested = true;
    }

    public static void onChat(String plain) {
        String finished = CommissionParser.completedFromChat(plain);
        if (finished != null && (AetherConfig.COMMISSION_AUTO_CLAIM.get() || CommissionMacro.isRunning())) {
            requestClaim("chat says " + finished + " is complete");
        }
    }

    public static List<Commission> currentCommissions(Minecraft client) {
        return CommissionParser.parseTab(TablistUtils.getTabLines(client));
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gameMode == null) return;
        ticks++;

        if (restoreSlotNextTick >= 0) {
            FailsafeManager.selectHotbarSlot(client, restoreSlotNextTick);
            restoreSlotNextTick = -1;
        }

        if (step == Step.IDLE && AetherConfig.COMMISSION_AUTO_CLAIM.get() && ticks % TAB_CHECK_TICKS == 0) {
            for (Commission c : currentCommissions(client)) {
                if (c.done() && System.currentTimeMillis() - lastAttemptAt > RETRY_AFTER_MS) {
                    requestClaim("tab list shows " + c.name() + " as DONE");
                    break;
                }
            }
        }

        switch (step) {
            case IDLE -> {
                if (requested && client.gui.screen() == null) openMenu(client);
            }
            case OPENING -> tickOpening(client);
            case CLAIMING -> tickClaiming(client);
            case CLOSING -> finish(client, "closed");
        }
    }

    private static void openMenu(Minecraft client) {
        requested = false;
        lastAttemptAt = System.currentTimeMillis();
        int pigeon = GearManager.findHotbarItemSlot(client, PIGEON);
        if (pigeon < 0) {
            failedClaims++;
            CommissionLog.log("no Royal Pigeon in the hotbar, cannot claim");
            ClientUtils.sendMessage("§cNo Royal Pigeon in your hotbar to claim commissions with.", false);
            return;
        }
        previousSlot = FailsafeManager.getCurrentSelectedSlot(client);
        if (previousSlot != pigeon) FailsafeManager.selectHotbarSlot(client, pigeon);
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        // swap straight back like a player flicking the pigeon, the menu opens a moment later either way
        if (previousSlot != pigeon) restoreSlotNextTick = previousSlot;
        CommissionLog.log("used Royal Pigeon in slot " + pigeon + " (was holding slot " + previousSlot + ")");
        setStep(Step.OPENING);
    }

    private static void tickOpening(Minecraft client) {
        if (client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getTitle().getString().equals(MENU_TITLE)) {
            CommissionLog.log("Commissions menu open after " + (System.currentTimeMillis() - stepStartedAt) + "ms");
            lastClickAt = 0L;
            setStep(Step.CLAIMING);
            return;
        }
        if (System.currentTimeMillis() - stepStartedAt > MENU_TIMEOUT_MS) {
            CommissionLog.log("Commissions menu did not open within " + MENU_TIMEOUT_MS + "ms, screen is "
                    + describeScreen(client));
            failedClaims++;
            finish(client, "menu timeout");
        }
    }

    private static void tickClaiming(Minecraft client) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || !screen.getTitle().getString().equals(MENU_TITLE)) {
            CommissionLog.log("Commissions menu closed early, screen is " + describeScreen(client));
            finish(client, "menu closed");
            return;
        }
        long now = System.currentTimeMillis();
        // the menu refreshes after each claim; give it a moment so a claimed slot isn't clicked twice
        if (now - lastClickAt < CLICK_SETTLE_MS) return;

        List<CommissionParser.MenuSlot> slots = readMenu(screen);
        List<CommissionParser.MenuSlot> ready = new ArrayList<>(CommissionParser.claimable(slots));
        for (CommissionParser.MenuSlot s : List.copyOf(ready)) {
            int clicks = clicksPerSlot.getOrDefault(s.slot(), 0);
            if (clicks == 0) continue;
            if (clicks >= MAX_CLICKS_PER_SLOT) {
                ready.remove(s);
                continue;
            }
            CommissionLog.log("slot " + s.slot() + " (" + commissionTitle(s) + ") still claimable after a click, trying again");
        }
        if (ready.isEmpty()) {
            int unconfirmed = (int) CommissionParser.claimable(slots).size();
            if (unconfirmed > 0) {
                failedClaims++;
                CommissionLog.log(unconfirmed + " commission(s) wouldn't claim after " + MAX_CLICKS_PER_SLOT + " clicks each");
            } else if (claimedThisRun > 0) {
                failedClaims = 0;
            }
            CommissionLog.log("nothing left to claim (" + claimedThisRun + " claimed), closing the menu");
            client.player.closeContainer();
            setStep(Step.CLOSING);
            return;
        }
        CommissionParser.MenuSlot slot = ready.getFirst();
        CommissionLog.log("claiming " + slot.name() + " (" + commissionTitle(slot) + ") in slot " + slot.slot());
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot.slot(), 1, ContainerInput.PICKUP,
                client.player);
        clicksPerSlot.merge(slot.slot(), 1, Integer::sum);
        if (clicksPerSlot.get(slot.slot()) == 1) claimedThisRun++;
        lastClickAt = now;
    }

    private static void finish(Minecraft client, String reason) {
        CommissionLog.log("claim finished (" + reason + "), claimed " + claimedThisRun);
        claimedThisRun = 0;
        clicksPerSlot.clear();
        setStep(Step.IDLE);
    }

    // claim runs in a row that ended with something still unclaimed, so the macro can stop instead of looping
    public static int failedClaims() {
        return failedClaims;
    }

    public static void resetFailures() {
        failedClaims = 0;
    }

    private static List<CommissionParser.MenuSlot> readMenu(AbstractContainerScreen<?> screen) {
        List<CommissionParser.MenuSlot> slots = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            List<String> lore = new ArrayList<>();
            ItemLore itemLore = stack.get(DataComponents.LORE);
            if (itemLore != null) {
                for (Component line : itemLore.lines()) lore.add(line.getString());
            }
            slots.add(new CommissionParser.MenuSlot(slot.index, stack.getHoverName().getString(), lore));
        }
        return slots;
    }

    // the commission's own name is the first lore line after the king's description
    private static String commissionTitle(CommissionParser.MenuSlot slot) {
        List<String> lore = slot.lore();
        for (int i = 0; i + 1 < lore.size(); i++) {
            if (!lore.get(i).strip().equals("rewards.")) continue;
            // a blank spacer line usually sits between the description and the name
            for (int j = i + 1; j < lore.size(); j++) {
                if (!lore.get(j).isBlank()) return lore.get(j).strip();
            }
        }
        return "?";
    }

    private static String describeScreen(Minecraft client) {
        var screen = client.gui.screen();
        return screen == null ? "none" : screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\"";
    }

    private static void setStep(Step next) {
        step = next;
        stepStartedAt = System.currentTimeMillis();
    }
}
