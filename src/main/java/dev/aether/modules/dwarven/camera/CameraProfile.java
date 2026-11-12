package dev.aether.modules.dwarven.camera;

// tuning for one camera move; speeds in degrees per second, accelerations in degrees per second squared
public record CameraProfile(Mode mode, Easing easing, float minSpeed, float maxSpeed, float acceleration,
                            float deceleration, float smoothing, float curve, float tolerance, boolean preventOvershoot) {
    public enum Mode { SMOOTH, ADAPTIVE, PRECISION }

    public enum Easing { CUBIC, SMOOTHERSTEP, LINEAR }

    public CameraProfile {
        minSpeed = Math.max(1f, minSpeed);
        maxSpeed = Math.max(minSpeed, maxSpeed);
        acceleration = Math.max(10f, acceleration);
        deceleration = Math.max(10f, deceleration);
        smoothing = Math.max(0f, Math.min(0.95f, smoothing));
        curve = Math.max(0f, Math.min(1f, curve));
        tolerance = Math.max(0.05f, tolerance);
    }

    public static CameraProfile standard() {
        return new CameraProfile(Mode.ADAPTIVE, Easing.SMOOTHERSTEP, 25f, 420f, 1800f, 1500f, 0.35f, 0.25f, 0.6f, true);
    }

    public CameraProfile withTolerance(float degrees) {
        return new CameraProfile(mode, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, curve, degrees, preventOvershoot);
    }

    public CameraProfile withCurve(float amount) {
        return new CameraProfile(mode, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, amount, tolerance, preventOvershoot);
    }
}
