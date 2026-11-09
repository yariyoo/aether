package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.config.entries.BooleanEntry;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class BlockMinerRegistryProvider extends AbstractMiningRegistryProvider {
    public BlockMinerRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup blocks = SettingGroup.alwaysOn("Blocks", "Which blocks the miner breaks")
                .add(toggle("Gray Mithril", AetherConfig.MINER_GRAY_MITHRIL))
                .add(toggle("Green Mithril", AetherConfig.MINER_GREEN_MITHRIL))
                .add(toggle("Blue Mithril", AetherConfig.MINER_BLUE_MITHRIL))
                .add(toggle("Titanium", AetherConfig.MINER_TITANIUM))
                .add(toggle("Prefer Titanium", AetherConfig.MINER_PREFER_TITANIUM));

        SettingGroup behaviour = SettingGroup.alwaysOn("Behaviour", "How the miner handles stuck blocks")
                .add(new InfoSetting("Trigger",
                        () -> "Run /aether miner to start or stop. It mines blocks in reach without moving.")
                        .multiline())
                .add(new SliderSetting("Block Timeout", 2, 30,
                        () -> AetherConfig.MINER_BLOCK_TIMEOUT_SECONDS.get(), value -> {
                            AetherConfig.MINER_BLOCK_TIMEOUT_SECONDS.set(Math.round(value * 2) / 2.0f);
                            AetherConfig.save();
                        }).withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.subTab(
                "Block Miner",
                "Mines mithril and titanium in reach",
                List.of(blocks, behaviour));
    }

    private static ToggleSetting toggle(String name, BooleanEntry entry) {
        return new ToggleSetting(name, entry::get, value -> {
            entry.set(value);
            AetherConfig.save();
        });
    }
}
