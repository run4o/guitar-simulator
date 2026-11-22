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

    /** Bright pluck (B4, string 5 fret 7) through one amp channel. */
    private WavAnalysis.Wav renderChannel(int model, float drive, String tag) throws Exception {
        return renderChannel(model, 0, drive, tag);
    }

    /** Same, with a guitar model selection (SET_MODEL lands before the pluck). */
    private WavAnalysis.Wav renderChannel(int ampModel, int guitarModel, float drive,
                                          String tag) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_SET_MODEL, -1, guitarModel, 0f, 0f),
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("amp-" + tag + "-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setAmpModel(ampModel);
            engine.setFx(drive, 1f, 0f, 1f);
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
     * Harmonic fizz: tone magnitudes from 2.4 kHz up, where the cascaded
     * clipper's generated harmonics live (the string's own harmonics
     * dominate below that in every channel). Raw sum — scale matters.
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
        // the cascaded voicing generates fizz the dry signal never has
        WavAnalysis.Wav clean = renderChannel(0, 0f, "clean");
        WavAnalysis.Wav metal = renderChannel(2, 1f, "metal");
        assertTrue(fizz(metal) > fizz(clean) * 5.0f,
                "max-drive metal must be harmonically rich, " + fizz(metal)
                        + " vs dry " + fizz(clean));
    }

    @Test
    void crunchChannelBreaksUp() throws Exception {
        WavAnalysis.Wav clean = renderChannel(0, 0f, "clean");
        WavAnalysis.Wav crunch = renderChannel(1, 1f, "crunch");
        assertTrue(fizz(crunch) > fizz(clean) * 5.0f,
                "crunch must break up against the dry signal, " + fizz(crunch)
                        + " vs dry " + fizz(clean));
    }

    @Test
    void metalChannelIsHeavyAtZeroDrive() throws Exception {
        // a lead channel is saturated at knob 0 — DRIVE is the gain, not a
        // blend. This failed before: METAL at DRIVE 0 was the dry signal.
        WavAnalysis.Wav clean = renderChannel(0, 0f, "clean");
        WavAnalysis.Wav metal = renderChannel(2, 0f, "metal");
        assertTrue(fizz(metal) > fizz(clean) * 3.0f,
                "metal at zero drive must already bite, " + fizz(metal)
                        + " vs dry " + fizz(clean));
    }

    @Test
    void metalChannelKeepsTheLevelSane() throws Exception {
        // cascaded stages compress hard; the makeup gain must not blast or drop
        WavAnalysis.Wav clean = renderChannel(0, 1f, "clean");
        WavAnalysis.Wav metal = renderChannel(2, 1f, "metal");
        float ratio = WavAnalysis.rms(metal, 0.05, 0.25) / WavAnalysis.rms(clean, 0.05, 0.25);
        assertTrue(ratio > 0.4f && ratio < 2.0f,
                "metal body must stay in sane range vs clean, ratio=" + ratio);
    }

    @Test
    void metalChannelStaysInBoundsAndDeterministic() throws Exception {
        WavAnalysis.Wav a = renderChannel(2, 1f, "metal-a");
        WavAnalysis.Wav b = renderChannel(2, 1f, "metal-b");
        assertTrue(WavAnalysis.peak(a, 0.0, 2.5) <= 1.05f,
                "metal output must respect the soft ceiling");
        for (int i = 0; i < a.samples().length; i++) {
            if (a.samples()[i] != b.samples()[i]) {
                fail("metal render must be deterministic at sample " + i);
            }
        }
    }

    @Test
    void humbuckerIntoMetalIsTheHotPath() throws Exception {
        // the pickup stage must give the amp more to bite than the flat
        // acoustic signal does: more harmonic fizz, and a strong body
        WavAnalysis.Wav acoustic = renderChannel(2, 0, 1f, "metal-acoustic");
        WavAnalysis.Wav humbucker = renderChannel(2, 2, 1f, "metal-hb");
        assertTrue(fizz(humbucker) > fizz(acoustic) * 1.05f,
                "humbucker into metal must add fizz, " + fizz(humbucker)
                        + " vs acoustic " + fizz(acoustic));
        float rmsRatio = WavAnalysis.rms(humbucker, 0.05, 0.25)
                / Math.max(WavAnalysis.rms(acoustic, 0.05, 0.25), 1e-9f);
        assertTrue(rmsRatio > 0.6f && rmsRatio < 2.0f,
                "the pickup stage must not wildly level-shift the amp, ratio=" + rmsRatio);
    }

    /* ---- string sustain ----------------------------------------------------- */

    /** Single pluck, no mute, long render — measures the ring-out tail. */
    private WavAnalysis.Wav renderSustain(float multiplier) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 0, 0, 0.9f, 0f));
        Path wav = tempDir.resolve("sustain-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setSustain(multiplier);
            assertTrue(engine.renderSequenceToWav(wav, 3.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void sustainKnobControlsRingOut() throws Exception {
        float base = WavAnalysis.rms(renderSustain(1.0f), 1.5, 2.5);
        float long_ = WavAnalysis.rms(renderSustain(4.0f), 1.5, 2.5);
        float short_ = WavAnalysis.rms(renderSustain(0.25f), 1.5, 2.5);
        assertTrue(long_ > base * 2.0f,
                "4x sustain must ring well past the nominal decay, " + long_
                        + " vs base " + base);
        assertTrue(short_ < base * 0.5f,
                "quarter sustain must die before the nominal decay, " + short_
                        + " vs base " + base);
    }

    /* ---- cabinet IR convolution -------------------------------------------- */

    /** Minimal 16-bit PCM mono WAV writer for test IR files. */
    private void writeIrWav(Path path, float[] samples) throws Exception {
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        int dataLen = samples.length * 2;
        int riffLen = 36 + dataLen;
        header[4] = (byte) riffLen; header[5] = (byte) (riffLen >> 8);
        header[6] = (byte) (riffLen >> 16); header[7] = (byte) (riffLen >> 24);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; // fmt chunk size
        header[20] = 1; header[21] = 0; // PCM
        header[22] = 1; header[23] = 0; // mono
        int rate = 44100;
        header[24] = (byte) rate; header[25] = (byte) (rate >> 8);
        header[26] = (byte) (rate >> 16); header[27] = (byte) (rate >> 24);
        int byteRate = rate * 2;
        header[28] = (byte) byteRate; header[29] = (byte) (byteRate >> 8);
        header[30] = (byte) (byteRate >> 16); header[31] = (byte) (byteRate >> 24);
        header[32] = 2; header[33] = 0; // block align
        header[34] = 16; header[35] = 0; // bits per sample
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) dataLen; header[41] = (byte) (dataLen >> 8);
        header[42] = (byte) (dataLen >> 16); header[43] = (byte) (dataLen >> 24);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(header);
        for (float s : samples) {
            int v = Math.max(-32768, Math.min(32767, Math.round(s * 32767f)));
            out.write(v & 0xFF);
            out.write((v >> 8) & 0xFF);
        }
        java.nio.file.Files.write(path, out.toByteArray());
    }

    private WavAnalysis.Wav renderCabinetIr(String irPath, boolean cabinetOn, boolean expectLoad,
                                            String tag) throws Exception {
        List<RenderCommand> commands = List.of(
                new RenderCommand(0f, GuitarEngine.EVENT_SET_MODEL, -1, 2, 0f, 0f),
                new RenderCommand(0f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f),
                new RenderCommand(0.3f, GuitarEngine.EVENT_MUTE, 5, 0, 0f, 0f));
        Path wav = tempDir.resolve("cabir-" + tag + "-" + System.nanoTime() + ".wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            engine.setAmpModel(2);
            engine.setFx(0f, 1f, 0f, 1f);
            engine.setCabinet(cabinetOn, 1.0f);
            if (irPath != null) {
                assertEquals(expectLoad, engine.loadCabinetIr(Path.of(irPath)),
                        "cabinet IR load result");
            }
            assertTrue(engine.renderSequenceToWav(wav, 1.0f, commands));
        }
        return WavAnalysis.read(wav);
    }

    @Test
    void deltaCabinetIrIsDelayedPassthrough() throws Exception {
        // a delta IR convolved with the signal is the signal itself — the
        // whole FFT partition pipeline must reproduce the dry render, one
        // 512-sample block late (block-processing latency)
        Path ir = tempDir.resolve("delta-ir.wav");
        // pad the delta to 512 taps so it passes the loader's minimum
        float[] delta = new float[512];
        delta[0] = 1.0f;
        writeIrWav(ir, delta);
        WavAnalysis.Wav dry = renderCabinetIr(null, false, true, "dry");
        WavAnalysis.Wav irOn = renderCabinetIr(ir.toString(), true, true, "ir");
        float[] dryS = dry.samples();
        float[] irS = irOn.samples();
        final int latency = 512;
        double maxDiff = 0.0;
        for (int i = 0; i + latency < irS.length && i < dryS.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(irS[i + latency] - dryS[i]));
        }
        assertTrue(maxDiff < 0.002,
                "a delta IR must pass the signal untouched (512-sample latency), maxDiff="
                        + maxDiff);
    }

    @Test
    void failedIrLoadFallsBackToTheModel() throws Exception {
        // a missing file returns false and leaves the biquad cabinet intact
        Path ir = tempDir.resolve("nope.wav");
        WavAnalysis.Wav dry = renderCabinetIr(null, false, true, "dry");
        WavAnalysis.Wav model = renderCabinetIr(ir.toString(), true, false, "missing");
        assertTrue(WavAnalysis.rms(model, 0.05, 0.9) > 0.001f,
                "a failed IR load must leave the modeled cabinet active");
        assertTrue(Math.abs(WavAnalysis.rms(model, 0.05, 0.9)
                - WavAnalysis.rms(dry, 0.05, 0.9)) > 1e-4f,
                "the modeled cabinet must still shape the tone");
    }
}
