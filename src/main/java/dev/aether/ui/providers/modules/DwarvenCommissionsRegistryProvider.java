package dev.aether.ui.providers.modules;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.config.entries.BooleanEntry;
import dev.aether.config.entries.FloatEntry;
import dev.aether.config.entries.IntEntry;
import dev.aether.modules.dwarven.DwarvenCommissionMacro;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class DwarvenCommissionsRegistryProvider extends AbstractMiningRegistryProvider {
    public DwarvenCommissionsRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup general = SettingGroup.alwaysOn("Dwarven Commissions", "Runs Dwarven Mines commissions: travel, mine, claim, repeat")
                .add(new ActionSetting("Start / Stop Dwarven Commissions", () -> {
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> {
                        // the macro only starts once this menu is closed, so close it for the player
                        if (!DwarvenCommissionMacro.isRunning()) client.gui.setScreen(null);
                        DwarvenCommissionMacro.toggle(client);
                    });
                }))
                .add(new KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getDwarvenCommissionsKey()))
                .add(new InfoSetting("Status", () -> DwarvenCommissionMacro.isRunning()
                        ? "Running: " + DwarvenCommissionMacro.hudState() + ", " + DwarvenCommissionMacro.hudCommission()
                        : "Off. Needs a mining tool in the hotbar, and a Royal Pigeon or a known Emissary to claim.").multiline())
                .add(new DropdownSetting("Commission Strategy", List.of("Fastest", "Closest", "Titanium First", "Mithril First"),
                        () -> AetherConfig.DWARVEN_STRATEGY.get(), v -> save(() -> AetherConfig.DWARVEN_STRATEGY.set(v))))
                .add(slider("Stop After Commissions (0 = never)", 0, 200, AetherConfig.DWARVEN_STOP_AFTER))
                .add(slider("Session Limit Minutes (0 = none)", 0, 600, AetherConfig.DWARVEN_SESSION_MINUTES))
                .add(toggle("Pause When I Move The Camera", AetherConfig.DWARVEN_PAUSE_ON_INPUT))
                .add(toggle("Auto Start In The Dwarven Mines", AetherConfig.DWARVEN_AUTO_START))
                .add(toggle("Resume After Leaving And Coming Back", AetherConfig.DWARVEN_AUTO_RESUME));

        SettingGroup commissions = SettingGroup.alwaysOn("Commissions", "Which commissions it takes and how it chooses")
                .add(toggle("Mithril Commissions", AetherConfig.DWARVEN_MITHRIL))
                .add(toggle("Titanium Commissions", AetherConfig.DWARVEN_TITANIUM))
                .add(toggle("Area Mining Commissions", AetherConfig.DWARVEN_AREA_MINING))
                .add(toggle("Combat Commissions", AetherConfig.DWARVEN_COMBAT))
                .add(toggle("Join A Goblin Raid Running Nearby", AetherConfig.DWARVEN_EVENTS))
                .add(new DropdownSetting("Claim Method", List.of("Pigeon, Else Emissary", "Royal Pigeon Only", "Emissary Only"),
                        () -> AetherConfig.DWARVEN_CLAIM_METHOD.get(), v -> save(() -> AetherConfig.DWARVEN_CLAIM_METHOD.set(v))))
                .add(toggle("Favour Commissions That Overlap", AetherConfig.DWARVEN_OVERLAP))
                .add(slider("Switch Only If This Much Faster", 0f, 0.9f, AetherConfig.DWARVEN_SWITCH_GAIN, 2).asPercentage())
                .add(slider("Progress Check Interval Ms", 250, 5000, AetherConfig.DWARVEN_OBSERVE_MS))
                .add(slider("Rest A Failing Commission Minutes", 1, 30, AetherConfig.DWARVEN_REST_MINUTES));

        SettingGroup discovery = SettingGroup.alwaysOn("Vein Discovery", "How it finds and remembers ore")
                .add(slider("Local Discovery Radius", 8, 48, AetherConfig.DWARVEN_LOCAL_RADIUS))
                .add(slider("Extended Discovery Radius", 16, 128, AetherConfig.DWARVEN_EXTENDED_RADIUS))
                .add(slider("Chunks Scanned Per Tick", 1, 40, AetherConfig.DWARVEN_SCAN_BUDGET))
                .add(slider("Smallest Vein Worth Walking To", 1, 20, AetherConfig.DWARVEN_MIN_VEIN))
                .add(toggle("Remember Productive Spots", AetherConfig.DWARVEN_VEIN_MEMORY))
                .add(toggle("Explore Other Spots When Dry", AetherConfig.DWARVEN_EXPLORE))
                .add(slider("Recheck Empty Spots After Seconds", 5, 300, AetherConfig.DWARVEN_RECHECK_SECONDS))
                .add(slider("Maximum Exploration Distance", 32, 400, AetherConfig.DWARVEN_MAX_EXPLORE));

        SettingGroup mining = SettingGroup.alwaysOn("Mining", "How it works a vein")
                .add(new TextSetting("Mining Tool Name", "blank = any drill, pickaxe or gauntlet",
                        AetherConfig.DWARVEN_TOOL_NAME::get, v -> save(() -> AetherConfig.DWARVEN_TOOL_NAME.set(v))))
                .add(toggle("Titanium Priority", AetherConfig.DWARVEN_TITANIUM_PRIORITY))
                .add(toggle("Mine Mithril For Titanium", AetherConfig.DWARVEN_MITHRIL_FOR_TITANIUM))
                .add(slider("Mining Timeout Seconds", 1f, 15f, AetherConfig.DWARVEN_BLOCK_TIMEOUT, 1))
                .add(toggle("Adaptive Mining Timeout", AetherConfig.DWARVEN_ADAPTIVE_TIMEOUT))
                .add(toggle("Automatic Repositioning", AetherConfig.DWARVEN_REPOSITION))
                .add(toggle("Sneak While Mining", AetherConfig.DWARVEN_SNEAK))
                .add(toggle("Use Pickaxe Ability", AetherConfig.DWARVEN_PICKAXE_ABILITY));

        SettingGroup combat = SettingGroup.alwaysOn("Combat", "Slayer and puncher commissions, fought with the mining tool")
                .add(new DropdownSetting("Combat Mode", List.of("Adaptive", "Fast Hit-And-Move", "Confirmed Kill"),
                        () -> AetherConfig.DWARVEN_COMBAT_MODE.get(), v -> save(() -> AetherConfig.DWARVEN_COMBAT_MODE.set(v))))
                .add(slider("Target Revisit Delay Ms", 200, 10000, AetherConfig.DWARVEN_REVISIT_MS))
                .add(slider("Attack Confirmation Timeout Ms", 250, 10000, AetherConfig.DWARVEN_CONFIRM_MS))
                .add(slider("Minimum Target Switch Interval Ms", 0, 2000, AetherConfig.DWARVEN_SWITCH_MS))
                .add(slider("Maximum Pending Attacks", 1, 10, AetherConfig.DWARVEN_MAX_PENDING))
                .add(toggle("Learn How Many Hits A Kill Takes", AetherConfig.DWARVEN_COMBAT_LEARNING))
                .add(toggle("Wait For Respawns Instead Of Giving Up", AetherConfig.DWARVEN_RESPAWN_SEARCH))
                .add(slider("Mob Scan Interval Ms", 50, 2000, AetherConfig.DWARVEN_MOB_SCAN_MS))
                .add(toggle("Remember Spawn Locations", AetherConfig.DWARVEN_SPAWN_MEMORY))
                .add(slider("Maximum Empty Location Seconds", 5, 600, AetherConfig.DWARVEN_MAX_EMPTY_SECONDS))
                .add(toggle("Move Between Spawns While Waiting", AetherConfig.DWARVEN_REPOSITION_SPAWNS));

        SettingGroup navigation = SettingGroup.alwaysOn("Navigation", "How it gets between areas")
                .add(toggle("Warp To Forge When Faster", AetherConfig.DWARVEN_FORGE_WARP))
                .add(toggle("Use Etherwarp When Available", AetherConfig.DWARVEN_USE_ETHERWARP))
                .add(toggle("Learn And Reuse Walked Routes", AetherConfig.DWARVEN_LEARN_TRAILS))
                .add(slider("Stuck Timeout Seconds", 2, 30, AetherConfig.DWARVEN_STUCK_SECONDS))
                .add(slider("Navigation Retries", 1, 10, AetherConfig.DWARVEN_NAV_RETRIES))
                .add(slider("Longest Single Walk Blocks", 8, 48, AetherConfig.DWARVEN_LEG_LENGTH))
                .add(slider("Failed Teleport Blacklist Seconds", 10, 3600, AetherConfig.DWARVEN_ROUTE_BLACKLIST_SECONDS))
                .add(slider("Forge Warp Confirmation Timeout Ms", 1000, 20000, AetherConfig.DWARVEN_WARP_TIMEOUT_MS));

        SettingGroup camera = SettingGroup.alwaysOn("Camera", "Smooth camera movement while mining")
                .add(new DropdownSetting("Rotation Mode", List.of("Smooth", "Adaptive", "Precision"),
                        () -> AetherConfig.DWARVEN_CAM_MODE.get(), v -> save(() -> AetherConfig.DWARVEN_CAM_MODE.set(v))))
                .add(new DropdownSetting("Easing Function", List.of("Cubic", "Smootherstep", "Linear"),
                        () -> AetherConfig.DWARVEN_CAM_EASING.get(), v -> save(() -> AetherConfig.DWARVEN_CAM_EASING.set(v))))
                .add(slider("Minimum Rotation Speed", 5f, 200f, AetherConfig.DWARVEN_CAM_MIN_SPEED, 0))
                .add(slider("Maximum Rotation Speed", 60f, 1200f, AetherConfig.DWARVEN_CAM_MAX_SPEED, 0))
                .add(slider("Angular Acceleration", 200f, 8000f, AetherConfig.DWARVEN_CAM_ACCEL, 0))
                .add(slider("Angular Deceleration", 200f, 8000f, AetherConfig.DWARVEN_CAM_DECEL, 0))
                .add(slider("Rotation Smoothing Strength", 0f, 0.9f, AetherConfig.DWARVEN_CAM_SMOOTHING, 2))
                .add(toggle("Curved Rotation", AetherConfig.DWARVEN_CAM_CURVED))
                .add(slider("Rotation Completion Tolerance", 0.1f, 3f, AetherConfig.DWARVEN_CAM_TOLERANCE, 1))
                .add(toggle("Overshoot Prevention", AetherConfig.DWARVEN_CAM_NO_OVERSHOOT));

        SettingGroup recovery = SettingGroup.alwaysOn("Recovery And HUD", "What it does when something goes wrong, and what it shows")
                .add(slider("Maximum Recovery Attempts", 1, 10, AetherConfig.DWARVEN_MAX_RECOVERY))
                .add(slider("Failed Target Blacklist Seconds", 5, 600, AetherConfig.DWARVEN_BLACKLIST_SECONDS))
                .add(toggle("Stop When A Commission Area Is Unknown", AetherConfig.DWARVEN_STOP_UNKNOWN_AREA))
                .add(toggle("Show Dwarven HUD", AetherConfig.SHOW_DWARVEN_HUD))
                .add(toggle("HUD Rate, Titanium And Powder Rows", AetherConfig.DWARVEN_HUD_STATS))
                .add(toggle("HUD Debug Rows", AetherConfig.DWARVEN_HUD_DEBUG));

        return MainGUIRegistry.subTab("Dwarven Commissions", "Dwarven Mines commission macro",
                List.of(general, commissions, discovery, mining, combat, navigation, camera, recovery));
    }

    private static void save(Runnable change) {
        change.run();
        AetherConfig.save();
    }

    private static Setting toggle(String name, BooleanEntry entry) {
        return new ToggleSetting(name, entry::get, v -> save(() -> entry.set(v)));
    }

    private static SliderSetting slider(String name, int min, int max, IntEntry entry) {
        return new SliderSetting(name, min, max, () -> (float) entry.get(), v -> save(() -> entry.set(Math.round(v)))).withDecimals(0);
    }

    private static SliderSetting slider(String name, float min, float max, FloatEntry entry, int decimals) {
        return new SliderSetting(name, min, max, entry::get, v -> save(() -> entry.set(v))).withDecimals(decimals);
    }
}
