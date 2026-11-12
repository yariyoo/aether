package dev.aether.modules.dwarven.camera;

// moves a view angle toward a target with bounded acceleration, carrying its speed from one target to the next
public final class CameraMotion {
    // motion is integrated in fixed slices so it comes out the same at any frame rate
    private static final float SLICE_SECONDS = 1f / 240f;
    private static final float SMOOTHING_REFERENCE_SECONDS = 1f / 60f;
    private static final float FULL_SPEED_ANGLE = 70f;

    private float yaw;
    private float pitch;
    private float yawSpeed;
    private float pitchSpeed;
    private float targetYaw;
    private float targetPitch;
    private float startDistance;
    private float curveSign = 1f;
    private boolean arrived = true;
    private CameraProfile profile = CameraProfile.standard();
    private float accel;
    // this move's share of the profile's speed, acceleration and bend; drawn when a move starts and drifting from
    // the last move's, so consecutive moves differ a little without ever jittering within one
    private float speedK = 1f;
    private float accelK = 1f;
    private float curveK = 1f;
    private float driftSpeed;
    private float driftAccel;
    private float driftCurve;
    private final java.util.SplittableRandom random;

    public CameraMotion() {
        this(System.nanoTime());
    }

    public CameraMotion(long seed) {
        random = new java.util.SplittableRandom(seed);
    }

    public void reset(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.yawSpeed = 0f;
        this.pitchSpeed = 0f;
        this.accel = 0f;
        this.arrived = true;
    }

    // re-aiming keeps the current speed, so a new target bends the motion instead of restarting it
    public void aim(float currentYaw, float currentPitch, float newTargetYaw, float newTargetPitch, CameraProfile profile) {
        this.yaw = currentYaw;
        this.pitch = currentPitch;
        float clamped = clampPitch(newTargetPitch);
        boolean same = !arrived && Math.abs(wrap(newTargetYaw - targetYaw)) < 0.01f && Math.abs(clamped - targetPitch) < 0.01f;
        this.profile = profile;
        this.targetYaw = newTargetYaw;
        this.targetPitch = clamped;
        if (same) return;
        this.startDistance = distance();
        // alternate the side the path bows to, from the target itself so the same move always looks the same
        this.curveSign = ((Math.round(newTargetYaw * 7f) + Math.round(clamped * 13f)) & 1) == 0 ? 1f : -1f;
        vary(profile.variation());
        this.arrived = startDistance <= profile.tolerance() && speed() < profile.minSpeed();
    }

    // each factor follows a slow random walk between moves, bounded by the variation amount
    private void vary(float amount) {
        if (amount <= 0f) {
            speedK = accelK = curveK = 1f;
            return;
        }
        driftSpeed = drift(driftSpeed);
        driftAccel = drift(driftAccel);
        driftCurve = drift(driftCurve);
        speedK = 1f + 0.2f * amount * driftSpeed;
        accelK = 1f + 0.2f * amount * driftAccel;
        curveK = 1f + 0.6f * amount * driftCurve;
        if (random.nextDouble() < 0.35 * amount) curveSign = -curveSign;
    }

    private float drift(float d) {
        float next = 0.6f * d + 0.4f * (float) (random.nextDouble() * 2.0 - 1.0) * 1.6f;
        return Math.max(-1f, Math.min(1f, next));
    }

    public float speedFactor() {
        return speedK;
    }

    public void step(float seconds) {
        if (arrived) return;
        float left = Math.max(0f, seconds);
        while (left > 1e-5f && !arrived) {
            float dt = Math.min(left, SLICE_SECONDS);
            integrate(dt);
            left -= dt;
        }
    }

