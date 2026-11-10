package dev.aether.modules.commission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// reads commissions out of the tab list and the king's Commissions menu
public final class CommissionParser {
    private static final Pattern TAB_LINE = Pattern.compile("^(.+?):\\s*(DONE|[\\d.]+%)$");
    private static final Pattern COMPLETE_CHAT = Pattern.compile("^([A-Z0-9' ]+) Commission Complete!");

    public record MenuSlot(int slot, String name, List<String> lore) {}

    private CommissionParser() {}

    // the tab list has a "Commissions:" header followed by one "Name: 12.4%" or "Name: DONE" line each
    public static List<Commission> parseTab(List<String> lines) {
        List<Commission> commissions = new ArrayList<>();
        boolean inSection = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.strip();
            if (line.equalsIgnoreCase("Commissions:")) {
                inSection = true;
                continue;
            }
            if (!inSection) continue;
            Matcher m = TAB_LINE.matcher(line);
            if (!m.matches()) {
                if (!commissions.isEmpty() || line.isEmpty()) break;
                continue;
            }
            String value = m.group(2);
            boolean done = value.equals("DONE");
            double progress = done ? 1.0 : Double.parseDouble(value.substring(0, value.length() - 1)) / 100.0;
            commissions.add(new Commission(m.group(1).strip(), progress, done));
        }
        return commissions;
    }

    public static List<MenuSlot> claimable(List<MenuSlot> slots) {
        List<MenuSlot> ready = new ArrayList<>();
        for (MenuSlot slot : slots) {
            if (!slot.name().startsWith("Commission #")) continue;
            for (String line : slot.lore()) {
                String text = line.strip();
                if (text.equals("COMPLETED") || text.startsWith("Click to claim")) {
                    ready.add(slot);
                    break;
                }
            }
        }
        return ready;
    }

    // "ROYAL MINES MITHRIL Commission Complete! Visit the King..." gives "royal mines mithril"
    public static String completedFromChat(String message) {
        if (message == null) return null;
        Matcher m = COMPLETE_CHAT.matcher(message.strip());
        return m.find() ? m.group(1).strip().toLowerCase(Locale.ROOT) : null;
    }
}
