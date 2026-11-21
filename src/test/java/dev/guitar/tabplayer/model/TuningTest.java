package dev.guitar.tabplayer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tuning frequencies: each entry's six open-string pitches in Hz, low to
 * high. Guards against unit slips (the half-step tuning used to lower a
 * quarter tone instead of a semitone).
 */
@Tag("phase4")
class TuningTest {

  @Test
  void standardIsE2ToE4() {
    assertFreqs(Tuning.STANDARD, 82.41, 110.00, 146.83, 196.00, 246.94, 329.63);
  }

  @Test
  void halfStepDownIsTrueEbStandard() {
    // E2 -> D#2/EB2 = 77.78 Hz; a quarter-tone bug would give ~80 Hz
    assertFreqs(Tuning.HALF_STEP, 77.78, 103.83, 138.59, 185.00, 233.08, 311.13);
  }

  @Test
  void dropDKeepsStandardUpperStrings() {
    // Drop D: only the low E drops two semitones; the upper five strings
    // stay standard. Drop C takes D standard and drops its low D.
    for (int s = 1; s < 6; s++) {
      assertEquals(Tuning.STANDARD.freqs[s], Tuning.DROP_D.freqs[s], 1e-9);
      assertEquals(Tuning.D_STANDARD.freqs[s], Tuning.DROP_C.freqs[s], 1e-9);
    }
    assertFreqs(Tuning.DROP_C, 65.41, 98.00, 130.81, 174.61, 220.00, 293.66);
  }

  @Test
  void extendedTunings() {
    assertFreqs(Tuning.D_STANDARD, 73.42, 98.00, 130.81, 174.61, 220.00, 293.66);
    assertFreqs(Tuning.DROP_C_SHARP, 69.30, 103.83, 138.59, 185.00, 233.08, 311.13);
    assertFreqs(Tuning.DROP_B, 61.74, 92.50, 123.47, 164.81, 207.65, 277.18);
    assertFreqs(Tuning.OPEN_D, 73.42, 110.00, 146.83, 185.00, 220.00, 293.66);
    assertFreqs(Tuning.OPEN_E, 82.41, 123.47, 164.81, 207.65, 246.94, 329.63);
    assertFreqs(Tuning.OPEN_G, 73.42, 98.00, 146.83, 196.00, 246.94, 293.66);
    assertFreqs(Tuning.DADGAD, 73.42, 110.00, 146.83, 196.00, 220.00, 293.66);
    // the guide-sample tuning: C# standard set with a low C
    assertFreqs(Tuning.C_SHARP_LOW_C, 65.41, 92.50, 123.47, 164.81, 207.65, 277.18);
    assertEquals(Tuning.C_SHARP_LOW_C, Tuning.values()[0], "guide tuning must be the boot default");
  }

  private static void assertFreqs(Tuning tuning, double... expected) {
    assertEquals(expected.length, tuning.freqs.length);
    for (int s = 0; s < expected.length; s++) {
      assertEquals(expected[s], tuning.freqs[s], 0.01, tuning + " string " + s);
    }
  }
}
