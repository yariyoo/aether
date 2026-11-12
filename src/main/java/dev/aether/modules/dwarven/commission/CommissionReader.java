package dev.aether.modules.dwarven.commission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// reads commissions from what the game shows: the tab list section and the commission menu's item text
public final class CommissionReader {
    private static final Pattern LINE = Pattern.compile("^(.+?):\\s*(DONE|[0-9]+(?:\\.[0-9]+)?%)$");

    private CommissionReader() {}

    public static List<DwarvenCommission> fromTab(List<String> lines) {
        List<DwarvenCommission> out = new ArrayList<>();
        boolean inside = false;
        for (String raw : lines) {
            String line = raw.replace(' ', ' ').strip();
            if (!inside) {
                inside = line.equalsIgnoreCase("Commissions:") || line.equalsIgnoreCase("Commissions");
                continue;
            }
            Matcher m = LINE.matcher(line);
            // the section ends at the first line that isn't a commission
            if (!m.matches()) break;
            boolean done = m.group(2).equals("DONE");
            double progress = done ? 1.0 : Double.parseDouble(m.group(2).replace("%", "")) / 100.0;
            out.add(new DwarvenCommission(m.group(1).strip(), Math.min(1.0, progress), done));
        }
        return out;
    }

    public static boolean isClaimable(List<String> itemText) {
        for (String raw : itemText) {
            String line = raw.strip().toLowerCase(Locale.ROOT);
            if (line.equals("completed") || line.contains("click to claim")) return true;
        }
        return false;
    }

    public static String completedInChat(String message) {
        String text = message.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(" commission complete");
        if (at <= 0) return null;
        return text.substring(0, at).strip();
    }
}
