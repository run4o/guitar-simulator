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
 *  - guitar simulations: acoustic (full string decay) or magnetic-pickup models
 *    (electric single coil / humbucker) that render a weighted sum of two taps
 *    of the same delay line at the pickup position — pickup comb notches and
 *    brighter, longer-sustained voicing per model.
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
        set_envelope(model_t60_, model_cutoff_);
    }

    void set_open_frequency(float hz) {
        if (hz > 0.0f) open_frequency_ = hz;
    }

    void pluck(int fret, float velocity, bool palm_mute) {
        float f = open_frequency_ * std::pow(2.0f, fret / 12.0f);
        nominal_delay_ = clamp_delay(fs_ / f - disp_gd_);
        delay_ = nominal_delay_;
        target_delay_ = nominal_delay_;
        slew_per_sample_ = 0.0f;
        set_envelope(palm_mute ? kPalmMuteT60 : model_t60_ * sustain_mult_,
                     palm_mute ? kPalmMuteCutoff : model_cutoff_);
        excite(velocity);
    }

    /* Hammer-on / pull-off: retune without new excitation. `slew_seconds`
     * > 0 gives an audible glide (true slide); 0 = the fast default. */
    void legato(int target_fret, float slew_seconds) {
        float f = open_frequency_ * std::pow(2.0f, target_fret / 12.0f);
        nominal_delay_ = clamp_delay(fs_ / f - disp_gd_);
        slew_to(nominal_delay_,
                slew_seconds > 0.0f ? slew_seconds : kLegatoSlewSeconds);
        set_envelope(model_t60_ * sustain_mult_, model_cutoff_);
    }

    /* Bend (also used for slides): pitch glides by `semitones` relative to the
     * current nominal pitch; slow slew keeps the allpass interpolation smooth. */
    void bend(float semitones) {
        nominal_delay_ = clamp_delay(nominal_delay_ * std::pow(2.0f, -semitones / 12.0f));
        slew_to(nominal_delay_, kBendSlewSeconds);
        set_envelope(model_t60_ * sustain_mult_, model_cutoff_);
    }

    /* Natural/artificial harmonic: the node at `node_fret` sounds the
     * partial 1/(1-2^(-f/12)) of the open string (fret 12 -> octave, 7 ->
     * fifth+octave, 5 -> two octaves). The delay line is initialized with a
     * pure sine of that partial, giving the flute-like harmonic timbre. */
    void pluck_harmonic(int node_fret, float velocity, bool palm_mute) {
        float partial = 1.0f / (1.0f - std::pow(2.0f, -node_fret / 12.0f));
        nominal_delay_ = clamp_delay(fs_ / (open_frequency_ * partial) - disp_gd_);
        delay_ = nominal_delay_;
        target_delay_ = nominal_delay_;
        slew_per_sample_ = 0.0f;
        set_envelope(palm_mute ? kPalmMuteT60 : kHarmonicT60, model_cutoff_);
        excite_harmonic(velocity);
    }

    void mute() {
        set_envelope(kMuteT60, model_cutoff_);
        slew_to(delay_, 0.0f); // stop any glide in progress
    }

    /* Pitch vibrato: a 5.5 Hz LFO on the loop delay while the note rings.
     * Ramps in over ~120 ms so the attack stays clean; any new articulation
     * on the string clears it. */
    void set_vibrato(bool on) {
        vibrato_on_ = on;
        vibrato_phase_ = 0.0f;
        vibrato_env_ = 0.0f;
    }

    /* Guitar simulations: 0 = acoustic (current behavior), 1 = electric single
     * coil, 2 = electric humbucker. Switching re-voices the string and changes
     * the output from the full string decay to magnetic-pickup tap sums. */
    void set_guitar_model(int model) {
        model_ = model;
        switch (model) {
            case 1: // single coil: bright, thin low end, very long sustain
                model_t60_ = 9.0f;
                model_cutoff_ = 11000.0f;
                excite_smooth_ = 0.05f;
                pickup_frac_ = 0.14f;
                pickup_frac2_ = -1.0f; // single pickup
                break;
            case 2: // humbucker: fatter, hotter pick, slightly shorter
                model_t60_ = 7.0f;
                model_cutoff_ = 8500.0f;
                excite_smooth_ = 0.2f;
                pickup_frac_ = 0.10f;
                pickup_frac2_ = 0.15f; // second coil 5% along the string
                break;
            default: // acoustic: full-body, warm
                model_ = 0;
                model_t60_ = kNormalT60;
                model_cutoff_ = kNormalCutoff;
                excite_smooth_ = 0.4f;
                pickup_frac_ = -1.0f;
                pickup_frac2_ = -1.0f;
                break;
        }
        // string stiffness: per-model dispersion strength; the DC group
        // delay of the two loop allpasses is pre-compensated in the tuning
        disp_a_ = model_ == 0 ? 0.30f : (model_ == 1 ? 0.40f : 0.45f);
        disp_gd_ = 2.0f * (2.0f * disp_a_ / (1.0f - disp_a_ * disp_a_));
        set_envelope(model_t60_, model_cutoff_);
    }

    /** Sustain multiplier (0.25..4) applied to the model's ring time —
     * knob-driven, defaults to 1.0 (nominal). Mutes/palm mutes ignore it. */
    void set_sustain_multiplier(float mult) {
        sustain_mult_ = std::clamp(mult, 0.25f, 4.0f);
    }

    float render_sample() {
        // slew delay toward target (legato / bend / slide)
        if (delay_ < target_delay_) {
            delay_ = std::min(delay_ + slew_per_sample_, target_delay_);
        } else if (delay_ > target_delay_) {
            delay_ = std::max(delay_ - slew_per_sample_, target_delay_);
        }

        // vibrato LFO: small oscillating offset on the loop delay
        float vibrato_offset = 0.0f;
        if (vibrato_on_) {
            if (vibrato_env_ < 1.0f) {
                vibrato_env_ = std::min(1.0f, vibrato_env_ + 1.0f / (kVibratoRampSeconds * fs_));
            }
            vibrato_phase_ += kVibratoRateHz / fs_;
            if (vibrato_phase_ >= 1.0f) {
                vibrato_phase_ -= 1.0f;
            }
            // delay/f relationship: ddelay/ddelay = -(2^(s/12)-1) ~= -0.0578*s
            vibrato_offset = -delay_ * 0.0578f * kVibratoSemitones * vibrato_env_
                * std::sin(6.2831853f * vibrato_phase_);
        }
        const float effective_delay = std::max(4.0f, delay_ + vibrato_offset);

        int n = static_cast<int>(effective_delay);
        float frac = effective_delay - static_cast<float>(n);
        float c = (1.0f - frac) / (1.0f + frac); // allpass coefficient = (1-d)/(1+d)
        // Keep the allpass pole (z = -c) strictly inside the unit circle: at
        // frac -> 0 the pole reaches -1 and the filter's internal state rings
        // at the sample rate forever (the metallic sustained artifact).
        c = std::min(c, 0.9f);

        float x0 = buf_[wrap(write_idx_ - n)];
        float x1 = buf_[wrap(write_idx_ - n - 1)];
        float ap = c * x0 + x1 - c * ap_state_;
        ap_state_ = ap;

        lp_state_ += loop_coef_ * (ap - lp_state_);

        float fb = lp_state_ * decay_gain_;
        // Stiffness dispersion: two first-order allpasses whose group delay
        // decreases with frequency, so upper partials travel a shorter
        // effective loop — slightly sharp, like real steel strings. The
        // nominal delay is compensated for the allpasses' DC group delay.
        if (disp_a_ > 0.0f) {
            float d1 = disp_a_ * (disp_y1_ - fb) + disp_x1_;
            disp_x1_ = fb; disp_y1_ = d1;
            float d2 = disp_a_ * (disp_y2_ - d1) + disp_x2_;
            disp_x2_ = d1; disp_y2_ = d2;
            fb = d2;
        }
        buf_[write_idx_] = fb;
        write_idx_ = wrap(write_idx_ + 1);

        if (model_ != 0) {
            /* Magnetic pickup: comb filter on the loop output. The tap pair is
             * separated by pickup_frac * loop length, giving per-harmonic
             * weighting |2 sin(pi m p)| — the true pickup-position response
             * (notches at harmonic k/p). Humbucker averages a second coil 5%
             * along the string, which fills notches -> fatter tone. */
            const int tau = std::max(2, std::min(
                    static_cast<int>(pickup_frac_ * nominal_delay_), kBufferSamples / 3));
            const float x0 = buf_[wrap(write_idx_ - n)];
            const float xt = buf_[wrap(write_idx_ - n - tau)];
            if (model_ == 1) {
                return 0.5f * (x0 - xt);
            }
            const int tau2 = std::max(tau + 2, std::min(
                    static_cast<int>(pickup_frac2_ * nominal_delay_), kBufferSamples / 3));
            const float xt2 = buf_[wrap(write_idx_ - n - tau2)];
            return 0.25f * ((x0 - xt) + (x0 - xt2));
        }

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
    static constexpr float kExciteSmooth = 0.4f; // acoustic pick burst low-pass
    static constexpr float kVibratoRateHz = 5.5f;
    static constexpr float kVibratoSemitones = 0.4f;
    static constexpr float kVibratoRampSeconds = 0.12f;
    static constexpr float kHarmonicT60 = 1.2f;

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

    /* Harmonic excitation: one clean sine cycle across the new delay line —
     * the loop then sustains exactly the partial's pitch. */
    void excite_harmonic(float velocity) {
        std::fill(buf_.begin(), buf_.end(), 0.0f); // choke any ringing energy
        int n = std::min(static_cast<int>(delay_), kBufferSamples - 8);
        for (int k = 0; k < n; ++k) {
            buf_[wrap(write_idx_ - n + k)] =
                velocity * 0.8f * std::sin(6.2831853f * static_cast<float>(k) / static_cast<float>(n));
        }
        ap_state_ = buf_[wrap(write_idx_ - 1)];
        lp_state_ = ap_state_;
    }

    void excite(float velocity) {        std::fill(buf_.begin(), buf_.end(), 0.0f); // choke any ringing energy
        disp_x1_ = disp_y1_ = disp_x2_ = disp_y2_ = 0.0f;
        int count = std::min(static_cast<int>(std::ceil(delay_)) + 4, kBufferSamples - 8);
        float amp = velocity;
        float last = 0.0f;
        double sum = 0.0;
        for (int k = 0; k < count; ++k) {
            // pick burst low-pass: acoustic = soft pick, electric = bright pick
            last += excite_smooth_ * (noise() - last);
            const float v = amp * last;
            buf_[wrap(write_idx_ - 1 - k)] = v;
            sum += v;
        }
        // Remove the burst's DC offset: the loop chain passes DC exactly, and a
        // decaying DC offset leaks back through the output DC blocker as a
        // non-decaying floor that masks the string's real T60 decay.
        const float mean = static_cast<float>(sum / count);
        if (std::fabs(mean) > 1e-6f) {
            for (int k = 0; k < count; ++k) {
                buf_[wrap(write_idx_ - 1 - k)] -= mean;
            }
        }
        ap_state_ = buf_[wrap(write_idx_ - 1)];
        lp_state_ = ap_state_;
    }

    float fs_ = 44100.0f;
    float open_frequency_;
    int model_ = 0;
    float model_t60_ = kNormalT60;
    float sustain_mult_ = 1.0f;
    float disp_a_ = 0.0f;   // stiffness dispersion allpass coefficient
    float disp_gd_ = 0.0f;  // the allpasses' DC group delay (tuning-compensated)
    float disp_x1_ = 0.0f, disp_y1_ = 0.0f;
    float disp_x2_ = 0.0f, disp_y2_ = 0.0f;
    float model_cutoff_ = kNormalCutoff;
    float excite_smooth_ = kExciteSmooth;
    float pickup_frac_ = -1.0f;
    float pickup_frac2_ = -1.0f;
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
    bool vibrato_on_ = false;
    float vibrato_phase_ = 0.0f;
    float vibrato_env_ = 0.0f;
    uint32_t rng_ = 1;
};

} // namespace gte
