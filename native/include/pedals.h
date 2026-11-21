#pragma once

#include <algorithm>
#include <cmath>

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
class OverdrivePedal : public Pedal {
public:
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
 * Fixed-order chain. One instance per channel (delay/chorus-style pedals
 * keep per-channel state), params snapped from atomics once per block.
 */
class PedalChain {
public:
    void prepare(float sample_rate) {
        fs_ = sample_rate;
        boost_.prepare(fs_);
    }

    void setBoost(bool enabled, float gain_db) {
        boost_.enabled = enabled;
        boost_.setParams(gain_db);
    }

    void setOverdrive(bool enabled, float drive, float tone) {
        overdrive_.enabled = enabled;
        overdrive_.setParams(drive, tone);
    }

    void setDistortion(bool enabled, float drive, float tone, float level) {
        distortion_.enabled = enabled;
        distortion_.setParams(drive, tone, level);
    }

    float process(float in) {
        if (boost_.enabled) {
            in = boost_.process(in);
        }
        if (overdrive_.enabled) {
            in = overdrive_.process(in);
        }
        if (distortion_.enabled) {
            in = distortion_.process(in);
        }
        return in;
    }

private:
    float fs_ = 44100.0f;
    BoostPedal boost_;
    OverdrivePedal overdrive_;
    DistortionPedal distortion_;
};

} // namespace gte
