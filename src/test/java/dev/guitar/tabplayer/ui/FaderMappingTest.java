package dev.guitar.tabplayer.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the fader interaction mapping is NOT inverted: the top of the
 * track must be maximum (like a real mixing console), the bottom minimum.
 */
@Tag("phase4")
class FaderMappingTest {

    private static final double HEIGHT = 160.0; // track 12..148, usable 136

    @Test
    void topOfTrackIsMaximum() {
        double frac = Fader.fracFromY(12, HEIGHT);
        assertEquals(1.0, frac, 1e-6, "clicking the top of the fader must give maximum");
    }

    @Test
    void bottomOfTrackIsMinimum() {
        double frac = Fader.fracFromY(HEIGHT - 12, HEIGHT);
        assertEquals(0.0, frac, 1e-6, "clicking the bottom of the fader must give minimum");
    }

    @Test
    void middleOfTrackIsHalf() {
        double frac = Fader.fracFromY(HEIGHT / 2, HEIGHT);
        assertEquals(0.5, frac, 1e-6, "clicking the middle of the fader must give 50%");
    }

    @Test
    void draggingDownDecreasesValue() {
        double up = Fader.fracFromY(40, HEIGHT);
        double down = Fader.fracFromY(110, HEIGHT);
        assertTrue(down < up, "moving the mouse down must decrease the fader value");
    }

    @Test
    void clampsOutOfRangePositions() {
        assertEquals(1.0, Fader.fracFromY(-50, HEIGHT), 1e-6);
        assertEquals(0.0, Fader.fracFromY(HEIGHT + 50, HEIGHT), 1e-6);
    }

    @Test
    void wheelUpIncreasesByOneStep() {
        assertEquals(0.55, Knob.scrollValue(40, 0.5, 0, 1), 1e-9,
                "one wheel notch up must add 1/20th of the range");
    }

    @Test
    void wheelDownDecreasesByOneStep() {
        assertEquals(0.45, Knob.scrollValue(-40, 0.5, 0, 1), 1e-9,
                "one wheel notch down must subtract 1/20th of the range");
    }

    @Test
    void wheelClampsAtLimits() {
        assertEquals(1.0, Knob.scrollValue(400, 0.9, 0, 1), 1e-9);
        assertEquals(0.0, Knob.scrollValue(-400, 0.1, 0, 1), 1e-9);
    }

    @Test
    void noteNamesMatchScientificPitchNotation() {
        assertEquals("E2", PlayerApp.noteName(82.41));
        assertEquals("D2", PlayerApp.noteName(73.42));
        assertEquals("C2", PlayerApp.noteName(65.41));
        assertEquals("E4", PlayerApp.noteName(329.63));
        assertEquals("D4", PlayerApp.noteName(293.66));
        assertEquals("A#3", PlayerApp.noteName(220.0 * Math.pow(2, 0.5 / 12.0)));
    }
}
