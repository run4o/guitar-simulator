#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace gte {

/*
 * Pedalboard: individual effect pedals, each a self-contained class with a
 * clean interface (prepare + process), inserted between the summed string
 * voices and the amp FX section (drive/tone/reverb) on both the real-time
 * and offline paths:
 *
 *   voices -> PedalChain -> FxDsp (drive/tone/reverb/master) -> out
 *
 * Pedals are added one at a time; each slot is bypass-able and lives in a
 * fixed position in the chain (the order below mirrors a physical board).
 */

class Pedal {
public:
    virtual ~Pedal() = default;
    virtual void prepare(float /*sample_rate*/) {}
    virtual float process(float in) = 0;

    bool enabled = false;
};

/*
 * Boost: makes the guitar louder with a clean transistor/op-amp style stage
 * that increases signal level without distorting it — pure linear gain,
 * 0..+18 dB, no shaping.
 */
class BoostPedal : public Pedal {
public:
    void prepare(float sample_rate) override { fs_ = sample_rate; setParams(gain_db_); }

    void setParams(float gain_db) {
        gain_db_ = std::clamp(gain_db, 0.0f, 18.0f);
        gain_ = std::pow(10.0f, gain_db_ / 20.0f);
    }

    float process(float in) override { return in * gain_; }

private:
    float fs_ = 44100.0f;
    float gain_db_ = 0.0f;
    float gain_ = 1.0f;
};

/*
 * Fuzz: thick, buzzy sustain from over-saturating the stage until the
 * smooth wave turns into a harsh square — a Fuzz Face style pair of
 * overdriven transistors. A 300 Hz high-pass sheds the bass (the classic
 * fuzz quirk), an enormous gain (1..100) slams a tanh waveshaper into its
 * flat regions, and a small bias leans the transfer asymmetrically (the
 * offset is subtracted again so the output stays DC-free).
 */
class FuzzPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        hp_state_ = 0.0f;
        setParams(fuzz_, volume_);
    }

    void setParams(float fuzz, float volume) {
        fuzz_ = std::clamp(fuzz, 0.0f, 1.0f);
        volume_ = std::clamp(volume, 0.0f, 1.0f);
        hp_coef_ = 1.0f - std::exp(-6.2831853f * 300.0f / fs_);
        gain_ = 1.0f + fuzz_ * 99.0f;
        out_ = 0.2f + 0.8f * volume_;
    }

    float process(float in) override {
        hp_state_ += hp_coef_ * (in - hp_state_);
        const float hp = in - hp_state_;
        const float bias = 0.15f * fuzz_;
        const float shaped = std::tanh(gain_ * hp + bias) - bias;
        return shaped * out_;
    }

private:
    float fs_ = 44100.0f;
    float fuzz_ = 0.0f;
    float volume_ = 0.5f;
    float hp_coef_ = 0.04f;
    float hp_state_ = 0.0f;
    float gain_ = 1.0f;
    float out_ = 0.6f;
};

/*
 * Distortion: heavy rock crunch from aggressively clipping the wave into a
 * sharper, flatter shape — diode-style. Very high gain (1..40) into a tanh
 * waveshaper, then a hard clamp at +/-0.85 gives the flat-top shape a diode
 * pair leaves behind, a tone low-pass (2..10 kHz) softens the buzz, and a
 * level knob restores output (distortion pedals run fully wet).
 */
class DistortionPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        lp_state_ = 0.0f;
        setParams(drive_, tone_, level_);
    }

    void setParams(float drive, float tone, float level) {
        drive_ = std::clamp(drive, 0.0f, 1.0f);
        tone_ = std::clamp(tone, 0.0f, 1.0f);
        level_ = std::clamp(level, 0.0f, 1.0f);
        const float cutoff = 2000.0f * std::pow(5.0f, tone_); // 2 kHz .. 10 kHz
        lp_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
        gain_ = 1.0f + drive_ * 39.0f;
        out_ = 0.25f + 0.75f * level_;
    }

    float process(float in) override {
        const float shaped = std::tanh(gain_ * in);
        const float clipped = std::clamp(shaped, -0.85f, 0.85f);
        lp_state_ += lp_coef_ * (clipped - lp_state_);
        return lp_state_ * out_;
    }

