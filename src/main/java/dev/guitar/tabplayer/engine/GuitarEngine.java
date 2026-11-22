package dev.guitar.tabplayer.engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Java FFM binding for the native guitar_engine shared library.
 * <p>
 * One instance owns one native library load. All methods are thin
 * downcall wrappers around the C ABI declared in native/include/guitar_engine.h.
 * <p>
 * Requires the JVM flag: --enable-native-access=ALL-UNNAMED
 */
public final class GuitarEngine implements AutoCloseable {

  /**
   * Must match ENGINE_VERSION in native/src/guitar_engine.cpp.
   */
  public static final String VERSION = "0.3.0";

  private static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;
  private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
  private static final ValueLayout.OfFloat C_FLOAT = ValueLayout.JAVA_FLOAT;
  private static final java.lang.foreign.AddressLayout C_POINTER = java.lang.foreign.ValueLayout.ADDRESS;

  private final Arena arena;
  private final MethodHandle initFn;
  private final MethodHandle shutdownFn;
  private final MethodHandle versionFn;
  private final MethodHandle setTuningFn;
  private final MethodHandle pluckFn;
  private final MethodHandle legatoFn;
  private final MethodHandle bendFn;
  private final MethodHandle muteFn;
  private final MethodHandle pushEventFn;
  private final MethodHandle startFn;
  private final MethodHandle clockFn;
  private final MethodHandle renderSequenceFn;
  private final MethodHandle setFxFn;
  private final MethodHandle setBoostFn;
  private final MethodHandle setOverdriveFn;
  private final MethodHandle setDistortionFn;
  private final MethodHandle setFuzzFn;
  private final MethodHandle setDelayFn;
  private final MethodHandle setChorusFn;
  private final MethodHandle setFlangerFn;
  private final MethodHandle setPhaserFn;
  private final MethodHandle setTremoloFn;
  private final MethodHandle setVibratoFn;
  private final MethodHandle setCompressorFn;
  private final MethodHandle setEqFn;
  private final MethodHandle setWahFn;
  private final MethodHandle setGateFn;
  private final MethodHandle setLooperFn;
  private final MethodHandle setVolumeFn;
  private final MethodHandle setTunerFn;
  private final MethodHandle setCabinetFn;
  private final MethodHandle setSpringFn;
  private final MethodHandle setAmpModelFn;
  private final MethodHandle setSustainFn;
  private final MethodHandle loadIrFn;
  private final MethodHandle getTunerHzFn;
  private final MethodHandle setOctaveFn;
  private final MethodHandle setGainFn;
  private final MethodHandle getLevelFn;
  private final MethodHandle resetTransportFn;

  private boolean shutdownCalled;

