package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.fight.ImpactPlanner;
import dev.aether.modules.dwarven.fight.ImpactPlanner.Target;
import dev.aether.modules.dwarven.fight.Mana;
import dev.aether.modules.dwarven.fight.Weapons;
import dev.aether.modules.dwarven.fight.Weapons.Kind;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// weapon priority by item id, where a wither impact would land and who it would hit, and hypixel's ability refusals
class GoblinCombatTests {
    private static final Weapons.Prefs ALL = new Weapons.Prefs(true, true, true, true, true);
    private static final List<String> IMPACT_LORE = List.of("Damage: +260", "", "Ability: Wither Impact  RIGHT CLICK", "Mana Cost: 150", "",
            "LEGENDARY DUNGEON SWORD");

    private static Weapons.Item hyperion(int slot) {
        return new Weapons.Item(slot, "HYPERION", "Heroic Hyperion ✪✪✪✪✪", IMPACT_LORE);
    }

    private static Weapons.Item terminator(int slot) {
        return new Weapons.Item(slot, "TERMINATOR", "Precise Terminator", List.of("Shortbow: Instantly shoots!", "", "LEGENDARY DUNGEON BOW"));
    }

    private static Weapons.Item drill(int slot) {
        return new Weapons.Item(slot, "DIVAN_DRILL", "Divan's Drill", List.of("Mining Speed: +1,500", "", "MYTHIC DRILL"));
    }

    private static Weapons.Item sword(int slot) {
        return new Weapons.Item(slot, "ASPECT_OF_THE_DRAGON", "Aspect of the Dragons", List.of("Damage: +225", "", "LEGENDARY SWORD"));
    }

    private static Weapons.Choice pick(List<Weapons.Item> bar, int toolSlot) {
        return Weapons.choose(bar, toolSlot, toolSlot >= 0 ? "Divan's Drill" : null, 500, ALL, Set.of());
    }

    @Test
    void hyperionTerminatorAndDrillPicksTheHyperion() {
        Weapons.Choice c = pick(List.of(drill(0), terminator(1), hyperion(2)), 0);
        assertEquals(Kind.HYPERION, c.kind());
        assertEquals(2, c.slot());
        assertTrue(c.impact());
    }

    @Test
    void hyperionAndDrillPicksTheHyperion() {
        assertEquals(Kind.HYPERION, pick(List.of(drill(0), hyperion(5)), 0).kind());
    }

    @Test
    void terminatorAndDrillPicksTheTerminator() {
        Weapons.Choice c = pick(List.of(drill(0), terminator(3)), 0);
        assertEquals(Kind.TERMINATOR, c.kind());
        assertEquals(3, c.slot());
    }

    @Test
    void anotherWeaponAndDrillPicksTheWeapon() {
        assertEquals(Kind.MELEE, pick(List.of(drill(0), sword(4)), 0).kind());
    }

    @Test
    void drillOnlyPicksTheDrill() {
        Weapons.Choice c = pick(List.of(drill(0)), 0);
        assertEquals(Kind.TOOL, c.kind());
        assertEquals(0, c.slot());
    }

    @Test
    void nothingUsableFailsSafelyWithAReason() {
        Weapons.Choice c = pick(List.of(new Weapons.Item(0, "", "Cobblestone", List.of())), -1);
        assertNull(c.kind());
        assertTrue(c.reason().contains("no usable combat equipment"));
    }

    @Test
    void itemIdsDecideOverDisplayNames() {
        // a renamed hyperion is still found, and a sword merely named like one isn't taken for it
        Weapons.Item renamed = new Weapons.Item(6, "HYPERION", "My Favourite Blade", IMPACT_LORE);
        Weapons.Item fake = new Weapons.Item(1, "ASPECT_OF_THE_END", "Hyperion Wannabe", List.of("Damage: +100", "", "RARE SWORD"));
        Weapons.Choice c = pick(List.of(drill(0), fake, renamed), 0);
        assertEquals(6, c.slot());
        assertEquals(Kind.HYPERION, c.kind());
        // a scrolled valkyrie counts for wither impact; a terminator renamed is still a terminator
        Weapons.Item valk = new Weapons.Item(2, "VALKYRIE", "Valkyrie", IMPACT_LORE);
        assertEquals(Kind.HYPERION, pick(List.of(drill(0), valk), 0).kind());
        Weapons.Item term = new Weapons.Item(3, "TERMINATOR", "Bow", List.of("Shortbow: Instantly shoots!", "", "LEGENDARY BOW"));
        assertEquals(Kind.TERMINATOR, pick(List.of(drill(0), term), 0).kind());
    }

