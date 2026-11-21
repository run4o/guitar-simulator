package dev.guitar.tabplayer.app;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.model.Tuning;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * Application-wide state shared by every panel controller: the synthesis
 * engine, its single-producer command queue, the four audio-parameter values
 * (bound bidirectionally to the knobs/faders), and the debug system-property
 * flags. Booted once by {@link PlayerApp} before the FXML is loaded.
 */
public final class AppContext {

  public static final long SR = 44100;

  private final GuitarEngine engine;
  private final boolean engineReady;
  private final ExecutorService commands; // the single producer for the engine queue
  private boolean deviceRunning;
  private final boolean autoplay;

  // Audio parameters: knobs/faders bind these bidirectionally; every change
  // is pushed to the engine as one setFx call (drive, tone, reverb, master).
  private final DoubleProperty drive = new SimpleDoubleProperty(0);
  private final DoubleProperty tone = new SimpleDoubleProperty(1);
  private final DoubleProperty reverb = new SimpleDoubleProperty(0);
  private final DoubleProperty master = new SimpleDoubleProperty(1);

  // Pedalboard parameters: one slot per pedal, pushed as one call each.
  private final DoubleProperty boostGain = new SimpleDoubleProperty(6);
  private final BooleanProperty boostEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty odDrive = new SimpleDoubleProperty(0.5);
  private final DoubleProperty odTone = new SimpleDoubleProperty(0.5);
  private final BooleanProperty odEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty fuzzAmount = new SimpleDoubleProperty(0.5);
  private final DoubleProperty fuzzVolume = new SimpleDoubleProperty(0.5);
  private final BooleanProperty fuzzEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty distDrive = new SimpleDoubleProperty(0.5);
  private final DoubleProperty distTone = new SimpleDoubleProperty(0.5);
  private final DoubleProperty distLevel = new SimpleDoubleProperty(0.5);
  private final BooleanProperty distEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty delayTime = new SimpleDoubleProperty(0.3);
  private final DoubleProperty delayFeedback = new SimpleDoubleProperty(0.4);
  private final DoubleProperty delayMix = new SimpleDoubleProperty(0.5);
  private final BooleanProperty delayEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty chorusRate = new SimpleDoubleProperty(0.5);
  private final DoubleProperty chorusDepth = new SimpleDoubleProperty(0.5);
  private final DoubleProperty chorusMix = new SimpleDoubleProperty(0.5);
  private final BooleanProperty chorusEnabled = new SimpleBooleanProperty(false);

  private AppContext(GuitarEngine engine, boolean engineReady, boolean deviceRunning,
      ExecutorService commands, boolean autoplay) {
    this.engine = engine;
    this.engineReady = engineReady;
    this.deviceRunning = deviceRunning;
    this.commands = commands;
    this.autoplay = autoplay;
    for (DoubleProperty p : new DoubleProperty[]{drive, tone, reverb, master}) {
      p.addListener((o, ov, nv) -> applyFx());
    }
    for (Object p : new Object[]{boostGain, boostEnabled, odDrive, odTone, odEnabled,
        distDrive, distTone, distLevel, distEnabled,
        fuzzAmount, fuzzVolume, fuzzEnabled,
        delayTime, delayFeedback, delayMix, delayEnabled,
        chorusRate, chorusDepth, chorusMix, chorusEnabled}) {
      if (p instanceof DoubleProperty d) {
        d.addListener((o, ov, nv) -> applyPedals());
      } else if (p instanceof BooleanProperty b) {
        b.addListener((o, ov, nv) -> applyPedals());
      }
    }
  }

