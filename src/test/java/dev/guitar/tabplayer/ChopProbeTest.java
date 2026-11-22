package dev.guitar.tabplayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** Temporary: RMS envelope of an audition file to find the chop points. */
class ChopProbeTest {

  @Test
  void probe() throws Exception {
    WavAnalysis.Wav w = WavAnalysis.read(Path.of("build", "audition-5-metal-cab-gate.wav"));
    float[] s = w.samples();
    int win = (int) (0.025 * w.sampleRate());
    StringBuilder line = new StringBuilder();
    for (int start = 0; start + win < s.length && start < (int) (2.0 * w.sampleRate());
         start += win) {
      double sum = 0;
      for (int i = start; i < start + win; i++) {
        sum += s[i] * s[i];
      }
      double rms = Math.sqrt(sum / win);
      line.append(String.format("%.3f ", rms));
    }
    System.out.println("rms 25ms windows (t=0..2s):");
    System.out.println(line);
  }
}
