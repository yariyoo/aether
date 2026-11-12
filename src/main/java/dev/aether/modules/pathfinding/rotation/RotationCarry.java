package dev.aether.modules.pathfinding.rotation;

// keeps the exact angle a rotation is at between frames, while what reaches the player is rounded to whole mouse
// steps. rounding each frame's turn on its own throws away anything under half a step, so a slow turn sits still
// and then jumps; carrying the exact angle lets those fractions add up into evenly spaced steps
public final class RotationCarry {
    private float yaw;
    private float pitch;
    private boolean held;

    public void reset() {
        held = false;
    }

    // where the next step starts from: the exact angle if the player is still where the last step put them,
    // otherwise the player's own angle (something else turned the camera)
    public float startYaw(float playerYaw, double gcd) {
        return held && Math.abs(wrap(yaw - playerYaw)) <= gcd * 1.01 ? yaw : playerYaw;
    }

    public float startPitch(float playerPitch, double gcd) {
        return held && Math.abs(pitch - playerPitch) <= gcd * 1.01 ? pitch : playerPitch;
    }

    public void hold(float exactYaw, float exactPitch) {
        yaw = exactYaw;
        pitch = exactPitch;
        held = true;
    }

    // the player's next angle: the exact one rounded to a whole number of mouse steps from where they are now
    public static float snap(float exact, float current, double gcd) {
        if (gcd <= 0) return exact;
        double delta = wrap(exact - current);
        return (float) (current + Math.round(delta / gcd) * gcd);
    }

    static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