  /**
   * Boots the engine and opens the audio device once at launch: removes Play
   * latency and starts the sample clock the playback cursor relies on.
   */
  public static AppContext boot() {
    GuitarEngine engine = null;
    boolean engineReady = false;
    try {
      engine = new GuitarEngine(GuitarEngine.defaultLibraryPath());
      engineReady = engine.init((int) SR, 256);
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
    ExecutorService commands = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "engine-commands");
      t.setDaemon(true);
      return t;
    });
    boolean deviceRunning = false;
    if (engineReady) {
      deviceRunning = engine.start();
    }
    boolean autoplay = "true".equalsIgnoreCase(System.getProperty("guitar.autoplay"));
    return new AppContext(engine, engineReady, deviceRunning, commands, autoplay);
  }

  public GuitarEngine engine() {
    return engine;
  }

  public boolean engineReady() {
    return engineReady;
  }

  public boolean deviceRunning() {
    return deviceRunning;
  }

  public void setDeviceRunning(boolean deviceRunning) {
    this.deviceRunning = deviceRunning;
  }

  public ExecutorService commands() {
    return commands;
  }

  public boolean autoplay() {
    return autoplay;
  }

  public DoubleProperty driveProperty() {
    return drive;
  }

  public DoubleProperty toneProperty() {
    return tone;
  }

  public DoubleProperty reverbProperty() {
    return reverb;
  }

  public DoubleProperty masterProperty() {
    return master;
  }

  public DoubleProperty boostGainProperty() {
    return boostGain;
  }

  public BooleanProperty boostEnabledProperty() {
    return boostEnabled;
  }

  public DoubleProperty odDriveProperty() {
    return odDrive;
  }

  public DoubleProperty odToneProperty() {
    return odTone;
  }

  public BooleanProperty odEnabledProperty() {
    return odEnabled;
  }

  public DoubleProperty fuzzAmountProperty() {
    return fuzzAmount;
  }

  public DoubleProperty fuzzVolumeProperty() {
    return fuzzVolume;
  }

  public BooleanProperty fuzzEnabledProperty() {
    return fuzzEnabled;
  }

  public DoubleProperty distDriveProperty() {
    return distDrive;
  }

  public DoubleProperty distToneProperty() {
    return distTone;
  }

  public DoubleProperty distLevelProperty() {
    return distLevel;
  }

  public BooleanProperty distEnabledProperty() {
    return distEnabled;
  }

  public DoubleProperty delayTimeProperty() {
    return delayTime;
  }

  public DoubleProperty delayFeedbackProperty() {
    return delayFeedback;
  }

  public DoubleProperty delayMixProperty() {
    return delayMix;
  }

  public BooleanProperty delayEnabledProperty() {
    return delayEnabled;
  }

  public DoubleProperty chorusRateProperty() {
    return chorusRate;
  }

  public DoubleProperty chorusDepthProperty() {
    return chorusDepth;
  }

  public DoubleProperty chorusMixProperty() {
    return chorusMix;
  }

  public BooleanProperty chorusEnabledProperty() {
    return chorusEnabled;
  }

  /** Debug flag: preselect FX (-Dguitar.fx=drive,tone,reverb,master). */
  public void setFxValues(double drive, double tone, double reverb, double master) {
    this.drive.set(Math.max(0, Math.min(1, drive)));
    this.tone.set(Math.max(0, Math.min(1, tone)));
    this.reverb.set(Math.max(0, Math.min(1, reverb)));
    this.master.set(Math.max(0, Math.min(2, master)));
  }

  /** Project-load setter for the pedalboard. */
  public void setBoostValues(boolean enabled, double gain) {
    boostEnabled.set(enabled);
    boostGain.set(Math.max(0, Math.min(18, gain)));
  }

  /**
   * The pedalboard state for project files: keys "pedal.<pedal>.<param>".
   */
  public Map<String, String> pedalState() {
    Map<String, String> state = new TreeMap<>();
    state.put("boost.enabled", Boolean.toString(boostEnabled.get()));
    state.put("boost.gain", Double.toString(boostGain.get()));
    state.put("od.enabled", Boolean.toString(odEnabled.get()));
    state.put("od.drive", Double.toString(odDrive.get()));
    state.put("od.tone", Double.toString(odTone.get()));
    state.put("dist.enabled", Boolean.toString(distEnabled.get()));
    state.put("dist.drive", Double.toString(distDrive.get()));
    state.put("dist.tone", Double.toString(distTone.get()));
    state.put("dist.level", Double.toString(distLevel.get()));
    state.put("fuzz.enabled", Boolean.toString(fuzzEnabled.get()));
    state.put("fuzz.fuzz", Double.toString(fuzzAmount.get()));
    state.put("fuzz.volume", Double.toString(fuzzVolume.get()));
    state.put("delay.enabled", Boolean.toString(delayEnabled.get()));
    state.put("delay.time", Double.toString(delayTime.get()));
    state.put("delay.feedback", Double.toString(delayFeedback.get()));
    state.put("delay.mix", Double.toString(delayMix.get()));
    state.put("chorus.enabled", Boolean.toString(chorusEnabled.get()));
    state.put("chorus.rate", Double.toString(chorusRate.get()));
    state.put("chorus.depth", Double.toString(chorusDepth.get()));
    state.put("chorus.mix", Double.toString(chorusMix.get()));
    return state;
  }

  /** Restores the pedalboard from a project file's pedal.* entries. */
  public void loadPedalState(Map<String, String> state) {
    boostEnabled.set(Boolean.parseBoolean(state.getOrDefault("boost.enabled", "false")));
    boostGain.set(clamp(parse(state.getOrDefault("boost.gain", "6")), 0, 18));
    odEnabled.set(Boolean.parseBoolean(state.getOrDefault("od.enabled", "false")));
    odDrive.set(clamp(parse(state.getOrDefault("od.drive", "0.5")), 0, 1));
    odTone.set(clamp(parse(state.getOrDefault("od.tone", "0.5")), 0, 1));
    distEnabled.set(Boolean.parseBoolean(state.getOrDefault("dist.enabled", "false")));
    distDrive.set(clamp(parse(state.getOrDefault("dist.drive", "0.5")), 0, 1));
    distTone.set(clamp(parse(state.getOrDefault("dist.tone", "0.5")), 0, 1));
    distLevel.set(clamp(parse(state.getOrDefault("dist.level", "0.5")), 0, 1));
    fuzzEnabled.set(Boolean.parseBoolean(state.getOrDefault("fuzz.enabled", "false")));
    fuzzAmount.set(clamp(parse(state.getOrDefault("fuzz.fuzz", "0.5")), 0, 1));
    fuzzVolume.set(clamp(parse(state.getOrDefault("fuzz.volume", "0.5")), 0, 1));
    delayEnabled.set(Boolean.parseBoolean(state.getOrDefault("delay.enabled", "false")));
    delayTime.set(clamp(parse(state.getOrDefault("delay.time", "0.3")), 0.02, 1.0));
    delayFeedback.set(clamp(parse(state.getOrDefault("delay.feedback", "0.4")), 0, 0.9));
    delayMix.set(clamp(parse(state.getOrDefault("delay.mix", "0.5")), 0, 1));
    chorusEnabled.set(Boolean.parseBoolean(state.getOrDefault("chorus.enabled", "false")));
    chorusRate.set(clamp(parse(state.getOrDefault("chorus.rate", "0.5")), 0, 1));
    chorusDepth.set(clamp(parse(state.getOrDefault("chorus.depth", "0.5")), 0, 1));
    chorusMix.set(clamp(parse(state.getOrDefault("chorus.mix", "0.5")), 0, 1));
  }

  private static double parse(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private void applyFx() {
    if (!engineReady) {
      return;
    }
    engine.setFx((float) drive.get(), (float) tone.get(), (float) reverb.get(),
        (float) master.get());
  }

  private void applyPedals() {
    if (!engineReady) {
      return;
    }
    engine.setBoost(boostEnabled.get(), (float) boostGain.get());
    engine.setOverdrive(odEnabled.get(), (float) odDrive.get(), (float) odTone.get());
    engine.setFuzz(fuzzEnabled.get(), (float) fuzzAmount.get(), (float) fuzzVolume.get());
    engine.setDistortion(distEnabled.get(), (float) distDrive.get(),
        (float) distTone.get(), (float) distLevel.get());
    engine.setDelay(delayEnabled.get(), (float) delayTime.get(),
        (float) delayFeedback.get(), (float) delayMix.get());
    engine.setChorus(chorusEnabled.get(), (float) chorusRate.get(),
        (float) chorusDepth.get(), (float) chorusMix.get());
  }

  public void applyTuning(Tuning tuning) {
    if (!engineReady) {
      return;
    }
    commands.execute(() -> {
      for (int s = 0; s < 6; s++) {
        engine.setStringTuning(s, (float) tuning.freqs[s]);
      }
    });
  }

  /** Status messages go to the console only (the on-screen log strip was removed). */
  public void status(String line) {
    System.out.println("[gtp] " + line);
  }

  public void shutdown() {
    commands.shutdownNow();
    if (engineReady) {
      engine.shutdown();
    }
    if (engine != null) {
      engine.close();
    }
  }

  /**
   * Scientific pitch notation from a frequency, e.g. 82.41 Hz -> "E2".
   */
  public static String noteName(double freq) {
    if (freq <= 0) {
      return "--";
    }
    String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    int midi = (int) Math.round(69.0 + 12.0 * Math.log(freq / 440.0) / Math.log(2.0));
    String name = names[((midi % 12) + 12) % 12];
    int octave = midi / 12 - 1;
    return name + octave;
  }
}
