#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace gte {

/*
 * Mono master FX chain: drive -> tone -> reverb -> master gain.
 *
 *  - drive:  0..1, TubeScreamer-style clipping stage. A one-pole high-pass
 *    (400 Hz, 6 dB/oct) keeps low fundamentals from turning into fuzz, then a
 *    variable gain (1 .. 25) feeds a tanh waveshaper, a moderate makeup gain
 *    restores loudness, and a dry/wet crossfade blends it in.
 *  - tone:   0..1, two cascaded one-pole low-passes (12 dB/oct), cutoff
 *    swept exponentially 500 Hz (dark) .. 16 kHz (bright).
 *  - reverb: 0..1 mix, Freeverb-style network: 8 parallel damped combs
 *    (feedback 0.72..0.88 rising with the mix, one-pole damping in the loop
 *    absorbs the metallic high-frequency ringing) followed by 4 series
 *    allpasses (gain 0.5). Channel 1 offsets every delay by Freeverb's
 *    23-sample stereo spread, so the per-channel FX instances produce a
 *    decorrelated wide tail instead of a doubled mono one.
 *  - master: 0..2 output gain, final hard clamp to +/-0.98.
 *
 * Used identically by the real-time callback and the offline WAV renderer so
 * both paths sound the same and offline renders stay CI-verifiable.
 */
class FxDsp {
public:
    void prepare(float sample_rate) { prepare(sample_rate, 0); }

    void prepare(float sample_rate, int channel) {
        fs_ = sample_rate;
        const float scale = fs_ / 44100.0f;
        const float spread = (channel == 1) ? kStereoSpread * scale : 0.0f;

        comb_.clear();
        for (float n : {1116.0f, 1188.0f, 1277.0f, 1356.0f,
                        1422.0f, 1491.0f, 1557.0f, 1617.0f}) {
            const int size = std::max(2, static_cast<int>(n * scale + spread));
            comb_.push_back(Comb{std::vector<float>(static_cast<size_t>(size), 0.0f), 0, 0.0f});
        }
        allpass_.clear();
        for (float n : {556.0f, 441.0f, 341.0f, 225.0f}) {
            const int size = std::max(2, static_cast<int>(n * scale + spread));
            allpass_.push_back(Allpass{std::vector<float>(static_cast<size_t>(size), 0.0f), 0});
        }

        hp_state_ = 0.0f;
        tone_s1_ = tone_s2_ = 0.0f;
        setParams(drive_, tone_, reverb_mix_, master_);
    }

    void setParams(float drive, float tone, float reverb_mix, float master) {
        drive_ = std::clamp(drive, 0.0f, 1.0f);
        tone_ = std::clamp(tone, 0.0f, 1.0f);
        reverb_mix_ = std::clamp(reverb_mix, 0.0f, 1.0f);
        master_ = std::clamp(master, 0.0f, 2.0f);

        const float cutoff = 500.0f * std::pow(32.0f, tone_); // 500 Hz .. 16 kHz
        tone_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
        hp_coef_ = 1.0f - std::exp(-6.2831853f * 400.0f / fs_);

        clip_gain_ = 1.0f + drive_ * 24.0f;
        makeup_ = 0.9f + drive_ * 0.7f;

        comb_fb_ = 0.72f + 0.16f * reverb_mix_;
        wet_gain_ = 0.9f * reverb_mix_ * (0.35f + comb_fb_); // louder tail at longer decay
    }

    float process(float in) {
        // drive: 400 Hz high-pass -> gain -> tanh -> makeup -> dry/wet blend
        float shaped = in;
        if (drive_ > 0.0005f) {
            hp_state_ += hp_coef_ * (in - hp_state_);
            const float hp = in - hp_state_;
            const float clipped = std::tanh(clip_gain_ * hp) * makeup_;
            shaped = (1.0f - drive_) * in + drive_ * clipped;
        }

        // tone: two cascaded one-pole low-passes (12 dB/oct)
        tone_s1_ += tone_coef_ * (shaped - tone_s1_);
        tone_s2_ += tone_coef_ * (tone_s1_ - tone_s2_);
        const float dry = tone_s2_;

        // reverb: 8 parallel damped combs -> 4 series allpasses
        float wet = 0.0f;
        for (auto& c : comb_) {
            const float out = c.buf[c.idx];
            c.filterstore = out * kDamp2 + c.filterstore * kDamp1;
            c.buf[c.idx] = dry + c.filterstore * comb_fb_;
            c.idx = (c.idx + 1) % c.buf.size();
            wet += out;
        }
        wet *= 0.125f;
        for (auto& a : allpass_) {
            const float bufOut = a.buf[a.idx];
            const float y = bufOut - kApGain * wet;
            a.buf[a.idx] = wet + kApGain * bufOut;
            a.idx = (a.idx + 1) % a.buf.size();
            wet = y;
        }

        const float mixed = dry * (1.0f - reverb_mix_) + wet * wet_gain_;
        const float out = mixed * master_;
        if (out > 0.98f) return 0.98f;
        if (out < -0.98f) return -0.98f;
        return out;
    }

private:
    struct Comb {
        std::vector<float> buf;
        size_t idx;
        float filterstore;
    };
    struct Allpass {
        std::vector<float> buf;
        size_t idx;
    };

    static constexpr float kStereoSpread = 23.0f; // Freeverb comb/allpass offset, samples @44.1k
    static constexpr float kApGain = 0.5f;
    static constexpr float kDamp1 = 0.4f; // loop damping (LP state weight)
    static constexpr float kDamp2 = 0.6f; // fresh-sample weight

    float fs_ = 44100.0f;
    float drive_ = 0.0f;
    float tone_ = 1.0f;
    float reverb_mix_ = 0.0f;
    float master_ = 1.0f;
    float clip_gain_ = 1.0f;
    float makeup_ = 0.9f;
    float hp_coef_ = 0.05f;
    float hp_state_ = 0.0f;
    float tone_coef_ = 0.5f;
    float tone_s1_ = 0.0f;
    float tone_s2_ = 0.0f;
    float comb_fb_ = 0.72f;
    float wet_gain_ = 0.0f;
    std::vector<Comb> comb_;
    std::vector<Allpass> allpass_;
};

} // namespace gte
