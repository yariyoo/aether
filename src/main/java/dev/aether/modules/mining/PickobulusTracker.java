package dev.aether.modules.mining;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// whether pickobulus can be cast right now, from the tab widget and the ability chat lines
public final class PickobulusTracker {
    private static final Pattern TAB_LINE = Pattern.compile("pickobulus:\\s*(available|(\\d+)s)");

    private static boolean known;
    private static boolean ready;
    private static long cooldownEndsAt;
    private static long usedAt;

    private PickobulusTracker() {}

    public static boolean isReady(long now) {
        if (!known) return false;
        return ready || now >= cooldownEndsAt && cooldownEndsAt > 0;
    }

    public static long usedAt() {
        return usedAt;
    }

    public static void onChat(String message) {
        String text = message.strip().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (text.equals("pickobulus is now available!")) {
            known = true;
            ready = true;
        } else if (text.equals("you used your pickobulus pickaxe ability!")) {
            known = true;
            ready = false;
            usedAt = now;
            cooldownEndsAt = 0;
        } else if (text.startsWith("your pickaxe ability is on cooldown for")) {
            Matcher m = Pattern.compile("(\\d+)s").matcher(text);
            known = true;
            ready = false;
            cooldownEndsAt = m.find() ? now + Long.parseLong(m.group(1)) * 1000L : now + 5000L;
        }
    }

    public static void onTab(List<String> lines) {
        for (String line : lines) {
            Matcher m = TAB_LINE.matcher(line.strip().toLowerCase(Locale.ROOT));
            if (!m.find()) continue;
            known = true;
            if (m.group(2) == null) {
                ready = true;
            } else {
                ready = false;
                cooldownEndsAt = System.currentTimeMillis() + Long.parseLong(m.group(2)) * 1000L;
            }
            return;
        }
    }

    // a cast that never showed its chat line probably isn't set up as the pickaxe ability; back off a while
    public static void castFailed(long now, long backoffMs) {
        ready = false;
        cooldownEndsAt = now + backoffMs;
    }

    static void reset() {
        known = false;
        ready = false;
        cooldownEndsAt = 0;
        usedAt = 0;
    }
}
