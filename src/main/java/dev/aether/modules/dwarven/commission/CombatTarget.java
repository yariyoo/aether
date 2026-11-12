package dev.aether.modules.dwarven.commission;

import java.util.Locale;
import java.util.Set;

// the mobs a combat commission counts, by the names their tags show, and the zone they live in when there is one
public record CombatTarget(Set<String> mobs, String area) {
    public static CombatTarget of(String commissionName) {
        String lower = commissionName.strip().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "goblin slayer" -> new CombatTarget(Set.of("Goblin", "Knifethrower", "Fireslinger"), "Goblin Burrows");
            case "glacite walker slayer" -> new CombatTarget(Set.of("Glacite Walker"), "Great Ice Wall");
            case "treasure hoarder puncher" -> new CombatTarget(Set.of("Treasure Hoarder"), null);
            case "star sentry puncher" -> new CombatTarget(Set.of("Star Sentry"), null);
            default -> null;
        };
    }
}
