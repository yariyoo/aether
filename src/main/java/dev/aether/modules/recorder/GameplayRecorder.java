package dev.aether.modules.recorder;

import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// records everything the player does and sees, tick by tick, so macros can be modelled on real play
public final class GameplayRecorder {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("recordings");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int FLUSH_EVERY_TICKS = 20;
    private static final int TAB_CHECK_EVERY_TICKS = 10;
    private static final int MAX_LORE_LINES = 12;
    private static final double NAMETAG_RADIUS = 3.0;

    private static BufferedWriter writer;
    private static Path file;
    private static long startedAt;
    private static int ticks;

    private static Object lastLevel;
    private static String lastLocation;
    private static String lastScreen;
    private static String lastContainerDump;
    private static int lastSlot = -1;
    private static String lastHeldItem;
    private static BlockPos lastHitBlock;
    private static String lastHitBlockName;
    private static long lastHitStartedAt;
    private static String lastOverlay;
    private static List<String> lastTab = List.of();
    private static List<String> lastSidebar = List.of();

    private GameplayRecorder() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GameplayRecorder::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop(false));
    }

    public static boolean isRecording() {
        return writer != null;
    }

    public static void toggle() {
        if (isRecording()) {
            stop(true);
        } else {
            start();
        }
    }

    private static void start() {
        Minecraft client = Minecraft.getInstance();
        try {
            Files.createDirectories(DIR);
            file = DIR.resolve("rec_" + LocalDateTime.now().format(FILE_TIME) + ".log");
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            writer = null;
            ClientUtils.sendMessage("§cCould not start recording: " + e.getMessage(), false);
            return;
        }
        startedAt = System.currentTimeMillis();
        ticks = 0;
        lastLevel = null;
        lastLocation = null;
        lastScreen = null;
        lastContainerDump = null;
        lastSlot = -1;
        lastHeldItem = null;
        lastHitBlock = null;
        lastOverlay = null;
        lastTab = List.of();
        lastSidebar = List.of();
        line("# aether gameplay recording " + LocalDateTime.now());
        line("# T <ms> <x> <y> <z> <yaw> <pitch> <keys> <slot> <crosshair>  keys: W A S D J=jump N=sneak R=sprint L=attack U=use");
        line("# E <ms> <event> <details>");
        if (client.options != null) {
            event("settings", "renderDistance=" + client.options.getEffectiveRenderDistance()
                    + " fov=" + client.options.fov().get());
        }
        ClientUtils.sendMessage("§aRecording gameplay to " + file.getFileName() + ". Run /aether record again to stop.", false);
    }

    private static void stop(boolean announce) {
        if (writer == null) return;
        event("stop", "ticks=" + ticks);
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
        if (announce) ClientUtils.sendMessage("§eRecording saved: " + file.getFileName(), false);
    }

    private static void tick(Minecraft client) {
        if (writer == null || client.player == null || client.level == null) return;
        ticks++;

        if (client.level != lastLevel) {
            lastLevel = client.level;
            event("world", "dimension=" + client.level.dimension().identifier());
            lastTab = List.of();
            lastSidebar = List.of();
        }

        String location = String.valueOf(ClientUtils.getCurrentLocation());
        if (!location.equals(lastLocation)) {
            lastLocation = location;
            event("location", location);
        }

        recordTick(client);
        recordHeldItem(client);
        recordHitBlock(client);
        recordScreen(client);
        if (ticks % TAB_CHECK_EVERY_TICKS == 0) {
            recordTabAndSidebar(client);
        }
        if (ticks % FLUSH_EVERY_TICKS == 0) flush();
    }

    private static void recordTick(Minecraft client) {
        var player = client.player;
        var o = client.options;
        StringBuilder keys = new StringBuilder();
        if (o.keyUp.isDown()) keys.append('W');
        if (o.keyLeft.isDown()) keys.append('A');
        if (o.keyDown.isDown()) keys.append('S');
        if (o.keyRight.isDown()) keys.append('D');
        if (o.keyJump.isDown()) keys.append('J');
        if (o.keyShift.isDown()) keys.append('N');
        if (o.keySprint.isDown() || player.isSprinting()) keys.append('R');
        if (o.keyAttack.isDown()) keys.append('L');
        if (o.keyUse.isDown()) keys.append('U');
        if (keys.isEmpty()) keys.append('-');
        line(String.format(Locale.ROOT, "T %d %.3f %.3f %.3f %.2f %.2f %s %d %s",
                elapsed(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), keys,
                FailsafeManager.getCurrentSelectedSlot(client), crosshair(client)));
    }

    private static String crosshair(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            return "b:" + pos(pos) + ":" + blockName(client, pos) + ":" + blockHit.getDirection().getName();
        }
        if (hit instanceof EntityHitResult entityHit) {
            return "e:" + entityLabel(entityHit.getEntity());
        }
        return "-";
    }

    private static void recordHeldItem(Minecraft client) {
        int slot = FailsafeManager.getCurrentSelectedSlot(client);
        String held = client.player.getMainHandItem().getHoverName().getString();
        if (slot != lastSlot || !held.equals(lastHeldItem)) {
            lastSlot = slot;
            lastHeldItem = held;
            event("held", "slot=" + slot + " item=" + held);
        }
    }

    // hypixel breaks blocks server side, so watch the last hit block for its state change rather than trusting destroyBlock
    private static void recordHitBlock(Minecraft client) {
        if (lastHitBlock == null) return;
        String now = blockName(client, lastHitBlock);
        if (!now.equals(lastHitBlockName)) {
            event("blockchange", pos(lastHitBlock) + " " + lastHitBlockName + "->" + now
                    + " after " + (System.currentTimeMillis() - lastHitStartedAt) + "ms");
            lastHitBlockName = now;
            lastHitStartedAt = System.currentTimeMillis();
        }
    }

    private static void recordScreen(Minecraft client) {
        Screen screen = client.gui.screen();
        String label = screen == null ? "none" : screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\"";
        if (!label.equals(lastScreen)) {
            lastScreen = label;
            lastContainerDump = null;
            event("screen", label);
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            String dump = dumpContainer(container);
            if (!dump.equals(lastContainerDump)) {
                lastContainerDump = dump;
                event("container", "\"" + screen.getTitle().getString() + "\"\n" + dump);
            }
        }
    }

    private static String dumpContainer(AbstractContainerScreen<?> screen) {
        StringBuilder out = new StringBuilder();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            out.append("    slot ").append(slot.index).append(": ").append(stack.getCount()).append("x ")
                    .append(stack.getHoverName().getString()).append(" [")
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append("]\n");
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                List<Component> lines = lore.lines();
                for (int i = 0; i < Math.min(MAX_LORE_LINES, lines.size()); i++) {
                    String text = lines.get(i).getString();
                    if (!text.isBlank()) out.append("      | ").append(text).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static void recordTabAndSidebar(Minecraft client) {
        List<String> tab = TablistUtils.getTabLines(client);
        if (!tab.equals(lastTab)) {
            logDiff("tab", lastTab, tab);
            lastTab = List.copyOf(tab);
        }
        List<String> sidebar = ClientUtils.getSidebarLines();
        if (!sidebar.equals(lastSidebar)) {
            event("sidebar", String.join(" | ", sidebar));
            lastSidebar = List.copyOf(sidebar);
        }
    }

    private static void logDiff(String name, List<String> before, List<String> after) {
        if (before.isEmpty()) {
            event(name, "full\n    " + String.join("\n    ", after));
            return;
        }
        Set<String> old = new HashSet<>(before);
        Set<String> current = new HashSet<>(after);
        List<String> changes = new ArrayList<>();
        for (String line : after) if (!old.contains(line)) changes.add("+ " + line);
        for (String line : before) if (!current.contains(line)) changes.add("- " + line);
        if (!changes.isEmpty()) event(name, "\n    " + String.join("\n    ", changes));
    }

    public static void onGameMessage(String plain, boolean overlay) {
        if (writer == null) return;
        if (overlay) {
            if (Objects.equals(plain, lastOverlay)) return;
            lastOverlay = plain;
            event("actionbar", plain);
        } else {
            event("chat", plain);
        }
    }

    public static void onOutgoing(String message, boolean command) {
        if (writer == null) return;
        event(command ? "command" : "say", command ? "/" + message : message);
    }

    public static void onBlockHit(Minecraft client, BlockPos pos) {
        if (writer == null || client.level == null || pos.equals(lastHitBlock)) return;
        lastHitBlock = pos.immutable();
        lastHitBlockName = blockName(client, pos);
        lastHitStartedAt = System.currentTimeMillis();
        event("hit", pos(pos) + " " + lastHitBlockName);
    }

    public static void onBlockBreak(BlockPos pos) {
        if (writer == null) return;
        Minecraft client = Minecraft.getInstance();
        event("break", pos(pos) + " " + (client.level == null ? "?" : blockName(client, pos)));
    }

    public static void onContainerInput(int containerId, int slot, int button, ContainerInput input) {
        if (writer == null) return;
        Minecraft client = Minecraft.getInstance();
        String item = "?";
        if (client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && slot >= 0 && slot < screen.getMenu().slots.size()) {
            item = screen.getMenu().slots.get(slot).getItem().getHoverName().getString();
        }
        event("click", "container=" + containerId + " slot=" + slot + " button=" + button + " type=" + input + " item=" + item);
    }

    public static void onEntityInteract(Entity entity, boolean attack) {
        if (writer == null) return;
        event(attack ? "attack" : "interact", entityLabel(entity) + " nearby names: " + nearbyNames(entity));
    }

    // hypixel npc names live on invisible armour stands stacked above the npc itself
    private static String nearbyNames(Entity entity) {
        List<String> names = new ArrayList<>();
        for (Entity other : entity.level().getEntities(entity, entity.getBoundingBox().inflate(NAMETAG_RADIUS))) {
            if (other.hasCustomName()) names.add(other.getCustomName().getString());
        }
        return names.isEmpty() ? "none" : String.join(" / ", names);
    }

    private static String entityLabel(Entity entity) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        String name = entity.hasCustomName() ? entity.getCustomName().getString() : entity.getName().getString();
        return type + ":\"" + name + "\"@" + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", entity.getX(), entity.getY(), entity.getZ());
    }

    private static String blockName(Minecraft client, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).getPath();
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static long elapsed() {
        return System.currentTimeMillis() - startedAt;
    }

    private static void event(String name, String details) {
        line("E " + elapsed() + " " + name + " " + details);
    }

    private static void line(String text) {
        if (writer == null) return;
        try {
            writer.write(text);
            writer.newLine();
        } catch (IOException e) {
            ClientUtils.sendMessage("§cRecording stopped: " + e.getMessage(), false);
            writer = null;
        }
    }

    private static void flush() {
        try {
            if (writer != null) writer.flush();
        } catch (IOException ignored) {
        }
    }
}
