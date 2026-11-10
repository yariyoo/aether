package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.modules.commission.CommissionMacro;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;

public class CommissionHudElement extends HudElement {
    public static final float W = 220f;
    private static final float ROW_H = 18f;
    private static final float LABEL_SZ = 10f;
    private static final int ROWS = 7;

    @Override public float   getX()           { return AetherConfig.COMMISSION_HUD_X.get(); }
    @Override public float   getY()           { return AetherConfig.COMMISSION_HUD_Y.get(); }
    @Override public void    setX(float x)    { AetherConfig.COMMISSION_HUD_X.set((int) x); }
    @Override public void    setY(float y)    { AetherConfig.COMMISSION_HUD_Y.set((int) y); }
    @Override public float   getScale()       { return AetherConfig.COMMISSION_HUD_SCALE.get(); }
    @Override public void    setScale(float s){ AetherConfig.COMMISSION_HUD_SCALE.set(s); }
    @Override public float   getWidth()       { return W; }
    @Override public float   getHeight()      { return HudStyle.CONTENT_Y + ROWS * ROW_H + 8f + HudStyle.PAD; }
    @Override public boolean isEnabled()      { return AetherConfig.SHOW_COMMISSION_HUD.get(); }
    @Override public boolean isVisible()      { return isEnabled() && CommissionMacro.isRunningOrPending(); }
    @Override public String  getName()        { return "Commission HUD"; }
    @Override public void    savePosition()   { AetherConfig.save(); }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        long sessionMs = CommissionMacro.sessionMs();
        int done = CommissionMacro.completedThisSession();
        double hours = sessionMs / 3_600_000.0;
        double progress = CommissionMacro.hudProgress();

        HudStyle.panel(nvg, W, getHeight());
        HudStyle.header(nvg, W, "Commissions", "Aether");
        float y = HudStyle.CONTENT_Y;
        row(nvg, y, "Status", CommissionMacro.hudStatus(), Theme.HUD_ACCENT); y += ROW_H;
        row(nvg, y, "Commission", CommissionMacro.hudCommission(), Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Progress", progress < 0 ? "-" : String.format("%.0f%%", progress * 100), Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Area", CommissionMacro.hudArea(), Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Session", formatTime(sessionMs), Theme.HUD_VALUE); y += ROW_H;
        // under a few minutes the hourly rate swings wildly, so wait before showing it
        String rate = sessionMs < 180_000L ? "-" : String.format("%.1f/h", done / hours);
        row(nvg, y, "Completed", done + "  (" + rate + ")", Theme.HUD_SUCCESS); y += ROW_H;
        row(nvg, y, "Blocks mined", String.valueOf(CommissionMacro.minedThisSession()), Theme.HUD_VALUE); y += ROW_H;
        HudStyle.progress(nvg, HudStyle.PAD, y + 3f, W - HudStyle.PAD * 2, 4f, (float) Math.max(0, progress));
    }

    private void row(NVGRenderer nvg, float y, String label, String value, int color) {
        HudStyle.row(nvg, HudStyle.PAD, y, W - HudStyle.PAD * 2, label, value, LABEL_SZ, color);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%02d:%02d", m, s % 60);
    }
}