    @Test
    void theDrillIsNeverPreferredJustBecauseItWasInHandForMining() {
        // tool slot 0 was the last thing held; priority still goes to the weapons
        assertEquals(Kind.TERMINATOR, pick(List.of(drill(0), terminator(8)), 0).kind());
    }

    private static FakeOreWorld arena() {
        return new FakeOreWorld().floor(-20, -20, 20, 20, 99);
    }

    private static final Vec3 FEET = new Vec3(0.5, 100, 0.5);
    private static final Vec3 EYE = FEET.add(0, 1.62, 0);

    @Test
    void aGoblinRightBesideUsIsHitWithAnInPlaceCast() {
        ImpactPlanner.Plan p = ImpactPlanner.best(arena(), FEET, EYE, 0, List.of(new Target(1, new Vec3(3, 100, 0.5))), 6, true);
        assertNotNull(p);
        assertFalse(p.moves());
        assertEquals(90.0f, p.pitch());
        assertEquals(List.of(1), p.hits());
    }

    @Test
    void aClusterOutOfReachOnFootIsReachedWithTheTeleport() {
        List<Target> pack = List.of(new Target(1, new Vec3(12, 100, 0.5)), new Target(2, new Vec3(13, 100, 2)), new Target(3, new Vec3(12, 100, -1)),
                new Target(4, new Vec3(-4, 100, 0.5)));
        ImpactPlanner.Plan p = ImpactPlanner.best(arena(), FEET, EYE, 0, pack, 6, true);
        assertNotNull(p);
        assertTrue(p.moves());
        assertEquals(3, p.hits().size());
        assertFalse(p.hits().contains(4));
        assertTrue(p.landing().distanceTo(FEET) > 5 && p.landing().distanceTo(FEET) <= ImpactPlanner.RANGE + 0.01);
        // without the cluster preference, the cheap in-place cast on the single nearby goblin wins
        ImpactPlanner.Plan near = ImpactPlanner.best(arena(), FEET, EYE, 0, pack, 6, false);
        assertFalse(near.moves());
        assertEquals(List.of(4), near.hits());
    }

    @Test
    void aGoblinBeyondTeleportAndBlastRangeGetsNoCast() {
        assertNull(ImpactPlanner.best(arena(), FEET, EYE, 0, List.of(new Target(1, new Vec3(19, 100, 0.5))), 6, true));
    }

    @Test
    void aWallInTheWayStopsTheTeleportShortSoTheGoblinBehindItIsntCountedAsHit() {
        FakeOreWorld w = arena();
        for (int y = 100; y <= 104; y++) for (int z = -5; z <= 5; z++) w.stone(3, y, z);
        Vec3 land = ImpactPlanner.landing(w, EYE, new Vec3(1, 0, 0), ImpactPlanner.RANGE, 1.62);
        assertTrue(land.x < 3.0, "landed at " + land);
        assertNull(ImpactPlanner.best(w, FEET, EYE, 0, List.of(new Target(1, new Vec3(12, 100, 0.5))), 6, true));
    }

    @Test
    void aTeleportOffALedgeWithNothingBelowIsNotPlanned() {
        FakeOreWorld w = new FakeOreWorld().floor(-3, -3, 3, 3, 99);
        assertNull(ImpactPlanner.best(w, FEET, EYE, 0, List.of(new Target(1, new Vec3(12, 100, 0.5))), 6, true));
    }

    @Test
    void hypixelsAbilityRefusalsAreRecognisedAndToldApart() {
        long before = Mana.noManaAt();
        Mana.onChat("You don't have enough mana to do this!");
        assertTrue(Mana.noManaAt() >= before && Mana.noManaAt() > 0);
        long cd = Mana.cooldownAt();
        Mana.onChat("This ability is on cooldown for 1s.");
        assertTrue(Mana.cooldownAt() >= cd && Mana.cooldownAt() > 0);
        long nm = Mana.noManaAt();
        Mana.onChat("Commission Complete! Visit the King to claim your rewards!");
        assertEquals(nm, Mana.noManaAt());
    }
}
