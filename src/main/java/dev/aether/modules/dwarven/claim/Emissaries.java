package dev.aether.modules.dwarven.claim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// the emissary npcs, learned from their name tags whenever one is in view and kept across sessions
public final class Emissaries {
    private final Map<String, BlockPos> known = new HashMap<>();
    private boolean dirty;

    public int size() {
        return known.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public void observe(Minecraft client) {
        if (client.level == null) return;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            String name = TablistUtils.stripColors(stand.getCustomName().getString()).strip();
            if (!name.toLowerCase(Locale.ROOT).contains("emissary")) continue;
            LivingEntity npc = npcUnder(client, stand);
            BlockPos at = (npc != null ? npc : stand).blockPosition();
            BlockPos before = known.put(name, at);
            if (!at.equals(before)) dirty = true;
        }
    }

    public Map.Entry<String, BlockPos> nearest(BlockPos from) {
        Map.Entry<String, BlockPos> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, BlockPos> e : known.entrySet()) {
            double d = e.getValue().distSqr(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = e;
            }
        }
        return best;
    }

    // the emissary standing near a remembered spot, if it is loaded
    public static LivingEntity npcNear(Minecraft client, BlockPos pos, double radius) {
        LivingEntity best = null;
        double bestDistance = radius * radius;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            String name = TablistUtils.stripColors(stand.getCustomName().getString()).toLowerCase(Locale.ROOT);
            if (!name.contains("emissary")) continue;
            LivingEntity npc = npcUnder(client, stand);
            if (npc == null) continue;
            double d = npc.blockPosition().distSqr(pos);
            if (d < bestDistance) {
                bestDistance = d;
                best = npc;
            }
        }
        return best;
    }

    private static LivingEntity npcUnder(Minecraft client, ArmorStand stand) {
        AABB below = new AABB(stand.getX() - 0.8, stand.getY() - 3.0, stand.getZ() - 0.8, stand.getX() + 0.8, stand.getY() + 0.5, stand.getZ() + 0.8);
        for (Entity e : client.level.getEntities(stand, below)) {
            if (e instanceof LivingEntity living && !(e instanceof ArmorStand) && e != client.player) return living;
        }
        return null;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        known.forEach((name, pos) -> {
            JsonArray p = new JsonArray();
            p.add(pos.getX());
            p.add(pos.getY());
            p.add(pos.getZ());
            root.add(name, p);
        });
        dirty = false;
        return root;
    }

    public void load(JsonObject root) {
        known.clear();
        for (String name : root.keySet()) {
            JsonArray p = root.getAsJsonArray(name);
            known.put(name, new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
    }
}
