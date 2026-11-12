package dev.aether.modules.dwarven.camera;

// tuning for one camera move; speeds in degrees per second, accelerations in degrees per second squared, jerk in
// degrees per second cubed (0 leaves acceleration free to change at once). variation is how much each move's speed,
// acceleration and bend may differ from the last, 0 to 1
public record CameraProfile(Mode mode, Easing easing, float minSpeed, float maxSpeed, float acceleration,
                            float deceleration, float smoothing, float curve, float tolerance, boolean preventOvershoot,
                            float jerk, float variation) {
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
        jerk = Math.max(0f, jerk);
        variation = Math.max(0f, Math.min(1f, variation));
    }

    public CameraProfile(Mode mode, Easing easing, float minSpeed, float maxSpeed, float acceleration, float deceleration, float smoothing,
                         float curve, float tolerance, boolean preventOvershoot) {
        this(mode, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, curve, tolerance, preventOvershoot, 0f, 0f);
    }

    public static CameraProfile standard() {
        return new CameraProfile(Mode.ADAPTIVE, Easing.SMOOTHERSTEP, 25f, 420f, 1800f, 1500f, 0.35f, 0.25f, 0.6f, true);
    }

    public CameraProfile withTolerance(float degrees) {
        return new CameraProfile(mode, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, curve, degrees, preventOvershoot, jerk,
                variation);
    }

    public CameraProfile withCurve(float amount) {
        return new CameraProfile(mode, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, amount, tolerance, preventOvershoot, jerk,
                variation);
    }

    // the same feel, turning faster or slower; accelerations scale with it so the motion keeps its shape
    public CameraProfile scaled(float speed) {
        float k = Math.max(0.2f, speed);
        return new CameraProfile(mode, easing, minSpeed * k, maxSpeed * k, acceleration * k, deceleration * k, smoothing, curve, tolerance,
                preventOvershoot, jerk * k, variation);
    }

    public CameraProfile withMode(Mode m) {
        return new CameraProfile(m, easing, minSpeed, maxSpeed, acceleration, deceleration, smoothing, curve, tolerance, preventOvershoot, jerk,
                variation);
    }
}
