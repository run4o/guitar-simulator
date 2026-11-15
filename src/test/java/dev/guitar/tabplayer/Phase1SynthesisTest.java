package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 1 verification: Karplus-Strong synthesis is checked by rendering
 * command sequences offline to WAV and measuring pitch / decay with WavAnalysis.
 *
 * Pitch tolerances are generous (~2-4%) because the one-pole loop filter adds
 * roughly one sample of phase delay at the fundamental; tuning compensation
 * is a later refinement.
 */
@Tag("phase1")
class Phase1SynthesisTest {

    private static final float SR = 44100.0f;

    @TempDir
    Path tempDir;

    /* ---- helpers ---------------------------------------------------- */

    private WavAnalysis.Wav render(List<RenderCommand> commands, float durationSeconds) throws Exception {
        Path wav = tempDir.resolve("render-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256), "engine init must succeed");
            assertTrue(engine.renderSequenceToWav(wav, durationSeconds, commands), "render must succeed");
        }
        WavAnalysis.Wav parsed = WavAnalysis.read(wav);
        assertEquals(SR, parsed.sampleRate(), "sample rate must match");
        assertEquals(Math.round(durationSeconds * SR), parsed.samples().length, "sample count must match");
        return parsed;
    }

    private static List<RenderCommand> pluck(int stringIndex, int fret, float velocity, boolean palmMute) {
        List<RenderCommand> list = new ArrayList<>();
        list.add(new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, stringIndex, fret, velocity, palmMute ? 1f : 0f));
        return list;
    }

    private static void assertNear(float actual, float expected, float tolerance, String label) {
        if (Math.abs(actual - expected) > tolerance) {
            fail(label + ": expected ~" + expected + " ±" + tolerance + " but got " + actual);
        }
    }

    /* ---- pitch accuracy --------------------------------------------- */

    @Test
    void openLowEPlaysAt82Hz() throws Exception {
        WavAnalysis.Wav wav = render(pluck(0, 0, 0.9f, false), 2.2f);
        assertNear(WavAnalysis.detectPitch(wav, 0.5, 1.5), 82.41f, 4.0f, "open low E pitch");
        assertTrue(WavAnalysis.peak(wav, 0.0, 0.1) > 0.05f, "note must have audible attack");
    }

    @Test
    void fret12OnLowEIsAnOctaveUp() throws Exception {
        WavAnalysis.Wav wav = render(pluck(0, 12, 0.9f, false), 2.0f);
        assertNear(WavAnalysis.detectPitch(wav, 0.4, 1.2), 164.81f, 6.0f, "fret 12 pitch");
    }

    @Test
    void openHighEPlaysAt330Hz() throws Exception {
        WavAnalysis.Wav wav = render(pluck(5, 0, 0.9f, false), 1.5f);
        assertNear(WavAnalysis.detectPitch(wav, 0.3, 1.0), 329.63f, 10.0f, "open high E pitch");
    }

    @Test
    void customTuningIsApplied() throws Exception {
        // Drop D: low string down a whole step to D2 = 73.42 Hz
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_SET_TUNING, 0, 0, 73.42f, 0f),
                new RenderCommand(0.05f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        WavAnalysis.Wav wav = render(commands, 2.0f);
        assertNear(WavAnalysis.detectPitch(wav, 0.5, 1.5), 73.42f, 4.0f, "drop D pitch");
    }

    /* ---- decay / articulations --------------------------------------- */

    @Test
    void palmMuteDecaysMuchFasterThanOpenNote() throws Exception {
        WavAnalysis.Wav open = render(pluck(0, 0, 0.9f, false), 2.0f);
        WavAnalysis.Wav muted = render(pluck(0, 0, 0.9f, true), 2.0f);
        float openLate = WavAnalysis.rms(open, 0.9, 1.3);
        float mutedLate = WavAnalysis.rms(muted, 0.9, 1.3);
        assertTrue(mutedLate < 0.1f * openLate,
                "palm mute must be much quieter late in the note: muted=" + mutedLate
                        + " open=" + openLate);
    }

    @Test
    void muteChokesTheString() throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        WavAnalysis.Wav wav = render(commands, 1.8f);
        float early = WavAnalysis.rms(wav, 0.1, 0.25);
        float late = WavAnalysis.rms(wav, 1.2, 1.6);
        assertTrue(late < 0.01f * early,
                "mute must choke the string: early=" + early + " late=" + late);
    }

    @Test
    void hammerOnRetunesToTargetFret() throws Exception {
        // pluck fret 3 (G2 = 98.0 Hz), hammer-on to fret 5 (A2 = 110.0 Hz)
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 3, 0.9f, 0f),
                new RenderCommand(0.2f, GuitarEngine.EVENT_LEGATO, 0, 5, 0f, 0f));
        WavAnalysis.Wav wav = render(commands, 1.8f);
        assertNear(WavAnalysis.detectPitch(wav, 0.02, 0.15), 98.0f, 5.0f, "pre-hammer pitch");
        assertNear(WavAnalysis.detectPitch(wav, 0.7, 1.2), 110.0f, 5.0f, "post-hammer pitch");
    }

    @Test
    void bendGlidesUpOneSemitone() throws Exception {
        // low E 82.41 Hz bent up one semitone -> 87.31 Hz
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.02f, GuitarEngine.EVENT_BEND, 0, 0, 1.0f, 0f));
        WavAnalysis.Wav wav = render(commands, 1.8f);
        assertNear(WavAnalysis.detectPitch(wav, 0.02, 0.12), 82.41f, 5.0f, "pre-bend pitch");
        assertNear(WavAnalysis.detectPitch(wav, 0.6, 1.2), 87.31f, 5.0f, "post-bend pitch");
    }

    @Test
    void noteRingsOutWithSlowDecay() throws Exception {
        WavAnalysis.Wav wav = render(pluck(0, 0, 0.9f, false), 2.0f);
        float early = WavAnalysis.rms(wav, 0.05, 0.2);
        float mid = WavAnalysis.rms(wav, 0.9, 1.05);
        // T60 = 3s: after ~0.9s the RMS should be roughly exp(-6.9*0.9/3) ≈ 12.6% — allow generous band
        assertTrue(mid > 0.02f * early && mid < 0.5f * early,
                "natural decay curve out of expected range: early=" + early + " mid=" + mid);
    }
}
