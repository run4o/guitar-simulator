package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guitar simulations: acoustic vs electric single coil vs humbucker, rendered
 * offline from identical note sequences.
 */
@Tag("guitars")
class GuitarModelTest {

    private static final float SR = 44100.0f;

    @TempDir
    Path tempDir;

    /** Plucks open G (196 Hz) at t=0.05; `model` = 0 acoustic, 1 single, 2 humbucker. */
    private WavAnalysis.Wav renderModel(int model) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_SET_MODEL, -1, model, 0f, 0f),
                new RenderCommand(0.05f, GuitarEngine.EVENT_PLUCK, 3, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("model" + model + "-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            assertTrue(engine.renderSequenceToWav(wav, 3.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    private static float rmsOf(WavAnalysis.Wav w, double from, double to) {
        return WavAnalysis.rms(w, from, to);
    }

    /** High-frequency energy proxy: RMS of the first-difference (HF emphasis). */
    private static float hfRms(WavAnalysis.Wav w) {
        float[] s = w.samples();
        double sum = 0;
        long n = 0;
        for (int i = (int) (0.1 * w.sampleRate()); i < (int) (1.0 * w.sampleRate()); i++) {
            float d = s[i] - s[i - 1];
            sum += (double) d * d;
            n++;
        }
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    private static long crossingsPerSecond(WavAnalysis.Wav wav) {
        int from = (int) (0.1 * wav.sampleRate());
        int to = (int) (1.0 * wav.sampleRate());
        long crossings = 0;
        float[] s = wav.samples();
        for (int i = from + 1; i < to; i++) {
            if ((s[i - 1] < 0) != (s[i] < 0)) {
                crossings++;
            }
        }
        return Math.round(crossings / 0.9);
    }

    @Test
    void electricSustainsFarLongerThanAcoustic() throws Exception {
        WavAnalysis.Wav acoustic = renderModel(0);
        WavAnalysis.Wav single = renderModel(1);
        // T60 3 s vs 9 s: after ~1.75 s the acoustic body decay is far below the electric
        float acousticLate = rmsOf(acoustic, 1.5, 2.5);
        float electricLate = rmsOf(single, 1.5, 2.5);
        assertTrue(electricLate > 3.0f * acousticLate,
                "electric must sustain much longer: electric=" + electricLate
                        + " acoustic=" + acousticLate);
    }

    @Test
    void electricPickupSoundsBrighterThanAcoustic() throws Exception {
        WavAnalysis.Wav acoustic = renderModel(0);
        WavAnalysis.Wav single = renderModel(1);
        // brightness = harmonic wiggle density: brighter voicing crosses zero more often
        assertTrue(crossingsPerSecond(single) > 1.3f * crossingsPerSecond(acoustic),
                "single coil must have more zero crossings than acoustic: single="
                        + crossingsPerSecond(single) + " acoustic=" + crossingsPerSecond(acoustic));
        assertTrue(crossingsPerSecond(single) > crossingsPerSecond(acoustic),
                "single coil must have more HF zero crossings than acoustic");
    }

    @Test
    void humbuckerSoundsDifferentAndSustainsLongerThanAcoustic() throws Exception {
        WavAnalysis.Wav single = renderModel(1);
        WavAnalysis.Wav humbucker = renderModel(2);
        WavAnalysis.Wav acoustic = renderModel(0);
        // both electric renders share the same excitation seed, so any waveform
        // difference is pure voicing (hum = fatter combs, filled notches)
        float[] s1 = single.samples();
        float[] s2 = humbucker.samples();
        double sum = 0;
        long n = 0;
        for (int i = (int) (0.3 * single.sampleRate()); i < (int) (1.0 * single.sampleRate()); i++) {
            float d = s1[i] - s2[i];
            sum += (double) d * d;
            n++;
        }
        float diffRms = (float) Math.sqrt(sum / Math.max(1, n));
        float level = Math.min(rmsOf(single, 0.3, 1.0), rmsOf(humbucker, 0.3, 1.0));
        assertTrue(diffRms > 0.15f * level,
                "humbucker must sound different from the single coil: diff=" + diffRms
                        + " level=" + level);
        // and still sustains far longer than the acoustic body decay
        float acousticLate = rmsOf(acoustic, 1.5, 2.5);
        float humbuckerLate = rmsOf(humbucker, 1.5, 2.5);
        assertTrue(humbuckerLate > 2.5f * acousticLate,
                "humbucker must sustain longer than acoustic: humb=" + humbuckerLate
                        + " acoustic=" + acousticLate);
    }

    @Test
    void acousticRenderHasSaneLevel() throws Exception {
        WavAnalysis.Wav acoustic = renderModel(0);
        float early = WavAnalysis.rms(acoustic, 0.1, 0.4);
        if (early < 0.008f) {
            fail("acoustic render must be audible, rms=" + early);
        }
    }
}
