package dev.aether.modules.mining;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// the live spot memory: shipped seed spots plus what this player's runs learned, kept in config/aether
final class MiningSpotStore {
    private static final String SEED = "/assets/aether/routes/dwarven_spots.json";
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("mining_spots.json");

    private static MiningSpotMemory memory;

    private MiningSpotStore() {}

    static synchronized MiningSpotMemory get() {
        if (memory != null) return memory;
        memory = new MiningSpotMemory();
        try (InputStream in = MiningSpotStore.class.getResourceAsStream(SEED)) {
            if (in != null) memory.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            MinerLog.log("could not read the seed mining spots: " + e);
        }
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                memory.load(reader);
            } catch (Exception e) {
                MinerLog.log("could not read saved mining spots: " + e);
            }
        }
        MinerLog.log("spot memory has " + memory.size() + " spots");
        return memory;
    }

    static synchronized void save() {
        if (memory == null || !memory.isDirty()) return;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().create().toJson(memory.toJson()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MinerLog.log("could not save mining spots: " + e);
        }
    }
}
