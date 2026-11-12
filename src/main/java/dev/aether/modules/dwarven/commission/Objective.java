package dev.aether.modules.dwarven.commission;

import java.util.Locale;

// what a commission asks for, worked out from its name so new areas need no code change
public record Objective(Kind kind, Ore ore, String area) {
    public enum Kind { MINING, COMBAT, EVENT, UNKNOWN }

    public enum Ore { MITHRIL, TITANIUM }

    private static final String[] EVENT_WORDS = {"raffle", "goblin raid", "powder"};
    private static final String[] COMBAT_WORDS = {"slayer", "puncher"};

    public static Objective of(String commissionName) {
        String name = commissionName.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : EVENT_WORDS) {
            if (lower.contains(word)) return new Objective(Kind.EVENT, null, null);
        }
        CombatTarget combat = CombatTarget.of(name);
        if (combat != null) return new Objective(Kind.COMBAT, null, combat.area());
        for (String word : COMBAT_WORDS) {
            if (lower.contains(word)) return new Objective(Kind.UNKNOWN, null, null);
        }
        if (lower.equals("mithril miner")) return new Objective(Kind.MINING, Ore.MITHRIL, null);
        if (lower.equals("titanium miner")) return new Objective(Kind.MINING, Ore.TITANIUM, null);
        if (lower.endsWith(" mithril")) return new Objective(Kind.MINING, Ore.MITHRIL, name.substring(0, name.length() - " mithril".length()));
        if (lower.endsWith(" titanium")) return new Objective(Kind.MINING, Ore.TITANIUM, name.substring(0, name.length() - " titanium".length()));
        return new Objective(Kind.UNKNOWN, null, null);
    }

    public boolean anywhere() {
        return kind == Kind.MINING && area == null;
    }

    // true when mining for this objective at its spot also moves the other one along
    public boolean alsoAdvances(Objective other) {
        if (kind != Kind.MINING || other.kind != Kind.MINING) return false;
        boolean placeFits = other.area == null || other.area.equalsIgnoreCase(area);
        // titanium work breaks mithril on the way, mithril work only finds titanium by luck
        boolean oreFits = other.ore == ore || ore == Ore.TITANIUM;
        return placeFits && oreFits;
    }
}