private:
    float fs_ = 44100.0f;
    float drive_ = 0.0f;
    float tone_ = 0.5f;
    float level_ = 0.5f;
    float lp_coef_ = 0.3f;
    float lp_state_ = 0.0f;
    float gain_ = 1.0f;
    float out_ = 0.625f;
};

/*
 * Overdrive: warm, dynamic grit from gently clipping the wave's peaks —
 * a pushed tube amp. A 720 Hz one-pole high-pass (the TubeScreamer
 * hallmark) keeps bass fundamentals tight, a variable gain (1..15) feeds a
 * slightly asymmetric tanh waveshaper (a small bias lifts the positive
 * half, mimicking the tube's asymmetric transfer; the offset is removed
 * again so no DC reaches the output), a dry/wet blend restores body, and a
 * tone low-pass (3..12 kHz) trims the fizz.
 */
class OverdrivePedal : public Pedal {public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        hp_state_ = lp_state_ = 0.0f;
        setParams(drive_, tone_);
    }

    void setParams(float drive, float tone) {
        drive_ = std::clamp(drive, 0.0f, 1.0f);
        tone_ = std::clamp(tone, 0.0f, 1.0f);
        hp_coef_ = 1.0f - std::exp(-6.2831853f * 720.0f / fs_);
        const float cutoff = 3000.0f * std::pow(4.0f, tone_); // 3 kHz .. 12 kHz
        lp_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
        gain_ = 1.0f + drive_ * 14.0f;
        blend_ = 0.35f + 0.45f * drive_;
    }

    float process(float in) override {
        hp_state_ += hp_coef_ * (in - hp_state_);
        const float hp = in - hp_state_;
        const float bias = 0.08f * drive_;
        const float shaped = std::tanh(gain_ * hp + bias) - std::tanh(bias);
        lp_state_ += lp_coef_ * (shaped - lp_state_);
        return (1.0f - blend_) * in + blend_ * lp_state_ * 0.9f;
    }

private:
    float fs_ = 44100.0f;
    float drive_ = 0.0f;
    float tone_ = 0.5f;
    float hp_coef_ = 0.1f;
    float lp_coef_ = 0.3f;
    float hp_state_ = 0.0f;
    float lp_state_ = 0.0f;
    float gain_ = 1.0f;
    float blend_ = 0.35f;
};

/*
 * Delay: distinct repeats by feeding the signal into a circular delay
 * buffer and reading it back after `time` seconds. The bucket-brigade
 * character comes from a fixed ~4 kHz low-pass on the feedback path —
 * each repeat is a touch darker than the one before it. Input into the
 * line is signal + feedback (so repeats regenerate), output is dry plus
 * wet*mix. The buffer is heap-allocated in prepare() (up to 1.2 s) so the
 * offline renderer never puts a half-megabyte array on the stack.
 */
class DelayPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        const int capacity = static_cast<int>(sample_rate * 1.2f) + 16;
        if (static_cast<int>(buffer_.size()) != capacity) {
            buffer_.assign(capacity, 0.0f);
        } else {
            std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        }
        write_ = 0;
        lp_state_ = 0.0f;
        setParams(time_, feedback_, mix_);
    }

    void setParams(float time_seconds, float feedback, float mix) {
        time_ = std::clamp(time_seconds, 0.02f, 1.0f);
        feedback_ = std::clamp(feedback, 0.0f, 0.9f);
        mix_ = std::clamp(mix, 0.0f, 1.0f);
        delay_samples_ = std::min(
            static_cast<int>(time_ * fs_), static_cast<int>(buffer_.size()) - 2);
        delay_samples_ = std::max(delay_samples_, 2);
        lp_coef_ = 1.0f - std::exp(-6.2831853f * 4000.0f / fs_);
    }

    float process(float in) override {
        const int size = static_cast<int>(buffer_.size());
        int read = write_ - delay_samples_;
        if (read < 0) {
            read += size;
        }
        float delayed = buffer_[read];
        lp_state_ += lp_coef_ * (delayed - lp_state_);
        buffer_[write_] = in + lp_state_ * feedback_;
        write_ = write_ + 1 == size ? 0 : write_ + 1;
        return in + lp_state_ * mix_;
    }

