package dev.aether.ui.providers.modules;

import dev.aether.modules.commission.CommissionMacro;
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
        SettingGroup macro = SettingGroup.alwaysOn("Dwarven Commissions", "Do commissions automatically")
                .add(new ToggleSetting("Run Dwarven Commissions", CommissionMacro::isRunningOrPending,
                        value -> {
                            if (value != CommissionMacro.isRunningOrPending()) CommissionMacro.toggle();
                        }))
                .add(new InfoSetting("How it works",
                        () -> "Starts when you close this menu. Picks the quickest mining commission, walks to its area, "
                                + "mines it and claims it with your Royal Pigeon. Combat and event commissions are not "
                                + "supported yet. /aether commissions start, stop or toggle work too.")
                        .multiline());

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
                List.of(macro, claiming));
    }
}
