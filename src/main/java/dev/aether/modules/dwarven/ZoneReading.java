package dev.aether.modules.dwarven;

// whether the scoreboard's zone line can be trusted for where the player is right now. hypixel refreshes the sidebar
// on its own schedule, so for a moment after a teleport it still names the zone we left
public final class ZoneReading {
    // a move this far in one tick is a teleport, not walking
    static final double JUMP = 3.0;
    public static final long SETTLE_MS = 1500L;

    private double lastX, lastY, lastZ;
    private boolean havePos;
    private long jumpAt = Long.MIN_VALUE / 2;
    private String zone = "";
    private long zoneChangedAt;

    public void tick(double x, double y, double z, String zoneNow, long now) {
        if (havePos) {
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz > JUMP * JUMP) jumpAt = now;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        havePos = true;
        String z2 = zoneNow == null ? "" : zoneNow;
        if (!z2.equals(zone)) {
            zone = z2;
            zoneChangedAt = now;
        }
    }

    public String zone() {
        return zone;
    }

    // a zone line that changed after the teleport was refreshed for where we landed; otherwise give it time
    public boolean fresh(long now) {
        if (zone.isEmpty()) return false;
        return zoneChangedAt > jumpAt || now - jumpAt >= SETTLE_MS;
    }

    public long sinceJump(long now) {
        return now - jumpAt;
    }

    public void reset() {
        havePos = false;
        jumpAt = Long.MIN_VALUE / 2;
        zone = "";
        zoneChangedAt = 0;
    }
}
