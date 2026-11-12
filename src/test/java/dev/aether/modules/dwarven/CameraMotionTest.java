package dev.aether.modules.dwarven;

import dev.aether.modules.dwarven.camera.CameraMotion;
import dev.aether.modules.dwarven.camera.CameraProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraMotionTest {
    private static final CameraProfile PROFILE = CameraProfile.standard();

    private static int run(CameraMotion motion, float dt, int maxSteps) {
        int steps = 0;
        while (!motion.arrived() && steps < maxSteps) {
            motion.step(dt);
            steps++;
        }
        return steps;
    }

    @Test
    void arrivesExactlyOnTarget() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 0);
        motion.aim(0, 0, 90, -30, PROFILE);
        run(motion, 1 / 60f, 600);
        assertTrue(motion.arrived());
        assertEquals(90, motion.yaw(), 1e-3);
        assertEquals(-30, motion.pitch(), 1e-3);
        assertEquals(0, motion.speed(), 1e-3);
    }

    @Test
    void takesTheShortWayAcrossTheYawSeam() {
        CameraMotion motion = new CameraMotion();
        motion.reset(170, 0);
        motion.aim(170, 0, -170, 0, PROFILE);
        float travelled = 0, last = 170;
        for (int i = 0; i < 600 && !motion.arrived(); i++) {
            motion.step(1 / 60f);
            travelled += Math.abs(motion.yaw() - last);
            last = motion.yaw();
        }
        assertTrue(motion.arrived());
        assertTrue(travelled < 40, "turned " + travelled + " degrees for a 20 degree move");
    }

    @Test
    void neverOvershootsAStraightMove() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 0);
        motion.aim(0, 0, 60, 0, PROFILE.withCurve(0));
        for (int i = 0; i < 600 && !motion.arrived(); i++) {
            motion.step(1 / 60f);
            assertTrue(motion.yaw() <= 60.0001f, "overshot to " + motion.yaw());
        }
        assertTrue(motion.arrived());
    }

    @Test
    void speedChangesAreBoundedSoMotionIsSmooth() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 0);
        motion.aim(0, 0, 120, 20, PROFILE);
        float dt = 1 / 120f, lastSpeed = 0;
        for (int i = 0; i < 2000 && !motion.arrived(); i++) {
            motion.step(dt);
            float speed = motion.speed();
            if (!motion.arrived()) {
                float limit = Math.max(PROFILE.acceleration(), PROFILE.deceleration()) * dt * 1.05f;
                assertTrue(Math.abs(speed - lastSpeed) <= limit, "speed jumped by " + Math.abs(speed - lastSpeed));
                assertTrue(speed <= PROFILE.maxSpeed() * 1.01f);
            }
            lastSpeed = speed;
        }
        assertTrue(motion.arrived());
    }

    @Test
    void frameRateBarelyChangesHowLongAMoveTakes() {
        float[] seconds = new float[2];
        float[] rates = {30f, 240f};
        for (int r = 0; r < rates.length; r++) {
            CameraMotion motion = new CameraMotion();
            motion.reset(0, 0);
            motion.aim(0, 0, 100, 10, PROFILE);
            seconds[r] = run(motion, 1 / rates[r], 5000) / rates[r];
            assertTrue(motion.arrived());
        }
        assertTrue(Math.abs(seconds[0] - seconds[1]) < 0.12f, "30fps took " + seconds[0] + "s, 240fps took " + seconds[1] + "s");
    }

    @Test
    void retargetingMidMoveKeepsItsSpeed() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 0);
        motion.aim(0, 0, 90, 0, PROFILE);
        for (int i = 0; i < 12; i++) motion.step(1 / 60f);
        float before = motion.speed();
        assertTrue(before > 50, "should be moving, was " + before);
        motion.aim(motion.yaw(), motion.pitch(), 120, 25, PROFILE);
        assertEquals(before, motion.speed(), 1e-3);
        run(motion, 1 / 60f, 600);
        assertEquals(120, motion.yaw(), 1e-3);
        assertEquals(25, motion.pitch(), 1e-3);
    }

    @Test
    void curvedMovesStillLandExactlyAndPitchStaysLegal() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 80);
        motion.aim(0, 80, 150, 89, PROFILE.withCurve(1f));
        for (int i = 0; i < 2000 && !motion.arrived(); i++) {
            motion.step(1 / 60f);
            assertTrue(motion.pitch() <= 90f && motion.pitch() >= -90f);
        }
        assertTrue(motion.arrived());
        assertEquals(89, motion.pitch(), 1e-3);
    }

    @Test
    void aLagSpikeCannotFlingTheCamera() {
        CameraMotion motion = new CameraMotion();
        motion.reset(0, 0);
        motion.aim(0, 0, 170, 0, PROFILE);
        motion.step(5f);
        assertTrue(motion.arrived());
        assertEquals(170, motion.yaw(), 1e-3);
    }
}
