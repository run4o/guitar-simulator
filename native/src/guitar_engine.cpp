#include "guitar_engine.h"
#include "plucked_string.h"
#include "wav_writer.h"
#include "dsp_chain.h"
#include "pedals.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <mutex>
#include <vector>

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

/*
 * Phase 2 implementation: real-time synthesis engine.
 *
 * Threading model:
 *  - Java pushes EngineEvents (with absolute sample-time timestamps) into a
 *    lock-free SPSC ring buffer; it must never block the audio thread.
 *  - The miniaudio callback is the sole consumer: for each frame it applies
 *    every event whose sample_time has been reached, then renders one sample
 *    of the 6-voice mix. This gives sample-accurate event timing.
 *  - Immediate commands (pluck_string, ...) wrap push_event at the current
 *    sample clock.
 *  - Offline WAV rendering is a separate, deterministic path (Phase 1).
 */

namespace {

constexpr const char* kEngineVersion = "0.3.0";
constexpr int kStringCount = 6;
constexpr int kMaxFret = 24;

bool valid_string(int i) { return i >= 0 && i < kStringCount; }
bool valid_command_type(int t) { return t >= GTE_EVENT_PLUCK && t <= GTE_EVENT_HARMONIC; }
bool valid_model(int m) { return m >= 0 && m <= 2; }

struct EngineEvent {
    uint64_t sample_time = 0;
    uint32_t generation = 1;
    int type = 0;
    int string_index = 0;
    int fret = 0;
    float a = 0.0f;
    float b = 0.0f;
};

/* Lock-free single-producer / single-consumer ring buffer (power-of-two size).
   Producer: any Java thread (one at a time). Consumer: the audio callback. */
template <typename T, size_t Capacity>
class SpscRing {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
public:
    bool push(const T& value) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail - head_.load(std::memory_order_acquire) >= Capacity) {
            return false; // full
        }
        slots_[tail & (Capacity - 1)] = value;
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    /* Pops the next event only if its time has been reached (peek-then-consume). */
    bool pop_if_reached(uint64_t now, T& out) {
        const size_t head = head_.load(std::memory_order_relaxed);
        if (head >= tail_.load(std::memory_order_acquire)) {
            return false; // empty
        }
        const T& slot = slots_[head & (Capacity - 1)];
        if (slot.sample_time > now) {
            return false;
        }
        out = slot;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    void clear() {
        /* Only called when no producer/consumer is active (init/shutdown). */
        head_.store(tail_.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }

private:
    std::array<T, Capacity> slots_{};
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

constexpr size_t kEventQueueCapacity = 8192;

/* Master FX parameters. Java writes these atomically; the audio thread reads
   them once per callback block and the offline renderer snapshots them. */
struct FxParams {
    std::atomic<float> drive{0.0f};
    std::atomic<float> tone{1.0f};
    std::atomic<float> reverb_mix{0.0f};
    std::atomic<float> master{1.0f};
};
FxParams g_fx;

/* Pedalboard parameters (one slot per pedal, added as pedals land). */
struct PedalParams {
    std::atomic<bool> boost_enabled{false};
    std::atomic<float> boost_gain{0.0f}; // dB, 0..18
    std::atomic<bool> fuzz_enabled{false};
    std::atomic<float> fuzz_amount{0.5f};
    std::atomic<float> fuzz_volume{0.5f};
    std::atomic<bool> od_enabled{false};
    std::atomic<float> od_drive{0.5f};
    std::atomic<float> od_tone{0.5f};
    std::atomic<bool> dist_enabled{false};
    std::atomic<float> dist_drive{0.5f};
    std::atomic<float> dist_tone{0.5f};
    std::atomic<float> dist_level{0.5f};
    std::atomic<bool> delay_enabled{false};
    std::atomic<float> delay_time{0.3f};      // seconds, 0.02..1.0
    std::atomic<float> delay_feedback{0.4f};
    std::atomic<float> delay_mix{0.5f};
    std::atomic<bool> chorus_enabled{false};
    std::atomic<float> chorus_rate{0.5f};
    std::atomic<float> chorus_depth{0.5f};
    std::atomic<float> chorus_mix{0.5f};
    std::atomic<bool> flanger_enabled{false};
    std::atomic<float> flanger_rate{0.5f};
    std::atomic<float> flanger_depth{0.6f};
    std::atomic<float> flanger_feedback{0.4f};
    std::atomic<float> flanger_mix{0.5f};
    std::atomic<bool> phaser_enabled{false};
    std::atomic<float> phaser_rate{0.5f};
    std::atomic<float> phaser_depth{0.5f};
    std::atomic<float> phaser_regen{0.4f};
    std::atomic<float> phaser_mix{0.5f};
    std::atomic<bool> tremolo_enabled{false};
    std::atomic<float> tremolo_rate{0.5f};
    std::atomic<float> tremolo_depth{0.5f};
    std::atomic<bool> vibrato_enabled{false};
    std::atomic<float> vibrato_rate{0.5f};
    std::atomic<float> vibrato_depth{0.5f};
    std::atomic<float> vibrato_mix{1.0f};
    std::atomic<bool> comp_enabled{false};
    std::atomic<float> comp_sustain{0.5f};
    std::atomic<float> comp_level{0.5f};
    std::atomic<bool> eq_enabled{false};
    std::atomic<float> eq_bass{0.5f};
    std::atomic<float> eq_mid{0.5f};
    std::atomic<float> eq_treble{0.5f};
    std::atomic<bool> wah_enabled{false};
    std::atomic<float> wah_rate{0.0f};
    std::atomic<float> wah_center{0.5f};
    std::atomic<float> wah_resonance{0.3f};
    std::atomic<bool> gate_enabled{false};
    std::atomic<float> gate_threshold{0.5f};
    std::atomic<float> gate_release{0.3f};
    std::atomic<bool> oct_enabled{false};
    std::atomic<float> oct_mode{0.0f};
    std::atomic<float> oct_mix{1.0f};
    std::atomic<bool> loop_enabled{false};
    std::atomic<float> loop_length{0.5f};
    std::atomic<float> loop_level{0.7f};
};
PedalParams g_pedals;

/* Per-string channel gains (faders) + output level envelope for the VU meter. */
struct ChannelState {
    std::array<std::atomic<float>, kStringCount> gains;
    std::atomic<float> level{0.0f};
    ChannelState() {
        for (auto& g : gains) {
            g.store(1.0f, std::memory_order_relaxed);
        }
    }
};
ChannelState g_ch;

/* Peak-envelope update: fast attack, slow ~2 s release at 44.1 kHz. */
inline void update_level(float sample) {
    const float peak = std::fabs(sample);
    float level = g_ch.level.load(std::memory_order_relaxed);
    level = std::max(peak, level * 0.99995f);
    g_ch.level.store(std::min(level, 1.5f), std::memory_order_relaxed);
}

struct EngineState {
    std::mutex control_mutex; // serializes init/shutdown/start

    float sample_rate = 44100.0f;
    int buffer_frames = 512;
    bool initialized = false;

    std::atomic<uint64_t> frames_rendered{0};
    std::atomic<uint32_t> generation{1}; // bumped by reset_transport; stale events are dropped
    SpscRing<EngineEvent, kEventQueueCapacity> queue;

    float open_frequencies[kStringCount] = { 82.41f, 110.00f, 146.83f, 196.00f, 246.94f, 329.63f };
    gte::PluckedString strings[kStringCount];
    gte::FxDsp fx_l; // per-channel FX keeps the per-string pan spread
    gte::FxDsp fx_r;
    gte::PedalChain pedals_l; // pedalboard runs before the amp section
    gte::PedalChain pedals_r;

    ma_device device{};
    bool device_ready = false;
    bool started = false;
};

EngineState g;

/* Fixed per-string stereo spread: low strings left, high strings right. */
constexpr float kPanLeft[kStringCount]  = { 0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f };
constexpr float kPanRight[kStringCount] = { 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f };

void apply_command(gte::PluckedString* strings, float open_frequencies[], int type,
                   int string_index, int fret, float a, float b) {
    if (type == GTE_EVENT_SET_MODEL) {
        if (!valid_model(fret)) return;
        for (int i = 0; i < kStringCount; ++i) {
            strings[i].set_guitar_model(fret);
        }
        return;
    }
    if (!valid_string(string_index)) return;
    gte::PluckedString& v = strings[string_index];
    switch (type) {
        case GTE_EVENT_PLUCK:      v.set_vibrato(false); v.pluck(fret, a, b != 0.0f); break;
        case GTE_EVENT_LEGATO:     v.set_vibrato(false); v.legato(fret, a); break;
        case GTE_EVENT_BEND:       v.bend(a); break;
        case GTE_EVENT_MUTE:       v.set_vibrato(false); v.mute(); break;
        case GTE_EVENT_VIBRATO:    v.set_vibrato(a != 0.0f); break;
        case GTE_EVENT_HARMONIC:   v.set_vibrato(false); v.pluck_harmonic(fret, a, b != 0.0f); break;
        case GTE_EVENT_SET_TUNING:
            open_frequencies[string_index] = a;
            v.set_open_frequency(a);
            break;
        default: break;
    }
}

void apply_event(EngineState& s, const EngineEvent& ev) {
    apply_command(s.strings, s.open_frequencies, ev.type, ev.string_index, ev.fret, ev.a, ev.b);
}

/* Snapshot FX params from the atomics; called once per callback block. */
void sync_fx_params(gte::FxDsp& fx, float cached[4]) {
    const float drive = g_fx.drive.load(std::memory_order_relaxed);
    const float tone = g_fx.tone.load(std::memory_order_relaxed);
    const float mix = g_fx.reverb_mix.load(std::memory_order_relaxed);
    const float master = g_fx.master.load(std::memory_order_relaxed);
    if (drive != cached[0] || tone != cached[1] || mix != cached[2] || master != cached[3]) {
        fx.setParams(drive, tone, mix, master);
        cached[0] = drive;
        cached[1] = tone;
        cached[2] = mix;
        cached[3] = master;
    }
}

void sync_pedal_params(gte::PedalChain& chain, float cached[57]) {
    const float boostOn = g_pedals.boost_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float boostGain = g_pedals.boost_gain.load(std::memory_order_relaxed);
    const float fuzzOn = g_pedals.fuzz_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float fuzzAmt = g_pedals.fuzz_amount.load(std::memory_order_relaxed);
    const float fuzzVol = g_pedals.fuzz_volume.load(std::memory_order_relaxed);
    const float odOn = g_pedals.od_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float odDrive = g_pedals.od_drive.load(std::memory_order_relaxed);
    const float odTone = g_pedals.od_tone.load(std::memory_order_relaxed);
    const float distOn = g_pedals.dist_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float distDrive = g_pedals.dist_drive.load(std::memory_order_relaxed);
    const float distTone = g_pedals.dist_tone.load(std::memory_order_relaxed);
    const float distLevel = g_pedals.dist_level.load(std::memory_order_relaxed);
    const float delayOn = g_pedals.delay_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float delayTime = g_pedals.delay_time.load(std::memory_order_relaxed);
    const float delayFb = g_pedals.delay_feedback.load(std::memory_order_relaxed);
    const float delayMix = g_pedals.delay_mix.load(std::memory_order_relaxed);
    const float chorusOn = g_pedals.chorus_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float chorusRate = g_pedals.chorus_rate.load(std::memory_order_relaxed);
    const float chorusDepth = g_pedals.chorus_depth.load(std::memory_order_relaxed);
    const float chorusMix = g_pedals.chorus_mix.load(std::memory_order_relaxed);
    const float flOn = g_pedals.flanger_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float flRate = g_pedals.flanger_rate.load(std::memory_order_relaxed);
    const float flDepth = g_pedals.flanger_depth.load(std::memory_order_relaxed);
    const float flFb = g_pedals.flanger_feedback.load(std::memory_order_relaxed);
    const float flMix = g_pedals.flanger_mix.load(std::memory_order_relaxed);
    const float phOn = g_pedals.phaser_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float phRate = g_pedals.phaser_rate.load(std::memory_order_relaxed);
    const float phDepth = g_pedals.phaser_depth.load(std::memory_order_relaxed);
    const float phRegen = g_pedals.phaser_regen.load(std::memory_order_relaxed);
    const float phMix = g_pedals.phaser_mix.load(std::memory_order_relaxed);
    const float tremOn = g_pedals.tremolo_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float tremRate = g_pedals.tremolo_rate.load(std::memory_order_relaxed);
    const float tremDepth = g_pedals.tremolo_depth.load(std::memory_order_relaxed);
    const float vibOn = g_pedals.vibrato_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float vibRate = g_pedals.vibrato_rate.load(std::memory_order_relaxed);
    const float vibDepth = g_pedals.vibrato_depth.load(std::memory_order_relaxed);
    const float vibMix = g_pedals.vibrato_mix.load(std::memory_order_relaxed);
    const float compOn = g_pedals.comp_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float compSustain = g_pedals.comp_sustain.load(std::memory_order_relaxed);
    const float compLevel = g_pedals.comp_level.load(std::memory_order_relaxed);
    const float eqOn = g_pedals.eq_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float eqBass = g_pedals.eq_bass.load(std::memory_order_relaxed);
    const float eqMid = g_pedals.eq_mid.load(std::memory_order_relaxed);
    const float eqTreb = g_pedals.eq_treble.load(std::memory_order_relaxed);
    const float wahOn = g_pedals.wah_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float wahRate = g_pedals.wah_rate.load(std::memory_order_relaxed);
    const float wahCenter = g_pedals.wah_center.load(std::memory_order_relaxed);
    const float wahRes = g_pedals.wah_resonance.load(std::memory_order_relaxed);
    const float gateOn = g_pedals.gate_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float gateThr = g_pedals.gate_threshold.load(std::memory_order_relaxed);
    const float gateRel = g_pedals.gate_release.load(std::memory_order_relaxed);
    const float octOn = g_pedals.oct_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    const float octMode = g_pedals.oct_mode.load(std::memory_order_relaxed);
    const float octMix = g_pedals.oct_mix.load(std::memory_order_relaxed);
    if (boostOn != cached[0] || boostGain != cached[1] || fuzzOn != cached[2]
        || fuzzAmt != cached[3] || fuzzVol != cached[4] || odOn != cached[5]
        || odDrive != cached[6] || odTone != cached[7] || distOn != cached[8]
        || distDrive != cached[9] || distTone != cached[10] || distLevel != cached[11]
        || delayOn != cached[12] || delayTime != cached[13]
        || delayFb != cached[14] || delayMix != cached[15]
        || chorusOn != cached[16] || chorusRate != cached[17]
        || chorusDepth != cached[18] || chorusMix != cached[19]
        || flOn != cached[20] || flRate != cached[21]
        || flDepth != cached[22] || flFb != cached[23] || flMix != cached[24]
        || phOn != cached[25] || phRate != cached[26] || phDepth != cached[27]
        || phRegen != cached[28] || phMix != cached[29]
        || tremOn != cached[30] || tremRate != cached[31] || tremDepth != cached[32]
        || vibOn != cached[33] || vibRate != cached[34] || vibDepth != cached[35]
        || vibMix != cached[36]
        || compOn != cached[37] || compSustain != cached[38] || compLevel != cached[39]
        || eqOn != cached[40] || eqBass != cached[41] || eqMid != cached[42]
        || eqTreb != cached[43]
        || wahOn != cached[44] || wahRate != cached[45] || wahCenter != cached[46]
        || wahRes != cached[47]
        || gateOn != cached[48] || gateThr != cached[49] || gateRel != cached[50]
        || octOn != cached[51] || octMode != cached[52] || octMix != cached[53]) {
        chain.setBoost(boostOn > 0.5f, boostGain);
        chain.setFuzz(fuzzOn > 0.5f, fuzzAmt, fuzzVol);
        chain.setOverdrive(odOn > 0.5f, odDrive, odTone);
        chain.setDistortion(distOn > 0.5f, distDrive, distTone, distLevel);
        chain.setDelay(delayOn > 0.5f, delayTime, delayFb, delayMix);
        chain.setChorus(chorusOn > 0.5f, chorusRate, chorusDepth, chorusMix);
        chain.setFlanger(flOn > 0.5f, flRate, flDepth, flFb, flMix);
        chain.setPhaser(phOn > 0.5f, phRate, phDepth, phRegen, phMix);
        chain.setTremolo(tremOn > 0.5f, tremRate, tremDepth);
        chain.setVibrato(vibOn > 0.5f, vibRate, vibDepth, vibMix);
        chain.setCompressor(compOn > 0.5f, compSustain, compLevel);
        chain.setEq(eqOn > 0.5f, eqBass, eqMid, eqTreb);
        chain.setWah(wahOn > 0.5f, wahRate, wahCenter, wahRes);
        chain.setGate(gateOn > 0.5f, gateThr, gateRel);
        chain.setOctave(octOn > 0.5f, octMode, octMix);
        cached[0] = boostOn;
        cached[1] = boostGain;
        cached[2] = fuzzOn;
        cached[3] = fuzzAmt;
        cached[4] = fuzzVol;
        cached[5] = odOn;
        cached[6] = odDrive;
        cached[7] = odTone;
        cached[8] = distOn;
        cached[9] = distDrive;
        cached[10] = distTone;
        cached[11] = distLevel;
        cached[12] = delayOn;
        cached[13] = delayTime;
        cached[14] = delayFb;
        cached[15] = delayMix;
        cached[16] = chorusOn;
        cached[17] = chorusRate;
        cached[18] = chorusDepth;
        cached[19] = chorusMix;
        cached[20] = flOn;
        cached[21] = flRate;
        cached[22] = flDepth;
        cached[23] = flFb;
        cached[24] = flMix;
        cached[25] = phOn;
        cached[26] = phRate;
        cached[27] = phDepth;
        cached[28] = phRegen;
        cached[29] = phMix;
        cached[30] = tremOn;
        cached[31] = tremRate;
        cached[32] = tremDepth;
        cached[33] = vibOn;
        cached[34] = vibRate;
        cached[35] = vibDepth;
        cached[36] = vibMix;
        cached[37] = compOn;
        cached[38] = compSustain;
        cached[39] = compLevel;
        cached[40] = eqOn;
        cached[41] = eqBass;
        cached[42] = eqMid;
        cached[43] = eqTreb;
        cached[44] = wahOn;
        cached[45] = wahRate;
        cached[46] = wahCenter;
        cached[47] = wahRes;
        cached[48] = gateOn;
        cached[49] = gateThr;
        cached[50] = gateRel;
        cached[51] = octOn;
        cached[52] = octMode;
        cached[53] = octMix;
    }
}

void data_callback(ma_device* /*device*/, void* output, const void* /*input*/, ma_uint32 frames) {
    auto* out = static_cast<float*>(output);
    EngineState& s = g;

    const uint64_t base = s.frames_rendered.load(std::memory_order_relaxed);
    EngineEvent ev;

    static thread_local float fx_cached_l[4] = { -1.0f, -1.0f, -1.0f, -1.0f };
    static thread_local float fx_cached_r[4] = { -1.0f, -1.0f, -1.0f, -1.0f };
    sync_fx_params(s.fx_l, fx_cached_l);
    sync_fx_params(s.fx_r, fx_cached_r);
    static thread_local float pedal_cached_l[54] = {         -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f };
    static thread_local float pedal_cached_r[54] = {         -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f };
    sync_pedal_params(s.pedals_l, pedal_cached_l);
    sync_pedal_params(s.pedals_r, pedal_cached_r);

    for (ma_uint32 i = 0; i < frames; ++i) {
        while (s.queue.pop_if_reached(base + i, ev)) {
            // Events queued before a transport reset are consumed and discarded,
            // which drains the queue race-free.
            if (ev.generation == s.generation.load(std::memory_order_relaxed)) {
                apply_event(s, ev);
            }
        }
        float leftSum = 0.0f;
        float rightSum = 0.0f;
        for (int k = 0; k < kStringCount; ++k) {
            const float v = s.strings[k].render_sample()
                    * g_ch.gains[k].load(std::memory_order_relaxed);
            leftSum += v * kPanLeft[k];
            rightSum += v * kPanRight[k];
        }
        const float outL = s.fx_l.process(s.pedals_l.process(leftSum * 0.45f));
        const float outR = s.fx_r.process(s.pedals_r.process(rightSum * 0.45f));
        out[2 * i]     = outL;
        out[2 * i + 1] = outR;
        update_level(std::max(std::fabs(outL), std::fabs(outR)));
    }
    s.frames_rendered.store(base + frames, std::memory_order_release);
}

void destroy_device(EngineState& s) {
    if (s.device_ready) {
        if (s.started) {
            ma_device_stop(&s.device);
            s.started = false;
        }
        ma_device_uninit(&s.device);
        s.device_ready = false;
    }
}

} // namespace

GTE_EXPORT bool guitar_engine_init(int sample_rate, int buffer_frames) {
    if (sample_rate <= 0 || buffer_frames <= 0) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g.control_mutex);
    destroy_device(g);
    g.sample_rate = static_cast<float>(sample_rate);
    g.buffer_frames = buffer_frames;
    for (int i = 0; i < kStringCount; ++i) {
        g.strings[i] = gte::PluckedString(g.open_frequencies[i]);
        g.strings[i].prepare(g.sample_rate);
    }
    g.fx_l.prepare(g.sample_rate, 0);
    g.fx_r.prepare(g.sample_rate, 1); // Freeverb 23-sample spread -> decorrelated stereo tail
    g.queue.clear();
    g.frames_rendered.store(0, std::memory_order_relaxed);
    g.initialized = true;
    return true;
}

GTE_EXPORT bool guitar_engine_start(void) {
    std::lock_guard<std::mutex> lock(g.control_mutex);
    if (!g.initialized) {
        return false;
    }
    if (g.started) {
        return true;
    }
    if (!g.device_ready) {
        ma_device_config config = ma_device_config_init(ma_device_type_playback);
        config.playback.format = ma_format_f32;
        config.playback.channels = 2;
        config.sampleRate = static_cast<ma_uint32>(g.sample_rate);
        config.periodSizeInFrames = static_cast<ma_uint32>(g.buffer_frames);
        config.periods = 2;
        config.dataCallback = data_callback;
        if (ma_device_init(nullptr, &config, &g.device) != MA_SUCCESS) {
            return false;
        }
        g.device_ready = true;
    }
    if (ma_device_start(&g.device) != MA_SUCCESS) {
        return false;
    }
    g.started = true;
    return true;
}

GTE_EXPORT void guitar_engine_shutdown(void) {
    std::lock_guard<std::mutex> lock(g.control_mutex);
    destroy_device(g);
    g.initialized = false;
}

GTE_EXPORT uint64_t guitar_engine_sample_clock(void) {
    return g.frames_rendered.load(std::memory_order_acquire);
}

GTE_EXPORT const char* guitar_engine_version(void) {
    return kEngineVersion;
}

GTE_EXPORT bool guitar_engine_push_event(uint64_t sample_time, int type, int string_index,
                                         int fret, float a, float b) {
    if (!g.initialized || !valid_command_type(type)) return false;
    if (type != GTE_EVENT_SET_TUNING && type != GTE_EVENT_SET_MODEL
            && !valid_string(string_index)) return false;
    if (type == GTE_EVENT_PLUCK && (fret < 0 || fret > kMaxFret)) return false;
    if (type == GTE_EVENT_LEGATO && (fret < 0 || fret > kMaxFret)) return false;
    EngineEvent ev;
    ev.generation = g.generation.load(std::memory_order_relaxed);
    ev.sample_time = sample_time;
    ev.type = type;
    ev.string_index = string_index;
    ev.fret = fret;
    ev.a = a;
    ev.b = b;
    return g.queue.push(ev);
}

GTE_EXPORT void guitar_engine_set_string_tuning(int string_index, float open_frequency_hz) {
    if (!g.initialized || !valid_string(string_index) || open_frequency_hz <= 0.0f) return;
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_SET_TUNING,
                             string_index, 0, open_frequency_hz, 0.0f);
}

