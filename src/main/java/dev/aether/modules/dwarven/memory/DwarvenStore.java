package dev.aether.modules.dwarven.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.modules.dwarven.DwarvenLog;
import dev.aether.modules.dwarven.commission.WorkStats;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// everything the macro has learned, loaded once and written back to config/aether/dwarven as it changes
public final class DwarvenStore {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("dwarven");

    public static final SpotMemory SPOTS = new SpotMemory();
    public static final AreaMemory AREAS = new AreaMemory();
    public static final TrailMemory TRAILS = new TrailMemory();
    public static final dev.aether.modules.dwarven.claim.Emissaries EMISSARIES = new dev.aether.modules.dwarven.claim.Emissaries();
    public static final WorkStats WORK = new WorkStats(75.0);
    private static boolean loaded;
    private static boolean workDirty;

    private DwarvenStore() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o;
        if ((o = read("spots.json")) != null) SPOTS.load(o);
        if ((o = read("areas.json")) != null) AREAS.load(o);
        if ((o = read("trails.json")) != null) TRAILS.load(o);
        if ((o = read("work.json")) != null) WORK.load(o);
        if ((o = read("emissaries.json")) != null) EMISSARIES.load(o);
        seed();
        DwarvenLog.log("memory loaded: " + SPOTS.all().size() + " spots, " + TRAILS.size() + " trail points");
    }

    // zone outlines and fight spots from a recorded commission run, so a fresh install knows the map
    private static void seed() {
        try (var in = DwarvenStore.class.getResourceAsStream("/assets/aether/dwarven/seed.json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject areas = root.getAsJsonObject("areas");
            int cells = 0;
            for (String area : areas.keySet()) {
                for (var el : areas.getAsJsonArray(area)) {
                    var p = el.getAsJsonArray();
                    AREAS.observe(area, new net.minecraft.core.BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
                    cells++;
                }
            }
            if (root.has("hunts")) HuntPoints.load(root.getAsJsonObject("hunts"));
            if (root.has("trails")) {
                JsonObject trails = root.getAsJsonObject("trails");
                for (var path : trails.getAsJsonArray("paths")) {
                    java.util.List<net.minecraft.core.BlockPos> points = new java.util.ArrayList<>();
                    for (var el : path.getAsJsonArray()) {
                        var p = el.getAsJsonArray();
                        points.add(new net.minecraft.core.BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
                    }
                    TRAILS.seedPath(points);
                }
                for (var el : trails.getAsJsonArray("hops")) {
                    var h = el.getAsJsonArray();
                    TRAILS.seedHop(new TrailMemory.Hop(TrailMemory.HopKind.valueOf(h.get(6).getAsString()), h.get(0).getAsDouble(),
                            h.get(1).getAsDouble(), h.get(2).getAsDouble(), h.get(3).getAsDouble(), h.get(4).getAsDouble(), h.get(5).getAsDouble()));
                }
            }
            // where /warp forge puts you, so the first warp of a fresh install is already planned for
            AREAS.observe(dev.aether.modules.dwarven.nav.AreaNavigator.FORGE_WARP, new net.minecraft.core.BlockPos(0, 149, -69));
            DwarvenLog.log("seeded " + areas.size() + " zones (" + cells + " cells) and fight spots from the bundled run");
        } catch (Exception e) {
            DwarvenLog.log("could not read the bundled seed: " + e);
        }
    }

    public static void workChanged() {
        workDirty = true;
    }

    public static synchronized void save() {
        if (!loaded) return;
        if (SPOTS.dirty()) write("spots.json", SPOTS.toJson());
        if (AREAS.dirty()) write("areas.json", AREAS.toJson());
        if (TRAILS.dirty()) write("trails.json", TRAILS.toJson());
        if (EMISSARIES.dirty()) write("emissaries.json", EMISSARIES.toJson());
        if (workDirty) {
            write("work.json", WORK.toJson());
            workDirty = false;
        }
    }

    private static JsonObject read(String name) {
        Path file = DIR.resolve(name);
        if (!Files.exists(file)) return null;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            DwarvenLog.log("could not read " + name + ": " + e);
            return null;
        }
    }

    private static void write(String name, JsonObject json) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(name), new Gson().toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DwarvenLog.log("could not save " + name + ": " + e);
        }
    }
}
