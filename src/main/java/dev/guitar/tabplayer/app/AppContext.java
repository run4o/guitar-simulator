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
  private final DoubleProperty flangerRate = new SimpleDoubleProperty(0.5);
  private final DoubleProperty flangerDepth = new SimpleDoubleProperty(0.6);
  private final DoubleProperty flangerFeedback = new SimpleDoubleProperty(0.4);
  private final DoubleProperty flangerMix = new SimpleDoubleProperty(0.5);
  private final BooleanProperty flangerEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty phaserRate = new SimpleDoubleProperty(0.5);
  private final DoubleProperty phaserDepth = new SimpleDoubleProperty(0.5);
  private final DoubleProperty phaserRegen = new SimpleDoubleProperty(0.4);
  private final DoubleProperty phaserMix = new SimpleDoubleProperty(0.5);
  private final BooleanProperty phaserEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty tremoloRate = new SimpleDoubleProperty(0.5);
  private final DoubleProperty tremoloDepth = new SimpleDoubleProperty(0.5);
  private final BooleanProperty tremoloEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty vibratoRate = new SimpleDoubleProperty(0.5);
  private final DoubleProperty vibratoDepth = new SimpleDoubleProperty(0.5);
  private final DoubleProperty vibratoMix = new SimpleDoubleProperty(1.0);
  private final BooleanProperty vibratoEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty compSustain = new SimpleDoubleProperty(0.5);
  private final DoubleProperty compLevel = new SimpleDoubleProperty(0.5);
  private final BooleanProperty compEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty eqBass = new SimpleDoubleProperty(0.5);
  private final DoubleProperty eqMid = new SimpleDoubleProperty(0.5);
  private final DoubleProperty eqTreble = new SimpleDoubleProperty(0.5);
  private final BooleanProperty eqEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty wahRate = new SimpleDoubleProperty(0.0);
  private final DoubleProperty wahCenter = new SimpleDoubleProperty(0.5);
  private final DoubleProperty wahResonance = new SimpleDoubleProperty(0.3);
  private final BooleanProperty wahEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty gateThreshold = new SimpleDoubleProperty(0.5);
  private final DoubleProperty gateRelease = new SimpleDoubleProperty(0.3);
  private final BooleanProperty gateEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty loopLength = new SimpleDoubleProperty(0.5);
  private final DoubleProperty loopLevel = new SimpleDoubleProperty(0.7);
  private final BooleanProperty loopEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty volLevel = new SimpleDoubleProperty(1.0);
  private final BooleanProperty volEnabled = new SimpleBooleanProperty(false);
  private final BooleanProperty tunEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty cabMix = new SimpleDoubleProperty(1.0);
  private final BooleanProperty cabEnabled = new SimpleBooleanProperty(false);
  private final DoubleProperty sprDwell = new SimpleDoubleProperty(0.5);
  private final DoubleProperty sprMix = new SimpleDoubleProperty(0.5);
  private final BooleanProperty sprEnabled = new SimpleBooleanProperty(false);
  /** Pedalboard drawer (pop-out over the score): closed by default. */
  private final BooleanProperty pedalsOpen = new SimpleBooleanProperty(false);
  private final DoubleProperty octMode = new SimpleDoubleProperty(0.0);
  private final DoubleProperty octMix = new SimpleDoubleProperty(0.7);
  private final BooleanProperty octEnabled = new SimpleBooleanProperty(false);

  AppContext(GuitarEngine engine, boolean engineReady, boolean deviceRunning,
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
        chorusRate, chorusDepth, chorusMix, chorusEnabled,
        flangerRate, flangerDepth, flangerFeedback, flangerMix, flangerEnabled,
        phaserRate, phaserDepth, phaserRegen, phaserMix, phaserEnabled,
        tremoloRate, tremoloDepth, tremoloEnabled,
        vibratoRate, vibratoDepth, vibratoMix, vibratoEnabled,
        compSustain, compLevel, compEnabled,
        eqBass, eqMid, eqTreble, eqEnabled,
        wahRate, wahCenter, wahResonance, wahEnabled,
        gateThreshold, gateRelease, gateEnabled,
        octMode, octMix, octEnabled,
        loopLength, loopLevel, loopEnabled,
        volLevel, volEnabled, tunEnabled,
        cabMix, cabEnabled,
        sprDwell, sprMix, sprEnabled}) {
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

  public DoubleProperty flangerRateProperty() {
    return flangerRate;
  }

  public DoubleProperty flangerDepthProperty() {
    return flangerDepth;
  }

  public DoubleProperty flangerFeedbackProperty() {
    return flangerFeedback;
  }

  public DoubleProperty flangerMixProperty() {
    return flangerMix;
  }

  public BooleanProperty flangerEnabledProperty() {
    return flangerEnabled;
  }

  public DoubleProperty phaserRateProperty() {
    return phaserRate;
  }

  public DoubleProperty phaserDepthProperty() {
    return phaserDepth;
  }

  public DoubleProperty phaserRegenProperty() {
    return phaserRegen;
  }

  public DoubleProperty phaserMixProperty() {
    return phaserMix;
  }

  public BooleanProperty phaserEnabledProperty() {
    return phaserEnabled;
  }

  public DoubleProperty tremoloRateProperty() {
    return tremoloRate;
  }

  public DoubleProperty tremoloDepthProperty() {
    return tremoloDepth;
  }

  public BooleanProperty tremoloEnabledProperty() {
    return tremoloEnabled;
  }

  public DoubleProperty vibratoRateProperty() {
    return vibratoRate;
  }

  public DoubleProperty vibratoDepthProperty() {
    return vibratoDepth;
  }

  public DoubleProperty vibratoMixProperty() {
    return vibratoMix;
  }

  public BooleanProperty vibratoEnabledProperty() {
    return vibratoEnabled;
  }

  public DoubleProperty compSustainProperty() {
    return compSustain;
  }

  public DoubleProperty compLevelProperty() {
    return compLevel;
  }

  public BooleanProperty compEnabledProperty() {
    return compEnabled;
  }

  public DoubleProperty eqBassProperty() {
    return eqBass;
  }

  public DoubleProperty eqMidProperty() {
    return eqMid;
  }

  public DoubleProperty eqTrebleProperty() {
    return eqTreble;
  }

  public BooleanProperty eqEnabledProperty() {
    return eqEnabled;
  }

  public DoubleProperty wahRateProperty() {
    return wahRate;
  }

  public DoubleProperty wahCenterProperty() {
    return wahCenter;
  }

  public DoubleProperty wahResonanceProperty() {
    return wahResonance;
  }

  public BooleanProperty wahEnabledProperty() {
    return wahEnabled;
  }

  public DoubleProperty gateThresholdProperty() {
    return gateThreshold;
  }

  public DoubleProperty gateReleaseProperty() {
    return gateRelease;
  }

  public BooleanProperty gateEnabledProperty() {
    return gateEnabled;
  }

  public DoubleProperty octModeProperty() {
    return octMode;
  }

  public DoubleProperty octMixProperty() {
    return octMix;
  }

  public BooleanProperty octEnabledProperty() {
    return octEnabled;
  }

  public DoubleProperty loopLengthProperty() {
    return loopLength;
  }

  public DoubleProperty loopLevelProperty() {
    return loopLevel;
  }

  public BooleanProperty loopEnabledProperty() {
    return loopEnabled;
  }

  public DoubleProperty volLevelProperty() {
    return volLevel;
  }

  public BooleanProperty volEnabledProperty() {
    return volEnabled;
  }

  public BooleanProperty tunEnabledProperty() {
    return tunEnabled;
  }

  public DoubleProperty cabMixProperty() {
    return cabMix;
  }

  public BooleanProperty cabEnabledProperty() {
    return cabEnabled;
  }

  public DoubleProperty sprDwellProperty() {
    return sprDwell;
  }

  public DoubleProperty sprMixProperty() {
    return sprMix;
  }

  public BooleanProperty sprEnabledProperty() {
    return sprEnabled;
  }

  public BooleanProperty pedalsOpenProperty() {
    return pedalsOpen;
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
    state.put("flanger.enabled", Boolean.toString(flangerEnabled.get()));
    state.put("flanger.rate", Double.toString(flangerRate.get()));
    state.put("flanger.depth", Double.toString(flangerDepth.get()));
    state.put("flanger.feedback", Double.toString(flangerFeedback.get()));
    state.put("flanger.mix", Double.toString(flangerMix.get()));
    state.put("phaser.enabled", Boolean.toString(phaserEnabled.get()));
    state.put("phaser.rate", Double.toString(phaserRate.get()));
    state.put("phaser.depth", Double.toString(phaserDepth.get()));
    state.put("phaser.regen", Double.toString(phaserRegen.get()));
    state.put("phaser.mix", Double.toString(phaserMix.get()));
    state.put("tremolo.enabled", Boolean.toString(tremoloEnabled.get()));
    state.put("tremolo.rate", Double.toString(tremoloRate.get()));
    state.put("tremolo.depth", Double.toString(tremoloDepth.get()));
    state.put("vibrato.enabled", Boolean.toString(vibratoEnabled.get()));
    state.put("vibrato.rate", Double.toString(vibratoRate.get()));
    state.put("vibrato.depth", Double.toString(vibratoDepth.get()));
    state.put("vibrato.mix", Double.toString(vibratoMix.get()));
    state.put("comp.enabled", Boolean.toString(compEnabled.get()));
    state.put("comp.sustain", Double.toString(compSustain.get()));
    state.put("comp.level", Double.toString(compLevel.get()));
    state.put("eq.enabled", Boolean.toString(eqEnabled.get()));
    state.put("eq.bass", Double.toString(eqBass.get()));
    state.put("eq.mid", Double.toString(eqMid.get()));
    state.put("eq.treble", Double.toString(eqTreble.get()));
    state.put("wah.enabled", Boolean.toString(wahEnabled.get()));
    state.put("wah.rate", Double.toString(wahRate.get()));
    state.put("wah.center", Double.toString(wahCenter.get()));
    state.put("wah.resonance", Double.toString(wahResonance.get()));
    state.put("gate.enabled", Boolean.toString(gateEnabled.get()));
    state.put("gate.threshold", Double.toString(gateThreshold.get()));
    state.put("gate.release", Double.toString(gateRelease.get()));
    state.put("oct.enabled", Boolean.toString(octEnabled.get()));
    state.put("oct.mode", Double.toString(octMode.get()));
    state.put("oct.mix", Double.toString(octMix.get()));
    state.put("loop.enabled", Boolean.toString(loopEnabled.get()));
    state.put("loop.length", Double.toString(loopLength.get()));
    state.put("loop.level", Double.toString(loopLevel.get()));
    state.put("vol.enabled", Boolean.toString(volEnabled.get()));
    state.put("vol.level", Double.toString(volLevel.get()));
    state.put("tun.enabled", Boolean.toString(tunEnabled.get()));
    state.put("cab.enabled", Boolean.toString(cabEnabled.get()));
    state.put("cab.mix", Double.toString(cabMix.get()));
    state.put("spr.enabled", Boolean.toString(sprEnabled.get()));
    state.put("spr.dwell", Double.toString(sprDwell.get()));
    state.put("spr.mix", Double.toString(sprMix.get()));
    state.put("ui.pedalsOpen", Boolean.toString(pedalsOpen.get()));
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
    flangerEnabled.set(Boolean.parseBoolean(state.getOrDefault("flanger.enabled", "false")));
    flangerRate.set(clamp(parse(state.getOrDefault("flanger.rate", "0.5")), 0, 1));
    flangerDepth.set(clamp(parse(state.getOrDefault("flanger.depth", "0.6")), 0, 1));
    flangerFeedback.set(clamp(parse(state.getOrDefault("flanger.feedback", "0.4")), 0, 0.9));
    flangerMix.set(clamp(parse(state.getOrDefault("flanger.mix", "0.5")), 0, 1));
    phaserEnabled.set(Boolean.parseBoolean(state.getOrDefault("phaser.enabled", "false")));
    phaserRate.set(clamp(parse(state.getOrDefault("phaser.rate", "0.5")), 0, 1));
    phaserDepth.set(clamp(parse(state.getOrDefault("phaser.depth", "0.5")), 0, 1));
    phaserRegen.set(clamp(parse(state.getOrDefault("phaser.regen", "0.4")), 0, 0.9));
    phaserMix.set(clamp(parse(state.getOrDefault("phaser.mix", "0.5")), 0, 1));
    tremoloEnabled.set(Boolean.parseBoolean(state.getOrDefault("tremolo.enabled", "false")));
    tremoloRate.set(clamp(parse(state.getOrDefault("tremolo.rate", "0.5")), 0, 1));
    tremoloDepth.set(clamp(parse(state.getOrDefault("tremolo.depth", "0.5")), 0, 1));
    vibratoEnabled.set(Boolean.parseBoolean(state.getOrDefault("vibrato.enabled", "false")));
    vibratoRate.set(clamp(parse(state.getOrDefault("vibrato.rate", "0.5")), 0, 1));
    vibratoDepth.set(clamp(parse(state.getOrDefault("vibrato.depth", "0.5")), 0, 1));
    vibratoMix.set(clamp(parse(state.getOrDefault("vibrato.mix", "1")), 0, 1));
    compEnabled.set(Boolean.parseBoolean(state.getOrDefault("comp.enabled", "false")));
    compSustain.set(clamp(parse(state.getOrDefault("comp.sustain", "0.5")), 0, 1));
    compLevel.set(clamp(parse(state.getOrDefault("comp.level", "0.5")), 0, 1));
    eqEnabled.set(Boolean.parseBoolean(state.getOrDefault("eq.enabled", "false")));
    eqBass.set(clamp(parse(state.getOrDefault("eq.bass", "0.5")), 0, 1));
    eqMid.set(clamp(parse(state.getOrDefault("eq.mid", "0.5")), 0, 1));
    eqTreble.set(clamp(parse(state.getOrDefault("eq.treble", "0.5")), 0, 1));
    wahEnabled.set(Boolean.parseBoolean(state.getOrDefault("wah.enabled", "false")));
    wahRate.set(clamp(parse(state.getOrDefault("wah.rate", "0")), 0, 1));
    wahCenter.set(clamp(parse(state.getOrDefault("wah.center", "0.5")), 0, 1));
    wahResonance.set(clamp(parse(state.getOrDefault("wah.resonance", "0.3")), 0, 1));
    gateEnabled.set(Boolean.parseBoolean(state.getOrDefault("gate.enabled", "false")));
    gateThreshold.set(clamp(parse(state.getOrDefault("gate.threshold", "0.5")), 0, 1));
    gateRelease.set(clamp(parse(state.getOrDefault("gate.release", "0.3")), 0, 1));
    octEnabled.set(Boolean.parseBoolean(state.getOrDefault("oct.enabled", "false")));
    octMode.set(clamp(parse(state.getOrDefault("oct.mode", "0")), 0, 1));
    octMix.set(clamp(parse(state.getOrDefault("oct.mix", "0.7")), 0, 1));
    loopEnabled.set(Boolean.parseBoolean(state.getOrDefault("loop.enabled", "false")));
    loopLength.set(clamp(parse(state.getOrDefault("loop.length", "0.5")), 0, 1));
    loopLevel.set(clamp(parse(state.getOrDefault("loop.level", "0.7")), 0, 1));
    volEnabled.set(Boolean.parseBoolean(state.getOrDefault("vol.enabled", "false")));
    volLevel.set(clamp(parse(state.getOrDefault("vol.level", "1")), 0, 1));
    tunEnabled.set(Boolean.parseBoolean(state.getOrDefault("tun.enabled", "false")));
    cabEnabled.set(Boolean.parseBoolean(state.getOrDefault("cab.enabled", "false")));
    cabMix.set(clamp(parse(state.getOrDefault("cab.mix", "1")), 0, 1));
    sprEnabled.set(Boolean.parseBoolean(state.getOrDefault("spr.enabled", "false")));
    sprDwell.set(clamp(parse(state.getOrDefault("spr.dwell", "0.5")), 0, 1));
    sprMix.set(clamp(parse(state.getOrDefault("spr.mix", "0.5")), 0, 1));
    pedalsOpen.set(Boolean.parseBoolean(state.getOrDefault("ui.pedalsOpen", "false")));
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
    engine.setFlanger(flangerEnabled.get(), (float) flangerRate.get(),
        (float) flangerDepth.get(), (float) flangerFeedback.get(),
        (float) flangerMix.get());
    engine.setPhaser(phaserEnabled.get(), (float) phaserRate.get(),
        (float) phaserDepth.get(), (float) phaserRegen.get(), (float) phaserMix.get());
    engine.setTremolo(tremoloEnabled.get(), (float) tremoloRate.get(), (float) tremoloDepth.get());
    engine.setVibrato(vibratoEnabled.get(), (float) vibratoRate.get(),
        (float) vibratoDepth.get(), (float) vibratoMix.get());
    engine.setCompressor(compEnabled.get(), (float) compSustain.get(), (float) compLevel.get());
    engine.setEq(eqEnabled.get(), (float) eqBass.get(), (float) eqMid.get(), (float) eqTreble.get());
    engine.setWah(wahEnabled.get(), (float) wahRate.get(), (float) wahCenter.get(),
        (float) wahResonance.get());
    engine.setGate(gateEnabled.get(), (float) gateThreshold.get(), (float) gateRelease.get());
    engine.setOctave(octEnabled.get(), (float) octMode.get(), (float) octMix.get());
    engine.setLooper(loopEnabled.get(), (float) loopLength.get(), (float) loopLevel.get());
    engine.setVolume(volEnabled.get(), (float) volLevel.get());
    engine.setTuner(tunEnabled.get());
    engine.setCabinet(cabEnabled.get(), (float) cabMix.get());
    engine.setSpring(sprEnabled.get(), (float) sprDwell.get(), (float) sprMix.get());
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