GTE_EXPORT void guitar_engine_set_guitar_model(int model) {
    if (!g.initialized || !valid_model(model)) return;
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_SET_MODEL,
                             -1, model, 0.0f, 0.0f);
}

GTE_EXPORT void guitar_engine_pluck_string(int string_index, int fret, float velocity, bool palm_mute) {
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_PLUCK,
                             string_index, fret, velocity, palm_mute ? 1.0f : 0.0f);
}

GTE_EXPORT void guitar_engine_legato_fret(int string_index, int target_fret) {
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_LEGATO,
                             string_index, target_fret, 0.0f, 0.0f);
}

GTE_EXPORT void guitar_engine_bend_string(int string_index, float semitones) {
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_BEND,
                             string_index, 0, semitones, 0.0f);
}

GTE_EXPORT void guitar_engine_mute_string(int string_index) {
    guitar_engine_push_event(guitar_engine_sample_clock(), GTE_EVENT_MUTE,
                             string_index, 0, 0.0f, 0.0f);
}

GTE_EXPORT void guitar_engine_set_fx(float drive, float tone, float reverb_mix, float master_gain) {
    g_fx.drive.store(drive, std::memory_order_relaxed);
    g_fx.tone.store(tone, std::memory_order_relaxed);
    g_fx.reverb_mix.store(reverb_mix, std::memory_order_relaxed);
    g_fx.master.store(master_gain, std::memory_order_relaxed);
}