  public GuitarEngine(Path libraryPath) {
    // Shared arena: downcalls are made from several threads (command
    // executor, offline renderer); a confined arena would throw
    // WrongThreadException on every call not from the owning thread.
    arena = Arena.ofShared();
    try {
      SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath, arena);
      Linker linker = Linker.nativeLinker();

      initFn = linker.downcallHandle(symbols.find("guitar_engine_init").orElseThrow(),
          FunctionDescriptor.of(C_BOOL, C_INT, C_INT));
      shutdownFn = linker.downcallHandle(symbols.find("guitar_engine_shutdown").orElseThrow(),
          FunctionDescriptor.ofVoid());
      versionFn = linker.downcallHandle(symbols.find("guitar_engine_version").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
      setTuningFn = linker.downcallHandle(symbols.find("guitar_engine_set_string_tuning").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT, C_FLOAT));
      pluckFn = linker.downcallHandle(symbols.find("guitar_engine_pluck_string").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT, C_INT, C_FLOAT, C_BOOL));
      legatoFn = linker.downcallHandle(symbols.find("guitar_engine_legato_fret").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT, C_INT));
      bendFn = linker.downcallHandle(symbols.find("guitar_engine_bend_string").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT, C_FLOAT));
      muteFn = linker.downcallHandle(symbols.find("guitar_engine_mute_string").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT));
      pushEventFn = linker.downcallHandle(symbols.find("guitar_engine_push_event").orElseThrow(),
          FunctionDescriptor.of(C_BOOL, ValueLayout.JAVA_LONG, C_INT, C_INT, C_INT, C_FLOAT, C_FLOAT));
      startFn = linker.downcallHandle(symbols.find("guitar_engine_start").orElseThrow(), FunctionDescriptor.of(C_BOOL));
      clockFn = linker.downcallHandle(symbols.find("guitar_engine_sample_clock").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_LONG));
      renderSequenceFn = linker.downcallHandle(symbols.find("guitar_engine_render_sequence_to_wav").orElseThrow(),
          FunctionDescriptor.of(C_BOOL, ValueLayout.ADDRESS, C_FLOAT, ValueLayout.ADDRESS, C_INT));
      setFxFn = linker.downcallHandle(symbols.find("guitar_engine_set_fx").orElseThrow(),
          FunctionDescriptor.ofVoid(C_FLOAT, C_FLOAT, C_FLOAT, C_FLOAT));
      setBoostFn = linker.downcallHandle(symbols.find("guitar_engine_set_boost").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT));
      setOverdriveFn = linker.downcallHandle(symbols.find("guitar_engine_set_overdrive").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setDistortionFn = linker.downcallHandle(symbols.find("guitar_engine_set_distortion").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setFuzzFn = linker.downcallHandle(symbols.find("guitar_engine_set_fuzz").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setDelayFn = linker.downcallHandle(symbols.find("guitar_engine_set_delay").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setChorusFn = linker.downcallHandle(symbols.find("guitar_engine_set_chorus").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setFlangerFn = linker.downcallHandle(symbols.find("guitar_engine_set_flanger").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT, C_FLOAT));
      setPhaserFn = linker.downcallHandle(symbols.find("guitar_engine_set_phaser").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT, C_FLOAT));
      setTremoloFn = linker.downcallHandle(symbols.find("guitar_engine_set_tremolo").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setVibratoFn = linker.downcallHandle(symbols.find("guitar_engine_set_vibrato").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setCompressorFn = linker.downcallHandle(symbols.find("guitar_engine_set_compressor").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setEqFn = linker.downcallHandle(symbols.find("guitar_engine_set_eq").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setWahFn = linker.downcallHandle(symbols.find("guitar_engine_set_wah").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT, C_FLOAT));
      setGateFn = linker.downcallHandle(symbols.find("guitar_engine_set_gate").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setLooperFn = linker.downcallHandle(symbols.find("guitar_engine_set_looper").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setVolumeFn = linker.downcallHandle(symbols.find("guitar_engine_set_volume").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT));
      setTunerFn = linker.downcallHandle(symbols.find("guitar_engine_set_tuner").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL));
      setCabinetFn = linker.downcallHandle(symbols.find("guitar_engine_set_cabinet").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT));
      setSpringFn = linker.downcallHandle(symbols.find("guitar_engine_set_spring").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setAmpModelFn = linker.downcallHandle(symbols.find("guitar_engine_set_amp_model").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT));
      setSustainFn = linker.downcallHandle(symbols.find("guitar_engine_set_sustain").orElseThrow(),
          FunctionDescriptor.ofVoid(C_FLOAT));
      loadIrFn = linker.downcallHandle(symbols.find("guitar_engine_load_cabinet_ir").orElseThrow(),
          FunctionDescriptor.of(C_BOOL, C_POINTER));
      getTunerHzFn = linker.downcallHandle(symbols.find("guitar_engine_get_tuner_hz").orElseThrow(),
          FunctionDescriptor.of(C_FLOAT));
      setOctaveFn = linker.downcallHandle(symbols.find("guitar_engine_set_octave").orElseThrow(),
          FunctionDescriptor.ofVoid(C_BOOL, C_FLOAT, C_FLOAT));
      setGainFn = linker.downcallHandle(symbols.find("guitar_engine_set_string_gain").orElseThrow(),
          FunctionDescriptor.ofVoid(C_INT, C_FLOAT));
      getLevelFn = linker.downcallHandle(symbols.find("guitar_engine_get_level").orElseThrow(),
          FunctionDescriptor.of(C_FLOAT));
      resetTransportFn = linker.downcallHandle(symbols.find("guitar_engine_reset_transport").orElseThrow(),
          FunctionDescriptor.ofVoid());
    } catch (RuntimeException e) {
      arena.close();
      throw e;
    }
  }

  /**
   * Resolves the library location: -Dguitar.engine.lib=... override, then
   * build/native/ (dev runs), then the directory of the application jar
   * (packaged app images ship the DLL next to the main jar).
   */
  public static Path defaultLibraryPath() {
    String prop = System.getProperty("guitar.engine.lib");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    String name;
    if (os.contains("win")) {
      name = "guitar_engine.dll";
    } else if (os.contains("mac")) {
      name = "libguitar_engine.dylib";
    } else {
      name = "libguitar_engine.so";
    }
    Path devPath = Path.of("build", "native", name);
    if (java.nio.file.Files.exists(devPath)) {
      return devPath;
    }
    try {
      var source = GuitarEngine.class.getProtectionDomain().getCodeSource();
      if (source != null && source.getLocation() != null) {
        Path dir = Path.of(source.getLocation().toURI()).getParent();
        if (dir != null) {
          Path packaged = dir.resolve(name);
          if (java.nio.file.Files.exists(packaged)) {
            return packaged;
          }
        }
      }
    } catch (Exception ignored) {
      // fall through to the dev path
    }
    return devPath;
  }

  public boolean init(int sampleRate, int bufferFrames) {
    try {
      return (boolean) initFn.invokeExact(sampleRate, bufferFrames);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_init failed", t);
    }
  }

  public String version() {
    try {
      MemorySegment result = (MemorySegment) versionFn.invokeExact();
      // Returned pointers have byteSize 0; the version string lives in
      // static storage in the DLL, so an unbounded reinterpret is safe.
      return result.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_version failed", t);
    }
  }

  public void setStringTuning(int stringIndex, float openFrequencyHz) {
    invokeVoid(setTuningFn, "guitar_engine_set_string_tuning", stringIndex, openFrequencyHz);
  }

  public void pluck(int stringIndex, int fret, float velocity, boolean palmMute) {
    invokeVoid(pluckFn, "guitar_engine_pluck_string", stringIndex, fret, velocity, palmMute);
  }

  public void legatoFret(int stringIndex, int targetFret) {
    invokeVoid(legatoFn, "guitar_engine_legato_fret", stringIndex, targetFret);
  }

  public void bend(int stringIndex, float semitones) {
    invokeVoid(bendFn, "guitar_engine_bend_string", stringIndex, semitones);
  }

  public void mute(int stringIndex) {
    invokeVoid(muteFn, "guitar_engine_mute_string", stringIndex);
  }

  /* ------------------------------------------------------------------
   * Real-time transport (Phase 2): timestamped event queue + device
   * ------------------------------------------------------------------ */

  /**
   * Starts the audio device. Returns false when no usable output device
   * exists (e.g. headless environments).
   */
  public boolean start() {
    try {
      return (boolean) startFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_start failed", t);
    }
  }

  /**
   * Absolute frame count rendered since init() — the engine's time base.
   */
  public long sampleClock() {
    try {
      return (long) clockFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_sample_clock failed", t);
    }
  }

  /**
   * Schedules an event at an absolute sample time. The audio thread fires
   * it exactly when the engine clock reaches that sample (sample-accurate).
   * Returns false when the engine is not initialized or the queue is full.
   */
  public boolean pushEvent(long sampleTime, int type, int stringIndex, int fret, float a, float b) {
    try {
      return (boolean) pushEventFn.invokeExact(sampleTime, type, stringIndex, fret, a, b);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_push_event failed", t);
    }
  }

  public boolean pushPluck(long sampleTime, int stringIndex, int fret, float velocity, boolean palmMute) {
    return pushEvent(sampleTime, EVENT_PLUCK, stringIndex, fret, velocity, palmMute ? 1.0f : 0.0f);
  }

  public boolean pushLegato(long sampleTime, int stringIndex, int targetFret) {
    return pushEvent(sampleTime, EVENT_LEGATO, stringIndex, targetFret, 0.0f, 0.0f);
  }

  public boolean pushBend(long sampleTime, int stringIndex, float semitones) {
    return pushEvent(sampleTime, EVENT_BEND, stringIndex, 0, semitones, 0.0f);
  }

  public boolean pushMute(long sampleTime, int stringIndex) {
    return pushEvent(sampleTime, EVENT_MUTE, stringIndex, 0, 0.0f, 0.0f);
  }

  public void shutdown() {
    if (shutdownCalled) {
      return;
    }
    shutdownCalled = true;
    try {
      shutdownFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_shutdown failed", t);
    }
  }

  @Override
  public void close() {
    shutdown();
    arena.close();
  }

  /* ------------------------------------------------------------------
   * Offline render API (Phase 1)
   * ------------------------------------------------------------------ */

  /**
   * Event type ids — must match the GTE_EVENT_* enum in guitar_engine.h.
   */
  public static final int EVENT_PLUCK = 0;
  public static final int EVENT_LEGATO = 1;
  public static final int EVENT_BEND = 2;
  public static final int EVENT_MUTE = 3;
  public static final int EVENT_SET_TUNING = 4;
  public static final int EVENT_SET_MODEL = 5;
  public static final int EVENT_VIBRATO = 6;
  public static final int EVENT_HARMONIC = 7;

  /**
   * One timed synthesis command for the offline renderer. Field layout is a
   * mirror of the native GteRenderCommand struct: 6 x 4 bytes, no padding.
   * The 7th field (tag) is view-only metadata and never serialized.
   */
  public record RenderCommand(float timeOffset, int type, int stringIndex, int fret, float a, float b, int tag) {
    public static final int BYTE_SIZE = 24;
    static final long OFFSET_TIME = 0;
    static final long OFFSET_TYPE = 4;
    static final long OFFSET_STRING = 8;
    static final long OFFSET_FRET = 12;
    static final long OFFSET_A = 16;
    static final long OFFSET_B = 20;

    /** View-only articulation tags (never reach the native engine). */
    public static final int TAG_NONE = 0;
    public static final int TAG_HAMMER = 1;
    public static final int TAG_PULL = 2;
    public static final int TAG_TAP = 3;
    public static final int TAG_SLIDE = 4;
    public static final int TAG_TRILL = 5;
    public static final int TAG_TREMOLO = 6;
    public static final int TAG_GRACE = 7;

    public RenderCommand(float timeOffset, int type, int stringIndex, int fret, float a, float b) {
      this(timeOffset, type, stringIndex, fret, a, b, TAG_NONE);
    }
  }

  /**
   * Renders a timed command sequence offline into a 16-bit PCM mono WAV.
   * Deterministic — the primary audio verification path for CI.
   */
  public boolean renderSequenceToWav(java.nio.file.Path wavPath, float durationSeconds,
      java.util.List<RenderCommand> commands) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pathSeg = arena.allocateFrom(wavPath.toString());
      MemorySegment arraySeg = arena.allocate((long) RenderCommand.BYTE_SIZE * Math.max(1, commands.size()), 8);
      for (int i = 0; i < commands.size(); i++) {
        RenderCommand cmd = commands.get(i);
        long base = (long) i * RenderCommand.BYTE_SIZE;
        arraySeg.set(ValueLayout.JAVA_FLOAT, base + RenderCommand.OFFSET_TIME, cmd.timeOffset());
        arraySeg.set(ValueLayout.JAVA_INT, base + RenderCommand.OFFSET_TYPE, cmd.type());
        arraySeg.set(ValueLayout.JAVA_INT, base + RenderCommand.OFFSET_STRING, cmd.stringIndex());
        arraySeg.set(ValueLayout.JAVA_INT, base + RenderCommand.OFFSET_FRET, cmd.fret());
        arraySeg.set(ValueLayout.JAVA_FLOAT, base + RenderCommand.OFFSET_A, cmd.a());
        arraySeg.set(ValueLayout.JAVA_FLOAT, base + RenderCommand.OFFSET_B, cmd.b());
      }
      return (boolean) renderSequenceFn.invokeExact(pathSeg, durationSeconds, arraySeg, commands.size());
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_render_sequence_to_wav failed", t);
    }
  }

  /* ------------------------------------------------------------------
   * Master FX (Phase 5)
   * ------------------------------------------------------------------ */

  /**
   * Sets the master FX chain: drive 0..1 (tanh distortion), tone 0..1
   * (0 = dark, 1 = bright), reverbMix 0..1, masterGain 0..2.
   * Applied on both real-time and offline-render paths.
   */
  public void setFx(float drive, float tone, float reverbMix, float masterGain) {
    try {
      setFxFn.invokeExact(drive, tone, reverbMix, masterGain);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_fx failed", t);
    }
  }

  /**
   * Per-string channel gain 0..2 (channel fader). Applies live and offline.
   */
  public void setStringGain(int stringIndex, float gain) {
    try {
      setGainFn.invokeExact(stringIndex, gain);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_string_gain failed", t);
    }
  }

  /* ------------------------------------------------------------------
   * Pedalboard
   * ------------------------------------------------------------------ */

  /**
   * Clean boost pedal: pure linear gain 0..18 dB applied before the amp FX
   * on both real-time and offline-render paths.
   */
  public void setBoost(boolean enabled, float gainDb) {
    try {
      setBoostFn.invokeExact(enabled, gainDb);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_boost failed", t);
    }
  }

  /**
   * Overdrive pedal: warm soft-clipping grit (drive 0..1, tone 0..1), after
   * any boost, before the amp FX, on both real-time and offline-render paths.
   */
  public void setOverdrive(boolean enabled, float drive, float tone) {
    try {
      setOverdriveFn.invokeExact(enabled, drive, tone);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_overdrive failed", t);
    }
  }

  /**
   * Distortion pedal: heavy hard-clipped crunch (drive 0..1, tone 0..1,
   * level 0..1), after overdrive, before the amp FX, on both paths.
   */
  public void setDistortion(boolean enabled, float drive, float tone, float level) {
    try {
      setDistortionFn.invokeExact(enabled, drive, tone, level);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_distortion failed", t);
    }
  }

  /**
   * Fuzz pedal: buzzy near-square over-saturation (fuzz 0..1, volume 0..1),
   * first in the dirt section, on both real-time and offline-render paths.
   */
  public void setFuzz(boolean enabled, float fuzz, float volume) {
    try {
      setFuzzFn.invokeExact(enabled, fuzz, volume);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_fuzz failed", t);
    }
  }

  /**
   * Delay pedal: repeats with feedback (time in seconds 0.02..1.0,
   * feedback 0..0.9, mix 0..1), after the dirt section, on both real-time
   * and offline-render paths.
   */
  public void setDelay(boolean enabled, float time, float feedback, float mix) {
    try {
      setDelayFn.invokeExact(enabled, time, feedback, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_delay failed", t);
    }
  }

  /**
   * Chorus pedal: unison-wobble copy (rate/depth normalized 0..1,
   * mix 0..1), after the delay, on both real-time and offline paths.
   */
  public void setChorus(boolean enabled, float rate, float depth, float mix) {
    try {
      setChorusFn.invokeExact(enabled, rate, depth, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_chorus failed", t);
    }
  }

  /**
   * Flanger pedal: regenerating short-delay comb (rate/depth 0..1,
   * feedback 0..0.9, mix 0..1), after the chorus, on both paths.
   */
  public void setFlanger(boolean enabled, float rate, float depth, float feedback, float mix) {
    try {
      setFlangerFn.invokeExact(enabled, rate, depth, feedback, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_flanger failed", t);
    }
  }

  /**
   * Phaser pedal: LFO-swept all-pass notches (rate/depth/regen 0..1,
   * mix 0..1), after the flanger, on both paths.
   */
  public void setPhaser(boolean enabled, float rate, float depth, float regen, float mix) {
    try {
      setPhaserFn.invokeExact(enabled, rate, depth, regen, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_phaser failed", t);
    }
  }

  /**
   * Tremolo pedal: LFO volume pulse (rate/depth 0..1), after the phaser,
   * on both paths.
   */
  public void setTremolo(boolean enabled, float rate, float depth) {
    try {
      setTremoloFn.invokeExact(enabled, rate, depth);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_tremolo failed", t);
    }
  }

  /**
   * Vibrato pedal: pitch warble — the LFO-bent copy replaces the dry
   * signal (rate/depth/mix 0..1), after the tremolo, on both paths.
   */
  public void setVibrato(boolean enabled, float rate, float depth, float mix) {
    try {
      setVibratoFn.invokeExact(enabled, rate, depth, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_vibrato failed", t);
    }
  }

  /**
   * Compressor pedal: envelope follower with make-up gain (sustain/level
   * 0..1), after the vibrato, on both paths.
   */
  public void setCompressor(boolean enabled, float sustain, float level) {
    try {
      setCompressorFn.invokeExact(enabled, sustain, level);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_compressor failed", t);
    }
  }

  /**
   * EQ pedal: 3-band tone shaping (bass/mid/treble 0..1, 0.5 = flat),
   * after the compressor, on both paths.
   */
  public void setEq(boolean enabled, float bass, float mid, float treble) {
    try {
      setEqFn.invokeExact(enabled, bass, mid, treble);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_eq failed", t);
    }
  }

  /**
   * Wah pedal: resonant band-pass sweep (rate/center/resonance 0..1),
   * after the EQ, on both paths.
   */
  public void setWah(boolean enabled, float rate, float center, float resonance) {
    try {
      setWahFn.invokeExact(enabled, rate, center, resonance);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_wah failed", t);
    }
  }

  /**
   * Noise gate: mutes the path when the envelope drops below the
   * threshold (threshold/release 0..1), after the wah, on both paths.
   */
  public void setGate(boolean enabled, float threshold, float release) {
    try {
      setGateFn.invokeExact(enabled, threshold, release);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_gate failed", t);
    }
  }

  /**
   * Looper pedal: phrase looper (length/level 0..1), after the octave,
   * on both paths.
   */
  public void setLooper(boolean enabled, float length, float level) {
    try {
      setLooperFn.invokeExact(enabled, length, level);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_looper failed", t);
    }
  }

  /**
   * Octave pedal: analog-style octave doubling (mode 0 = down an octave,
   * 1 = up an octave; mix 0..1 blends the shifted copy), after the gate,
   * on both paths.
   */
  public void setOctave(boolean enabled, float mode, float mix) {
    try {
      setOctaveFn.invokeExact(enabled, mode, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_octave failed", t);
    }
  }

  /**
   * Volume pedal: foot-potentiometer attenuation with an audio taper
   * (level 0..1, gain = level²), after the looper, on both paths.
   */
  public void setVolume(boolean enabled, float level) {
    try {
      setVolumeFn.invokeExact(enabled, level);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_volume failed", t);
    }
  }

  /**
   * Tuner pedal: mutes the output while measuring the incoming pitch,
   * read back through {@link #getTunerHz()}. Last in the chain.
   */
  public void setTuner(boolean enabled) {
    try {
      setTunerFn.invokeExact(enabled);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_tuner failed", t);
    }
  }

  /**
   * Detected pitch of the tuner pedal in Hz (0 = muted/no reading yet).
   */
  public float getTunerHz() {
    try {
      return (float) getTunerHzFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_get_tuner_hz failed", t);
    }
  }

  /**
   * Cabinet pedal: a fixed 4x12 speaker response (HP 70 Hz, -4 dB dip at
   * 400 Hz, LP 5 kHz) blended over the dry signal (mix 0..1), last in
   * the chain.
   */
  public void setCabinet(boolean enabled, float mix) {
    try {
      setCabinetFn.invokeExact(enabled, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_cabinet failed", t);
    }
  }

  /**
   * Spring reverb pedal: three ringing combs with a band-limited, lightly
   * driven input path (dwell = ring decay 0..1, mix = wet blend 0..1),
   * last in the chain.
   */
  public void setSpring(boolean enabled, float dwell, float mix) {
    try {
      setSpringFn.invokeExact(enabled, dwell, mix);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_spring failed", t);
    }
  }

  /**
   * Amp channel: 0 clean, 1 crunch, 2 metal. The DRIVE knob acts as the
   * channel's gain.
   */
  public void setAmpModel(int model) {
    try {
      setAmpModelFn.invokeExact(model);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_amp_model failed", t);
    }
  }

  /**
   * String sustain: multiplier on the string model's ring time
   * (0.25..4, 1.0 = nominal). Mutes and palm mutes ignore it.
   */
  public void setSustain(float multiplier) {
    try {
      setSustainFn.invokeExact(multiplier);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_set_sustain failed", t);
    }
  }

  /**
   * Cabinet IR: loads a WAV impulse response that replaces the cabinet
   * pedal's modeled voicing with true FFT convolution. An empty path
   * reverts to the modeled cabinet. Returns false when the file is
   * unreadable or not a parseable WAV.
   */
  public boolean loadCabinetIr(java.nio.file.Path irPath) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pathSeg = arena.allocateFrom(irPath == null ? "" : irPath.toString());
      return (boolean) loadIrFn.invokeExact(pathSeg);
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_load_cabinet_ir failed", t);
    }
  }

  /**
   * Guitar simulation: 0 = acoustic, 1 = electric single coil, 2 = electric humbucker.
   */
  public void setGuitarModel(int model) {
    pushEvent(sampleClock(), EVENT_SET_MODEL, -1, model, 0.0f, 0.0f);
  }

  /**
   * Peak envelope of the rendered output (0..~1) for VU metering.
   */
  public float getLevel() {
    try {
      return (float) getLevelFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_get_level failed", t);
    }
  }

  /**
   * Invalidates every queued event (instant STOP): events already scheduled
   * with an older transport generation are dropped by the audio thread.
   */
  public void resetTransport() {
    try {
      resetTransportFn.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException("guitar_engine_reset_transport failed", t);
    }
  }

  private static void invokeVoid(MethodHandle handle, String name, Object... args) {
    try {
      handle.invokeWithArguments(args);
    } catch (Throwable t) {
      throw new RuntimeException(name + " failed", t);
    }
  }
}
