package dev.aether.modules.dwarven.event;

import dev.aether.util.TablistUtils;

import java.util.List;
import java.util.Set;

// reads a running goblin raid off the scoreboard: the event line plus a kill or goblins-remaining counter
public final class GoblinRaid {
    public static final Set<String> MOBS = Set.of("Weakling", "Fireslinger", "Knifethrower", "Pitfighter", "Goblin");

    private GoblinRaid() {}

    public static boolean active(List<String> sidebar) {
        boolean event = false;
        boolean counting = false;
        for (String raw : sidebar) {
            String line = TablistUtils.stripColors(raw).strip();
            if (line.equalsIgnoreCase("Event: GOBLIN RAID")) event = true;
            if (line.startsWith("Remaining:") && line.contains("goblin")) counting = true;
            if (line.startsWith("Your kills:") || line.equalsIgnoreCase("Kill goblins!")) counting = true;
        }
        return event && counting;
    }

    public static boolean isRaidCommission(String name) {
        String lower = name.strip().toLowerCase();
        return lower.equals("goblin raid") || lower.equals("goblin raid slayer");
    }
}