/* Pedalboard: clean boost, 0..18 dB gain, applied before the amp FX on both
 * real-time and offline paths. */
GTE_EXPORT void guitar_engine_set_boost(bool enabled, float gain_db) {
    g_pedals.boost_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.boost_gain.store(std::clamp(gain_db, 0.0f, 18.0f), std::memory_order_relaxed);
}

/* Pedalboard: warm soft-clipping grit, after any boost, before the amp FX. */
GTE_EXPORT void guitar_engine_set_overdrive(bool enabled, float drive, float tone) {
    g_pedals.od_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.od_drive.store(std::clamp(drive, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.od_tone.store(std::clamp(tone, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: heavy hard-clipped crunch, after overdrive, before the amp FX. */
GTE_EXPORT void guitar_engine_set_distortion(bool enabled, float drive, float tone, float level) {
    g_pedals.dist_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.dist_drive.store(std::clamp(drive, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.dist_tone.store(std::clamp(tone, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.dist_level.store(std::clamp(level, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: buzzy near-square fuzz, first in the dirt section. */
GTE_EXPORT void guitar_engine_set_fuzz(bool enabled, float fuzz, float volume) {
    g_pedals.fuzz_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.fuzz_amount.store(std::clamp(fuzz, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.fuzz_volume.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: repeats with feedback. time is seconds 0.02..1.0, feedback
 * 0..0.9 (each repeat darker), mix = wet blend. After the dirt section. */
GTE_EXPORT void guitar_engine_set_delay(bool enabled, float time, float feedback, float mix) {
    g_pedals.delay_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.delay_time.store(std::clamp(time, 0.02f, 1.0f), std::memory_order_relaxed);
    g_pedals.delay_feedback.store(std::clamp(feedback, 0.0f, 0.9f), std::memory_order_relaxed);
    g_pedals.delay_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: unison-wobble chorus (rate/depth normalized 0..1, mix 0..1),
 * after the delay. */
GTE_EXPORT void guitar_engine_set_chorus(bool enabled, float rate, float depth, float mix) {
    g_pedals.chorus_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.chorus_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.chorus_depth.store(std::clamp(depth, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.chorus_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: regenerating jet-flange (rate/depth normalized 0..1,
 * feedback 0..0.9, mix 0..1), after the chorus. */
GTE_EXPORT void guitar_engine_set_flanger(bool enabled, float rate, float depth,
                                           float feedback, float mix) {
    g_pedals.flanger_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.flanger_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.flanger_depth.store(std::clamp(depth, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.flanger_feedback.store(std::clamp(feedback, 0.0f, 0.9f), std::memory_order_relaxed);
    g_pedals.flanger_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: all-pass notch phaser (rate/depth/regen normalized 0..1,
 * mix 0..1), after the flanger. */
GTE_EXPORT void guitar_engine_set_phaser(bool enabled, float rate, float depth,
                                         float regen, float mix) {
    g_pedals.phaser_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.phaser_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.phaser_depth.store(std::clamp(depth, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.phaser_regen.store(std::clamp(regen, 0.0f, 0.9f), std::memory_order_relaxed);
    g_pedals.phaser_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: LFO volume pulse (rate/depth normalized 0..1), after the
 * phaser. */
GTE_EXPORT void guitar_engine_set_tremolo(bool enabled, float rate, float depth) {
    g_pedals.tremolo_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.tremolo_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.tremolo_depth.store(std::clamp(depth, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: pitch-wobble vibrato (rate/depth/mix normalized 0..1) —
 * the LFO-bent copy REPLACES the dry signal, after the tremolo. */
GTE_EXPORT void guitar_engine_set_vibrato(bool enabled, float rate, float depth, float mix) {
    g_pedals.vibrato_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.vibrato_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.vibrato_depth.store(std::clamp(depth, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.vibrato_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: envelope-follower compressor (sustain/level normalized
 * 0..1), after the vibrato. */
GTE_EXPORT void guitar_engine_set_compressor(bool enabled, float sustain, float level) {
    g_pedals.comp_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.comp_sustain.store(std::clamp(sustain, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.comp_level.store(std::clamp(level, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: 3-band EQ (bass/mid/treble normalized 0..1, 0.5 = flat),
 * after the compressor. */
GTE_EXPORT void guitar_engine_set_eq(bool enabled, float bass, float mid, float treble) {
    g_pedals.eq_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.eq_bass.store(std::clamp(bass, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.eq_mid.store(std::clamp(mid, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.eq_treble.store(std::clamp(treble, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: resonant band-pass sweep (rate/center/resonance normalized
 * 0..1), after the EQ. */
GTE_EXPORT void guitar_engine_set_wah(bool enabled, float rate, float center, float resonance) {
    g_pedals.wah_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.wah_rate.store(std::clamp(rate, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.wah_center.store(std::clamp(center, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.wah_resonance.store(std::clamp(resonance, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: noise gate (threshold/release normalized 0..1), after the
 * wah. */
GTE_EXPORT void guitar_engine_set_gate(bool enabled, float threshold, float release) {
    g_pedals.gate_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.gate_threshold.store(std::clamp(threshold, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.gate_release.store(std::clamp(release, 0.0f, 1.0f), std::memory_order_relaxed);
}

/* Pedalboard: classic analog octave (mode/mix normalized 0..1), after
 * the gate. */
GTE_EXPORT void guitar_engine_set_octave(bool enabled, float mode, float mix) {
    g_pedals.oct_enabled.store(enabled, std::memory_order_relaxed);
    g_pedals.oct_mode.store(std::clamp(mode, 0.0f, 1.0f), std::memory_order_relaxed);
    g_pedals.oct_mix.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
}

GTE_EXPORT bool guitar_engine_render_sequence_to_wav(const char* path_utf8,
                                                     float duration_seconds,
                                                     const GteRenderCommand* commands,
                                                     int command_count) {
    if (!path_utf8 || duration_seconds <= 0.0f || (commands == nullptr && command_count > 0)) {
        return false;
    }

    const float fs = g.sample_rate;
    if (fs <= 0.0f) return false;

    gte::PluckedString strings[kStringCount];
    float offline_tunings[kStringCount];
    float offline_gains[kStringCount];
    for (int i = 0; i < kStringCount; ++i) {
        offline_tunings[i] = g.open_frequencies[i];
        offline_gains[i] = g_ch.gains[i].load(std::memory_order_relaxed);
        strings[i] = gte::PluckedString(offline_tunings[i]);
        strings[i].prepare(fs);
    }

    std::vector<GteRenderCommand> sorted;
    if (commands != nullptr && command_count > 0) {
        sorted.assign(commands, commands + command_count);
        std::stable_sort(sorted.begin(), sorted.end(),
                         [](const GteRenderCommand& a, const GteRenderCommand& b) {
                             return a.time_offset < b.time_offset;
                         });
    }

    gte::WavWriter wav(path_utf8, static_cast<int>(fs));
    if (!wav.ok()) return false;

    // Offline path uses the same FX as the live path (current params snapshot).
    gte::FxDsp fx;
    fx.prepare(fs);
    {
        float cached[4] = { -1.0f, -1.0f, -1.0f, -1.0f };
        sync_fx_params(fx, cached);
    }
    gte::PedalChain pedals;
    pedals.prepare(fs);
    {
        float cached[54] = {         -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f , -1.0f, -1.0f, -1.0f };
        sync_pedal_params(pedals, cached);
    }

    const int64_t total_samples = static_cast<int64_t>(duration_seconds * fs);
    size_t next_command = 0;
    for (int64_t n = 0; n < total_samples; ++n) {
        const float t = static_cast<float>(n) / fs;
        while (next_command < sorted.size() && sorted[next_command].time_offset <= t) {
            const GteRenderCommand& c = sorted[next_command++];
            apply_command(strings, offline_tunings, c.type, c.string_index, c.fret, c.a, c.b);
        }
        float sum = 0.0f;
        for (int i = 0; i < kStringCount; ++i) {
            sum += strings[i].render_sample() * offline_gains[i];
        }
        const float out = fx.process(pedals.process(sum * 0.45f));
        update_level(out);
        wav.write(out);
    }
    return true;
}

GTE_EXPORT void guitar_engine_set_string_gain(int string_index, float gain) {
    if (valid_string(string_index)) {
        g_ch.gains[string_index].store(std::clamp(gain, 0.0f, 2.0f), std::memory_order_relaxed);
    }
}

GTE_EXPORT void guitar_engine_reset_transport(void) {
    // Invalidates every queued event (the audio thread drops stale generations)
    // so a scheduled iteration cannot keep playing after STOP.
    g.generation.fetch_add(1, std::memory_order_relaxed);
}

GTE_EXPORT float guitar_engine_get_level(void) {
    return g_ch.level.load(std::memory_order_acquire);
}
