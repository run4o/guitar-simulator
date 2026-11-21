package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 verification: the pedalboard, one pedal at a time, through
 * deterministic offline renders (the pedals sit before the amp FX, so both
 * paths share them).
 */
@Tag("phase6")
class Phase6PedalTest {

    private static final float SR = 44100.0f;

    @TempDir
    Path tempDir;

    /** Plucked note, choked at 0.3 s — a known envelope for pedal math. */
    private WavAnalysis.Wav render(boolean boost, float gainDb) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("pedal-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f); // amp section neutral
            engine.setBoost(boost, gainDb);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedPedalLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav clean = render(false, 12f);
        WavAnalysis.Wav boosted = render(true, 0f);
        // 0 dB boost = unity gain: the two renders must match within a hair
        float ratio = WavAnalysis.rms(boosted, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "0 dB boost must be unity gain, ratio=" + ratio);
    }

    @Test
    void boostRaisesLevelWithoutDistorting() throws Exception {
        // +12 dB = x3.98 amplitude; the amp section is neutral (drive 0,
        // master 1) and the boosted peak stays below the 0.98 clamp
        WavAnalysis.Wav clean = render(false, 0f);
        WavAnalysis.Wav boosted = render(true, 12f);
        float cleanRms = WavAnalysis.rms(clean, 0.05, 0.25);
        float boostedRms = WavAnalysis.rms(boosted, 0.05, 0.25);
        float ratio = boostedRms / cleanRms;
        assertTrue(ratio > 3.2f && ratio < 4.8f,
                "+12 dB boost must scale by ~x3.98, ratio=" + ratio);
        // a clean transistor stage adds no clipping: crest factor unchanged
        float cleanCrest = WavAnalysis.peak(clean, 0.05, 0.25) / cleanRms;
        float boostedCrest = WavAnalysis.peak(boosted, 0.05, 0.25) / boostedRms;
        assertTrue(Math.abs(boostedCrest - cleanCrest) < 0.15f,
                "boost must not change the waveform shape: crest " + cleanCrest + " vs " + boostedCrest);
    }

    /* ---- overdrive --------------------------------------------------- */

    /** Same fixture with overdrive params (amp section neutral). */
    private WavAnalysis.Wav renderOd(boolean enabled, float drive, float tone) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("od-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setOverdrive(enabled, drive, tone);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedOverdriveLeavesTheSignalUntouched() throws Exception {
        // both renders bypassed: knob positions must not leak into the signal
        WavAnalysis.Wav knobsUp = renderOd(false, 0.8f, 0.8f);
        WavAnalysis.Wav knobsZero = renderOd(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(knobsZero, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed overdrive must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void overdriveAddsSustainedGrit() throws Exception {
        WavAnalysis.Wav clean = renderOd(false, 0f, 0f);
        WavAnalysis.Wav driven = renderOd(true, 0.8f, 0.5f);
        float cleanLate = WavAnalysis.rms(clean, 0.15, 0.28);
        float drivenLate = WavAnalysis.rms(driven, 0.15, 0.28);
        // clipping fattens the decaying note: more energy late in the envelope
        assertTrue(drivenLate > 1.5f * cleanLate,
                "overdrive must fatten the decay: clean=" + cleanLate + " driven=" + drivenLate);
        // and it must change the waveform shape (clip): crest factor drops
        float cleanCrest = WavAnalysis.peak(clean, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        float drivenCrest = WavAnalysis.peak(driven, 0.05, 0.25) / WavAnalysis.rms(driven, 0.05, 0.25);
        assertTrue(drivenCrest < cleanCrest * 0.9f,
                "overdrive must clip the peaks: crest " + cleanCrest + " -> " + drivenCrest);
    }

    /* ---- distortion --------------------------------------------------- */

    /** Same fixture with distortion params (amp section neutral). */
    private WavAnalysis.Wav renderDist(boolean enabled, float drive, float tone, float level) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("dist-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setDistortion(enabled, drive, tone, level);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void distortionClampsToFlatPeaks() throws Exception {
        WavAnalysis.Wav clean = renderDist(false, 0f, 0f, 0.5f);
        WavAnalysis.Wav crushed = renderDist(true, 0.9f, 0.5f, 0.5f);
        float cleanCrest = WavAnalysis.peak(clean, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        float crushedCrest = WavAnalysis.peak(crushed, 0.05, 0.25) / WavAnalysis.rms(crushed, 0.05, 0.25);
        // a hard-clipped square-ish wave is far flatter than a plucked string
        assertTrue(crushedCrest < cleanCrest * 0.7f,
                "distortion must flatten the wave hard: crest " + cleanCrest + " -> " + crushedCrest);
        // and it must be far louder than the overdrive's gentle grit at the
        // same knob positions would be
        float crushedLate = WavAnalysis.rms(crushed, 0.15, 0.28);
        float cleanLate = WavAnalysis.rms(clean, 0.15, 0.28);
        assertTrue(crushedLate > 2.0f * cleanLate,
                "distortion must sustain heavily: clean=" + cleanLate + " crushed=" + crushedLate);
    }

    @Test
    void distortionLevelScalesOutput() throws Exception {
        WavAnalysis.Wav full = renderDist(true, 0.9f, 0.5f, 0.9f);
        WavAnalysis.Wav quiet = renderDist(true, 0.9f, 0.5f, 0.3f);
        float fullRms = WavAnalysis.rms(full, 0.05, 0.25);
        float quietRms = WavAnalysis.rms(quiet, 0.05, 0.25);
        float ratio = quietRms / fullRms;
        // level 0.3/0.9 = 0.5 vs 0.925 output scales: ~0.54 ratio
        assertTrue(ratio > 0.35f && ratio < 0.75f, "level knob must scale the output, ratio=" + ratio);
    }
}
