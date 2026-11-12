package dev.aether.modules.dwarven.diag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the profiler, the 50ms sampler and the log writer, on a fake clock so the numbers are exact
class DiagnosticsTests {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void setUp() {
        Ops.reset();
        Ops.clock = nanos::get;
    }

    @AfterEach
    void tearDown() {
        Ops.clock = System::nanoTime;
        Diag.close();
    }

    private void advanceMs(double ms) {
        nanos.addAndGet((long) (ms * 1_000_000));
    }

    private static DiagLog log(Path dir, int capacity, long maxBytes, int rotations, java.util.concurrent.Executor ex) {
        return new DiagLog(dir, "test", capacity, capacity, maxBytes, rotations, ex, 0);
    }

    @Test
    void operationsAreTimedToTheMicrosecondNotRoundedToWholeMilliseconds() {
        for (int i = 0; i < 20; i++) {
            Ops.Op op = Ops.begin("nav.plan", 0);
            advanceMs(i == 19 ? 5.0 : 0.8);
            op.end("done");
        }
        Ops.Summary s = Ops.summary("nav.plan");
        assertEquals(20, s.count());
        assertEquals((19 * 0.8 + 5.0) / 20, s.meanMs(), 1e-6);
        // nearest-rank 95th percentile of 20 samples is the 19th smallest
        assertEquals(0.8, s.p95Ms(), 1e-6);
        assertEquals(5.0, s.maxMs(), 1e-6);
        Ops.recordNanos("hop.confirm", 123_456);
        assertEquals(0.123456, Ops.summary("hop.confirm").meanMs(), 1e-9);
    }

    @Test
    void elapsedTimeIncludesWaitingNotJustWork() {
        // an operation that spans ticks waiting on the server is timed start to finish
        Ops.Op op = Ops.begin("hop.aotv.exec", 900);
        for (int tick = 0; tick < 6; tick++) advanceMs(50);
        assertEquals(300, op.end("moved"));
        assertEquals(300.0, Ops.summary("hop.aotv.exec").totalMs(), 1e-6);
        // ending twice never counts twice
        op.end("again");
        assertEquals(1, Ops.summary("hop.aotv.exec").count());
    }

    @Test
    void theWatchdogTellsSlowProgressFromAStall() {
        Ops.Op walking = Ops.begin("nav.leg", 1000);
        Ops.Op stalled = Ops.begin("mine.walk", 1000);
        for (int i = 0; i < 30; i++) {
            advanceMs(100);
            walking.progress();
        }
        List<String> w = Ops.watchdog(2.0);
        assertEquals(2, w.size());
        assertTrue(w.stream().anyMatch(x -> x.contains("nav.leg") && x.contains("still progressing")));
        assertTrue(w.stream().anyMatch(x -> x.contains("WARNING: mine.walk exceeded expected duration by 2000ms")));
        // each is reported once, and nothing was stopped
        assertTrue(Ops.watchdog(2.0).isEmpty());
        assertFalse(walking.done());
        assertFalse(stalled.done());
    }

    @Test
    void cancellingNavigationClosesEveryOpenOperationWithoutCountingIt() {
        Ops.begin("nav.trip", 0);
        Ops.begin("nav.leg", 0);
        advanceMs(400);
        Ops.cancelAll("navigation stopped");
        assertEquals(0, Ops.openCount());
        assertEquals(0, Ops.summary("nav.trip").count());
        assertEquals(0, Ops.summary("nav.leg").count());
    }

    @Test
    void snapshotsComeEveryFiftyMillisecondsAndALateOneSaysSo() {
        Sampler s = new Sampler(50);
        long t = 0;
        assertNotNull(s.due(t));
        for (int i = 0; i < 20; i++) {
            // client ticks wobble a little around 50ms
            t += (i % 2 == 0 ? 49 : 51) * 1_000_000L;
            Sampler.Sample smp = s.due(t);
            assertNotNull(smp);
            assertEquals(0, smp.lateNs());
            assertEquals(0, smp.missed());
        }
        assertEquals(21, s.samples());
        // a frame in between isn't another sample
        assertNull(s.due(t + 10_000_000L));
        // a 130ms hitch is recorded as late: it stands in for the 100ms deadline, the 50ms one is missed outright,
        // and nothing is filled in with made-up samples
        t += 130_000_000L;
        Sampler.Sample late = s.due(t);
        assertEquals(130_000_000L, late.intervalNs());
        assertTrue(late.lateNs() > 0);
        assertEquals(1, late.missed());
        assertEquals(1, s.lateSamples());
        assertEquals(22, s.samples());
    }

