package dev.guitar.tabplayer.app;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.model.Tuning;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.beans.property.DoubleProperty;
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

  /** Debug flag: preselect FX (-Dguitar.fx=drive,tone,reverb,master). */
  public void setFxValues(double drive, double tone, double reverb, double master) {
    this.drive.set(Math.max(0, Math.min(1, drive)));
    this.tone.set(Math.max(0, Math.min(1, tone)));
    this.reverb.set(Math.max(0, Math.min(1, reverb)));
    this.master.set(Math.max(0, Math.min(2, master)));
  }

  private void applyFx() {
    if (!engineReady) {
      return;
    }
    engine.setFx((float) drive.get(), (float) tone.get(), (float) reverb.get(),
        (float) master.get());
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
