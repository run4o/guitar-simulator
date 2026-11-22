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
        pre_state_ = hp2_state_ = dc_state_ = 0.0f;
        prev_in_ = 0.0f;
        gate_env_ = 0.0f;
        std::fill(std::begin(sub_ring_), std::end(sub_ring_), 0.0f);
        sub_w_ = 0;
        scoop_x1_ = scoop_x2_ = scoop_y1_ = scoop_y2_ = 0.0f;
        pres_x1_ = pres_x2_ = pres_y1_ = pres_y2_ = 0.0f;
        setParams(drive_, tone_, reverb_mix_, master_);
    }

    void setParams(float drive, float tone, float reverb_mix, float master) {
        drive_ = std::clamp(drive, 0.0f, 1.0f);
        tone_ = std::clamp(tone, 0.0f, 1.0f);
        reverb_mix_ = std::clamp(reverb_mix, 0.0f, 1.0f);
        master_ = std::clamp(master, 0.0f, 2.0f);
        model_ = std::clamp(model_, 0, 2);

        const float cutoff = 500.0f * std::pow(32.0f, tone_); // 500 Hz .. 16 kHz
        tone_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
        hp_coef_ = 1.0f - std::exp(-6.2831853f * 400.0f / fs_);

        // Oversampling: 32-tap Blackman-windowed sinc anti-alias FIR, cutoff
        // 20 kHz at the 4x rate. Recomputed only when fs changes.
        if (static_cast<int>(fir_.size()) != 32 || last_fir_fs_ != fs_) {
            fir_.assign(32, 0.0f);
            const float fc = 20000.0f / (4.0f * fs_);
            float sum = 0.0f;
            for (int k = 0; k < 32; ++k) {
                const float m = k - 15.5f;
                const float sinc = m == 0.0f ? 2.0f * fc
                    : std::sin(6.2831853f * fc * m) / (3.14159265f * m);
                const float win = 0.42f - 0.5f * std::cos(6.2831853f * k / 31.0f)
                    + 0.08f * std::cos(12.5663706f * k / 31.0f);
                fir_[k] = sinc * win;
                sum += fir_[k];
            }
            for (float& h : fir_) {
                h /= sum;
            }
            last_fir_fs_ = fs_;
        }

        // channel voicings. CLEAN keeps the classic single soft-clip; CRUNCH
        // and METAL are always-on cascaded channels (a lead channel is
        // saturated at knob 0 — DRIVE is the channel's gain, not a blend).
        // Gains assume the small signal the strings deliver (~0.1 peak).
        const float os_fs = 4.0f * fs_;
        pre_cut_coef_ = 1.0f - std::exp(-6.2831853f * 100.0f / os_fs);
        dc_coef_ = 1.0f - std::exp(-6.2831853f * 12.0f / os_fs);
        if (model_ == 2) { // METAL: deep cascade, scooped, tight
            s1_gain_ = 4.0f + drive_ * 2.0f;
            clip_gain_ = 30.0f + drive_ * 60.0f;
            makeup_ = 1.3f;
            asym_ = 0.12f;
            gate_threshold_ = 0.0006f;
        } else if (model_ == 1) { // CRUNCH: same structure, milder voicing
            s1_gain_ = 2.0f + drive_ * 1.0f;
            clip_gain_ = 8.0f + drive_ * 25.0f;
            makeup_ = 1.0f;
            asym_ = 0.05f;
            gate_threshold_ = 0.0004f;
        } else { // CLEAN: the original single-tanh path
            clip_gain_ = 1.0f + drive_ * 24.0f;
            makeup_ = 0.9f + drive_ * 0.7f;
        }
        // post-EQ biquads: RBJ peaking mid scoop + high-shelf presence.
        // The scoop is shared; the presence boost is the voicing's fang —
        // metal leans on it hard, crunch barely.
        float scoopDb = -4.0f;
        float presenceDb = 1.5f;
        if (model_ == 2) {
            scoopDb = -4.0f;
            presenceDb = 7.0f;
        } else if (model_ == 1) {
            presenceDb = 1.5f;
        }
        {
            const float w0 = 6.2831853f * 500.0f / fs_;
            const float alpha = std::sin(w0) / (2.0f * 0.8f);
            const float A = std::pow(10.0f, scoopDb / 40.0f);
            const float a0 = 1.0f + alpha / A;
            scoop_.assign({
                (1.0f + alpha * A) / a0,
                -2.0f * std::cos(w0) / a0,
                (1.0f - alpha * A) / a0,
                -2.0f * std::cos(w0) / a0,
                (1.0f - alpha / A) / a0});
            const float wp = 6.2831853f * 3500.0f / fs_;
            const float Ap = std::pow(10.0f, presenceDb / 40.0f);
            const float alp = std::sin(wp) / 2.0f * std::sqrt(2.0f);
            const float sq = 2.0f * std::sqrt(Ap) * alp;
            const float cw = std::cos(wp);
            const float p0 = (Ap + 1.0f) - (Ap - 1.0f) * cw + sq;
            presence_.assign({
                Ap * ((Ap + 1.0f) + (Ap - 1.0f) * cw + sq) / p0,
                -2.0f * Ap * ((Ap - 1.0f) + Ap * cw) / p0,
                Ap * ((Ap + 1.0f) + (Ap - 1.0f) * cw - sq) / p0,
                (-2.0f * ((Ap - 1.0f) - (Ap + 1.0f) * cw)) / p0,
                ((Ap + 1.0f) - (Ap - 1.0f) * cw - sq) / p0});
        }

        comb_fb_ = 0.72f + 0.16f * reverb_mix_;
        wet_gain_ = 0.9f * reverb_mix_ * (0.35f + comb_fb_); // louder tail at longer decay
    }

    /** Amp channel: 0 clean, 1 crunch, 2 metal. */
    void setModel(int model) {
        model_ = std::clamp(model, 0, 2);
        setParams(drive_, tone_, reverb_mix_, master_);
    }

    // Oversampled cascade: pre-cut -> input stage -> main clipper with a
    // touch of asymmetric diode bite (DC-blocked). Runs at 4x rate.
    float cascade(float x) {
        float s = x - pre_state_;
        pre_state_ += pre_cut_coef_ * (x - pre_state_);
        s = std::tanh(s1_gain_ * s);
        s = s - hp2_state_;
        hp2_state_ += pre_cut_coef_ * (s - hp2_state_);
        float c = std::tanh(clip_gain_ * s);
        c += asym_ * c * c;
        c -= dc_state_;
        dc_state_ += dc_coef_ * (c - dc_state_);
        return c;
    }

    // One biquad pass (b0 b1 b2 a1 a2 packed in coeffs).
    static float biquad(const float coeffs[5], float& x1, float& x2, float& y1, float& y2,
                        float in) {
        const float y = coeffs[0] * in + coeffs[1] * x1 + coeffs[2] * x2
            - coeffs[3] * y1 - coeffs[4] * y2;
        x2 = x1; x1 = in; y2 = y1; y1 = y;
        return y;
    }

    float process(float in) {
        // drive: 400 Hz high-pass -> gain -> tanh -> makeup -> dry/wet blend
        float shaped = in;
        if (model_ != 0) {
            // 4x linear upsample between the previous and current input,
            // cascade at 4x rate, 32-tap sinc downsample.
            for (int j = 1; j <= 4; ++j) {
                const float x = prev_in_ + (in - prev_in_) * (j / 4.0f);
                sub_ring_[sub_w_] = cascade(x);
                sub_w_ = sub_w_ + 1 == 32 ? 0 : sub_w_ + 1;
            }
            prev_in_ = in;
            float y = 0.0f;
            for (int k = 0; k < 32; ++k) {
                const int idx = sub_w_ - 1 - k < 0 ? sub_w_ - 1 - k + 32 : sub_w_ - 1 - k;
                y += fir_[k] * sub_ring_[idx];
            }
            // post-EQ at the native rate
            y = biquad(scoop_.data(), scoop_x1_, scoop_x2_, scoop_y1_, scoop_y2_, y);
            y = biquad(presence_.data(), pres_x1_, pres_x2_, pres_y1_, pres_y2_, y);
            // light internal gate: kills the mush floor, never the note
            const float a = std::fabs(y);
            const float coef = a > gate_env_
                ? 1.0f - std::exp(-1.0f / (0.001f * fs_))
                : 1.0f - std::exp(-1.0f / (0.03f * fs_));
            gate_env_ += coef * (a - gate_env_);
            if (gate_env_ < gate_threshold_) {
                const float r = gate_env_ / gate_threshold_;
                y *= r * r;
            }
            shaped = y * makeup_;
        } else if (drive_ > 0.0005f) {
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
        float out = mixed * master_;
        // Soft ceiling: linear below 0.98, smoothly limited above — a hard
        // clamp would re-alias everything the oversampled clipper avoided.
        constexpr float kCeil = 0.98f;
        constexpr float kKnee = 0.03f;
        if (out > kCeil) {
            out = kCeil + kKnee * std::tanh((out - kCeil) / kKnee);
        } else if (out < -kCeil) {
            out = -kCeil + kKnee * std::tanh((out + kCeil) / kKnee);
        }
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
    int model_ = 0;
    float drive_ = 0.0f;
    float tone_ = 1.0f;
    float reverb_mix_ = 0.0f;
    float master_ = 1.0f;
    float clip_gain_ = 1.0f;
    float makeup_ = 0.9f;
    float s1_gain_ = 1.0f;
    float asym_ = 0.0f;
    float gate_threshold_ = 0.0f;
    float pre_cut_coef_ = 0.01f;
    float dc_coef_ = 0.01f;
    float hp_coef_ = 0.05f;
    float hp_state_ = 0.0f;
    float pre_state_ = 0.0f;
    float hp2_state_ = 0.0f;
    float dc_state_ = 0.0f;
    float prev_in_ = 0.0f;
    float gate_env_ = 0.0f;
    float sub_ring_[32] = {};
    int sub_w_ = 0;
    float last_fir_fs_ = 0.0f;
    std::vector<float> fir_;
    std::vector<float> scoop_;    // b0 b1 b2 a1 a2
    std::vector<float> presence_; // b0 b1 b2 a1 a2
    float scoop_x1_ = 0.0f, scoop_x2_ = 0.0f, scoop_y1_ = 0.0f, scoop_y2_ = 0.0f;
    float pres_x1_ = 0.0f, pres_x2_ = 0.0f, pres_y1_ = 0.0f, pres_y2_ = 0.0f;
    float tone_coef_ = 0.5f;
    float tone_s1_ = 0.0f;
    float tone_s2_ = 0.0f;
    float comb_fb_ = 0.72f;
    float wet_gain_ = 0.0f;
    std::vector<Comb> comb_;
    std::vector<Allpass> allpass_;
};

/*
 * Pickup stage: the hot instrument output a real amp expects to see.
 * Sits before the pedalboard — soft saturation plus a resonant presence
 * peak and a top roll-off, per guitar model. Acoustic stays transparent
 * (clean tones keep their character); single coil runs mild, humbucker
 * runs hot and dark, feeding the amp the harmonic-rich DI it can bite.
 */
class PickupStage {
public:
    void prepare(float sample_rate) {
        fs_ = sample_rate;
        lp1_ = lp2_ = 0.0f;
        pk_x1_ = pk_x2_ = pk_y1_ = pk_y2_ = 0.0f;
        setModel(model_);
    }

    void setModel(int model) {
        model_ = std::clamp(model, 0, 2);
        // resonant presence peak + top roll-off + soft saturation
        if (model_ == 2) { // humbucker: hot, dark, compressed
            drive_ = 2.5f;
            makeup_ = 0.72f;
            peak_hz_ = 700.0f;
            peak_q_ = 1.0f;
            peak_db_ = 2.0f;
            lp_hz_ = 7500.0f;
        } else if (model_ == 1) { // single coil: milder, brighter
            drive_ = 1.5f;
            makeup_ = 0.85f;
            peak_hz_ = 1600.0f;
            peak_q_ = 1.2f;
            peak_db_ = 2.5f;
            lp_hz_ = 9500.0f;
        } else { // acoustic: transparent
            drive_ = 1.0f;
            makeup_ = 1.0f;
        }
        recompute();
    }

    float process(float in) {
        if (model_ == 0) {
            return in;
        }
        float x = in;
        // RBJ peaking: the pickup's resonant hump
        const float y1 = pk_[0] * x + pk_[1] * pk_x1_ + pk_[2] * pk_x2_
            - pk_[3] * pk_y1_ - pk_[4] * pk_y2_;
        pk_x2_ = pk_x1_; pk_x1_ = x; pk_y2_ = pk_y1_; pk_y1_ = y1;
        // one-pole top roll-off (two for a gentler slope)
        lp1_ += lp_coef_ * (y1 - lp1_);
        lp2_ += lp_coef_ * (lp1_ - lp2_);
        // soft saturation: the hot pickup's compression
        return std::tanh(drive_ * lp2_) * makeup_;
    }

private:
    void recompute() {
        const float w0 = 6.2831853f * peak_hz_ / fs_;
        const float alpha = std::sin(w0) / (2.0f * peak_q_);
        const float A = std::pow(10.0f, peak_db_ / 40.0f);
        const float a0 = 1.0f + alpha / A;
        pk_.assign({
            (1.0f + alpha * A) / a0,
            -2.0f * std::cos(w0) / a0,
            (1.0f - alpha * A) / a0,
            -2.0f * std::cos(w0) / a0,
            (1.0f - alpha / A) / a0});
        lp_coef_ = 1.0f - std::exp(-6.2831853f * lp_hz_ / fs_);
    }

    float fs_ = 44100.0f;
    int model_ = 0;
    float drive_ = 1.0f;
    float makeup_ = 1.0f;
    float peak_hz_ = 700.0f;
    float peak_q_ = 1.0f;
    float peak_db_ = 0.0f;
    float lp_hz_ = 7500.0f;
    float lp_coef_ = 0.5f;
    float lp1_ = 0.0f;
    float lp2_ = 0.0f;
    float pk_x1_ = 0.0f, pk_x2_ = 0.0f, pk_y1_ = 0.0f, pk_y2_ = 0.0f;
    std::vector<float> pk_;
};

} // namespace gte
