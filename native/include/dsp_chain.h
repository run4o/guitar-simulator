#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace gte {

/*
 * Mono master FX chain (Phase 5): drive -> tone -> reverb -> master gain.
 *
 *  - drive: 0..1, crossfade from clean to tanh waveshaper (soft clipping)
 *  - tone:  0..1, one-pole low-pass, cutoff 800 Hz (dark) .. 8000 Hz (bright)
 *  - reverb_mix: 0..1, mono Schroeder-style network (4 combs + 2 allpasses),
 *    wet path only; output = dry*(1-mix) + wet*mix
 *  - master: 0..2 output gain, final hard clamp to ±0.98
 *
 * Used identically by the real-time callback and the offline WAV renderer so
 * both paths sound the same and offline renders stay CI-verifiable.
 */
class FxDsp {
public:
    void prepare(float sample_rate) {
        fs_ = sample_rate;
        const float scale = fs_ / 44100.0f;
        comb_.clear();
        for (float ms : {29.7f, 37.7f, 41.1f, 43.7f}) {
            int n = std::max(2, static_cast<int>(ms * 0.001f * scale * 44100.0f));
            comb_.push_back(Comb{std::vector<float>(static_cast<size_t>(n), 0.0f), 0, 0.85f});
        }
        allpass_.clear();
        for (float ms : {5.0f, 1.7f}) {
            int n = std::max(2, static_cast<int>(ms * 0.001f * scale * 44100.0f));
            allpass_.push_back(Allpass{std::vector<float>(static_cast<size_t>(n), 0.0f), 0, 0.7f});
        }
        tone_state_ = 0.0f;
        setParams(drive_, tone_, reverb_mix_, master_);
    }

    void setParams(float drive, float tone, float reverb_mix, float master) {
        drive_ = std::clamp(drive, 0.0f, 1.0f);
        tone_ = std::clamp(tone, 0.0f, 1.0f);
        reverb_mix_ = std::clamp(reverb_mix, 0.0f, 1.0f);
        master_ = std::clamp(master, 0.0f, 2.0f);
        const float cutoff = 800.0f * std::pow(10.0f, tone_); // 800 Hz .. 8000 Hz
        tone_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
        pre_gain_ = 1.0f + drive_ * 9.0f;
    }

    float process(float in) {
        // drive: crossfade from clean to tanh(pre * in) / tanh(pre)
        float shaped = in;
        if (drive_ > 0.0005f) {
            const float clipped = std::tanh(pre_gain_ * in) / std::tanh(pre_gain_);
            shaped = (1.0f - drive_) * in + drive_ * clipped;
        }

        // tone: one-pole low-pass
        tone_state_ += tone_coef_ * (shaped - tone_state_);
        const float dry = tone_state_;

        // reverb: 4 parallel combs -> 2 series allpasses
        float wet = 0.0f;
        for (auto& c : comb_) {
            const float delayed = c.buf[c.idx];
            c.buf[c.idx] = dry + delayed * c.feedback;
            c.idx = (c.idx + 1) % c.buf.size();
            wet += delayed;
        }
        wet *= 0.25f;
        for (auto& a : allpass_) {
            const float bufOut = a.buf[a.idx];
            const float y = bufOut - a.gain * wet;
            a.buf[a.idx] = wet + a.gain * bufOut;
            a.idx = (a.idx + 1) % a.buf.size();
            wet = y;
        }

        const float mixed = dry * (1.0f - reverb_mix_) + wet * 2.0f * reverb_mix_;
        const float out = mixed * master_;
        if (out > 0.98f) return 0.98f;
        if (out < -0.98f) return -0.98f;
        return out;
    }

private:
    struct Comb {
        std::vector<float> buf;
        size_t idx;
        float feedback;
    };
    struct Allpass {
        std::vector<float> buf;
        size_t idx;
        float gain;
    };

    static float clamp01(float v) {
        return std::clamp(v, 0.0f, 1.0f);
    }

    float fs_ = 44100.0f;
    float drive_ = 0.0f;
    float tone_ = 1.0f;
    float reverb_mix_ = 0.0f;
    float master_ = 1.0f;
    float pre_gain_ = 1.0f;
    float tone_coef_ = 0.5f;
    float tone_state_ = 0.0f;
    std::vector<Comb> comb_;
    std::vector<Allpass> allpass_;
};

} // namespace gte
