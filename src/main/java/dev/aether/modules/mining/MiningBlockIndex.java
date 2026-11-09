package dev.aether.modules.mining;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// every mithril and titanium block in the loaded world, swept a few chunks per tick, nearest chunks first
final class MiningBlockIndex {
    private static final long TICK_BUDGET_NS = 3_000_000L;
    private static final long MIN_SWEEP_INTERVAL_MS = 2000L;
    private static final Predicate<BlockState> IS_ORE = state -> MiningBlockKind.fromBlock(state.getBlock()) != null;
    private static final MiningBlockKind[] KINDS = MiningBlockKind.values();

    private record Section(long[] positions, byte[] kinds) {}

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private ClientLevel level;
    private List<long[]> sweepChunks = List.of();
    private int sweepCursor;
    private long sweepStartedAt;
    private long lastSweepFinishedAt;
    private int sweepsDone;
    private int sectionsScanned;
    private int sectionsSkipped;
    private long sweepNanos;

    void reset() {
        sections.clear();
        level = null;
        sweepChunks = List.of();
        sweepCursor = 0;
        sweepsDone = 0;
        lastSweepFinishedAt = 0L;
    }

    boolean hasFinishedASweep() {
        return sweepsDone > 0;
    }

    String progress() {
        return sweepChunks.isEmpty() ? "idle" : sweepCursor + "/" + sweepChunks.size() + " chunks";
    }

    int blockCount() {
        int total = 0;
        for (Section section : sections.values()) total += section.positions().length;
        return total;
    }

    void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        if (client.level != level) {
            reset();
            level = client.level;
        }
        if (sweepCursor >= sweepChunks.size()) {
            if (System.currentTimeMillis() - lastSweepFinishedAt < MIN_SWEEP_INTERVAL_MS) return;
            beginSweep(client);
        }

        long started = System.nanoTime();
        while (sweepCursor < sweepChunks.size() && System.nanoTime() - started < TICK_BUDGET_NS) {
            long[] chunk = sweepChunks.get(sweepCursor++);
            scanChunk((int) chunk[0], (int) chunk[1]);
        }
        sweepNanos += System.nanoTime() - started;

        if (sweepCursor >= sweepChunks.size()) {
            sweepsDone++;
            lastSweepFinishedAt = System.currentTimeMillis();
            MinerLog.log(String.format("index sweep %d: %d chunks in %dms (%.0fms of tick time), %d sections scanned, %d skipped, %d ore blocks",
                    sweepsDone, sweepChunks.size(), lastSweepFinishedAt - sweepStartedAt, sweepNanos / 1e6,
                    sectionsScanned, sectionsSkipped, blockCount()));
        }
    }

    // nearest first, already filtered by type and skip list; positions may be stale until checked against the world
    List<BlockPos> nearest(BlockPos from, Set<MiningBlockKind> allowed, Set<BlockPos> skip, int limit) {
        List<BlockPos> found = new ArrayList<>();
        for (Section section : sections.values()) {
            long[] positions = section.positions();
            byte[] kinds = section.kinds();
            for (int i = 0; i < positions.length; i++) {
                if (!allowed.contains(KINDS[kinds[i]])) continue;
                BlockPos pos = BlockPos.of(positions[i]);
                if (!skip.contains(pos)) found.add(pos);
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private void beginSweep(Minecraft client) {
        int radius = client.options.getEffectiveRenderDistance() + 1;
        int centerX = SectionPos.blockToSectionCoord(client.player.getBlockX());
        int centerZ = SectionPos.blockToSectionCoord(client.player.getBlockZ());
        List<long[]> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
            }
        }
        chunks.sort(Comparator.comparingLong(c -> c[2]));
        sweepChunks = chunks;
        sweepCursor = 0;
        sweepStartedAt = System.currentTimeMillis();
        sweepNanos = 0L;
        sectionsScanned = 0;
        sectionsSkipped = 0;
    }

    private void scanChunk(int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        int minSectionY = level.getMinSectionY();
        int sectionCount = level.getSectionsCount();
        if (chunk == null) {
            for (int i = 0; i < sectionCount; i++) sections.remove(SectionPos.asLong(chunkX, minSectionY + i, chunkZ));
            return;
        }
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int i = 0; i < chunkSections.length; i++) {
            int sectionY = minSectionY + i;
            long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            LevelChunkSection section = chunkSections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(IS_ORE)) {
                sections.remove(key);
                sectionsSkipped++;
                continue;
            }
            sectionsScanned++;
            Section scanned = scanSection(section, chunkX << 4, sectionY << 4, chunkZ << 4);
            if (scanned == null) {
                sections.remove(key);
            } else {
                sections.put(key, scanned);
            }
        }
    }

    private static Section scanSection(LevelChunkSection section, int baseX, int baseY, int baseZ) {
        long[] positions = new long[64];
        byte[] kinds = new byte[64];
        int count = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    MiningBlockKind kind = MiningBlockKind.fromBlock(section.getBlockState(x, y, z).getBlock());
                    if (kind == null) continue;
                    if (count == positions.length) {
                        positions = Arrays.copyOf(positions, count * 2);
                        kinds = Arrays.copyOf(kinds, count * 2);
                    }
                    positions[count] = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                    kinds[count] = (byte) kind.ordinal();
                    count++;
                }
            }
        }
        if (count == 0) return null;
        return new Section(Arrays.copyOf(positions, count), Arrays.copyOf(kinds, count));
    }
}
