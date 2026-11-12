package dev.aether.modules.dwarven.commission;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

// picks the commission that should finish soonest, counting travel, remaining work and overlap with others
public final class CommissionScheduler {
    public enum Strategy { FASTEST, CLOSEST, TITANIUM_FIRST, MITHRIL_FIRST }

    public record Choice(DwarvenCommission commission, Objective objective, double seconds, List<DwarvenCommission> alsoAdvanced) {}

    public record Rules(Strategy strategy, boolean mithril, boolean titanium, boolean areaMining, boolean overlap, double switchGain,
                        boolean combat, boolean events) {}

    private static final double OVERLAP_CREDIT = 0.35;

    private CommissionScheduler() {}

    // travelSeconds and workSeconds give the cost of an objective from where the player stands now
    public static Choice choose(List<DwarvenCommission> commissions, Rules rules, String currentName,
                                ToDoubleFunction<Objective> travelSeconds, ToDoubleFunction<DwarvenCommission> workSeconds) {
        Choice best = null;
        Choice current = null;
        for (DwarvenCommission c : commissions) {
            if (c.done()) continue;
            Objective o = Objective.of(c.name());
            if (!allowed(o, rules)) continue;
            double travel = travelSeconds.applyAsDouble(o);
            double work = workSeconds.applyAsDouble(c) * (1.0 - c.progress());
            List<DwarvenCommission> shared = new ArrayList<>();
            double credit = 0;
            if (rules.overlap()) {
                for (DwarvenCommission other : commissions) {
                    if (other == c || other.done()) continue;
                    Objective oo = Objective.of(other.name());
                    if (allowed(oo, rules) && o.alsoAdvances(oo)) {
                        shared.add(other);
                        credit += OVERLAP_CREDIT;
                    }
                }
            }
            double seconds = switch (rules.strategy()) {
                case CLOSEST -> travel;
                case TITANIUM_FIRST -> (o.ore() == Objective.Ore.TITANIUM ? 0 : 10_000) + travel + work;
                case MITHRIL_FIRST -> (o.ore() == Objective.Ore.MITHRIL ? 0 : 10_000) + travel + work;
                case FASTEST -> (travel + work) / (1.0 + credit);
            };
            Choice choice = new Choice(c, o, seconds, shared);
            if (best == null || seconds < best.seconds()) best = choice;
            if (c.name().equals(currentName)) current = choice;
        }
        // stay on the current commission unless another is clearly quicker, so small estimate changes don't cause flip-flopping
        if (current != null && best != current && best.seconds() > current.seconds() * (1.0 - rules.switchGain())) return current;
        return best;
    }

    public static boolean allowed(Objective o, Rules rules) {
        if (o.kind() == Objective.Kind.COMBAT) return rules.combat();
        if (o.kind() == Objective.Kind.EVENT) return rules.events();
        if (o.kind() != Objective.Kind.MINING) return false;
        if (o.ore() == Objective.Ore.MITHRIL && !rules.mithril()) return false;
        if (o.ore() == Objective.Ore.TITANIUM && !rules.titanium()) return false;
        return o.anywhere() || rules.areaMining();
    }
}