    @Test
    void eventsCarryMillisecondTimestampsAndDurations() {
        DiagLog l = log(Path.of("unused"), 64, 1 << 20, 2, Runnable::run);
        long wall = java.time.LocalDate.of(2026, 11, 12).atTime(16, 15, 54, 201_000_000).atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        String line = l.format(new StringBuilder(), new DiagLog.Entry(wall, 12_345_678_901L, DiagLog.Kind.EVENT, "AREA",
                "expected=Upper Mines observed=Rampart's Quarry", 812_000)).toString();
        assertEquals("[16:15:54.201] +12.346s [AREA] expected=Upper Mines observed=Rampart's Quarry (0.8ms)", line);
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfBlocking() throws Exception {
        Path dir = Files.createTempDirectory("diag");
        List<Runnable> held = new ArrayList<>();
        DiagLog l = log(dir, 16, 1 << 20, 2, held::add);
        int ok = 0;
        for (int i = 0; i < 100; i++) if (l.offer(new DiagLog.Entry(i, i, DiagLog.Kind.SNAPSHOT, "SNAP", "s" + i, -1))) ok++;
        assertEquals(16, ok);
        assertEquals(84, l.dropped());
        l.maybeFlush(10_000);
        assertEquals(1, held.size());
        held.get(0).run();
        String text = Files.readString(dir.resolve("test.log"));
        assertTrue(text.startsWith("[DIAG] 84 records dropped because the queue was full"));
        assertEquals(17, text.lines().count());
        // the in-memory ring keeps the recent history regardless, for an export
        assertEquals(16, l.recent().size());
    }

    @Test
    void logsRotateAtTheSizeLimitAndKeepOnlyTheConfiguredCount() throws Exception {
        Path dir = Files.createTempDirectory("diag");
        DiagLog l = log(dir, 4096, 64 * 1024, 2, Runnable::run);
        String body = "x".repeat(200);
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 100; i++) l.offer(new DiagLog.Entry(0, 0, DiagLog.Kind.SNAPSHOT, "SNAP", body, -1));
            l.flushNow();
        }
        assertTrue(Files.exists(dir.resolve("test.log")));
        assertTrue(Files.exists(dir.resolve("test.1.log")));
        assertTrue(Files.exists(dir.resolve("test.2.log")));
        assertFalse(Files.exists(dir.resolve("test.3.log")));
        for (String f : List.of("test.log", "test.1.log", "test.2.log")) assertTrue(Files.size(dir.resolve(f)) <= 64 * 1024 + 1024);
    }

    @Test
    void writingOnAnotherThreadWhileTheGameThreadKeepsLoggingLosesNothingUncounted() throws Exception {
        Path dir = Files.createTempDirectory("diag");
        ExecutorService writer = Executors.newSingleThreadExecutor();
        DiagLog l = log(dir, 512, 64 << 20, 2, writer);
        int offered = 20_000;
        Thread game = new Thread(() -> {
            for (int i = 0; i < offered; i++) {
                l.offer(new DiagLog.Entry(i, i, DiagLog.Kind.EVENT, "NAV", "e" + i, -1));
                l.maybeFlush(i);
            }
        });
        game.start();
        game.join();
        writer.submit(l::flushNow).get();
        writer.shutdown();
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(offered, l.written() + l.dropped());
    }

    @Test
    void withDiagnosticsOffNothingIsRecorded() {
        Diag.configure(false, Set.of(), () -> {
            throw new AssertionError("no log should be made while off");
        });
        Diag.event(Diag.NAV, "ignored");
        Diag.snapshot("ignored");
        assertFalse(Diag.enabled());
    }

    @Test
    void aSwitchedOffCategoryIsLeftOutButOthersAreKept() throws Exception {
        Path dir = Files.createTempDirectory("diag");
        Diag.configure(true, Set.of(Diag.ROT), () -> log(dir, 64, 1 << 20, 2, Runnable::run));
        Diag.event(Diag.ROT, "turning");
        Diag.event(Diag.AREA, "zone confirmed", 812L);
        List<DiagLog.Entry> got = Diag.log().recent();
        assertEquals(1, got.size());
        assertEquals("AREA", got.get(0).category());
        assertEquals(812_000_000L, got.get(0).durationNs());
    }

    @Test
    void theReportRanksTheBiggestTimeSinks() {
        Ops.record("recovery.wait", 9000);
        Ops.record("nav.trip.wrong_area", 40_000);
        Ops.record("nav.idle", 1200);
        Ops.record("mine.stuck", 2500);
        Ops.record("nav.stuck", 1000);
        List<NavReport.Waste> w = NavReport.waste(Map.of("repeated trips to the same destination", 20_000L));
        assertEquals("trips that ended in the wrong area", w.get(0).what());
        assertEquals("repeated trips to the same destination", w.get(1).what());
        assertEquals("waiting to retry after failures", w.get(2).what());
        assertEquals(3500, w.get(3).ms());
        String text = NavReport.text(Map.of(), "test");
        assertTrue(text.contains("1. trips that ended in the wrong area: 40.0s"));
    }
}
