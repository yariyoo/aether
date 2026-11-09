package dev.aether.modules.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// tells real veins from decorative wool and prismarine in buildings by what the blocks are embedded in
public final class MiningVeinFilter {
    private static final int MAX_BLOCKS_CHECKED = 48;
    private static final double MIN_NATURAL_SHARE = 0.75;

    public record Surroundings(int natural, int other, Map<String, Integer> otherBlocks) {
        public boolean looksNatural() {
            int total = natural + other;
            return total > 0 && natural >= total * MIN_NATURAL_SHARE;
        }

        public String describe() {
            String others = otherBlocks.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(6)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            return natural + " natural, " + other + " other" + (others.isEmpty() ? "" : " (" + others + ")");
        }
    }

    private MiningVeinFilter() {}

    public static Surroundings inspect(MiningView view, List<BlockPos> vein) {
        Set<BlockPos> members = new HashSet<>(vein);
        int natural = 0;
        int other = 0;
        Map<String, Integer> otherBlocks = new TreeMap<>();
        for (int i = 0; i < Math.min(MAX_BLOCKS_CHECKED, vein.size()); i++) {
            BlockPos pos = vein.get(i);
            for (Direction dir : Direction.values()) {
                BlockPos side = pos.relative(dir);
                if (members.contains(side) || view.isOpen(side)) continue;
                if (view.isNaturalHost(side)) {
                    natural++;
                } else {
                    other++;
                    otherBlocks.merge(view.nameAt(side), 1, Integer::sum);
                }
            }
        }
        return new Surroundings(natural, other, otherBlocks);
    }
}
