package dev.aether.modules.dwarven.claim;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// adds up what claimed commissions paid, read from the "Rewards" lines on the commission item
public final class Rewards {
    private static final Pattern LINE = Pattern.compile("^-\\s*\\+?([\\d,.]+)([kKmM]?)\\s+(.+)$");

    public record Tally(double mithrilPowder, double miningExp, double hotmExp) {
        public Tally plus(Tally o) {
            return new Tally(mithrilPowder + o.mithrilPowder, miningExp + o.miningExp, hotmExp + o.hotmExp);
        }
    }

    public static final Tally NONE = new Tally(0, 0, 0);

    private Rewards() {}

    public static Tally parse(List<String> lore) {
        double powder = 0, mining = 0, hotm = 0;
        boolean inRewards = false;
        for (String raw : lore) {
            String line = raw.strip();
            if (line.equalsIgnoreCase("Rewards")) {
                inRewards = true;
                continue;
            }
            if (!inRewards) continue;
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                if (line.isEmpty()) break;
                continue;
            }
            double amount;
            try {
                amount = Double.parseDouble(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("k")) amount *= 1_000;
            if (unit.equals("m")) amount *= 1_000_000;
            String what = m.group(3).toLowerCase(Locale.ROOT);
            if (what.contains("mithril powder")) powder += amount;
            else if (what.contains("hotm") || what.contains("heart of the mountain")) hotm += amount;
            else if (what.contains("mining exp")) mining += amount;
        }
        return new Tally(powder, mining, hotm);
    }
}