private:
    float fs_ = 44100.0f;
    float time_ = 0.3f;
    float feedback_ = 0.4f;
    float mix_ = 0.5f;
    float lp_coef_ = 0.3f;
    float lp_state_ = 0.0f;
    int delay_samples_ = 13230;
    int write_ = 0;
    std::vector<float> buffer_;
};

/*
 * Chorus: thickens the tone by adding a slightly delayed, pitch-wobbling
 * copy of the signal — several guitars in unison. The copy reads a
 * circular buffer at a fractionally interpolated (linear) offset that an
 * LFO sweeps between 5 and 35 ms (base 20 ms, depth up to 15 ms of
 * deviation) at 0.1..4 Hz. Output is dry + wet*mix.
 */
class ChorusPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        const int capacity = static_cast<int>(sample_rate * 0.1f) + 16;
        if (static_cast<int>(buffer_.size()) != capacity) {
            buffer_.assign(capacity, 0.0f);
        } else {
            std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        }
        write_ = 0;
        phase_ = 0.0f;
        setParams(rate_, depth_, mix_);
    }

    void setParams(float rate, float depth, float mix) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        depth_ = std::clamp(depth, 0.0f, 1.0f);
        mix_ = std::clamp(mix, 0.0f, 1.0f);
        rate_hz_ = 0.1f * std::pow(40.0f, rate_);       // 0.1 .. 4 Hz
        base_samples_ = 0.020f * fs_;                    // 20 ms center
        depth_samples_ = depth_ * 0.015f * fs_;          // up to 15 ms
    }

    float process(float in) override {
        const int size = static_cast<int>(buffer_.size());
        phase_ += 6.2831853f * rate_hz_ / fs_;
        if (phase_ > 6.2831853f) {
            phase_ -= 6.2831853f;
        }
        float delay = base_samples_ + depth_samples_ * std::sin(phase_);
        delay = std::clamp(delay, 1.0f, static_cast<float>(size) - 2.0f);
        const int d = static_cast<int>(delay);
        const float frac = delay - static_cast<float>(d);
        int r0 = write_ - d;
        if (r0 < 0) {
            r0 += size;
        }
        int r1 = r0 - 1 < 0 ? size - 1 : r0 - 1;
        const float wet = buffer_[r0] * (1.0f - frac) + buffer_[r1] * frac;
        buffer_[write_] = in;
        write_ = write_ + 1 == size ? 0 : write_ + 1;
        return in + wet * mix_;
    }

private:
    float fs_ = 44100.0f;
    float rate_ = 0.5f;
    float depth_ = 0.5f;
    float mix_ = 0.5f;
    float rate_hz_ = 2.0f;
    float base_samples_ = 882.0f;
    float depth_samples_ = 330.0f;
    float phase_ = 0.0f;
    int write_ = 0;
    std::vector<float> buffer_;
};

/*
 * Flanger: the jet-plane whoosh — a very short modulated delay (1..10 ms
 * sweep) whose output is FED BACK into the delay line, stacking notches
 * into the spectrum that sweep up and down. Rate 0.05..2 Hz (log), depth
 * up to 5 ms of deviation, feedback up to 0.9, mix = wet blend.
 */
class FlangerPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        const int capacity = static_cast<int>(sample_rate * 0.05f) + 16;
        if (static_cast<int>(buffer_.size()) != capacity) {
            buffer_.assign(capacity, 0.0f);
        } else {
            std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        }
        write_ = 0;
        phase_ = 0.0f;
        setParams(rate_, depth_, feedback_, mix_);
    }

    void setParams(float rate, float depth, float feedback, float mix) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        depth_ = std::clamp(depth, 0.0f, 1.0f);
        feedback_ = std::clamp(feedback, 0.0f, 0.9f);
        mix_ = std::clamp(mix, 0.0f, 1.0f);
        rate_hz_ = 0.05f * std::pow(160.0f, rate_); // 0.05 .. 8 Hz
        base_samples_ = 0.005f * fs_;              // 5 ms center
        depth_samples_ = depth_ * 0.005f * fs_;    // up to 5 ms
    }

    float process(float in) override {
        const int size = static_cast<int>(buffer_.size());
        phase_ += 6.2831853f * rate_hz_ / fs_;
        if (phase_ > 6.2831853f) {
            phase_ -= 6.2831853f;
        }
        float delay = base_samples_ + depth_samples_ * std::sin(phase_);
        delay = std::clamp(delay, 1.0f, static_cast<float>(size) - 2.0f);
        const int d = static_cast<int>(delay);
        const float frac = delay - static_cast<float>(d);
        int r0 = write_ - d;
        if (r0 < 0) {
            r0 += size;
        }
        int r1 = r0 - 1 < 0 ? size - 1 : r0 - 1;
        const float wet = buffer_[r0] * (1.0f - frac) + buffer_[r1] * frac;
        buffer_[write_] = in + wet * feedback_; // the regenerating comb
        write_ = write_ + 1 == size ? 0 : write_ + 1;
        return in + wet * mix_;
    }

private:
    float fs_ = 44100.0f;
    float rate_ = 0.5f;
    float depth_ = 0.6f;
    float feedback_ = 0.4f;
    float mix_ = 0.5f;
    float rate_hz_ = 0.5f;
    float base_samples_ = 220.0f;
    float depth_samples_ = 220.0f;
    float phase_ = 0.0f;
    int write_ = 0;
    std::vector<float> buffer_;
};

/*
 * Phaser: the watery swirl — a stack of all-pass filters whose pole
 * coefficients an LFO sweeps up and down, recombined against the dry
 * signal with a NEGATIVE mix so swept notches carve through the spectrum
 * (that is what separates it from the chorus/flanger delay family).
 * Regeneration feeds the phase-shifted copy back into the stage stack and
 * deepens the notches. Rate 0.05..8 Hz (log), four stages.
 */
class PhaserPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        phase_ = 0.0f;
        wet_ = 0.0f;
        for (int i = 0; i < kStages; ++i) {
            x_prev_[i] = 0.0f;
            y_prev_[i] = 0.0f;
        }
        setParams(rate_, depth_, regen_, mix_);
    }

    void setParams(float rate, float depth, float regen, float mix) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        depth_ = std::clamp(depth, 0.0f, 1.0f);
        regen_ = std::clamp(regen, 0.0f, 0.9f);
        mix_ = std::clamp(mix, 0.0f, 1.0f);
        rate_hz_ = 0.05f * std::pow(160.0f, rate_); // 0.05 .. 8 Hz
        c_min_ = 0.02f;
        c_max_ = c_min_ + (0.65f - c_min_) * depth_;
    }

    float process(float in) override {
        phase_ += 6.2831853f * rate_hz_ / fs_;
        if (phase_ > 6.2831853f) {
            phase_ -= 6.2831853f;
        }
        // swept pole coefficient: the notch sweep the pedal is named for
        float c = c_min_ + (c_max_ - c_min_) * (0.5f + 0.5f * std::sin(phase_));
        const float a = (1.0f - c) / (1.0f + c);
        float x = in + wet_ * regen_;
        for (int i = 0; i < kStages; ++i) {
            // one-pole all-pass: y[n] = a*(x[n] - y[n-1]) + x[n-1]
            const float y = a * (x - y_prev_[i]) + x_prev_[i];
            x_prev_[i] = x;
            y_prev_[i] = y;
            x = y;
        }
        wet_ = x;
        return in - wet_ * mix_;
    }

