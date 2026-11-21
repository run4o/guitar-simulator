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

    /* ---- fuzz --------------------------------------------------------- */

    /** Same fixture with fuzz params (amp section neutral). */
    private WavAnalysis.Wav renderFuzz(boolean enabled, float fuzz, float volume) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("fuzz-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setFuzz(enabled, fuzz, volume);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void fuzzSquaresTheWave() throws Exception {
        // high string, fret 7 (~494 Hz): above the fuzz input high-pass, so
        // the waveshaper actually saturates
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path cleanWav = tempDir.resolve("fuzz-clean-" + System.nanoTime() + ".wav");
        Path fuzzWav = tempDir.resolve("fuzz-fz-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setFuzz(false, 0.9f, 0.5f);
            assertTrue(engine.renderSequenceToWav(cleanWav, 2.2f, commands));
            engine.setFuzz(true, 0.9f, 0.5f);
            assertTrue(engine.renderSequenceToWav(fuzzWav, 2.2f, commands));
        }
        WavAnalysis.Wav clean = WavAnalysis.read(cleanWav);
        WavAnalysis.Wav fuzzed = WavAnalysis.read(fuzzWav);
        // measure right after the pluck: the string decays fast, so this is
        // where the shaper actually saturates
        float cleanCrest = WavAnalysis.peak(clean, 0.01, 0.10) / WavAnalysis.rms(clean, 0.01, 0.10);
        float fuzzCrest = WavAnalysis.peak(fuzzed, 0.01, 0.10) / WavAnalysis.rms(fuzzed, 0.01, 0.10);
        // an over-saturated wave is nearly square: crest factor approaches 1
        assertTrue(fuzzCrest < 1.4f,
                "fuzz must crush the wave toward a square: crest " + fuzzCrest);
        assertTrue(fuzzCrest < cleanCrest * 0.6f,
                "fuzz must be far flatter than the clean wave: " + cleanCrest + " -> " + fuzzCrest);
        // buzz = sustain: energy stays long after the clean note dies
        float fuzzedLate = WavAnalysis.rms(fuzzed, 0.2, 0.28);
        float cleanLate = WavAnalysis.rms(clean, 0.2, 0.28);
        assertTrue(fuzzedLate > 3.0f * cleanLate,
                "fuzz must sustain: clean=" + cleanLate + " fuzz=" + fuzzedLate);
    }

    /* ---- delay --------------------------------------------------------- */

    /** Same fixture with delay params (amp section neutral). */
    private WavAnalysis.Wav renderDelay(boolean enabled, float time, float feedback, float mix)
            throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
        Path wav = tempDir.resolve("delay-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setDelay(enabled, time, feedback, mix);
            assertTrue(engine.renderSequenceToWav(wav, 2.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedDelayLeavesTheSignalUntouched() throws Exception {
        // both renders bypassed: knob positions must not leak into the signal
        WavAnalysis.Wav knobsUp = renderDelay(false, 0.9f, 0.85f, 0.9f);
        WavAnalysis.Wav clean = renderDelay(false, 0.02f, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed delay must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void delayEchoesAfterTheDryTail() throws Exception {
        // string tau is far from dead by 0.42 s (mute at 0.3 s), so an echo at
        // 0.4 s with no feedback is almost pure wet content
        WavAnalysis.Wav clean = renderDelay(false, 0.4f, 0f, 0f);
        WavAnalysis.Wav echoed = renderDelay(true, 0.4f, 0f, 0.6f);
        float echo = WavAnalysis.rms(echoed, 0.42, 0.60);
        float tail = WavAnalysis.rms(clean, 0.42, 0.60);
        assertTrue(echo > 5.0f * tail,
                "the delay line must produce an echo: echo=" + echo + " tail=" + tail);
        // no echo should land inside the dry window (echo arrives at 0.4 s)
        float dryWin = WavAnalysis.rms(echoed, 0.05, 0.20);
        float dryClean = WavAnalysis.rms(clean, 0.05, 0.20);
        float ratio = dryWin / dryClean;
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "the dry window must stay untouched, ratio=" + ratio);
    }

    @Test
    void delayFeedbackRegeneratesTheRepeats() throws Exception {
        // 2nd echo region (0.5-0.7 s): fb 0.7 keeps ~half of the first echo,
        // fb 0.1 has already lost 99% of it
        WavAnalysis.Wav lowFb = renderDelay(true, 0.25f, 0.1f, 0.6f);
        WavAnalysis.Wav highFb = renderDelay(true, 0.25f, 0.7f, 0.6f);
        float secondLow = WavAnalysis.rms(lowFb, 0.55, 0.70);
        float secondHigh = WavAnalysis.rms(highFb, 0.55, 0.70);
        assertTrue(secondHigh > 4.0f * secondLow,
                "high feedback must sustain the repeats: " + secondHigh + " vs " + secondLow);
    }

    @Test
    void delayMixScalesTheWetLevel() throws Exception {
        WavAnalysis.Wav quiet = renderDelay(true, 0.25f, 0f, 0.2f);
        WavAnalysis.Wav loud = renderDelay(true, 0.25f, 0f, 0.9f);
        float low = WavAnalysis.rms(quiet, 0.28, 0.45);
        float high = WavAnalysis.rms(loud, 0.28, 0.45);
        float ratio = high / low;
        assertTrue(ratio > 2.5f && ratio < 6.5f,
                "mix must scale the echo level (~4.5x from 0.2 to 0.9): " + ratio);
    }

    /* ---- chorus --------------------------------------------------------- */

    /** Same fixture with chorus params (amp section neutral). */
    private WavAnalysis.Wav renderChorus(boolean enabled, float rate, float depth, float mix)
            throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f));
        Path wav = tempDir.resolve("chorus-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setChorus(enabled, rate, depth, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedChorusLeavesTheSignalUntouched() throws Exception {
        // both renders bypassed: knob positions must not leak into the signal
        WavAnalysis.Wav knobsUp = renderChorus(false, 0.9f, 0.9f, 0.9f);
        WavAnalysis.Wav clean = renderChorus(false, 0f, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed chorus must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void chorusSpreadsModulationSidebands() throws Exception {
        // the pitch-wobbling copy phase-modulates the note: energy spreads
        // to f0 ± k*rate (rate knob 1.0 -> 4 Hz). The 15 ms depth is deep
        // modulation, so the energy fans out over many sidebands - sum them.
        WavAnalysis.Wav clean = renderChorus(false, 0f, 0f, 0f);
        WavAnalysis.Wav thick = renderChorus(true, 1.0f, 0.9f, 1.0f);
        float f0 = WavAnalysis.detectPitch(clean, 0.02, 0.15);
        assertTrue(f0 > 100, "fixture must have a pitch: " + f0);
        float cleanSb = 0;
        float thickSb = 0;
        for (int k = 1; k <= 12; k++) {
            cleanSb += WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 4.0);
            thickSb += WavAnalysis.toneMagnitude(thick, 0.05, 0.30, f0 + k * 4.0);
        }
        assertTrue(thickSb > 3.0f * cleanSb,
                "chorus must create modulation sidebands: clean=" + cleanSb
                        + " chorus=" + thickSb);
    }

    @Test
    void chorusMixScalesTheWetCopy() throws Exception {
        // summed sideband energy scales with the wet copy's amplitude; the
        // f0 grid comes from a clean render (autocorrelation on the
        // modulated signal can wander)
        WavAnalysis.Wav clean = renderChorus(false, 0f, 0f, 0f);
        WavAnalysis.Wav quiet = renderChorus(true, 1.0f, 0.9f, 0.2f);
        WavAnalysis.Wav loud = renderChorus(true, 1.0f, 0.9f, 0.9f);
        float f0 = WavAnalysis.detectPitch(clean, 0.02, 0.15);
        // the decaying dry note leaks into every bin; subtract the clean
        // baseline so only the wet copy's sidebands are compared
        float low = 0;
        float high = 0;
        for (int k = 1; k <= 12; k++) {
            low += WavAnalysis.toneMagnitude(quiet, 0.05, 0.30, f0 + k * 4.0)
                    - WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 4.0);
            high += WavAnalysis.toneMagnitude(loud, 0.05, 0.30, f0 + k * 4.0)
                    - WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 4.0);
        }
        float ratio = high / low;
        // the wet copy scales the sidebands (in-band ratio shifts with the
        // deep-modulation leakage), so assert direction and strong growth
        assertTrue(ratio > 1.5f && ratio < 15.0f,
                "mix must scale the wobble copy: " + ratio);
    }
}
