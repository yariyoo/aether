package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class CommissionRegistryProvider extends AbstractMiningRegistryProvider {
    public CommissionRegistryProvider() {
        super(5);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup claiming = SettingGroup.alwaysOn("Claiming", "Claim finished commissions with the Royal Pigeon")
                .add(new ToggleSetting("Auto Claim", AetherConfig.COMMISSION_AUTO_CLAIM::get, value -> {
                    AetherConfig.COMMISSION_AUTO_CLAIM.set(value);
                    AetherConfig.save();
                }))
                .add(new InfoSetting("Commands",
                        () -> "/aether commissions lists your commissions. /aether commissions claim claims finished ones now.")
                        .multiline());

        return MainGUIRegistry.subTab(
                "Commissions",
                "Dwarven Mines commissions",
                List.of(claiming));
    }
}
