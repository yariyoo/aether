package dev.aether.modules.dwarven.claim;

import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.camera.DwarvenCamera;
import dev.aether.modules.dwarven.commission.CommissionReader;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// claims finished commissions through the royal pigeon or an emissary: open the menu, click what reads as completed, check it took
public final class ClaimManager {
    public enum Status { IDLE, RUNNING, CLAIMED, NOTHING_TO_CLAIM, NO_PIGEON, FAILED }

    private enum Step { HOLD, USE, AIM_NPC, WAIT_MENU, CLICK, CLOSE }

    private static final String PIGEON = "Royal Pigeon";
    private static final long MENU_WAIT_MS = 3500L;
    private static final long CLICK_GAP_MS = 450L;
    private static final long COOLDOWN_WAIT_MS = 5000L;
    private static final int MAX_CLICKS_PER_SLOT = 2;
    private static final int MAX_OPENS = 3;
    private static final long NPC_AIM_LIMIT_MS = 3000L;

    private Status status = Status.IDLE;
    private Step step;
    private long stepSince;
    private int opens;
    private int claimed;
    private int restoreSlot = -1;
    private long holdUntil;
    private final Map<Integer, Integer> clicks = new HashMap<>();
    private Entity npc;
    private Rewards.Tally earned = Rewards.NONE;
    private final Map<Integer, Rewards.Tally> pending = new HashMap<>();

    public static boolean hasPigeon(Minecraft client) {
        return GearManager.findHotbarItemSlot(client, PIGEON) >= 0;
    }

    public Status status() {
        return status;
    }

    public int claimed() {
        return claimed;
    }

    // what this claim run paid out, counted only for slots that really stopped reading as claimable
    public Rewards.Tally earned() {
        return earned;
    }

    public boolean running() {
        return status == Status.RUNNING;
    }

    public void start(Minecraft client) {
        claimed = 0;
        opens = 0;
        clicks.clear();
        pending.clear();
        earned = Rewards.NONE;
        if (GearManager.findHotbarItemSlot(client, PIGEON) < 0) {
            status = Status.NO_PIGEON;
            return;
        }
        status = Status.RUNNING;
        npc = null;
        restoreSlot = FailsafeManager.getCurrentSelectedSlot(client);
        enter(Step.HOLD);
    }

    // claim by right-clicking an emissary standing within reach
    public void startEmissary(Minecraft client, Entity emissary) {
        claimed = 0;
        opens = 0;
        clicks.clear();
        pending.clear();
        earned = Rewards.NONE;
        npc = emissary;
        restoreSlot = -1;
        status = Status.RUNNING;
        enter(Step.AIM_NPC);
    }

    public void reset() {
        status = Status.IDLE;
    }

    public void onChat(String message) {
        if (status == Status.RUNNING && message.toLowerCase().contains("on cooldown")) {
            // the pigeon has a short cooldown; wait it out instead of counting a failed open
            holdUntil = System.currentTimeMillis() + COOLDOWN_WAIT_MS;
            enter(Step.HOLD);
        }
    }

