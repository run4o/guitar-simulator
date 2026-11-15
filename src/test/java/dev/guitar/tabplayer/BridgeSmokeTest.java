package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java FFM bridge against the native stub engine.
 * Requires the DLL to be built first: scripts/build-native.cmd
 */
class BridgeSmokeTest {

    @Test
    void reportsExpectedVersion() {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertEquals(GuitarEngine.VERSION, engine.version());
        }
    }

    @Test
    void initAcceptsValidSettingsAndRejectsInvalid() {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init(44100, 512));
            assertTrue(engine.init(48000, 256));
            assertFalse(engine.init(-1, 512), "negative sample rate must be rejected");
            assertFalse(engine.init(44100, 0), "zero buffer frames must be rejected");
        }
    }

    @Test
    void stubCommandsWithoutCrashing() {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init(48000, 256));
            assertDoesNotThrow(() -> {
                engine.setStringTuning(5, 329.63f);
                engine.pluck(5, 12, 1.0f, false);
                engine.legatoFret(5, 14);
                engine.bend(5, 2.0f);
                engine.mute(5);
                engine.shutdown();
            });
        }
    }
}
