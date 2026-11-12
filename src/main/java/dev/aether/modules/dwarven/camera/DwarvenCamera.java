package dev.aether.modules.dwarven.camera;

import dev.aether.config.AetherConfig;

// the camera profile the settings currently describe
public final class DwarvenCamera {
    private DwarvenCamera() {}

    public static CameraProfile profile() {
        CameraProfile.Mode mode = CameraProfile.Mode.values()[AetherConfig.DWARVEN_CAM_MODE.get()];
        CameraProfile.Easing easing = switch (AetherConfig.DWARVEN_CAM_EASING.get()) {
            case 0 -> CameraProfile.Easing.CUBIC;
            case 2 -> CameraProfile.Easing.LINEAR;
            default -> CameraProfile.Easing.SMOOTHERSTEP;
        };
        return new CameraProfile(mode, easing, AetherConfig.DWARVEN_CAM_MIN_SPEED.get(), AetherConfig.DWARVEN_CAM_MAX_SPEED.get(),
                AetherConfig.DWARVEN_CAM_ACCEL.get(), AetherConfig.DWARVEN_CAM_DECEL.get(), AetherConfig.DWARVEN_CAM_SMOOTHING.get(),
                AetherConfig.DWARVEN_CAM_CURVED.get() ? 0.25f : 0f, AetherConfig.DWARVEN_CAM_TOLERANCE.get(),
                AetherConfig.DWARVEN_CAM_NO_OVERSHOOT.get());
    }
}
