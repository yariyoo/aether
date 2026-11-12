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
    private static volatile long noManaAt;
    private static volatile long cooldownAt;

    private Mana() {
    }

    public static void onActionBar(Component component) {
        if (component == null) return;
        String text = component.getString();
        signal(text, System.currentTimeMillis());
        int[] parsed = parse(text);
        if (parsed == null) return;
        current = parsed[0];
        max = parsed[1];
        seenAt = System.currentTimeMillis();
    }

    public static void onChat(String text) {
        if (text != null) signal(text, System.currentTimeMillis());
    }

    // hypixel's own words when an ability can't fire, so a refusal is told apart from a click that did nothing
    static void signal(String text, long now) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("not enough mana") || t.contains("don't have enough mana") || t.contains("do not have enough mana")) noManaAt = now;
        if (t.contains("ability is on cooldown") || t.contains("this ability is currently on cooldown")) cooldownAt = now;
    }

    public static long noManaAt() {
        return noManaAt;
    }

    public static long cooldownAt() {
        return cooldownAt;
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
