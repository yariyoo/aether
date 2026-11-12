package dev.aether.modules.dwarven.fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// picks what to fight with from what's in the hotbar, by what the item's own lore says it is and can do
public final class Weapons {
    public enum Kind { HYPERION, TERMINATOR, MELEE, SHORTBOW, TOOL }

    public record Item(int slot, String name, List<String> lore) {}

    public record Choice(int slot, Kind kind, String name, int manaCost, String reason) {}

    // which tiers may be used; auto off means the mining tool, as before
    public record Prefs(boolean auto, boolean hyperion, boolean terminator, boolean other, boolean tool) {}

    // the type line at the bottom of hypixel lore, e.g. "LEGENDARY DUNGEON SWORD"; recombobulated items wrap it in junk letters
    private static final Pattern TYPE = Pattern.compile(
            "(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|ULTIMATE|ADMIN)( DUNGEON)? ([A-Z ]+)$");
    private static final Pattern DAMAGE = Pattern.compile("Damage: \\+([\\d,]+)");
    private static final Pattern MANA_COST = Pattern.compile("Mana Cost: ([\\d,]+)");
    private static final int DEFAULT_WITHER_IMPACT_COST = 300;

    private Weapons() {
    }

    public static boolean hasWitherImpact(List<String> lore) {
        for (String l : lore) if (l.contains("Wither Impact")) return true;
        return false;
    }

    // the mana cost listed under wither impact, or the base cost when the lore doesn't list one
    public static int witherImpactCost(List<String> lore) {
        boolean inAbility = false;
        for (String l : lore) {
            if (l.contains("Wither Impact")) inAbility = true;
            else if (inAbility && l.startsWith("Ability:")) break;
            if (!inAbility) continue;
            Matcher m = MANA_COST.matcher(l);
            if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        }
        return DEFAULT_WITHER_IMPACT_COST;
    }

    public static boolean isShortbow(List<String> lore) {
        for (String l : lore) if (l.contains("Shortbow: Instantly shoots")) return true;
        return false;
    }

    public static String typeLine(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String l = lore.get(i).replaceAll("[^A-Z ]", "").strip();
            if (l.isEmpty()) continue;
            Matcher m = TYPE.matcher(l);
            return m.find() ? m.group(3).strip() : null;
        }
        return null;
    }

    public static int damage(List<String> lore) {
        for (String l : lore) {
            Matcher m = DAMAGE.matcher(l);
            if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        }
        return 0;
    }

    // mana is -1 when unknown; an ability that can't be afforded now drops to the next tier and comes back once mana does.
    // disabled holds kinds found not to work this session (no damage landing), with the reason kept for the hud
    public static Choice choose(List<Item> hotbar, int toolSlot, String toolName, int mana, Prefs prefs, Set<Kind> disabled) {
        List<String> why = new ArrayList<>();
        if (prefs.auto()) {
            if (prefs.hyperion() && !disabled.contains(Kind.HYPERION)) {
                Item hype = find(hotbar, "hyperion");
                if (hype == null) why.add("no Hyperion");
                else if (!hasWitherImpact(hype.lore())) why.add("Hyperion has no Wither Impact");
                else {
                    int cost = witherImpactCost(hype.lore());
                    if (mana >= 0 && mana < cost) why.add("not enough mana for Wither Impact (" + mana + "/" + cost + ")");
                    else return new Choice(hype.slot(), Kind.HYPERION, hype.name(), cost, "Hyperion with Wither Impact");
                }
            } else if (disabled.contains(Kind.HYPERION)) {
                why.add("Wither Impact wasn't landing damage");
            }
            if (prefs.terminator() && !disabled.contains(Kind.TERMINATOR)) {
                Item term = find(hotbar, "terminator");
                if (term == null) why.add("no Terminator");
                else return new Choice(term.slot(), Kind.TERMINATOR, term.name(), 0, join("Terminator", why));
            } else if (disabled.contains(Kind.TERMINATOR)) {
                why.add("Terminator shots weren't landing");
            }
            if (prefs.other()) {
                Item best = null;
                Kind bestKind = null;
                int bestDamage = -1;
                for (Item it : hotbar) {
                    if (it.slot() == toolSlot) continue;
                    String type = typeLine(it.lore());
                    if (type == null) continue;
                    Kind k = null;
                    if (type.equals("SWORD") || type.equals("LONGSWORD")) k = Kind.MELEE;
                    else if (type.equals("BOW") && isShortbow(it.lore())) k = Kind.SHORTBOW;
                    // a normal bow has to be drawn and released, which this fighter doesn't do
                    if (k == null || disabled.contains(k)) continue;
                    int dmg = damage(it.lore());
                    if (dmg > bestDamage) {
                        bestDamage = dmg;
                        best = it;
                        bestKind = k;
                    }
                }
                if (best != null) return new Choice(best.slot(), bestKind, best.name(), 0, join("best weapon by damage (" + bestDamage + ")", why));
                why.add("no other sword or shortbow");
            }
        }
        if ((prefs.tool() || !prefs.auto()) && toolSlot >= 0 && !disabled.contains(Kind.TOOL)) {
            return new Choice(toolSlot, Kind.TOOL, toolName, 0, prefs.auto() ? join("mining tool", why) : "mining tool (automatic weapons off)");
        }
        if (toolSlot < 0) why.add("no mining tool");
        else if (disabled.contains(Kind.TOOL)) why.add("the mining tool wasn't doing damage");
        return new Choice(-1, null, null, 0, "no usable combat equipment: " + String.join(", ", why));
    }

    private static Item find(List<Item> hotbar, String word) {
        for (Item it : hotbar) if (it.name().toLowerCase(Locale.ROOT).contains(word)) return it;
        return null;
    }

    private static String join(String what, List<String> why) {
        return why.isEmpty() ? what : what + " (" + String.join(", ", why) + ")";
    }
}
