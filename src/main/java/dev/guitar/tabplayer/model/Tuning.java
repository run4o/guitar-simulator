package dev.guitar.tabplayer.model;

/**
 * The six guitar tunings offered by the TUNE selector, as open-string
 * frequencies in Hz (low E first).
 */
public enum Tuning {
  STANDARD("STANDARD", new double[]{82.41, 110.00, 146.83, 196.00, 246.94, 329.63}),
  DROP_D("DROP D", new double[]{73.42, 110.00, 146.83, 196.00, 246.94, 329.63}),
  DROP_C("DROP C", new double[]{65.41, 98.00, 130.81, 174.61, 220.00, 293.66}),
  DROP_C_SHARP("DROP C#", new double[]{69.30, 103.83, 138.59, 185.00, 233.08, 311.13}),
  DROP_B("DROP B", new double[]{61.74, 92.50, 123.47, 164.81, 207.65, 277.18}),
  D_STANDARD("D STANDARD", new double[]{73.42, 98.00, 130.81, 174.61, 220.00, 293.66}),
  HALF_STEP("HALF STEP DOWN", scale(1.0)),
  OPEN_G("OPEN G", new double[]{73.42, 98.00, 146.83, 196.00, 246.94, 293.66}),
  OPEN_D("OPEN D", new double[]{73.42, 110.00, 146.83, 185.00, 220.00, 293.66}),
  OPEN_E("OPEN E", new double[]{82.41, 123.47, 164.81, 207.65, 246.94, 329.63}),
  DADGAD("DADGAD", new double[]{73.42, 110.00, 146.83, 196.00, 220.00, 293.66});

  public final String label;
  public final double[] freqs;

  Tuning(String label, double[] freqs) {
    this.label = label;
    this.freqs = freqs;
  }

  @Override
  public String toString() {
    return label;
  }

  private static double[] scale(double semitones) {
    double factor = Math.pow(2.0, -semitones / 12.0);
    double[] out = new double[6];
    for (int i = 0; i < 6; i++) {
      out[i] = STANDARD.freqs[i] * factor;
    }
    return out;
  }
}
