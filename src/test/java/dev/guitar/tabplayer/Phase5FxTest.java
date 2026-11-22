package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 5 verification: master FX chain (drive / tone / reverb / master),
 * verified through deterministic offline renders.
 */
@Tag("phase5")
class Phase5FxTest {

    private static final float SR = 44100.0f;

    @TempDir
    Path tempDir;

    private WavAnalysis.Wav render(float drive, float tone, float mix, float master) throws Exception {
        // Note plucked at t=0, choked at t=0.3 -> isolates the FX on a known envelope.
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("fx-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(drive, tone, mix, master);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    /** Bright pluck (B4, string 5 fret 7) at max drive through one amp channel. */
    private WavAnalysis.Wav renderChannel(int model, String tag) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("amp-" + tag + "-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setAmpModel(model);
            engine.setFx(1f, 1f, 0f, 1f); // max drive, bright tone, no reverb
            assertTrue(engine.renderSequenceToWav(wav, 0.6f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void reverbLengthensDecayTail() throws Exception {
        WavAnalysis.Wav dry = render(0f, 1f, 0f, 1f);
        WavAnalysis.Wav wet = render(0f, 1f, 0.8f, 1f);
        float dryLate = WavAnalysis.rms(dry, 1.0, 1.9);
        float wetLate = WavAnalysis.rms(wet, 1.0, 1.9);
        assertTrue(wetLate > 3.0f * dryLate,
                "reverb must add a long tail: wetLate=" + wetLate + " dryLate=" + dryLate);
        assertTrue(wetLate > 5.0e-4f, "reverb tail must be audible, got " + wetLate);
    }

    @Test
    void driveIncreasesLoudnessOnPluckedNote() throws Exception {
        WavAnalysis.Wav clean = render(0f, 1f, 0f, 1f);
        WavAnalysis.Wav driven = render(0.8f, 1f, 0f, 1f);
        float cleanRms = WavAnalysis.rms(clean, 0.05, 0.25);
        float drivenRms = WavAnalysis.rms(driven, 0.05, 0.25);
        assertTrue(drivenRms > 2.0f * cleanRms,
                "drive must add sustain/loudness: clean=" + cleanRms + " driven=" + drivenRms);
    }

    @Test
    void masterGainScalesOutputLinearly() throws Exception {
        WavAnalysis.Wav full = render(0f, 1f, 0f, 1f);
        WavAnalysis.Wav half = render(0f, 1f, 0f, 0.5f);
        float fullRms = WavAnalysis.rms(full, 0.05, 0.25);
        float halfRms = WavAnalysis.rms(half, 0.05, 0.25);
        float ratio = halfRms / fullRms;
        assertTrue(ratio > 0.4f && ratio < 0.6f,
                "master 0.5 must halve the output, ratio=" + ratio);
    }

    @Test
    void toneKnobControlsBrightness() throws Exception {
        // Brightness proxy: raw zero-crossing density in the attack window.
        WavAnalysis.Wav dark = render(0f, 0f, 0f, 1f);
        WavAnalysis.Wav bright = render(0f, 1f, 0f, 1f);
        assertTrue(crossingsPerSecond(bright) > crossingsPerSecond(dark),
                "tone=1 must be brighter (more HF content) than tone=0");
    }

    @Test
    void stringGainScalesVoiceLevel() throws Exception {
        WavAnalysis.Wav full = render(0f, 1f, 0f, 1f); // default gains = 1.0
        // render helper uses default gains; for the halved render, set gain first
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("gain-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setStringGain(0, 0.5f);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        WavAnalysis.Wav half = WavAnalysis.read(wav);
        float fullRms = WavAnalysis.rms(full, 0.05, 0.25);
        float halfRms = WavAnalysis.rms(half, 0.05, 0.25);
        float ratio = halfRms / fullRms;
        assertTrue(ratio > 0.35f && ratio < 0.65f,
                "gain 0.5 must halve the string level, ratio=" + ratio);
    }

    @Test
    void levelMeterTracksRenderedAudio() throws Exception {
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            assertTrue(engine.renderSequenceToWav(
                    tempDir.resolve("lvl-" + System.nanoTime() + ".wav"), 1.5f, List.of(
                            new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f))));
            float during = engine.getLevel();
            assertTrue(during > 0.002f, "level must track the ringing note, got " + during);
            // after a silent render the envelope must have decayed
            assertTrue(engine.renderSequenceToWav(
                    tempDir.resolve("lvl2-" + System.nanoTime() + ".wav"), 1.5f, List.of()));
            assertTrue(engine.getLevel() < 0.01f, "level must decay to silence, got " + engine.getLevel());
        }
    }

    @Test
    void extremeParamsStayInBoundsAndDeterministic() throws Exception {        WavAnalysis.Wav a = render(1f, 1f, 1f, 2f);
        WavAnalysis.Wav b = render(1f, 1f, 1f, 2f);
        float peak = WavAnalysis.peak(a, 0.0, 2.2);
        assertTrue(peak <= 0.99f, "output must be clamped, peak=" + peak);
        assertTrue(peak > 0.3f, "extreme settings must still produce audio, peak=" + peak);
        // determinism of the offline path
        assertEquals(a.samples().length, b.samples().length);
        for (int i = 0; i < a.samples().length; i++) {
            if (a.samples()[i] != b.samples()[i]) {
                fail("offline render must be deterministic at sample " + i);
            }
        }
    }

    private static long crossingsPerSecond(WavAnalysis.Wav wav) {
        int from = (int) (0.05 * wav.sampleRate());
        int to = (int) (0.5 * wav.sampleRate());
        long crossings = 0;
        float[] s = wav.samples();
        for (int i = from + 1; i < to; i++) {
            if ((s[i - 1] < 0) != (s[i] < 0)) {
                crossings++;
            }
        }
        return Math.round(crossings / 0.45);
    }

    /* ---- amp channels ------------------------------------------------------ */

    /**
     * Harmonic fizz: tone magnitudes above 2.4 kHz, where the cascaded
     * clipper's generated harmonics live (the string's own harmonics
     * dominate below that in every channel).
     */
    private float fizz(WavAnalysis.Wav wav) {
        float sum = 0f;
        for (double f : new double[]{2470.0, 3458.0, 4446.0, 5434.0}) {
            sum += WavAnalysis.toneMagnitude(wav, 0.05, 0.25, f);
        }
        return sum;
    }

    @Test
    void metalChannelAddsHarmonicBite() throws Exception {
        // deep clipping generates fizz the clean channel never has
        WavAnalysis.Wav clean = renderChannel(0, "clean");
        WavAnalysis.Wav metal = renderChannel(2, "metal");
        assertTrue(fizz(metal) > fizz(clean) * 2.0f,
                "max-drive metal must fatten the upper harmonics, fizz " + fizz(metal)
                        + " vs clean " + fizz(clean));
    }

    @Test
    void crunchSitsBetweenCleanAndMetal() throws Exception {
        WavAnalysis.Wav clean = renderChannel(0, "clean");
        WavAnalysis.Wav crunch = renderChannel(1, "crunch");
        WavAnalysis.Wav metal = renderChannel(2, "metal");
        float cleanFizz = fizz(clean);
        float crunchFizz = fizz(crunch);
        float metalFizz = fizz(metal);
        assertTrue(crunchFizz > cleanFizz && crunchFizz < metalFizz,
                "crunch must sit between clean and metal on fizz, " + cleanFizz
                        + " < " + crunchFizz + " < " + metalFizz);
    }

    @Test
    void metalChannelKeepsTheLevelSane() throws Exception {
        // cascaded stages compress hard; the makeup gain must not blast or drop
        WavAnalysis.Wav clean = renderChannel(0, "clean");
        WavAnalysis.Wav metal = renderChannel(2, "metal");
        float ratio = WavAnalysis.rms(metal, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.45f && ratio < 2.0f,
                "metal body must stay in sane range vs clean, ratio=" + ratio);
    }

    @Test
    void metalChannelStaysInBoundsAndDeterministic() throws Exception {
        WavAnalysis.Wav a = renderChannel(2, "metal-a");
        WavAnalysis.Wav b = renderChannel(2, "metal-b");
        assertTrue(WavAnalysis.peak(a, 0.0, 2.5) <= 1.0f,
                "metal output must respect the soft ceiling");
        for (int i = 0; i < a.samples().length; i++) {
            if (a.samples()[i] != b.samples()[i]) {
                fail("metal render must be deterministic at sample " + i);
            }
        }
    }
}
