#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace gte {

/*
 * Karplus-Strong plucked string with:
 *  - first-order allpass fractional-delay loop (pitch-accurate, enables bends/slides
 *    via allpass coefficient interpolation while ringing)
 *  - one-pole loop low-pass (natural high-harmonic damping; lowered for palm mute)
 *  - T60-based per-sample decay gain (pitch-independent decay time)
 *  - per-voice DC blocker
 *
 * Loop structure per sample:
 *   read tap at integer delay N  ->  allpass(+delta)  ->  one-pole LP  ->  gain  ->  write
 * Total loop delay at DC = N + delta, so f = fs / (N + delta).
 */
class PluckedString {
public:
    explicit PluckedString(float open_frequency_hz = 82.41f)
        : open_frequency_(open_frequency_hz) {}

    void prepare(float sample_rate) {
        fs_ = sample_rate;
        buf_.assign(kBufferSamples, 0.0f);
        write_idx_ = 0;
        ap_state_ = lp_state_ = dc_prev_ = dc_state_ = 0.0f;
        rng_ = 0x9E3779B9u ^ static_cast<uint32_t>(open_frequency_ * 7919.0f);
        nominal_delay_ = clamp_delay(fs_ / open_frequency_);
        delay_ = nominal_delay_;
        target_delay_ = delay_;
        slew_per_sample_ = 0.0f;
        set_envelope(kNormalT60, kNormalCutoff);
    }

    void set_open_frequency(float hz) {
        if (hz > 0.0f) open_frequency_ = hz;
    }

    void pluck(int fret, float velocity, bool palm_mute) {
        float f = open_frequency_ * std::pow(2.0f, fret / 12.0f);
        nominal_delay_ = clamp_delay(fs_ / f);
        delay_ = nominal_delay_;
        target_delay_ = nominal_delay_;
        slew_per_sample_ = 0.0f;
        set_envelope(palm_mute ? kPalmMuteT60 : kNormalT60,
                     palm_mute ? kPalmMuteCutoff : kNormalCutoff);
        excite(velocity);
    }

    /* Hammer-on / pull-off: retune without new excitation, fast slew. */
    void legato(int target_fret) {
        float f = open_frequency_ * std::pow(2.0f, target_fret / 12.0f);
        nominal_delay_ = clamp_delay(fs_ / f);
        slew_to(nominal_delay_, kLegatoSlewSeconds);
        set_envelope(t60_current_, cutoff_current_);
    }

    /* Bend (also used for slides): pitch glides by `semitones` relative to the
     * current nominal pitch; slow slew keeps the allpass interpolation smooth. */
    void bend(float semitones) {
        nominal_delay_ = clamp_delay(nominal_delay_ * std::pow(2.0f, -semitones / 12.0f));
        slew_to(nominal_delay_, kBendSlewSeconds);
        set_envelope(t60_current_, cutoff_current_);
    }

    void mute() {
        set_envelope(kMuteT60, cutoff_current_);
        slew_to(delay_, 0.0f); // stop any glide in progress
    }

    float render_sample() {
        // slew delay toward target (legato / bend / slide)
        if (delay_ < target_delay_) {
            delay_ = std::min(delay_ + slew_per_sample_, target_delay_);
        } else if (delay_ > target_delay_) {
            delay_ = std::max(delay_ - slew_per_sample_, target_delay_);
        }

        int n = static_cast<int>(delay_);
        float frac = delay_ - static_cast<float>(n);
        float c = (1.0f - frac) / (1.0f + frac); // allpass coefficient = (1-d)/(1+d)

        float x0 = buf_[wrap(write_idx_ - n)];
        float x1 = buf_[wrap(write_idx_ - n - 1)];
        float ap = c * x0 + x1 - c * ap_state_;
        ap_state_ = ap;

        lp_state_ += loop_coef_ * (ap - lp_state_);

        float fb = lp_state_ * decay_gain_;
        buf_[write_idx_] = fb;
        write_idx_ = wrap(write_idx_ + 1);

        // DC blocker: hp = x - x_prev + R*hp_prev
        float hp = fb - dc_prev_ + 0.995f * dc_state_;
        dc_prev_ = fb;
        dc_state_ = hp;
        return hp;
    }

private:
    static constexpr int kBufferSamples = 2048; // power of two; supports tunings down to ~21 Hz
    static constexpr float kNormalT60 = 3.0f;
    static constexpr float kPalmMuteT60 = 0.35f;
    static constexpr float kMuteT60 = 0.08f;
    static constexpr float kNormalCutoff = 5000.0f;
    static constexpr float kPalmMuteCutoff = 1200.0f;
    static constexpr float kLegatoSlewSeconds = 0.008f;
    static constexpr float kBendSlewSeconds = 0.18f;

    static int wrap(int i) { return i & (kBufferSamples - 1); }

    static float clamp_delay(float d) {
        return std::clamp(d, 8.0f, static_cast<float>(kBufferSamples - 16));
    }

    /*
     * The loop gain is applied once per loop round (the whole delay line is
     * rewritten once per period), so the per-round gain must be derived from
     * the loop frequency (rounds/sec = fs/loopDelay) to get a pitch-independent
     * decay time: g = exp(-6.9 * loopDelay / (fs * T60)).
     */
    void set_envelope(float t60, float cutoff) {
        t60_current_ = t60;
        cutoff_current_ = cutoff;
        decay_gain_ = std::exp(-6.9f * nominal_delay_ / (fs_ * t60));
        loop_coef_ = 1.0f - std::exp(-6.2831853f * cutoff / fs_);
    }

    void slew_to(float target, float slew_seconds) {
        target_delay_ = target;
        float delta = std::fabs(target - delay_);
        slew_per_sample_ = delta / std::max(1.0f, slew_seconds * fs_);
    }

    float noise() {
        rng_ ^= rng_ << 13;
        rng_ ^= rng_ >> 17;
        rng_ ^= rng_ << 5;
        return static_cast<float>(static_cast<int32_t>(rng_)) / 2147483648.0f;
    }

    void excite(float velocity) {
        std::fill(buf_.begin(), buf_.end(), 0.0f); // choke any ringing energy
        int count = std::min(static_cast<int>(std::ceil(delay_)) + 4, kBufferSamples - 8);
        float amp = velocity;
        float last = 0.0f;
        for (int k = 0; k < count; ++k) {
            last += 0.4f * (noise() - last); // mildly low-passed noise burst
            buf_[wrap(write_idx_ - 1 - k)] = amp * last;
        }
        ap_state_ = buf_[wrap(write_idx_ - 1)];
        lp_state_ = ap_state_;
    }

    float fs_ = 44100.0f;
    float open_frequency_;
    std::vector<float> buf_;
    int write_idx_ = 0;
    float delay_ = 100.0f;
    float target_delay_ = 100.0f;
    float nominal_delay_ = 100.0f;
    float slew_per_sample_ = 0.0f;
    float ap_state_ = 0.0f;
    float lp_state_ = 0.0f;
    float decay_gain_ = 0.999f;
    float loop_coef_ = 0.5f;
    float t60_current_ = kNormalT60;
    float cutoff_current_ = kNormalCutoff;
    float dc_prev_ = 0.0f;
    float dc_state_ = 0.0f;
    uint32_t rng_ = 1;
};

} // namespace gte
