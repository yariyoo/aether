package dev.aether.modules.dwarven.nav;

import dev.aether.modules.dwarven.memory.TrailMemory.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// judges where a teleport actually put us against the route it was part of, not just against the recorded landing
public final class HopLanding {
    public enum Verdict { ON_TARGET, CONTINUE, REPLAN, NO_TELEPORT }

    // continueAt is the leg to walk to next; it may skip ahead when we landed further along than planned
    public record Result(Verdict verdict, int continueAt, double deviation, double moved) {}

    // a hop is aimed from where we stand, so it can land a few blocks from where the recording did
    public static final double ON_TARGET_WITHIN = 4.5;
    public static final double MOVED = 2.5;
    public static final double CONTINUE_WITHIN = 12.0;
    public static final int CONTINUE_CLIMB = 4;

    private HopLanding() {
    }

    public static Result judge(Vec3 launch, Vec3 intended, Vec3 actual, List<Waypoint> legs, int hopIndex) {
        double moved = actual.distanceTo(launch);
        double deviation = actual.distanceTo(intended);
        if (moved <= MOVED) return new Result(Verdict.NO_TELEPORT, -1, deviation, moved);
        if (deviation <= ON_TARGET_WITHIN) return new Result(Verdict.ON_TARGET, hopIndex + 1, deviation, moved);
        BlockPos at = BlockPos.containing(actual.x, actual.y + 0.01, actual.z);
        // the furthest stop from the hop's landing on that we can walk to from here; earlier stops would only repeat the miss
        for (int j = legs.size() - 1; j >= hopIndex; j--) {
            BlockPos p = legs.get(j).pos();
            if (Math.abs(p.getY() - at.getY()) <= CONTINUE_CLIMB && Math.sqrt(p.distSqr(at)) <= CONTINUE_WITHIN) {
                return new Result(Verdict.CONTINUE, j, deviation, moved);
            }
        }
        return new Result(Verdict.REPLAN, -1, deviation, moved);
    }
}
