#ifndef GUITAR_ENGINE_H
#define GUITAR_ENGINE_H

#include <stdint.h>

/*
 * guitar_engine — standalone C++ synthesis engine for the Guitar Tab Player.
 *
 * Exposed as a C ABI so it can be bound from Java via the FFM API
 * (java.lang.foreign.Linker / SymbolLookup).
 *
 * Phase 1: Karplus-Strong voices + offline WAV render API.
 * Phase 2: real-time device output (miniaudio), lock-free timestamped
 *          event queue, sample clock.
 *
 * Threading: push_event() is safe to call from any single producer thread;
 * the audio callback is the sole consumer. Immediate commands
 * (pluck_string, ...) simply wrap push_event at the current sample clock.
 */

#ifdef _WIN32
  #define GTE_EXPORT extern "C" __declspec(dllexport)
#else
  #define GTE_EXPORT extern "C" __attribute__((visibility("default")))
#endif

/* Event types (shared with the Java side). */
enum {
    GTE_EVENT_PLUCK      = 0,
    GTE_EVENT_LEGATO     = 1,
    GTE_EVENT_BEND       = 2,
    GTE_EVENT_MUTE       = 3,
    GTE_EVENT_SET_TUNING = 4,
    GTE_EVENT_SET_MODEL  = 5,  /* fret field = guitar model (0=acoustic, 1=single, 2=humbucker) */
    GTE_EVENT_VIBRATO    = 6,  /* a != 0: vibrato on for this string until its next articulation */
    GTE_EVENT_HARMONIC   = 7   /* fret = node fret; a = velocity; b != 0 = palm mute */
};

/* One timed synthesis command, used by the offline render API. */
typedef struct GteRenderCommand {
    float time_offset;   /* seconds from the start of the render */
    int   type;          /* GTE_EVENT_* */
    int   string_index;  /* 0..5 (0 = low E) */
    int   fret;          /* used by PLUCK / LEGATO */
    float a;             /* velocity (PLUCK) / semitones (BEND) / frequency (SET_TUNING) */
    float b;             /* palm mute flag (PLUCK, nonzero = true) / unused */
} GteRenderCommand;

/* Lifecycle */
GTE_EXPORT bool      guitar_engine_init(int sample_rate, int buffer_frames);
GTE_EXPORT bool      guitar_engine_start(void);        /* opens + starts the audio device */
GTE_EXPORT void      guitar_engine_shutdown(void);
GTE_EXPORT uint64_t  guitar_engine_sample_clock(void); /* frames rendered since init */

GTE_EXPORT const char* guitar_engine_version(void);

/* Scheduled control path: fires at the given absolute sample time. */
GTE_EXPORT bool      guitar_engine_push_event(uint64_t sample_time, int type, int string_index,
                                              int fret, float a, float b);

/* Immediate convenience wrappers (schedule at the current sample clock). */
GTE_EXPORT void      guitar_engine_set_string_tuning(int string_index, float open_frequency_hz);
GTE_EXPORT void      guitar_engine_set_guitar_model(int model);
GTE_EXPORT void      guitar_engine_pluck_string(int string_index, int fret, float velocity, bool palm_mute);
GTE_EXPORT void      guitar_engine_legato_fret(int string_index, int target_fret);
GTE_EXPORT void      guitar_engine_bend_string(int string_index, float semitones);
GTE_EXPORT void      guitar_engine_mute_string(int string_index);

/* Offline render: replays `commands` into fresh voices from t=0 and writes
 * a 16-bit PCM mono WAV. Deterministic; used for CI verification + export. */
GTE_EXPORT bool      guitar_engine_render_sequence_to_wav(const char* path_utf8,
                                                          float duration_seconds,
                                                          const GteRenderCommand* commands,
                                                          int command_count);

/* Master FX (applied to the summed mono mix on both real-time and offline paths).
 * drive 0..1 (tanh distortion), tone 0..1 (0 = dark, 1 = bright),
 * reverb_mix 0..1, master_gain 0..2. */
GTE_EXPORT void      guitar_engine_set_fx(float drive, float tone, float reverb_mix, float master_gain);

/* Pedalboard: clean boost, 0..18 dB, applied before the amp FX on both paths. */
GTE_EXPORT void      guitar_engine_set_boost(bool enabled, float gain_db);

/* Pedalboard: warm soft-clipping grit (drive 0..1, tone 0..1), after any
 * boost and before the amp FX, on both paths. */
GTE_EXPORT void      guitar_engine_set_overdrive(bool enabled, float drive, float tone);

/* Pedalboard: heavy hard-clipped crunch (drive 0..1, tone 0..1, level 0..1),
 * after overdrive and before the amp FX, on both paths. */
GTE_EXPORT void      guitar_engine_set_distortion(bool enabled, float drive, float tone, float level);

/* Per-string channel gain (0..2), like a mixing-desk channel fader. */
GTE_EXPORT void      guitar_engine_set_string_gain(int string_index, float gain);

/* Peak envelope of the rendered output (0..~1), fast-decaying — for VU metering. */
GTE_EXPORT float     guitar_engine_get_level(void);

/* Invalidates all queued events and silences playback — instant STOP. */
GTE_EXPORT void      guitar_engine_reset_transport(void);

#endif /* GUITAR_ENGINE_H */
