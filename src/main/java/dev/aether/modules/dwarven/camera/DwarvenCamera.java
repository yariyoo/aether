package dev.aether.modules.dwarven.camera;

import dev.aether.config.AetherConfig;

// the camera profile the settings currently describe, for each kind of move
public final class DwarvenCamera {
    public enum Activity { MINING, NAVIGATION, ETHERWARP, COMBAT }

    private static final float MINING_PACE = 1.4f;

    private DwarvenCamera() {}

    // the same camera, turning about twice as fast, for when smooth ranged aiming is off
    public static CameraProfile quick() {
        CameraProfile p = profile(Activity.COMBAT);
        return new CameraProfile(p.mode(), CameraProfile.Easing.LINEAR, p.minSpeed() * 2, p.maxSpeed() * 2, p.acceleration() * 2,
                p.deceleration() * 2, p.smoothing() / 2, 0f, p.tolerance(), p.preventOvershoot(), p.jerk() * 2, p.variation());
    }

    public static CameraProfile profile() {
        CameraProfile.Mode mode = CameraProfile.Mode.values()[AetherConfig.DWARVEN_CAM_MODE.get()];
        CameraProfile.Easing easing = switch (AetherConfig.DWARVEN_CAM_EASING.get()) {
            case 0 -> CameraProfile.Easing.CUBIC;
            case 2 -> CameraProfile.Easing.LINEAR;
            default -> CameraProfile.Easing.SMOOTHERSTEP;
        };
        float variation = AetherConfig.DWARVEN_CAM_VARIATION_ON.get() ? AetherConfig.DWARVEN_CAM_VARIATION.get() : 0f;
        return new CameraProfile(mode, easing, AetherConfig.DWARVEN_CAM_MIN_SPEED.get(), AetherConfig.DWARVEN_CAM_MAX_SPEED.get(),
                AetherConfig.DWARVEN_CAM_ACCEL.get(), AetherConfig.DWARVEN_CAM_DECEL.get(), AetherConfig.DWARVEN_CAM_SMOOTHING.get(),
                AetherConfig.DWARVEN_CAM_CURVED.get() ? 0.25f : 0f, AetherConfig.DWARVEN_CAM_TOLERANCE.get(),
                AetherConfig.DWARVEN_CAM_NO_OVERSHOOT.get(), AetherConfig.DWARVEN_CAM_JERK.get(), variation);
    }

    // each activity scales the base feel by its own speed; etherwarp drops the bend and the variation and turns in
    // precision mode, since a teleport aimed a little off lands somewhere else
    public static CameraProfile profile(Activity activity) {
        CameraProfile base = profile();
        return switch (activity) {
            // block-to-block turns are short; a bend or a random change of pace on them reads as wobble, and the
            // 22:03 recording measured them at 39 deg/s against 56 for a clean run, so they get a faster base
            case MINING -> new CameraProfile(base.mode(), base.easing(), base.minSpeed(), base.maxSpeed(), base.acceleration(),
                    base.deceleration(), base.smoothing(), 0f, base.tolerance(), base.preventOvershoot(), base.jerk(),
                    Math.min(base.variation(), 0.1f)).scaled(AetherConfig.DWARVEN_CAM_MINING_SPEED.get() * MINING_PACE);
            case NAVIGATION -> base.scaled(AetherConfig.DWARVEN_CAM_NAV_SPEED.get());
            case COMBAT -> base.scaled(AetherConfig.DWARVEN_CAM_COMBAT_SPEED.get()).withMode(CameraProfile.Mode.ADAPTIVE);
            case ETHERWARP -> new CameraProfile(CameraProfile.Mode.PRECISION, base.easing(), base.minSpeed(), base.maxSpeed(), base.acceleration(),
                    base.deceleration(), base.smoothing(), 0f, Math.min(base.tolerance(), 0.5f), true, base.jerk(), 0f)
                    .scaled(AetherConfig.DWARVEN_CAM_ETHERWARP_SPEED.get());
        };
    }
}