private:
    static const int kStages = 4;
    float fs_ = 44100.0f;
    float rate_ = 0.5f;
    float depth_ = 0.5f;
    float regen_ = 0.4f;
    float mix_ = 0.5f;
    float rate_hz_ = 0.5f;
    float c_min_ = 0.02f;
    float c_max_ = 0.4f;
    float wet_ = 0.0f;
    float phase_ = 0.0f;
    float x_prev_[kStages] = {0.0f, 0.0f, 0.0f, 0.0f};
    float y_prev_[kStages] = {0.0f, 0.0f, 0.0f, 0.0f};
};

/*
 * Tremolo: the rhythmic pulsing — an LFO rides the volume up and down.
 * Gain = 1 - depth + depth*(0.5 + 0.5*sin(phase)), so depth 0 is a
 * constant unity gain and depth 1 ducks fully to silence at the LFO
 * troughs. Rate 0.5..16 Hz (log).
 */
class TremoloPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        phase_ = 0.0f;
        setParams(rate_, depth_);
    }

    void setParams(float rate, float depth) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        depth_ = std::clamp(depth, 0.0f, 1.0f);
        rate_hz_ = 0.5f * std::pow(32.0f, rate_); // 0.5 .. 16 Hz
    }

    float process(float in) override {
        phase_ += 6.2831853f * rate_hz_ / fs_;
        if (phase_ > 6.2831853f) {
            phase_ -= 6.2831853f;
        }
        const float gain = 1.0f - depth_ + depth_ * (0.5f + 0.5f * std::sin(phase_));
        return in * gain;
    }

private:
    float fs_ = 44100.0f;
    float rate_ = 0.5f;
    float depth_ = 0.5f;
    float rate_hz_ = 4.0f;
    float phase_ = 0.0f;
};

/*
 * Vibrato: notes warble — the LFO-bent pitch copy REPLACES the dry signal
 * (that is what separates it from the chorus, which adds the copy on
 * top). The fractional delay line sweeps up to 20 ms (10 ms center,
 * depth up to 10 ms of deviation) at 0.1..4 Hz; MIX blends back toward
 * dry.
 */
class VibratoPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        const int capacity = static_cast<int>(sample_rate * 0.05f) + 16;
        if (static_cast<int>(buffer_.size()) != capacity) {
            buffer_.assign(capacity, 0.0f);
        } else {
            std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        }
        write_ = 0;
        phase_ = 0.0f;
        setParams(rate_, depth_, mix_);
    }

    void setParams(float rate, float depth, float mix) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        depth_ = std::clamp(depth, 0.0f, 1.0f);
        mix_ = std::clamp(mix, 0.0f, 1.0f);
        rate_hz_ = 0.1f * std::pow(40.0f, rate_);   // 0.1 .. 4 Hz
        base_samples_ = 0.010f * fs_;                // 10 ms center
        depth_samples_ = depth_ * 0.010f * fs_;      // up to 10 ms
    }

    float process(float in) override {
        const int size = static_cast<int>(buffer_.size());
        phase_ += 6.2831853f * rate_hz_ / fs_;
        if (phase_ > 6.2831853f) {
            phase_ -= 6.2831853f;
        }
        float delay = base_samples_ + depth_samples_ * std::sin(phase_);
        delay = std::clamp(delay, 1.0f, static_cast<float>(size) - 2.0f);
        const int d = static_cast<int>(delay);
        const float frac = delay - static_cast<float>(d);
        int r0 = write_ - d;
        if (r0 < 0) {
            r0 += size;
        }
        int r1 = r0 - 1 < 0 ? size - 1 : r0 - 1;
        const float wet = buffer_[r0] * (1.0f - frac) + buffer_[r1] * frac;
        buffer_[write_] = in;
        write_ = write_ + 1 == size ? 0 : write_ + 1;
        return in + (wet - in) * mix_;
    }

