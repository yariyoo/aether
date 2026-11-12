package dev.aether.modules.dwarven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneLineTest {
    @Test
    void readsTheZoneAfterThe26_2FontIcon() {
        assertEquals("The Lift", DwarvenCommissionMacro.zoneFromSidebarLine("  The Lift"));
        assertEquals("Rampart's Quarry", DwarvenCommissionMacro.zoneFromSidebarLine(" Rampart's Quarry"));
    }

    @Test
    void readsTheZoneAfterTheOldIcon() {
        assertEquals("Royal Mines", DwarvenCommissionMacro.zoneFromSidebarLine("⏣ Royal Mines"));
    }

    @Test
    void ignoresOtherScoreboardLines() {
        assertEquals("", DwarvenCommissionMacro.zoneFromSidebarLine("Zone: The Forge"));
        assertEquals("", DwarvenCommissionMacro.zoneFromSidebarLine("Purse: 32,295,478"));
        assertEquals("", DwarvenCommissionMacro.zoneFromSidebarLine("11/09/26 m78DN"));
        assertEquals("", DwarvenCommissionMacro.zoneFromSidebarLine(""));
    }
}
