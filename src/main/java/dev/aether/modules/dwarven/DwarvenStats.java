package dev.aether.modules.dwarven;

import java.util.EnumMap;
import java.util.Map;

// where a session's time actually went, measured from stage changes and real outcomes, for the hud and for tuning
public final class DwarvenStats {
    private final Map<DwarvenCommissionMacro.Stage, Long> stageMs = new EnumMap<>(DwarvenCommissionMacro.Stage.class);
    private int commissions;
    private long commissionMs;
    private int trips;
    private int tripsArrived;
    private long travelMsThisCommission;
    private long travelMsTotal;
    private int kills;
    private int wrongAreas;
    private long fightMs;

    public synchronized void reset() {
        stageMs.clear();
        commissions = 0;
        commissionMs = 0;
        trips = 0;
        tripsArrived = 0;
        travelMsThisCommission = 0;
        travelMsTotal = 0;
        kills = 0;
        wrongAreas = 0;
        fightMs = 0;
    }

    public synchronized void stageTime(DwarvenCommissionMacro.Stage stage, long ms) {
        if (ms <= 0) return;
        stageMs.merge(stage, ms, Long::sum);
        if (stage == DwarvenCommissionMacro.Stage.TRAVEL) {
            travelMsThisCommission += ms;
            travelMsTotal += ms;
        }
        if (stage == DwarvenCommissionMacro.Stage.FIGHT) fightMs += ms;
    }

    public synchronized void commissionDone(long ms) {
        commissions++;
        commissionMs += ms;
        travelMsThisCommission = 0;
    }

    public synchronized void trip(boolean arrived) {
        trips++;
        if (arrived) tripsArrived++;
    }

    public synchronized void wrongArea() {
        wrongAreas++;
    }

    public synchronized int wrongAreas() {
        return wrongAreas;
    }

    public synchronized void kill() {
        kills++;
    }

    public synchronized long averageCommissionMs() {
        return commissions == 0 ? 0 : commissionMs / commissions;
    }

    // travel per finished commission, so a long trip that led somewhere is counted against what it led to
    public synchronized long averageTravelMs() {
        return commissions == 0 ? 0 : travelMsTotal / commissions;
    }

    // time spent waiting out failures and retries rather than working or travelling
    public synchronized long recoveryMs() {
        return stageMs.getOrDefault(DwarvenCommissionMacro.Stage.WAIT, 0L);
    }

    public synchronized double navigationSuccess() {
        return trips == 0 ? -1 : (double) tripsArrived / trips;
    }

    public synchronized int trips() {
        return trips;
    }

    public synchronized double killsPerMinute() {
        return fightMs < 30_000L ? -1 : kills / (fightMs / 60_000.0);
    }

    public synchronized int kills() {
        return kills;
    }
}
