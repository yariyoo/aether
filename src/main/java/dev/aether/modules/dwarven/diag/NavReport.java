package dev.aether.modules.dwarven.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// a session's navigation numbers from the timed operations, and where the wasted time went
public final class NavReport {
    public record Waste(String what, long ms) {}

    private NavReport() {
    }

    // time that produced nothing: retries, stalls, trips that ended in the wrong place, waits between moves
    public static List<Waste> waste(Map<String, Long> extra) {
        List<Waste> w = new ArrayList<>();
        w.add(new Waste("waiting to retry after failures", sum("recovery.wait")));
        w.add(new Waste("walks that got stuck before giving up", sum("nav.stuck") + sum("mine.stuck")));
        w.add(new Waste("trips that ended in the wrong area", sum("nav.trip.wrong_area")));
        w.add(new Waste("standing still between route legs", sum("nav.idle")));
        w.add(new Waste("failed teleports", sum("hop.failed")));
        w.add(new Waste("waiting for the zone line after arriving", sum("area.confirm")));
        w.add(new Waste("forge warps after a failure", sum("nav.warp.recovery")));
        w.add(new Waste("failed mining-position walks", sum("mine.walk.failed")));
        extra.forEach((k, v) -> w.add(new Waste(k, v)));
        w.removeIf(x -> x.ms() <= 0);
        w.sort(Comparator.comparingLong(Waste::ms).reversed());
        return w;
    }

    private static long sum(String op) {
        return (long) Ops.summary(op).totalMs();
    }

    public static String text(Map<String, Long> extra, String header) {
        StringBuilder b = new StringBuilder();
        b.append("=== ").append(header).append(" ===\n");
        b.append(line("route planning (graph search, cpu)", "nav.plan"));
        b.append(line("pathfinder start (request to first step)", "path.start"));
        b.append(line("walking leg", "nav.leg"));
        b.append(line("whole trip", "nav.trip"));
        b.append(line("idle between legs", "nav.idle"));
        b.append(line("aotv preparation", "hop.aotv.prep"));
        b.append(line("aotv execution", "hop.aotv.exec"));
        b.append(line("etherwarp preparation", "hop.etherwarp.prep"));
        b.append(line("etherwarp execution", "hop.etherwarp.exec"));
        b.append(line("landing confirmation", "hop.confirm"));
        b.append(line("aim rotation", "rot.aim"));
        b.append(line("forge warp", "nav.warp"));
        b.append(line("zone confirmation", "area.confirm"));
        b.append(line("stuck detection delay", "nav.stuck"));
        b.append(line("mining-position walk", "mine.walk"));
        b.append(line("dig start", "mine.dig.start"));
        List<Waste> w = waste(extra);
        b.append("biggest sources of wasted time:\n");
        for (int i = 0; i < Math.min(3, w.size()); i++) b.append(String.format("  %d. %s: %.1fs%n", i + 1, w.get(i).what(), w.get(i).ms() / 1000.0));
        if (w.isEmpty()) b.append("  nothing measured yet\n");
        return b.toString();
    }

    private static String line(String label, String op) {
        Ops.Summary s = Ops.summary(op);
        if (s.count() == 0) return String.format("%-42s -%n", label);
        return String.format("%-42s n=%d mean=%.1fms p95=%.1fms max=%.0fms total=%.1fs%n", label, s.count(), s.meanMs(), s.p95Ms(), s.maxMs(),
                s.totalMs() / 1000.0);
    }
}