    public void tick(Minecraft client) {
        if (status != Status.RUNNING || client.player == null) return;
        long now = System.currentTimeMillis();
        switch (step) {
            case HOLD -> {
                if (now < holdUntil) return;
                int slot = GearManager.findHotbarItemSlot(client, PIGEON);
                if (slot < 0) {
                    finish(client, Status.NO_PIGEON);
                } else if (FailsafeManager.getCurrentSelectedSlot(client) != slot) {
                    FailsafeManager.selectHotbarSlot(client, slot);
                } else if (now - stepSince > 150L) {
                    enter(Step.USE);
                }
            }
            case USE -> {
                if (++opens > MAX_OPENS) {
                    DwarvenLog.log("claim: the commission menu never opened after " + MAX_OPENS + " tries");
                    finish(client, Status.FAILED);
                    return;
                }
                ClientUtils.performUseClickInstant();
                enter(Step.WAIT_MENU);
            }
            case AIM_NPC -> {
                if (npc == null || !npc.isAlive()) {
                    finish(client, Status.FAILED);
                    return;
                }
                if (now - stepSince > NPC_AIM_LIMIT_MS || client.player.distanceTo(npc) > 4.5) {
                    DwarvenLog.log("claim: couldn't line up on the emissary");
                    finish(client, Status.FAILED);
                    return;
                }
                RotationManager.steer(client, npc.getBoundingBox().getCenter(), DwarvenCamera.profile(DwarvenCamera.Activity.NAVIGATION));
                if (client.hitResult instanceof EntityHitResult hit && hit.getEntity() == npc) {
                    if (++opens > MAX_OPENS) {
                        finish(client, Status.FAILED);
                        return;
                    }
                    ClientUtils.performUseClickInstant();
                    enter(Step.WAIT_MENU);
                }
            }
            case WAIT_MENU -> {
                if (menu(client) != null) {
                    enter(Step.CLICK);
                } else if (now - stepSince > MENU_WAIT_MS) {
                    enter(npc != null ? Step.AIM_NPC : Step.HOLD);
                }
            }
            case CLICK -> click(client, now);
            case CLOSE -> {
                if (now - stepSince < CLICK_GAP_MS) return;
                if (client.gui.screen() != null) client.player.closeContainer();
                finish(client, claimed > 0 ? Status.CLAIMED : Status.NOTHING_TO_CLAIM);
            }
        }
    }

    private void click(Minecraft client, long now) {
        AbstractContainerScreen<?> screen = menu(client);
        if (screen == null) {
            // closed under us; whatever we already claimed still counts
            if (claimed > 0) finish(client, Status.CLAIMED);
            else enter(npc != null ? Step.AIM_NPC : Step.HOLD);
            return;
        }
        if (now - stepSince < CLICK_GAP_MS) return;
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = slots.size() - 36;
        boolean stubborn = false;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty() || !CommissionReader.isClaimable(text(stack))) continue;
            int tries = clicks.getOrDefault(i, 0);
            if (tries >= MAX_CLICKS_PER_SLOT) {
                stubborn = true;
                continue;
            }
            // a slot is only counted as claimed once it stops reading as claimable on a later pass
            clicks.put(i, tries + 1);
            if (tries == 0) pending.put(i, Rewards.parse(text(stack)));
            DwarvenLog.log("claim: clicking " + stack.getHoverName().getString() + " in slot " + i + (tries > 0 ? " again" : ""));
            ClientUtils.performSlotClick(screen, i, 0, ContainerInput.PICKUP);
            stepSince = now;
            return;
        }
        claimed = 0;
        earned = Rewards.NONE;
        for (Map.Entry<Integer, Integer> e : clicks.entrySet()) {
            ItemStack stack = slots.get(e.getKey()).getItem();
            if (stack.isEmpty() || !CommissionReader.isClaimable(text(stack))) {
                claimed++;
                earned = earned.plus(pending.getOrDefault(e.getKey(), Rewards.NONE));
            }
        }
        if (stubborn) DwarvenLog.log("claim: a commission still reads as claimable after " + MAX_CLICKS_PER_SLOT + " clicks");
        enter(Step.CLOSE);
    }

    private static AbstractContainerScreen<?> menu(Minecraft client) {
        if (client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getTitle().getString().toLowerCase().contains("commissions")) return screen;
        return null;
    }

    private static List<String> text(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) lines.add(line.getString());
        return lines;
    }

    private void finish(Minecraft client, Status result) {
        if (restoreSlot >= 0 && result != Status.NO_PIGEON) FailsafeManager.selectHotbarSlot(client, restoreSlot);
        status = result;
    }

    private void enter(Step next) {
        step = next;
        stepSince = System.currentTimeMillis();
    }
}