private:
    float fs_ = 44100.0f;
    float rate_ = 0.5f;
    float depth_ = 0.5f;
    float mix_ = 1.0f;
    float rate_hz_ = 2.0f;
    float base_samples_ = 441.0f;
    float depth_samples_ = 220.0f;
    float phase_ = 0.0f;
    int write_ = 0;
    std::vector<float> buffer_;
};

/*
 * Compressor: evens out the volume — an envelope follower tracks the
 * note's level and a smoothed gain stage ducks whatever sits above the
 * threshold (harder the higher SUSTAIN), while LEVEL adds make-up gain.
 * The envelope attacks in ~5 ms and releases over ~150 ms; the gain
 * stage ducks in ~2 ms and lets go over ~60 ms.
 */
class CompressorPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        env_ = 0.0f;
        gain_ = 1.0f;
        setParams(sustain_, level_);
    }

    void setParams(float sustain, float level) {
        sustain_ = std::clamp(sustain, 0.0f, 1.0f);
        level_ = std::clamp(level, 0.0f, 1.0f);
        threshold_ = 0.3f * std::pow(0.05f, sustain_);  // -10 dB .. -26 dB-ish
        ratio_ = 2.0f + 6.0f * sustain_;                // 2:1 .. 8:1
        makeup_ = 0.4f + 1.6f * level_;                 // 0.4 .. 2.0
        env_attack_ = 1.0f - std::exp(-1.0f / (0.005f * fs_));
        env_release_ = 1.0f - std::exp(-1.0f / (0.15f * fs_));
        gain_attack_ = 1.0f - std::exp(-1.0f / (0.002f * fs_));
        gain_release_ = 1.0f - std::exp(-1.0f / (0.06f * fs_));
    }

    float process(float in) override {
        const float abs = std::fabs(in);
        const float envCoef = abs > env_ ? env_attack_ : env_release_;
        env_ += envCoef * (abs - env_);
        float target = 1.0f;
        if (env_ > threshold_) {
            target = std::pow(threshold_ / env_, 1.0f / ratio_);
        }
        const float gainCoef = target < gain_ ? gain_attack_ : gain_release_;
        gain_ += gainCoef * (target - gain_);
        return in * gain_ * makeup_;
    }

private:
    float fs_ = 44100.0f;
    float sustain_ = 0.5f;
    float level_ = 0.5f;
    float threshold_ = 0.05f;
    float ratio_ = 5.0f;
    float makeup_ = 1.2f;
    float env_ = 0.0f;
    float gain_ = 1.0f;
    float env_attack_ = 0.01f;
    float env_release_ = 0.0001f;
    float gain_attack_ = 0.01f;
    float gain_release_ = 0.0001f;
};

/*
 * EQ: sculpts the tone with three bands — a 250 Hz low split (BASS), a
 * 1500 Hz split of the remainder (MID), and whatever is left (TREBLE).
 * Each knob is about +/-12 dB of boost/cut on its band's share; all three
 * flat recombine to the exact input (the bands sum to the signal).
 */
class EqualizerPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        lp1_ = lp2_ = 0.0f;
        setParams(bass_, mid_, treble_);
    }

    void setParams(float bass, float mid, float treble) {
        bass_ = std::clamp(bass, 0.0f, 1.0f);
        mid_ = std::clamp(mid, 0.0f, 1.0f);
        treble_ = std::clamp(treble, 0.0f, 1.0f);
        g_bass_ = (bass_ * 2.0f - 1.0f) * 0.75f;   // -0.75 .. +0.75
        g_mid_ = (mid_ * 2.0f - 1.0f) * 0.75f;
        g_treb_ = (treble_ * 2.0f - 1.0f) * 0.75f;
        lp1_coef_ = 1.0f - std::exp(-6.2831853f * 250.0f / fs_);
        lp2_coef_ = 1.0f - std::exp(-6.2831853f * 1500.0f / fs_);
    }

    float process(float in) override {
        lp1_ += lp1_coef_ * (in - lp1_);
        const float bass = lp1_;
        const float rest = in - lp1_;
        lp2_ += lp2_coef_ * (rest - lp2_);
        const float midBand = lp2_;
        const float treble = rest - lp2_;
        return in + g_bass_ * bass + g_mid_ * midBand + g_treb_ * treble;
    }

