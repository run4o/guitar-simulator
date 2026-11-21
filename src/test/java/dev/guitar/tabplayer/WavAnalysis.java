package dev.guitar.tabplayer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal WAV reader + DSP measurements for verifying rendered audio in tests.
 * Supports the 16-bit PCM mono files produced by guitar_engine_render_sequence_to_wav.
 */
public final class WavAnalysis {

    public record Wav(float sampleRate, float[] samples) {}

    private WavAnalysis() {}

    public static Wav read(Path path) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);

        // Chunk ids are ASCII; with a little-endian buffer their int values are byte-reversed.
        final int RIFF_LE = 0x46464952; // "RIFF"
        final int WAVE_LE = 0x45564157; // "WAVE"
        final int FMT_LE  = 0x20746D66; // "fmt "
        final int DATA_LE = 0x61746164; // "data"

        if (buf.getInt(0) != RIFF_LE) {
            throw new IOException("not a RIFF file: " + path);
        }
        if (buf.getInt(8) != WAVE_LE) {
            throw new IOException("not a WAVE file: " + path);
        }

        float sampleRate = 0;
        float[] samples = null;
        int pos = 12;
        while (pos + 8 <= buf.limit()) {
            int chunkId = buf.getInt(pos);
            int chunkSize = buf.getInt(pos + 4);
            int dataStart = pos + 8;
            if (chunkId == FMT_LE) { // "fmt "
                short audioFormat = buf.getShort(dataStart);
                short channels = buf.getShort(dataStart + 2);
                sampleRate = buf.getInt(dataStart + 4);
                short bitsPerSample = buf.getShort(dataStart + 14);
                if (audioFormat != 1 || channels != 1 || bitsPerSample != 16) {
                    throw new IOException("unsupported WAV format: fmt=" + audioFormat
                            + " ch=" + channels + " bits=" + bitsPerSample);
                }
            } else if (chunkId == DATA_LE) { // "data"
                int count = chunkSize / 2;
                samples = new float[count];
                for (int i = 0; i < count; i++) {
                    samples[i] = buf.getShort(dataStart + i * 2) / 32768.0f;
                }
            }
            pos = dataStart + chunkSize + (chunkSize & 1);
        }
        if (sampleRate <= 0 || samples == null) {
            throw new IOException("WAV missing fmt/data chunk: " + path);
        }
        return new Wav(sampleRate, samples);
    }

    public static float rms(Wav wav, double fromSec, double toSec) {
        int from = clampIndex(wav, fromSec);
        int to = clampIndex(wav, toSec);
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) wav.samples()[i] * wav.samples()[i];
        }
        int n = Math.max(1, to - from);
        return (float) Math.sqrt(sum / n);
    }

    /**
     * Goertzel magnitude at one frequency over a window. Used to detect
     * modulation sidebands (the delayed, pitch-wobbling copy of a chorus
     * spreads energy to f0 ± k*rate around the note).
     */
    public static float toneMagnitude(Wav wav, double fromSec, double toSec, double freqHz) {
        int from = clampIndex(wav, fromSec);
        int to = clampIndex(wav, toSec);
        int n = Math.max(1, to - from);
        double k = 2.0 * Math.PI * freqHz / wav.sampleRate();
        double re = 0;
        double im = 0;
        for (int i = from; i < to; i++) {
            double v = wav.samples()[i];
            re += v * Math.cos(k * i);
            im -= v * Math.sin(k * i);
        }
        return (float) (Math.sqrt(re * re + im * im) / n);
    }

    public static float peak(Wav wav, double fromSec, double toSec) {
        int from = clampIndex(wav, fromSec);
        int to = clampIndex(wav, toSec);
        float peak = 0;
        for (int i = from; i < to; i++) {
            peak = Math.max(peak, Math.abs(wav.samples()[i]));
        }
        return peak;
    }

    /**
     * Fundamental frequency estimate via mean-removed autocorrelation with
     * parabolic lag interpolation. Robust against the strong harmonics and
     * slight waveform asymmetry of Karplus-Strong output (zero-crossing
     * counting fails on that timbre).
     *
     * Returns 0 when no clear periodicity is found in the window.
     */
    public static float detectPitch(Wav wav, double fromSec, double toSec) {
        int from = clampIndex(wav, fromSec);
        int to = clampIndex(wav, toSec);
        int n = to - from;
        if (n < 128) {
            return 0;
        }
        float[] x = new float[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += wav.samples()[from + i];
        }
        mean /= n;
        double r0 = 0;
        for (int i = 0; i < n; i++) {
            x[i] = wav.samples()[from + i] - (float) mean;
            r0 += (double) x[i] * x[i];
        }
        if (r0 < 1e-9) {
            return 0;
        }

        double sr = wav.sampleRate();
        int minLag = Math.max(2, (int) (sr / 1300.0)); // up to ~1300 Hz
        int maxLag = Math.min((int) (sr / 60.0), n / 2);

        double bestCorr = 0;
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < n; i++) {
                sum += (double) x[i] * x[i + lag];
            }
            double norm = sum / (n - lag);
            if (norm > bestCorr) {
                bestCorr = norm;
                bestLag = lag;
            }
        }
        if (bestLag < 0) {
            return 0;
        }
        // Require clear periodicity relative to the window energy density.
        if (bestCorr < 0.35 * (r0 / n)) {
            return 0;
        }

        // Parabolic interpolation for sub-sample lag accuracy.
        double rLag = autocorrAt(x, bestLag);
        double rPrev = bestLag > minLag ? autocorrAt(x, bestLag - 1) : rLag;
        double rNext = bestLag < maxLag ? autocorrAt(x, bestLag + 1) : rLag;
        double denom = rPrev - 2 * rLag + rNext;
        double shift = denom != 0 ? 0.5 * (rPrev - rNext) / denom : 0;
        shift = Math.max(-0.5, Math.min(0.5, shift));
        double lag = bestLag + shift;
        return (float) (sr / lag);
    }

    private static double autocorrAt(float[] x, int lag) {
        double sum = 0;
        for (int i = 0; i + lag < x.length; i++) {
            sum += (double) x[i] * x[i + lag];
        }
        return sum / (x.length - lag);
    }

    private static int clampIndex(Wav wav, double sec) {
        int idx = (int) Math.round(sec * wav.sampleRate());
        return Math.max(0, Math.min(idx, wav.samples().length));
    }
}
