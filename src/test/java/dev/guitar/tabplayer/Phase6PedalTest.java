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

    /* ---- flanger --------------------------------------------------------- */

    /** Same fixture with flanger params (amp section neutral). */
    private WavAnalysis.Wav renderFlanger(boolean enabled, float rate, float depth,
                                           float feedback, float mix) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f));
        Path wav = tempDir.resolve("flanger-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setFlanger(enabled, rate, depth, feedback, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedFlangerLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderFlanger(false, 0.9f, 0.9f, 0.8f, 0.9f);
        WavAnalysis.Wav clean = renderFlanger(false, 0f, 0f, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed flanger must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void flangerFansSidebandsLikeTheChorus() throws Exception {
        // the swept sub-15 ms delay phase-modulates the note (rate knob 1.0 -> 8 Hz); sum the sidebands over f0 +/- k*2 Hz
        WavAnalysis.Wav clean = renderFlanger(false, 0f, 0f, 0f, 0f);
        WavAnalysis.Wav flanged = renderFlanger(true, 1.0f, 0.9f, 0.2f, 1.0f);
        float f0 = WavAnalysis.detectPitch(clean, 0.02, 0.15);
        assertTrue(f0 > 100, "fixture must have a pitch: " + f0);
        float cleanSb = 0;
        float flangedSb = 0;
        for (int k = 1; k <= 12; k++) {
            cleanSb += WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 8.0);
            flangedSb += WavAnalysis.toneMagnitude(flanged, 0.05, 0.30, f0 + k * 8.0);
        }
        assertTrue(flangedSb > 3.0f * cleanSb,
                "flanger must create modulation sidebands: clean=" + cleanSb
                        + " flanger=" + flangedSb);
    }

    @Test
    void flangerFeedbackDeepensTheComb() throws Exception {
        // the regenerating loop keeps the flange ringing after the dry
        // note decays (0.15-0.30 s is where the loop's tail dominates)
        WavAnalysis.Wav noFb = renderFlanger(true, 1.0f, 0.9f, 0.0f, 1.0f);
        WavAnalysis.Wav maxFb = renderFlanger(true, 1.0f, 0.9f, 0.9f, 1.0f);
        float low = WavAnalysis.rms(noFb, 0.15, 0.30);
        float high = WavAnalysis.rms(maxFb, 0.15, 0.30);
        assertTrue(high > 1.6f * low,
                "feedback must keep the comb ringing: " + high + " vs " + low);
    }

    /* ---- phaser ------------------------------------------------------- */

    /** Same fixture with phaser params (amp section neutral). */
    private WavAnalysis.Wav renderPhaser(boolean enabled, float rate, float depth,
                                         float regen, float mix) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("phaser-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setPhaser(enabled, rate, depth, regen, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedPhaserLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderPhaser(false, 0.8f, 0.8f, 0.6f, 0.8f);
        WavAnalysis.Wav clean = renderPhaser(false, 0f, 0f, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed phaser must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void phaserWithMixZeroIsDry() throws Exception {
        // out = in - wet*mix, so mix 0 is an exact passthrough even with the
        // pedal engaged and every knob turned up
        WavAnalysis.Wav clean = renderPhaser(false, 0f, 0f, 0f, 0f);
        WavAnalysis.Wav dry = renderPhaser(true, 0.6f, 0.8f, 0.85f, 0.0f);
        float ratio = WavAnalysis.rms(dry, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "mix 0 must pass the signal untouched, ratio=" + ratio);
    }

    @Test
    void phaserReshapesTheTone() throws Exception {
        // the phase-shifted copy recombined against the dry signal changes
        // the note's energy (swept notches dip and boost as they travel)
        WavAnalysis.Wav clean = renderPhaser(false, 0f, 0f, 0f, 0f);
        WavAnalysis.Wav phased = renderPhaser(true, 0.6f, 0.8f, 0.0f, 1.0f);
        float ratio = WavAnalysis.rms(phased, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio < 0.9f || ratio > 1.1f,
                "phaser must reshape the note, ratio=" + ratio);
    }

    @Test
    void phaserRegenDeepensTheSweep() throws Exception {
        // regeneration feeds the phase-shifted copy back into the stage
        // stack, so the two settings must render measurably different
        WavAnalysis.Wav noRegen = renderPhaser(true, 0.6f, 0.8f, 0.0f, 1.0f);
        WavAnalysis.Wav maxRegen = renderPhaser(true, 0.6f, 0.8f, 0.85f, 1.0f);
        float low = WavAnalysis.rms(noRegen, 0.05, 0.30);
        float high = WavAnalysis.rms(maxRegen, 0.05, 0.30);
        float ratio = high / low;
        assertTrue(ratio < 0.85f || ratio > 1.15f,
                "regeneration must change the render measurably, ratio=" + ratio);
    }

    /* ---- tremolo ------------------------------------------------------- */

    /** Same fixture with tremolo params (amp section neutral). */
    private WavAnalysis.Wav renderTremolo(boolean enabled, float rate, float depth) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("tremolo-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setTremolo(enabled, rate, depth);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedTremoloLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderTremolo(false, 0.8f, 0.8f);
        WavAnalysis.Wav clean = renderTremolo(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed tremolo must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void tremoloWithZeroDepthIsUnity() throws Exception {
        // gain = 1 - depth + depth*(...): depth 0 pins the gain at 1.0
        WavAnalysis.Wav clean = renderTremolo(false, 0f, 0f);
        WavAnalysis.Wav zeroDepth = renderTremolo(true, 1.0f, 0.0f);
        float ratio = WavAnalysis.rms(zeroDepth, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "depth 0 tremolo must be unity gain, ratio=" + ratio);
    }

    @Test
    void tremoloDucksTheAverageLevel() throws Exception {
        // average gain over many LFO cycles = 1 - depth/2; depth 0.8 at
        // 16 Hz averages ~4 cycles in the 0.05-0.30 s window
        WavAnalysis.Wav clean = renderTremolo(false, 0f, 0f);
        WavAnalysis.Wav pulsed = renderTremolo(true, 1.0f, 0.8f);
        float ratio = WavAnalysis.rms(pulsed, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.45f && ratio < 0.75f,
                "tremolo must duck the average level to ~0.6, ratio=" + ratio);
    }

    @Test
    void tremoloFansAmSidebands() throws Exception {
        // amplitude modulation puts energy at f0 +/- k*rate; rate 1.0
        // (16 Hz) fans sidebands every 16 Hz, summed over k = 1..6
        WavAnalysis.Wav clean = renderTremolo(false, 0f, 0f);
        WavAnalysis.Wav pulsed = renderTremolo(true, 1.0f, 0.8f);
        float f0 = WavAnalysis.detectPitch(clean, 0.02, 0.15);
        assertTrue(f0 > 100, "fixture must have a pitch: " + f0);
        float cleanSb = 0;
        float tremSb = 0;
        for (int k = 1; k <= 6; k++) {
            cleanSb += WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 16.0f);
            tremSb += WavAnalysis.toneMagnitude(pulsed, 0.05, 0.30, f0 + k * 16.0f);
        }
        assertTrue(tremSb > 2.0f * cleanSb,
                "tremolo must create AM sidebands: clean=" + cleanSb + " trem=" + tremSb);
    }

    /* ---- vibrato -------------------------------------------------------- */

    /** Same fixture with vibrato params (amp section neutral). */
    private WavAnalysis.Wav renderVibrato(boolean enabled, float rate, float depth,
                                          float mix) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("vibrato-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setVibrato(enabled, rate, depth, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedVibratoLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderVibrato(false, 0.8f, 0.8f, 0.8f);
        WavAnalysis.Wav clean = renderVibrato(false, 0f, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed vibrato must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void vibratoWithMixZeroIsDry() throws Exception {
        // out = in + (wet - in)*mix: mix 0 is an exact passthrough even
        // with the pedal engaged and every knob turned up
        WavAnalysis.Wav clean = renderVibrato(false, 0f, 0f, 0f);
        WavAnalysis.Wav dry = renderVibrato(true, 0.8f, 0.8f, 0.0f);
        float ratio = WavAnalysis.rms(dry, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "mix 0 vibrato must pass the signal untouched, ratio=" + ratio);
    }

    @Test
    void vibratoWobblesThePitch() throws Exception {
        // the wet-only pitch-wobbled copy fans FM sidebands around the note
        // (rate knob 1.0 -> 4 Hz), summed over f0 +/- k*4 Hz
        WavAnalysis.Wav clean = renderVibrato(false, 0f, 0f, 0f);
        WavAnalysis.Wav wob = renderVibrato(true, 1.0f, 0.8f, 1.0f);
        float f0 = WavAnalysis.detectPitch(clean, 0.02, 0.15);
        assertTrue(f0 > 100, "fixture must have a pitch: " + f0);
        float cleanSb = 0;
        float vibSb = 0;
        for (int k = 1; k <= 12; k++) {
            cleanSb += WavAnalysis.toneMagnitude(clean, 0.05, 0.30, f0 + k * 4.0f);
            vibSb += WavAnalysis.toneMagnitude(wob, 0.05, 0.30, f0 + k * 4.0f);
        }
        assertTrue(vibSb > 3.0f * cleanSb,
                "vibrato must create FM sidebands: clean=" + cleanSb + " vibrato=" + vibSb);
    }

    @Test
    void vibratoDepthScalesTheWobble() throws Exception {
        // a wider delay sweep bends the pitch further, spreading energy over
        // MORE sidebands (wideband FM) — sum over a wide fan so the deeper
        // setting's total sideband energy wins
        WavAnalysis.Wav light = renderVibrato(true, 1.0f, 0.2f, 1.0f);
        WavAnalysis.Wav deep = renderVibrato(true, 1.0f, 0.9f, 1.0f);
        float f0 = 494.0f; // string 5 fret 7
        float lightSb = 0;
        float deepSb = 0;
        for (int k = 1; k <= 40; k++) {
            lightSb += WavAnalysis.toneMagnitude(light, 0.05, 0.30, f0 + k * 4.0f);
            deepSb += WavAnalysis.toneMagnitude(deep, 0.05, 0.30, f0 + k * 4.0f);
        }
        assertTrue(deepSb > 1.5f * lightSb,
                "depth must scale the pitch wobble: light=" + lightSb + " deep=" + deepSb);
    }

    /* ---- compressor ------------------------------------------------------ */

    /**
     * Dynamic fixture: a loud pluck at t=0 (choked at 0.3 s), then a quiet
     * pluck at 0.5 s — the compressor must even out the difference.
     */
    private WavAnalysis.Wav renderComp(boolean enabled, float sustain, float level) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f),
                new RenderCommand(0.5f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.05f, 0f));
        Path wav = tempDir.resolve("comp-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setCompressor(enabled, sustain, level);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedCompressorLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderComp(false, 0.8f, 0.8f);
        WavAnalysis.Wav clean = renderComp(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed compressor must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void compressorDucksTheLoudNote() throws Exception {
        // sustain 0 barely engages (threshold -10 dB, ratio 2:1); sustain 1
        // ducks the loud note hard
        WavAnalysis.Wav mild = renderComp(true, 0.0f, 0.5f);
        WavAnalysis.Wav heavy = renderComp(true, 1.0f, 0.5f);
        float ratio = WavAnalysis.rms(heavy, 0.05, 0.25) / WavAnalysis.rms(mild, 0.05, 0.25);
        assertTrue(ratio < 0.9f,
                "high sustain must duck the loud note: ratio=" + ratio);
    }

    @Test
    void compressorEvensOutDynamics() throws Exception {
        // the quiet pluck rides closer to the loud one after compression
        WavAnalysis.Wav mild = renderComp(true, 0.0f, 0.5f);
        WavAnalysis.Wav heavy = renderComp(true, 1.0f, 0.5f);
        float mildSpread = WavAnalysis.rms(mild, 0.55, 0.75) / WavAnalysis.rms(mild, 0.05, 0.25);
        float heavySpread = WavAnalysis.rms(heavy, 0.55, 0.75) / WavAnalysis.rms(heavy, 0.05, 0.25);
        assertTrue(heavySpread > mildSpread * 1.2f,
                "compression must even out the two notes: mild=" + mildSpread
                        + " heavy=" + heavySpread);
    }

    @Test
    void compressorLevelScalesTheMakeup() throws Exception {
        WavAnalysis.Wav low = renderComp(true, 0.8f, 0.2f);
        WavAnalysis.Wav high = renderComp(true, 0.8f, 1.0f);
        float ratio = WavAnalysis.rms(high, 0.05, 0.25) / WavAnalysis.rms(low, 0.05, 0.25);
        assertTrue(ratio > 2.0f,
                "level must restore the output volume, ratio=" + ratio);
    }

    /* ---- EQ --------------------------------------------------------------- */

    /** Fixture with a chosen string/fret and EQ params (amp section neutral). */
    private WavAnalysis.Wav renderEq(int stringIndex, int fret, boolean enabled,
                                     float bass, float mid, float treble) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, stringIndex, fret, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, stringIndex, 0, 0f, 0f));
        Path wav = tempDir.resolve("eq-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setEq(enabled, bass, mid, treble);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedEqLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsPinned = renderEq(0, 0, false, 1.0f, 0.0f, 1.0f);
        WavAnalysis.Wav clean = renderEq(0, 0, false, 0.5f, 0.5f, 0.5f);
        float ratio = WavAnalysis.rms(knobsPinned, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed EQ must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void flatEqIsAnExactIdentity() throws Exception {
        // the three bands recombine to the signal when all knobs are flat
        WavAnalysis.Wav clean = renderEq(0, 0, false, 0.5f, 0.5f, 0.5f);
        WavAnalysis.Wav flat = renderEq(0, 0, true, 0.5f, 0.5f, 0.5f);
        float ratio = WavAnalysis.rms(flat, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "flat EQ must be an identity, ratio=" + ratio);
    }

    @Test
    void bassBoostRaisesTheLowEnd() throws Exception {
        // open low E (82 Hz) sits mostly in the 250 Hz bass band — its
        // harmonics spill into mid/treble, so the lift lands below the
        // full +0.75 band gain
        WavAnalysis.Wav clean = renderEq(0, 0, true, 0.5f, 0.5f, 0.5f);
        WavAnalysis.Wav boosted = renderEq(0, 0, true, 1.0f, 0.5f, 0.5f);
        float ratio = WavAnalysis.rms(boosted, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 1.2f,
                "bass boost must lift the low note, ratio=" + ratio);
    }

    @Test
    void midBoostRaisesTheMiddle() throws Exception {
        // fret 7 on the high e (494 Hz) lives mostly in the mid band
        WavAnalysis.Wav clean = renderEq(5, 7, true, 0.5f, 0.5f, 0.5f);
        WavAnalysis.Wav boosted = renderEq(5, 7, true, 0.5f, 1.0f, 0.5f);
        float ratio = WavAnalysis.rms(boosted, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 1.15f,
                "mid boost must lift the mid note, ratio=" + ratio);
    }

    @Test
    void trebleBoostBrightensTheNote() throws Exception {
        // the 4th harmonic of 494 Hz (1976 Hz) sits in the treble band
        WavAnalysis.Wav clean = renderEq(5, 7, true, 0.5f, 0.5f, 0.5f);
        WavAnalysis.Wav bright = renderEq(5, 7, true, 0.5f, 0.5f, 1.0f);
        float flatMag = WavAnalysis.toneMagnitude(clean, 0.05, 0.30, 1976.0f);
        float boostedMag = WavAnalysis.toneMagnitude(bright, 0.05, 0.30, 1976.0f);
        assertTrue(boostedMag > flatMag * 1.15f,
                "treble boost must lift the high harmonics: flat=" + flatMag
                        + " boosted=" + boostedMag);
    }

    /* ---- wah -------------------------------------------------------------- */

    /** Same fixture with wah params (amp section neutral). */
    private WavAnalysis.Wav renderWah(boolean enabled, float rate, float center,
                                      float resonance) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("wah-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setWah(enabled, rate, center, resonance);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedWahLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsPinned = renderWah(false, 1.0f, 1.0f, 1.0f);
        WavAnalysis.Wav clean = renderWah(false, 0f, 0.5f, 0.3f);
        float ratio = WavAnalysis.rms(knobsPinned, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed wah must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void staticWahPassesItsCenter() throws Exception {
        // a static rocker position (rate 0) tuned onto the note keeps its
        // fundamental (494 Hz, center knob 0.334) and sheds the harmonics
        WavAnalysis.Wav clean = renderWah(false, 0f, 0.334f, 0.3f);
        WavAnalysis.Wav on = renderWah(true, 0f, 0.334f, 0.526f);
        float ratio = WavAnalysis.rms(on, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.3f && ratio < 0.98f,
                "the wah must keep the note but shed its harmonics, ratio=" + ratio);
    }

    @Test
    void wahAwayFromTheNoteKillsIt() throws Exception {
        // center ~1 kHz (knob 0.594): the 494 Hz fundamental is rejected —
        // only the 2nd harmonic (988 Hz) near the band survives
        WavAnalysis.Wav on = renderWah(true, 0f, 0.334f, 0.526f);
        WavAnalysis.Wav away = renderWah(true, 0f, 0.594f, 0.526f);
        float ratio = WavAnalysis.rms(away, 0.05, 0.30) / WavAnalysis.rms(on, 0.05, 0.30);
        assertTrue(ratio < 0.5f,
                "the wah must reject the note when centered away, ratio=" + ratio);
    }

    @Test
    void wahLfoSweepsTheBand() throws Exception {
        // with the LFO running the band leaves the note's frequency part of
        // the cycle, so the swept render is quieter than a static filter
        // parked exactly on the note
        WavAnalysis.Wav parked = renderWah(true, 0f, 0.334f, 0.526f);
        WavAnalysis.Wav swept = renderWah(true, 1.0f, 0.334f, 0.526f);
        float ratio = WavAnalysis.rms(swept, 0.05, 0.30) / WavAnalysis.rms(parked, 0.05, 0.30);
        assertTrue(ratio < 0.85f && ratio > 0.2f,
                "the LFO must sweep the band across the note, ratio=" + ratio);
    }

    /* ---- noise gate ------------------------------------------------------- */

    /** Fixture: a pluck left to decay naturally (no mute — the gate must
     * cut the ring-out the gate-less render still carries). */
    private WavAnalysis.Wav renderGate(boolean enabled, float threshold, float release) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("gate-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setGate(enabled, threshold, release);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    /** Two-pluck fixture: a loud note, then a quiet one the gate must
     * silence (its envelope sits below the threshold). */
    private WavAnalysis.Wav renderGateQuiet(boolean enabled, float threshold, float release) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f),
                new RenderCommand(0.5f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.05f, 0f));
        Path wav = tempDir.resolve("gateq-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setGate(enabled, threshold, release);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    /** Fixture: a single pluck at t=0.5 — the gate starts closed, so the
     * RELEASE knob shapes how fast the note fades in. */
    private WavAnalysis.Wav renderGateLate(boolean enabled, float threshold, float release) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0.5f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("gatel-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setGate(enabled, threshold, release);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedGateLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderGate(false, 0.8f, 0.9f);
        WavAnalysis.Wav clean = renderGate(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed gate must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void gatePassesTheNote() throws Exception {
        // threshold 0.2 (env threshold ~0.03): the note's tracked envelope
        // (~0.05 — the 5 ms follower under-tracks each half-cycle) sits
        // above it, so gain stays 1.0 through the window
        WavAnalysis.Wav clean = renderGate(false, 0f, 0f);
        WavAnalysis.Wav gated = renderGate(true, 0.2f, 0.3f);
        float ratio = WavAnalysis.rms(gated, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.9f && ratio < 1.1f,
                "the gate must pass the note untouched, ratio=" + ratio);
    }

    @Test
    void gateSilencesNotesBelowTheThreshold() throws Exception {
        // threshold 0.2 (env threshold ~0.03): the quiet pluck's envelope
        // (~0.003) sits far below it, so the gate must mute it
        WavAnalysis.Wav clean = renderGateQuiet(false, 0f, 0f);
        WavAnalysis.Wav gated = renderGateQuiet(true, 0.2f, 0.3f);
        float ratio = WavAnalysis.rms(gated, 0.55, 0.75) / WavAnalysis.rms(clean, 0.55, 0.75);
        assertTrue(ratio < 0.3f,
                "the gate must silence the quiet pluck, ratio=" + ratio);
    }

    @Test
    void gateReleaseSmoothsTheClosure() throws Exception {
        // threshold 0.2: the natural decay crosses the env threshold around
        // 0.3 s while still audible — by 0.40-0.55 s the fast gate (10 ms
        // close) is fully shut while the slow one (500 ms) still leaks the
        // decaying tail
        WavAnalysis.Wav fast = renderGate(true, 0.2f, 0.0f);
        WavAnalysis.Wav slow = renderGate(true, 0.2f, 0.9f);
        float shortTail = WavAnalysis.rms(fast, 0.40, 0.55);
        float longTail = WavAnalysis.rms(slow, 0.40, 0.55);
        assertTrue(longTail > 2.0f * shortTail,
                "release must smooth the gate closure: fast=" + shortTail + " slow=" + longTail);
    }

    /* ---- octave ----------------------------------------------------------- */

    /** Same fixture with octave params (amp section neutral). */
    private WavAnalysis.Wav renderOctave(boolean enabled, float mode, float mix) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("oct-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setOctave(enabled, mode, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedOctaveLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderOctave(false, 1.0f, 1.0f);
        WavAnalysis.Wav clean = renderOctave(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed octave must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void octaveDownHalvesThePitch() throws Exception {
        // mode 0 at full mix: the signal becomes the octave-down flip-flop
        WavAnalysis.Wav clean = renderOctave(false, 0f, 0f);
        WavAnalysis.Wav down = renderOctave(true, 0.0f, 1.0f);
        float f0 = WavAnalysis.detectPitch(clean, 0.05, 0.30);
        float downF0 = WavAnalysis.detectPitch(down, 0.05, 0.30);
        assertTrue(downF0 < f0 * 0.6f,
                "octave-down must halve the pitch: f0=" + f0 + " down=" + downF0);
    }

    @Test
    void octaveUpDoublesThePitch() throws Exception {
        // mode 1 at full mix: the signal becomes the rectified octave-up
        WavAnalysis.Wav clean = renderOctave(false, 0f, 0f);
        WavAnalysis.Wav up = renderOctave(true, 1.0f, 1.0f);
        float f0 = WavAnalysis.detectPitch(clean, 0.05, 0.30);
        float upF0 = WavAnalysis.detectPitch(up, 0.05, 0.30);
        assertTrue(upF0 > f0 * 1.6f,
                "octave-up must double the pitch: f0=" + f0 + " up=" + upF0);
    }

    @Test
    void octaveWithMixZeroIsDry() throws Exception {
        // out = in + (shifted - in)*mix: mix 0 is an exact passthrough even
        // with the pedal engaged and the mode knob anywhere
        WavAnalysis.Wav clean = renderOctave(false, 0f, 0f);
        WavAnalysis.Wav dry = renderOctave(true, 1.0f, 0.0f);
        float ratio = WavAnalysis.rms(dry, 0.05, 0.30) / WavAnalysis.rms(clean, 0.05, 0.30);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "mix 0 octave must pass the signal untouched, ratio=" + ratio);
    }

    /* ---- looper ----------------------------------------------------------- */

    /** Fixture: a single pluck left to decay (no mute) — the looper must
     * replay the loud early content one loop length later. */
    private WavAnalysis.Wav renderLooper(boolean enabled, float length, float level) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("looper-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setLooper(enabled, length, level);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedLooperLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderLooper(false, 0.8f, 0.9f);
        WavAnalysis.Wav clean = renderLooper(false, 0f, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed looper must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void dryAtTheStart() throws Exception {
        // the loop buffer starts empty: the first 50 ms must be dry
        WavAnalysis.Wav clean = renderLooper(false, 0f, 0f);
        WavAnalysis.Wav looped = renderLooper(true, 0.0f, 1.0f);
        float ratio = WavAnalysis.rms(looped, 0.00, 0.05) / WavAnalysis.rms(clean, 0.00, 0.05);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "an empty loop must pass the signal dry, ratio=" + ratio);
    }

    @Test
    void loopReplaysThePast() throws Exception {
        // a 0.25 s loop (length knob 0) replays the loud early pluck one
        // loop later: 0.30-0.45 s carries content recorded at 0.05-0.20 s
        WavAnalysis.Wav clean = renderLooper(false, 0f, 0f);
        WavAnalysis.Wav looped = renderLooper(true, 0.0f, 1.0f);
        float ratio = WavAnalysis.rms(looped, 0.30, 0.45) / WavAnalysis.rms(clean, 0.30, 0.45);
        assertTrue(ratio > 2.0f,
                "the loop must replay the recorded past, ratio=" + ratio);
    }

    @Test
    void looperLevelScalesTheLoop() throws Exception {
        // the looped content rides on top of the dry signal, so the LEVEL
        // knob scales how much past comes back (level 0 = dry only)
        WavAnalysis.Wav low = renderLooper(true, 0.0f, 0.0f);
        WavAnalysis.Wav high = renderLooper(true, 0.0f, 1.0f);
        float ratio = WavAnalysis.rms(high, 0.30, 0.40) / WavAnalysis.rms(low, 0.30, 0.40);
        assertTrue(ratio > 1.8f,
                "level must scale the loop's presence, ratio=" + ratio);
    }

    /* ---- volume ----------------------------------------------------------- */

    /** Fixture: a single pluck (no mute) — the volume pedal just scales it. */
    private WavAnalysis.Wav renderVolume(boolean enabled, float level) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("volume-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setVolume(enabled, level);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedVolumeLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav knobsUp = renderVolume(false, 0.3f);
        WavAnalysis.Wav clean = renderVolume(false, 0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed volume must ignore its knob, ratio=" + ratio);
    }

    @Test
    void fullVolumeIsUnity() throws Exception {
        // gain = level²: level 1.0 is exactly 1.0
        WavAnalysis.Wav clean = renderVolume(false, 0f);
        WavAnalysis.Wav full = renderVolume(true, 1.0f);
        float ratio = WavAnalysis.rms(full, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "full volume must be unity gain, ratio=" + ratio);
    }

    @Test
    void halfVolumeDropsQuadratically() throws Exception {
        // gain = level²: level 0.5 is 0.25 (about -12 dB)
        WavAnalysis.Wav clean = renderVolume(false, 0f);
        WavAnalysis.Wav half = renderVolume(true, 0.5f);
        float ratio = WavAnalysis.rms(half, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.2f && ratio < 0.3f,
                "half volume must drop to a quarter of the amplitude, ratio=" + ratio);
    }

    @Test
    void zeroVolumeSilences() throws Exception {
        // gain = level²: level 0.0 is silence
        WavAnalysis.Wav clean = renderVolume(false, 0.0f);
        WavAnalysis.Wav zero = renderVolume(true, 0.0f);
        float ratio = WavAnalysis.rms(zero, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio < 0.02f,
                "zero volume must silence the output, ratio=" + ratio);
    }

    /* ---- tuner ------------------------------------------------------------- */

    /** Fixture: one pluck at B4 (string 5 fret 7 ≈ 494 Hz) for pitch detection. */
    private static final float TUNER_HZ = 494.0f;

    private WavAnalysis.Wav renderTuner(boolean enabled) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f));
        Path wav = tempDir.resolve("tuner-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setTuner(enabled);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
            if (enabled) {
                detectedHz = engine.getTunerHz();
            }
        }
        return WavAnalysis.read(wav);
    }

    private float detectedHz;

    @Test
    void bypassedTunerLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav clean = renderTuner(false);
        assertTrue(WavAnalysis.rms(clean, 0.05, 0.25) > 0.01f,
                "the bypassed tuner must not mute the output");
    }

    @Test
    void engagedTunerMutesTheOutput() throws Exception {
        WavAnalysis.Wav muted = renderTuner(true);
        assertTrue(WavAnalysis.rms(muted, 0.05, 0.9) < 0.02f,
                "the engaged tuner must mute the output");
    }

    @Test
    void engagedTunerMeasuresThePitch() throws Exception {
        renderTuner(true);
        assertTrue(detectedHz > TUNER_HZ * 0.97f && detectedHz < TUNER_HZ * 1.03f,
                "the tuner must read about 494 Hz, got " + detectedHz);
    }

    @Test
    void disengagedTunerReportsNothing() throws Exception {
        renderTuner(false);
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            assertEquals(0.0f, engine.getTunerHz(), 0.0001f,
                    "a fresh engine with the tuner off must report no pitch");
        }
    }

    /* ---- cabinet ----------------------------------------------------------- */

    /**
     * Fixture: one bright pluck (string 5 fret 7 ≈ 494 Hz) — the cabinet
     * shapes its spectrum without changing the notes.
     */
    private WavAnalysis.Wav renderCabinet(boolean enabled, float mix) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f));
        Path wav = tempDir.resolve("cab-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setCabinet(enabled, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedCabinetLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav clean = renderCabinet(false, 0f);
        WavAnalysis.Wav knobsUp = renderCabinet(false, 1.0f);
        float ratio = WavAnalysis.rms(knobsUp, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed cabinet must ignore its knob, ratio=" + ratio);
    }

    @Test
    void cabinetMixZeroIsDry() throws Exception {
        // out = in + (cabbed - in)*mix: mix 0 is an exact passthrough
        WavAnalysis.Wav clean = renderCabinet(true, 0.0f);
        WavAnalysis.Wav ref = renderCabinet(false, 0.0f);
        float ratio = WavAnalysis.rms(clean, 0.05, 0.25) / WavAnalysis.rms(ref, 0.05, 0.25);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "mix 0 cabinet must pass the signal untouched, ratio=" + ratio);
    }

    @Test
    void fullCabinetTamesTheTone() throws Exception {
        // the fixed response (HP + dip + LP) is purely attenuative, so the
        // body drops a little but must not collapse
        WavAnalysis.Wav clean = renderCabinet(false, 0f);
        WavAnalysis.Wav cabbed = renderCabinet(true, 1.0f);
        float ratio = WavAnalysis.rms(cabbed, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.5f && ratio < 0.95f,
                "full cabinet must tame but not kill the body, ratio=" + ratio);
    }

    @Test
    void cabinetScoopsTheLowMids() throws Exception {
        // the fixed response dips hard around 400 Hz, so the fundamental
        // (494 Hz) must drop more than an octave-up harmonic (988 Hz)
        WavAnalysis.Wav clean = renderCabinet(false, 0f);
        WavAnalysis.Wav cabbed = renderCabinet(true, 1.0f);
        double from = 0.05, to = 0.25;
        float dropFund = WavAnalysis.toneMagnitude(cabbed, from, to, 494.0)
                / Math.max(WavAnalysis.toneMagnitude(clean, from, to, 494.0), 1e-9f);
        float dropOct = WavAnalysis.toneMagnitude(cabbed, from, to, 988.0)
                / Math.max(WavAnalysis.toneMagnitude(clean, from, to, 988.0), 1e-9f);
        assertTrue(dropFund < dropOct - 0.1f,
                "the cabinet must scoop the low mids (fundamental drop " + dropFund
                        + " vs octave drop " + dropOct + ")");
    }

    @Test
    void cabinetMixBlendsMonotonically() throws Exception {
        // more SIM = more of the attenuative response = strictly less body
        WavAnalysis.Wav half = renderCabinet(true, 0.5f);
        WavAnalysis.Wav full = renderCabinet(true, 1.0f);
        float rmsHalf = WavAnalysis.rms(half, 0.05, 0.25);
        float rmsFull = WavAnalysis.rms(full, 0.05, 0.25);
        assertTrue(rmsFull < rmsHalf,
                "more SIM must sit below half SIM (attenuative blend), "
                        + rmsFull + " vs " + rmsHalf);
    }

    /* ---- spring ------------------------------------------------------------- */

    /**
     * Fixture: a pluck muted at 0.3 s — whatever sounds after the string
     * stops is the spring's tail.
     */
    private WavAnalysis.Wav renderSpring(boolean enabled, float dwell, float mix)
            throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("spring-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setSpring(enabled, dwell, mix);
            assertTrue(engine.renderSequenceToWav(wav, 1.2f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void bypassedSpringLeavesTheSignalUntouched() throws Exception {
        WavAnalysis.Wav clean = renderSpring(false, 0.9f, 1.0f);
        WavAnalysis.Wav ref = renderSpring(false, 0f, 0f);
        float ratio = WavAnalysis.rms(clean, 0.05, 0.25) / WavAnalysis.rms(ref, 0.05, 0.25);
        assertTrue(ratio > 0.95f && ratio < 1.05f,
                "bypassed spring must ignore its knobs, ratio=" + ratio);
    }

    @Test
    void springMixZeroIsDry() throws Exception {
        // out = in + (wet - in)*mix: mix 0 is an exact passthrough
        WavAnalysis.Wav dry = renderSpring(true, 0.9f, 0.0f);
        WavAnalysis.Wav ref = renderSpring(false, 0f, 0f);
        float ratio = WavAnalysis.rms(dry, 0.05, 0.25) / WavAnalysis.rms(ref, 0.05, 0.25);
        assertTrue(ratio > 0.98f && ratio < 1.02f,
                "mix 0 spring must pass the signal untouched, ratio=" + ratio);
    }

    @Test
    void engagedSpringAddsATail() throws Exception {
        // after the mute the string is silent; only the spring keeps
        // ringing (window starts right at the mute so the ring is alive)
        WavAnalysis.Wav clean = renderSpring(false, 0f, 0f);
        WavAnalysis.Wav wet = renderSpring(true, 0.95f, 0.5f);
        float tailClean = WavAnalysis.rms(clean, 0.31, 0.45);
        float tailWet = WavAnalysis.rms(wet, 0.31, 0.45);
        assertTrue(tailWet > 0.001f && tailWet > tailClean * 1.5f,
                "the spring must ring on after the mute, tail " + tailWet
                        + " vs clean " + tailClean);
    }

    @Test
    void dwellExtendsTheTail() throws Exception {
        // more feedback = the ring-out lasts longer after the mute
        WavAnalysis.Wav short_ = renderSpring(true, 0.1f, 0.5f);
        WavAnalysis.Wav long_ = renderSpring(true, 0.95f, 0.5f);
        float tailShort = WavAnalysis.rms(short_, 0.31, 0.45);
        float tailLong = WavAnalysis.rms(long_, 0.31, 0.45);
        assertTrue(tailLong > tailShort * 1.5f,
                "more dwell must extend the tail, " + tailLong + " vs " + tailShort);
    }

    @Test
    void engagedSpringKeepsTheBodySane() throws Exception {
        // wet blend must not blow up the dry body (regeneration guard)
        WavAnalysis.Wav clean = renderSpring(false, 0f, 0f);
        WavAnalysis.Wav wet = renderSpring(true, 1.0f, 0.5f);
        float ratio = WavAnalysis.rms(wet, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.6f && ratio < 1.6f,
                "full dwell must stay sane on the body, ratio=" + ratio);
    }
}
