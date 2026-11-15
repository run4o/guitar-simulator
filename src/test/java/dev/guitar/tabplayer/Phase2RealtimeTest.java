package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 verification: the lock-free event queue, sample clock, and the
 * real-time device path.
 *
 * The device test is skipped gracefully (returns early) when no output
 * device is available, e.g. in headless CI.
 */
@Tag("phase2")
class Phase2RealtimeTest {

    @Test
    void queueAcceptsTimestampedEventsWithoutDevice() {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init(44100, 256));
            long clock = engine.sampleClock();
            List<Boolean> results = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                results.add(engine.pushPluck(clock + i * 100L, i % 6, i % 25, 0.5f, false));
            }
            assertTrue(results.stream().allMatch(Boolean::booleanValue),
                    "all queued events must be accepted");
            // invalid commands must be rejected
            assertTrue(!engine.pushEvent(clock, 99, 0, 0, 0f, 0f), "unknown event type rejected");
            assertTrue(!engine.pushEvent(clock, GuitarEngine.EVENT_PLUCK, 7, 0, 0f, 0f),
                    "invalid string index rejected");
        }
    }

    @Test
    void sampleClockAdvancesWhileDeviceRuns() throws Exception {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init(44100, 256));
            if (!engine.start()) {
                return; // no audio device (headless) — nothing to verify
            }
            // Schedule a note slightly in the future and let it ring.
            long t = engine.sampleClock() + 4410;
            engine.pushPluck(t, 0, 0, 0.8f, false);
            Thread.sleep(500);
            long clock = engine.sampleClock();
            assertTrue(clock > t, "clock must advance past the scheduled note, got " + clock);
        }
    }

    @Test
    void queueRejectsEventsWhenFull() {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init(44100, 256));
            boolean allAccepted = true;
            for (int i = 0; i < 20000 && allAccepted; i++) {
                allAccepted = engine.pushPluck(i * 10L, 0, 0, 0.1f, false);
            }
            assertTrue(!allAccepted, "queue must eventually fill up (capacity 8192)");
        }
    }
}