    private void integrate(float dt) {
        float errorYaw = wrap(targetYaw - yaw);
        float errorPitch = targetPitch - pitch;
        float distance = (float) Math.sqrt(errorYaw * errorYaw + errorPitch * errorPitch);
        if (distance <= profile.tolerance() && speed() <= profile.minSpeed() * 1.5f) {
            finish();
            return;
        }
        float dirYaw = distance > 1e-4f ? errorYaw / distance : 0f;
        float dirPitch = distance > 1e-4f ? errorPitch / distance : 0f;

        float wanted = wantedSpeed(distance) * speedK;
        float wantYaw = dirYaw * wanted;
        float wantPitch = dirPitch * wanted;

        // bow the path sideways mid-move; the bend fades to nothing near the target so aim stays exact
        if (profile.curve() > 0f && startDistance > 8f) {
            float progress = 1f - Math.min(1f, distance / startDistance);
            float bend = (float) Math.sin(Math.PI * progress) * Math.min(1f, profile.curve() * curveK) * 0.5f * wanted * curveSign;
            wantYaw += -dirPitch * bend;
            wantPitch += dirYaw * bend;
        }

        // smoothing is defined per sixtieth of a second and scaled to the slice actually taken
        float keep = (float) Math.pow(profile.smoothing(), dt / SMOOTHING_REFERENCE_SECONDS);
        wantYaw = yawSpeed * keep + wantYaw * (1f - keep);
        wantPitch = pitchSpeed * keep + wantPitch * (1f - keep);

        float changeYaw = wantYaw - yawSpeed;
        float changePitch = wantPitch - pitchSpeed;
        float change = (float) Math.sqrt(changeYaw * changeYaw + changePitch * changePitch);
        boolean slowing = wantYaw * wantYaw + wantPitch * wantPitch < yawSpeed * yawSpeed + pitchSpeed * pitchSpeed;
        float limit = (slowing ? profile.deceleration() : profile.acceleration()) * accelK;
        // the push may only build up at the jerk rate, which rounds off each start; easing off or braking is never
        // held back, since a lagging brake is what makes a jerk-limited camera swing past its target
        if (profile.jerk() > 0f && !slowing) limit = Math.min(limit, accel + profile.jerk() * dt);
        float allowed = limit * dt;
        if (change > allowed) {
            changeYaw *= allowed / change;
            changePitch *= allowed / change;
            change = allowed;
        }
        accel = dt > 0f ? change / dt : 0f;
        yawSpeed += changeYaw;
        pitchSpeed += changePitch;

        float moveYaw = yawSpeed * dt;
        float movePitch = pitchSpeed * dt;
        if (profile.preventOvershoot()) {
            float along = moveYaw * dirYaw + movePitch * dirPitch;
            if (along >= distance) {
                finish();
                return;
            }
        }
        yaw += moveYaw;
        pitch = clampPitch(pitch + movePitch);
    }

    private float wantedSpeed(float distance) {
        float top = profile.maxSpeed();
        if (profile.mode() == CameraProfile.Mode.ADAPTIVE) {
            // big turns earn the full speed, small corrections stay gentle
            float share = Math.min(1f, startDistance / FULL_SPEED_ANGLE);
            top = profile.minSpeed() + (profile.maxSpeed() - profile.minSpeed()) * (0.35f + 0.65f * share);
        } else if (profile.mode() == CameraProfile.Mode.PRECISION) {
            top = profile.minSpeed() + (profile.maxSpeed() - profile.minSpeed()) * 0.45f;
        }
        float progress = startDistance > 1e-3f ? 1f - Math.min(1f, distance / startDistance) : 1f;
        float shaped = top * Math.max(0.15f, envelope(progress));
        // never faster than what can still be braked to a stop in the distance left
        float decel = profile.deceleration() * accelK;
        float brake = (float) Math.sqrt(2f * decel * Math.max(0f, distance - profile.tolerance() * 0.5f));
        return Math.max(Math.min(profile.minSpeed(), distance * 20f), Math.min(shaped, brake));
    }

    // speed over the course of the move, as the slope of the chosen easing curve scaled to peak at 1
    private float envelope(float p) {
        return switch (profile.easing()) {
            case LINEAR -> 1f;
            case CUBIC -> p < 0.5f ? Math.max(0.2f, 4f * p * p) : Math.max(0f, 4f * (1f - p) * (1f - p));
            case SMOOTHERSTEP -> Math.max(p < 0.5f ? 0.2f : 0f, 16f * p * p * (1f - p) * (1f - p));
        };
    }

    private void finish() {
        yaw = yaw + wrap(targetYaw - yaw);
        pitch = targetPitch;
        yawSpeed = 0f;
        pitchSpeed = 0f;
        accel = 0f;
        arrived = true;
    }

    private float distance() {
        float dy = wrap(targetYaw - yaw);
        float dp = targetPitch - pitch;
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    public float speed() {
        return (float) Math.sqrt(yawSpeed * yawSpeed + pitchSpeed * pitchSpeed);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean arrived() {
        return arrived;
    }

    public float remaining() {
        return arrived ? 0f : distance();
    }

    public static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }
}
