package dev.aether.modules.commission;

import dev.aether.modules.mining.MiningArea;
import dev.aether.modules.mining.MiningBlockKind;
import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// what a commission asks for, and where it has to be done
public record CommissionTask(String commission, Kind kind, Set<MiningBlockKind> ores, MiningArea area) {
    public enum Kind { MINING, COMBAT, EVENT, IGNORED, UNKNOWN }

    private static final int AREA_MARGIN = 4;

    // boxes are where a working macro stood while each area's commission went up, so they are known to count
    public static final List<MiningArea> AREAS = List.of(
            MiningArea.around("Upper Mines", -111, 167, -75, -109, 167, -73, new BlockPos(-110, 167, -74), AREA_MARGIN),
            MiningArea.around("Royal Mines", 144, 150, 22, 178, 152, 30, new BlockPos(177, 152, 24), AREA_MARGIN),
            MiningArea.around("Lava Springs", 39, 198, -25, 55, 202, -9, new BlockPos(42, 198, -13), AREA_MARGIN),
            MiningArea.around("Cliffside Veins", 7, 128, 38, 17, 129, 39, new BlockPos(8, 129, 38), AREA_MARGIN),
            MiningArea.around("Rampart's Quarry", -86, 154, -18, -84, 154, -15, new BlockPos(-85, 154, -16), AREA_MARGIN));

    private static final Set<MiningBlockKind> MITHRIL = EnumSet.of(
            MiningBlockKind.GRAY_MITHRIL, MiningBlockKind.GREEN_MITHRIL, MiningBlockKind.BLUE_MITHRIL);
    // titanium only spawns where mithril is mined, so a titanium commission mines mithril while none is in reach
    private static final Set<MiningBlockKind> TITANIUM = EnumSet.of(MiningBlockKind.TITANIUM,
            MiningBlockKind.GRAY_MITHRIL, MiningBlockKind.GREEN_MITHRIL, MiningBlockKind.BLUE_MITHRIL);

    public boolean isMining() {
        return kind == Kind.MINING;
    }

    public static CommissionTask of(String name) {
        String lower = name.toLowerCase(Locale.ROOT).strip();
        // powder collector only progresses during 2x powder, raffle is its own project for later
        if (lower.endsWith("mithril powder collector") || lower.equals("raffle")) {
            return new CommissionTask(name, Kind.IGNORED, Set.of(), null);
        }
        if (lower.equals("mithril miner")) {
            return new CommissionTask(name, Kind.MINING, MITHRIL, null);
        }
        if (lower.equals("titanium miner")) {
            return new CommissionTask(name, Kind.MINING, TITANIUM, null);
        }
        for (MiningArea area : AREAS) {
            String areaName = area.name().toLowerCase(Locale.ROOT);
            if (lower.equals(areaName + " mithril")) return new CommissionTask(name, Kind.MINING, MITHRIL, area);
            if (lower.equals(areaName + " titanium")) return new CommissionTask(name, Kind.MINING, TITANIUM, area);
        }
        if (lower.contains("raid")) return new CommissionTask(name, Kind.EVENT, Set.of(), null);
        if (lower.contains("slayer") || lower.contains("puncher")) return new CommissionTask(name, Kind.COMBAT, Set.of(), null);
        return new CommissionTask(name, Kind.UNKNOWN, Set.of(), null);
    }
}
