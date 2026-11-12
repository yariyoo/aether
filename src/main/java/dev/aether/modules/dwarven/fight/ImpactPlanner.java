package dev.aether.modules.dwarven.fight;

import dev.aether.modules.dwarven.world.OreWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// where a wither impact would put us and who it would hit. the ability carries the player up to its range along the
// view and stops short of a block, then damages everything around the landing; this predicts that from the blocks
public final class ImpactPlanner {
    public static final double RANGE = 10.0;
    private static final double STEP = 0.25;
    // a landing with nothing under it for this far would drop us somewhere we didn't plan
    private static final int MAX_FALL = 6;

    public record Target(int id, Vec3 pos) {}

    // pitch 90 is the in-place cast: looking straight down the teleport goes nowhere and the blast is centred on us
    public record Plan(float yaw, float pitch, Vec3 landing, List<Integer> hits, boolean moves) {}

    private ImpactPlanner() {
    }

    // the view ray from the eyes goes until a block is in the way; the feet end up one eye height below where it
    // stopped, lifted onto the floor if that point is inside it
    public static Vec3 landing(OreWorld world, Vec3 eye, Vec3 dir, double range, double eyeHeight) {
        Vec3 d = dir.normalize();
        Vec3 last = eye;
        for (double t = STEP; t <= range + 1e-6; t += STEP) {
            Vec3 p = eye.add(d.scale(t));
            if (!world.passable(BlockPos.containing(p.x, p.y, p.z))) break;
            last = p;
        }
        Vec3 feet = last.subtract(0, eyeHeight, 0);
        BlockPos fb = BlockPos.containing(feet.x, feet.y, feet.z);
        if (!world.passable(fb) && world.passable(fb.above()) && world.passable(fb.above(2))) feet = new Vec3(feet.x, fb.getY() + 1, feet.z);
        return feet;
    }

    static boolean supported(OreWorld world, Vec3 landing) {
        BlockPos at = BlockPos.containing(landing.x, landing.y, landing.z);
        for (int d = 0; d <= MAX_FALL; d++) if (!world.passable(at.below(d + 1))) return true;
        return false;
    }

    public static Plan best(OreWorld world, Vec3 feet, Vec3 eye, float yawNow, List<Target> targets, double radius, boolean preferClusters) {
        List<Plan> options = new ArrayList<>();
        options.add(new Plan(yawNow, 90.0f, feet, within(targets, feet, radius), false));
        for (Target t : targets) {
            Vec3 aimAt = t.pos().add(0, 0.9, 0);
            if (aimAt.distanceTo(eye) > RANGE + radius) continue;
            Vec3 dir = aimAt.subtract(eye);
            Vec3 land = landing(world, eye, dir, RANGE, eye.y - feet.y);
            if (land.distanceTo(feet) < 1.0 || !supported(world, land)) continue;
            double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontal));
            options.add(new Plan(yaw, pitch, land, within(targets, land, radius), true));
        }
        Plan best = null;
        for (Plan p : options) {
            if (p.hits().isEmpty()) continue;
            if (best == null || better(p, best, feet, preferClusters)) best = p;
        }
        return best;
    }

    // more goblins hit wins when clusters are preferred; otherwise any hit will do and staying put is cheapest
    private static boolean better(Plan a, Plan b, Vec3 feet, boolean preferClusters) {
        if (preferClusters && a.hits().size() != b.hits().size()) return a.hits().size() > b.hits().size();
        if (a.moves() != b.moves()) return !a.moves();
        if (!preferClusters && a.hits().size() != b.hits().size()) return a.hits().size() > b.hits().size();
        return a.landing().distanceTo(feet) < b.landing().distanceTo(feet);
    }

    private static List<Integer> within(List<Target> targets, Vec3 centre, double radius) {
        List<Integer> out = new ArrayList<>();
        for (Target t : targets) if (t.pos().distanceTo(centre) <= radius) out.add(t.id());
        return out;
    }
}
