package dev.aether.modules.commission;

import dev.aether.modules.mining.MiningBlockKind;
import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// what a commission asks for, and where it has to be done
public record CommissionTask(String commission, Kind kind, Set<MiningBlockKind> ores, Area area) {
    public enum Kind { MINING, COMBAT, EVENT, IGNORED, UNKNOWN }

    // anchors are the spots a working macro mined each area from; the radius keeps mining inside the area
    public record Area(String name, BlockPos anchor, int radius) {}

    public static final List<Area> AREAS = List.of(
            new Area("Upper Mines", new BlockPos(-110, 167, -74), 30),
            new Area("Royal Mines", new BlockPos(177, 152, 24), 30),
            new Area("Lava Springs", new BlockPos(42, 198, -13), 30),
            new Area("Cliffside Veins", new BlockPos(8, 129, 38), 30),
            new Area("Rampart's Quarry", new BlockPos(-85, 154, -16), 30));

    private static final Set<MiningBlockKind> MITHRIL = EnumSet.of(
            MiningBlockKind.GRAY_MITHRIL, MiningBlockKind.GREEN_MITHRIL, MiningBlockKind.BLUE_MITHRIL);
    private static final Set<MiningBlockKind> TITANIUM = EnumSet.of(MiningBlockKind.TITANIUM);

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
        for (Area area : AREAS) {
            String areaName = area.name().toLowerCase(Locale.ROOT);
            if (lower.equals(areaName + " mithril")) return new CommissionTask(name, Kind.MINING, MITHRIL, area);
            if (lower.equals(areaName + " titanium")) return new CommissionTask(name, Kind.MINING, TITANIUM, area);
        }
        if (lower.contains("raid")) return new CommissionTask(name, Kind.EVENT, Set.of(), null);
        if (lower.contains("slayer") || lower.contains("puncher")) return new CommissionTask(name, Kind.COMBAT, Set.of(), null);
        return new CommissionTask(name, Kind.UNKNOWN, Set.of(), null);
    }
}
