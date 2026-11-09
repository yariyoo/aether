package dev.aether.modules.mining;

import dev.aether.util.ClientUtils;
import net.minecraft.core.BlockPos;

// every miner decision goes to aether's debug log file so in-game runs can be replayed afterwards
final class MinerLog {
    private MinerLog() {}

    static void log(String message) {
        ClientUtils.sendDebugMessage("[Miner] " + message);
    }

    static String pos(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
