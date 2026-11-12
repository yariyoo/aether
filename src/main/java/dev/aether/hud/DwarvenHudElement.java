package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.modules.dwarven.DwarvenCommissionMacro;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import net.minecraft.core.BlockPos;

public class DwarvenHudElement extends HudElement {
    public static final float W = 230f;
    private static final float ROW_H = 18f;
    private static final float LABEL_SZ = 10f;

    @Override public float   getX()           { return AetherConfig.DWARVEN_HUD_X.get(); }
    @Override public float   getY()           { return AetherConfig.DWARVEN_HUD_Y.get(); }
    @Override public void    setX(float x)    { AetherConfig.DWARVEN_HUD_X.set((int) x); }
    @Override public void    setY(float y)    { AetherConfig.DWARVEN_HUD_Y.set((int) y); }
    @Override public float   getScale()       { return AetherConfig.DWARVEN_HUD_SCALE.get(); }
    @Override public void    setScale(float s){ AetherConfig.DWARVEN_HUD_SCALE.set(s); }
    @Override public float   getWidth()       { return W; }
    @Override public float   getHeight()      { return HudStyle.CONTENT_Y + rows() * ROW_H + 8f + HudStyle.PAD; }
    @Override public boolean isEnabled()      { return AetherConfig.SHOW_DWARVEN_HUD.get(); }
    @Override public boolean isVisible()      { return isEnabled() && DwarvenCommissionMacro.isRunning(); }
    @Override public String  getName()        { return "Dwarven Commissions HUD"; }
    @Override public void    savePosition()   { AetherConfig.save(); }

    private static int rows() {
        return AetherConfig.DWARVEN_HUD_DEBUG.get() ? 10 : 7;
    }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        long sessionMs = DwarvenCommissionMacro.sessionMs();
        int done = DwarvenCommissionMacro.completed();
        double progress = DwarvenCommissionMacro.hudProgress();
        int mined = DwarvenCommissionMacro.minedThisSession();

        HudStyle.panel(nvg, W, getHeight());
        HudStyle.header(nvg, W, "Dwarven Commissions", "Aether");
        float y = HudStyle.CONTENT_Y;
        y = row(nvg, y, "State", DwarvenCommissionMacro.hudState(), Theme.HUD_ACCENT);
        y = row(nvg, y, "Commission", DwarvenCommissionMacro.hudCommission(), Theme.HUD_VALUE);
        y = row(nvg, y, "Progress", progress < 0 ? "-" : String.format("%.0f%%", progress * 100), Theme.HUD_VALUE);
        y = row(nvg, y, "Zone", DwarvenCommissionMacro.hudZone(), Theme.HUD_VALUE);
        y = row(nvg, y, "Session", time(sessionMs), Theme.HUD_VALUE);
        // the hourly rate swings wildly in the first minutes, so hold it back until it means something
        String rate = sessionMs < 180_000L ? "-" : String.format("%.1f/h", done / (sessionMs / 3_600_000.0));
        y = row(nvg, y, "Completed", done + "  (" + rate + ")", Theme.HUD_SUCCESS);
        y = row(nvg, y, "Blocks mined", mined + "  (" + DwarvenCommissionMacro.titaniumThisSession() + " titanium)", Theme.HUD_VALUE);
        if (AetherConfig.DWARVEN_HUD_DEBUG.get()) {
            BlockPos target = DwarvenCommissionMacro.hudTarget();
            y = row(nvg, y, "Target", target == null ? "-" : target.toShortString(), Theme.HUD_VALUE);
            y = row(nvg, y, "Break time", DwarvenCommissionMacro.usualBreakMs() + "ms", Theme.HUD_VALUE);
            y = row(nvg, y, "Recoveries", String.valueOf(DwarvenCommissionMacro.recoveries()), Theme.HUD_VALUE);
        }
        HudStyle.progress(nvg, HudStyle.PAD, y + 3f, W - HudStyle.PAD * 2, 4f, (float) Math.max(0, progress));
    }

    private float row(NVGRenderer nvg, float y, String label, String value, int color) {
        HudStyle.row(nvg, HudStyle.PAD, y, W - HudStyle.PAD * 2, label, value, LABEL_SZ, color);
        return y + ROW_H;
    }

    private static String time(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%02d:%02d", m, s % 60);
    }
}
