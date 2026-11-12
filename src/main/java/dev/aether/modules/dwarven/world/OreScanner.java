package dev.aether.modules.dwarven.world;

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
import java.util.List;
import java.util.Set;

// keeps a cache of where ore is in the loaded chunks around the player, refreshed a few chunk sections per tick
public final class OreScanner {
    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private final List<long[]> queue = new ArrayList<>();
    private int queueAt;
    private int sweeps;
    private int levelId;
    private long lastSweepStartedAt;

    public int sweeps() {
        return sweeps;
    }

    public void clear() {
        sections.clear();
        queue.clear();
        queueAt = 0;
        sweeps = 0;
    }

    // scans up to budget chunk columns; one full pass over the radius is a sweep, and stale columns are simply rescanned
    public void tick(Minecraft client, int radiusBlocks, int budget) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) return;
        if (System.identityHashCode(level) != levelId) {
            clear();
            levelId = System.identityHashCode(level);
        }
        if (queueAt >= queue.size()) {
            if (!queue.isEmpty()) sweeps++;
            // the immediate surroundings are re-read directly when mining, so a full pass needn't run back to back
            if (sweeps > 0 && System.currentTimeMillis() - lastSweepStartedAt < 1500L) return;
            buildQueue(client, radiusBlocks);
            lastSweepStartedAt = System.currentTimeMillis();
        }
        for (int i = 0; i < budget && queueAt < queue.size(); i++) {
            long[] column = queue.get(queueAt++);
            scanColumn(level, (int) column[0], (int) column[1]);
        }
    }

    private void buildQueue(Minecraft client, int radiusBlocks) {
        queue.clear();
        queueAt = 0;
        int cx = SectionPos.blockToSectionCoord(client.player.getBlockX());
        int cz = SectionPos.blockToSectionCoord(client.player.getBlockZ());
        int r = Math.max(1, (radiusBlocks + 15) >> 4);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) queue.add(new long[]{cx + dx, cz + dz, (long) dx * dx + (long) dz * dz});
        queue.sort((a, b) -> Long.compare(a[2], b[2]));
        // forget columns that fell out of range so the cache can't grow without bound
        sections.keySet().removeIf(key -> Math.abs(SectionPos.x(key) - cx) > r + 1 || Math.abs(SectionPos.z(key) - cz) > r + 1);
    }

    private void scanColumn(ClientLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        int minY = level.getMinSectionY();
        if (chunk == null) {
            for (int i = 0; i < level.getSectionsCount(); i++) sections.remove(SectionPos.asLong(chunkX, minY + i, chunkZ));
            return;
        }
        LevelChunkSection[] all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
            long key = SectionPos.asLong(chunkX, minY + i, chunkZ);
            LevelChunkSection section = all[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> LiveOreWorld.kindOf(state) != null)) {
                sections.remove(key);
                continue;
            }
            List<Long> found = new ArrayList<>();
            int baseX = chunkX << 4, baseY = (minY + i) << 4, baseZ = chunkZ << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (LiveOreWorld.kindOf(state) != null) found.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
            }
            if (found.isEmpty()) {
                sections.remove(key);
            } else {
                long[] packed = new long[found.size()];
                for (int j = 0; j < packed.length; j++) packed[j] = found.get(j);
                sections.put(key, packed);
            }
        }
    }

    // cached ore within radius of a point; callers must re-check the live block before acting on any of it
    public List<BlockPos> near(BlockPos centre, int radius, Set<BlockPos> skip) {
        List<BlockPos> out = new ArrayList<>();
        int minX = SectionPos.blockToSectionCoord(centre.getX() - radius), maxX = SectionPos.blockToSectionCoord(centre.getX() + radius);
        int minY = SectionPos.blockToSectionCoord(centre.getY() - radius), maxY = SectionPos.blockToSectionCoord(centre.getY() + radius);
        int minZ = SectionPos.blockToSectionCoord(centre.getZ() - radius), maxZ = SectionPos.blockToSectionCoord(centre.getZ() + radius);
        long r2 = (long) radius * radius;
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            long[] packed = sections.get(SectionPos.asLong(x, y, z));
            if (packed == null) continue;
            for (long p : packed) {
                BlockPos pos = BlockPos.of(p);
                if (pos.distSqr(centre) <= r2 && !skip.contains(pos)) out.add(pos);
            }
        }
        return out;
    }
}