private:
    float fs_ = 44100.0f;
    float bass_ = 0.5f;
    float mid_ = 0.5f;
    float treble_ = 0.5f;
    float g_bass_ = 0.0f;
    float g_mid_ = 0.0f;
    float g_treb_ = 0.0f;
    float lp1_coef_ = 0.035f;
    float lp2_coef_ = 0.19f;
    float lp1_ = 0.0f;
    float lp2_ = 0.0f;
};

/*
 * Wah: the vocal sweep — a resonant band-pass (RBJ biquad, unit peak
 * gain) whose center frequency slides across the spectrum. RATE sweeps
 * it with an LFO (auto-wah); 0 holds the filter at the CENTER position
 * (the foot rocker held still). CENTER spans 200 Hz..3 kHz (log),
 * RESONANCE sets the filter's Q (1..20, the vocal peak).
 */
class WahPedal : public Pedal {
public:
    void prepare(float sample_rate) override {
        fs_ = sample_rate;
        x1_ = x2_ = y1_ = y2_ = 0.0f;
        phase_ = 0.0f;
        setParams(rate_, center_, resonance_);
    }

    void setParams(float rate, float center, float resonance) {
        rate_ = std::clamp(rate, 0.0f, 1.0f);
        center_ = std::clamp(center, 0.0f, 1.0f);
        resonance_ = std::clamp(resonance, 0.0f, 1.0f);
        rate_hz_ = 8.0f * rate_;                         // 0 .. 8 Hz (0 = static)
        center_hz_ = 200.0f * std::pow(15.0f, center_);  // 200 Hz .. 3 kHz
        q_ = 20.0f - 19.0f * resonance_;                 // Q 20 .. 1 (knob up = peaky)
    }

    float process(float in) override {
        float fc = center_hz_;
        if (rate_hz_ > 0.0f) {
            phase_ += 6.2831853f * rate_hz_ / fs_;
            if (phase_ > 6.2831853f) {
                phase_ -= 6.2831853f;
            }
            fc = center_hz_ * std::pow(2.0f, std::sin(phase_)); // +/- 1 octave
        }
        // RBJ band-pass biquad (constant 0 dB peak gain)
        const float w0 = 6.2831853f * fc / fs_;
        const float alpha = std::sin(w0) / (2.0f * q_);
        const float a0 = 1.0f + alpha;
        const float b0 = alpha / a0;
        const float b2 = -alpha / a0;
        const float a1 = -2.0f * std::cos(w0) / a0;
        const float a2 = (1.0f - alpha) / a0;
        const float y = b0 * in + b2 * x2_ - a1 * y1_ - a2 * y2_;
        x2_ = x1_;
        x1_ = in;
        y2_ = y1_;
        y1_ = y;
        return y;
    }

private:
    float fs_ = 44100.0f;
    float rate_ = 0.0f;
    float center_ = 0.5f;
    float resonance_ = 0.3f;
    float rate_hz_ = 0.0f;
    float center_hz_ = 1000.0f;
    float q_ = 10.0f;
    float x1_ = 0.0f;
    float x2_ = 0.0f;
    float y1_ = 0.0f;
    float y2_ = 0.0f;
    float phase_ = 0.0f;
};

/*
 * Fixed-order chain. One instance per channel (delay/chorus-style pedals
 * keep per-channel state), params snapped from atomics once per block.
 */
