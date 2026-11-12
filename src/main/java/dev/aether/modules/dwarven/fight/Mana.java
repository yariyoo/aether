package dev.aether.modules.dwarven.fight;

import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// current mana as the action bar shows it ("1,234/2,000✎ Mana"); unknown when the bar hasn't said for a while
public final class Mana {
    private static final Pattern MANA = Pattern.compile("([\\d,]+)/([\\d,]+)✎");
    private static final long STALE_MS = 4000L;

    private static volatile int current = -1;
    private static volatile int max = -1;
    private static volatile long seenAt;

    private Mana() {
    }

    public static void onActionBar(Component component) {
        if (component == null) return;
        int[] parsed = parse(component.getString());
        if (parsed == null) return;
        current = parsed[0];
        max = parsed[1];
        seenAt = System.currentTimeMillis();
    }

    public static int[] parse(String text) {
        Matcher m = MANA.matcher(text);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1).replace(",", "")), Integer.parseInt(m.group(2).replace(",", ""))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -1 when unknown, so callers can tell "no mana" from "no reading"
    public static int current(long now) {
        return now - seenAt > STALE_MS ? -1 : current;
    }

    public static int max() {
        return max;
    }
}