class PedalChain {
public:
    void prepare(float sample_rate) {
        fs_ = sample_rate;
        boost_.prepare(fs_);
        fuzz_.prepare(fs_);
        distortion_.prepare(fs_);
        overdrive_.prepare(fs_);
        delay_.prepare(fs_);
        chorus_.prepare(fs_);
        flanger_.prepare(fs_);
        phaser_.prepare(fs_);
        tremolo_.prepare(fs_);
        vibrato_.prepare(fs_);
        compressor_.prepare(fs_);
        eq_.prepare(fs_);
        wah_.prepare(fs_);
    }

    void setBoost(bool enabled, float gain_db) {
        boost_.enabled = enabled;
        boost_.setParams(gain_db);
    }

    void setFuzz(bool enabled, float fuzz, float volume) {
        fuzz_.enabled = enabled;
        fuzz_.setParams(fuzz, volume);
    }

    void setOverdrive(bool enabled, float drive, float tone) {
        overdrive_.enabled = enabled;
        overdrive_.setParams(drive, tone);
    }

    void setDistortion(bool enabled, float drive, float tone, float level) {
        distortion_.enabled = enabled;
        distortion_.setParams(drive, tone, level);
    }

    void setDelay(bool enabled, float time_seconds, float feedback, float mix) {
        delay_.enabled = enabled;
        delay_.setParams(time_seconds, feedback, mix);
    }

    void setChorus(bool enabled, float rate, float depth, float mix) {
        chorus_.enabled = enabled;
        chorus_.setParams(rate, depth, mix);
    }

    void setFlanger(bool enabled, float rate, float depth, float feedback, float mix) {
        flanger_.enabled = enabled;
        flanger_.setParams(rate, depth, feedback, mix);
    }

    void setPhaser(bool enabled, float rate, float depth, float regen, float mix) {
        phaser_.enabled = enabled;
        phaser_.setParams(rate, depth, regen, mix);
    }

    void setTremolo(bool enabled, float rate, float depth) {
        tremolo_.enabled = enabled;
        tremolo_.setParams(rate, depth);
    }

    void setVibrato(bool enabled, float rate, float depth, float mix) {
        vibrato_.enabled = enabled;
        vibrato_.setParams(rate, depth, mix);
    }

    void setCompressor(bool enabled, float sustain, float level) {
        compressor_.enabled = enabled;
        compressor_.setParams(sustain, level);
    }

    void setEq(bool enabled, float bass, float mid, float treble) {
        eq_.enabled = enabled;
        eq_.setParams(bass, mid, treble);
    }

    void setWah(bool enabled, float rate, float center, float resonance) {
        wah_.enabled = enabled;
        wah_.setParams(rate, center, resonance);
    }

    float process(float in) {
        if (boost_.enabled) {
            in = boost_.process(in);
        }
        if (fuzz_.enabled) {
            in = fuzz_.process(in);
        }
        if (overdrive_.enabled) {
            in = overdrive_.process(in);
        }
        if (distortion_.enabled) {
            in = distortion_.process(in);
        }
        if (delay_.enabled) {
            in = delay_.process(in);
        }
        if (chorus_.enabled) {
            in = chorus_.process(in);
        }
        if (flanger_.enabled) {
            in = flanger_.process(in);
        }
        if (phaser_.enabled) {
            in = phaser_.process(in);
        }
        if (tremolo_.enabled) {
            in = tremolo_.process(in);
        }
        if (vibrato_.enabled) {
            in = vibrato_.process(in);
        }
        if (compressor_.enabled) {
            in = compressor_.process(in);
        }
        if (eq_.enabled) {
            in = eq_.process(in);
        }
        if (wah_.enabled) {
            in = wah_.process(in);
        }
        return in;
    }

private:
    float fs_ = 44100.0f;
    BoostPedal boost_;
    FuzzPedal fuzz_;
    OverdrivePedal overdrive_;
    DistortionPedal distortion_;
    DelayPedal delay_;
    ChorusPedal chorus_;
    FlangerPedal flanger_;
    PhaserPedal phaser_;
    TremoloPedal tremolo_;
    VibratoPedal vibrato_;
    CompressorPedal compressor_;
    EqualizerPedal eq_;
    WahPedal wah_;
};

} // namespace gte
