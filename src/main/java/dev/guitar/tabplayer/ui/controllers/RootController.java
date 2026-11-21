package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.app.ProjectFile;
import dev.guitar.tabplayer.model.Tuning;
import dev.guitar.tabplayer.playback.PlaybackService;
import dev.guitar.tabplayer.tab.TabParser;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Root controller of the main page: owns the app context, the playback
 * service and the 50 ms meter poll, and wires the panel controllers together.
 * Panels expose small APIs (cursor, level, labels); all cross-panel policy
 * lives here.
 */
public class RootController {

  @FXML
  private TopPanelController topPanelController;
  @FXML
  private TabPanelController tabPanelController;
  @FXML
  private GuitarPanelController guitarPanelController;
  @FXML
  private SoundPanelController soundPanelController;
  @FXML
  private VolumePanelController volumePanelController;
  @FXML
  private TunerPanelController tunerPanelController;
  @FXML
  private PedalPanelController pedalPanelController;

  private AppContext ctx;
  private PlaybackService playback;
  private Stage stage;

  public void init(AppContext ctx, Stage stage) {
    this.ctx = ctx;
    this.stage = stage;
    playback = new PlaybackService(ctx);

    playback.setTimelineListener(this::onTimelinePublished);
    playback.setStateListener(playing -> {
      transport().setPlaying(playing);
      if (!playing) {
        tabPanelController.deselectCaret();
        ctx.status("STOPPED.");
      }
    });

    transport().setActions(this::startPlayback, this::stopPlayback);
    transport().setLoopListener(playback::setLoop);

    tabPanelController.init(ctx, playback);
    tabPanelController.setOnEditStop(() -> {
      stopPlayback();
      ctx.status("Edited — playback stopped");
    });

    guitarPanelController.init(ctx);
    guitarPanelController.setTuningListener(tuning -> {
      soundPanelController.updateStringLabels(tuning);
      tunerPanelController.updateTuning(tuning);
      ctx.applyTuning(tuning);
      refreshScoreLabels(); // lane labels must not wait for the next compile
    });
    guitarPanelController.setBpmListener(playback::requestBpm);
    soundPanelController.init(ctx);
    volumePanelController.init(ctx);
    tunerPanelController.init(ctx);
    pedalPanelController.init(ctx);

    // App-header File menu: dialogs live in the header panel, actions here.
    appHeader().setActionHandler(this::handleMenuAction);

    // Debug flags (behavior preserved from the monolith).
    if ("source".equalsIgnoreCase(System.getProperty("guitar.view"))) {
      tabPanelController.showSource(); // start in source view
    }
    String tuningProp = System.getProperty("guitar.tuning");
    if (tuningProp != null) {
      guitarPanelController.selectTuningNamed(tuningProp);
    }
    soundPanelController.updateStringLabels(guitarPanelController.getTuning());
    tunerPanelController.updateTuning(guitarPanelController.getTuning());

    ctx.status(ctx.engineReady()
        ? (ctx.deviceRunning() ? "READY — EDIT THE TAB, THEN PLAY"
                               : "ENGINE READY BUT NO AUDIO DEVICE — PLAYBACK UNAVAILABLE")
        : "ERROR: ENGINE FAILED TO INITIALIZE — SEE CONSOLE");
    ctx.status("UI BUILD " + java.time.LocalDate.now() + " · engine " +
        (ctx.engine() != null ? ctx.engine().version() : "unavailable") +
        (ctx.autoplay() ? " · autoplay" : ""));
    System.out.println("[gtp] ui build " + java.time.LocalDate.now() + " · engine " +
        (ctx.engine() != null ? ctx.engine().version() : "unavailable"));

    if (ctx.engineReady()) {
      ctx.applyTuning(guitarPanelController.getTuning());
      tabPanelController.compileCurrent(); // render the initial score view + lane labels

      String fxProp = System.getProperty("guitar.fx");
      if (fxProp != null) {
        String[] parts = fxProp.split(",");
        if (parts.length == 4) {
          try {
            ctx.setFxValues(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
          } catch (NumberFormatException ignored) {
            // malformed flag values are simply ignored
          }
        }
      }

      if (ctx.autoplay()) {
        Platform.runLater(this::startPlayback);
      }
      String shotPath = System.getProperty("guitar.screenshot");
      if (shotPath != null) {
        // optional capture delay: -Dguitar.screenshot=path@seconds
        int at = shotPath.lastIndexOf('@');
        double delay = 2.0;
        if (at >= 0) {
          try {
            delay = Double.parseDouble(shotPath.substring(at + 1));
          } catch (NumberFormatException ignored) {
            // keep the default 2 s
          }
          shotPath = shotPath.substring(0, at);
        }
        final String finalPath = shotPath;
        captureScreenshotLater(stage, finalPath, delay);
      }
    }

    startMeterPoll();
  }

  public void handleClose() {
    playback.halt();
    ctx.shutdown();
  }

  private TransportPanelController transport() {
    return topPanelController.transport();
  }

  private AppHeaderPanelController appHeader() {
    return topPanelController.appHeader();
  }

  /* ------------------------------------------------------------------
   * Meter poll + screenshot
   * ------------------------------------------------------------------ */

  private void startMeterPoll() {
    Timeline poll = new Timeline(new KeyFrame(Duration.millis(50), e -> {
      if (!ctx.engineReady()) {
        return;
      }
      volumePanelController.setVuLevel(ctx.engine().getLevel());
      long clock = ctx.engine().sampleClock();
      double cursor = playback.cursorSeconds(clock, AppContext.SR);
      tabPanelController.updateCursor(cursor);
      if (cursor >= 0) {
        long total = (long) cursor;
        transport().setTime(String.format("%02d:%02d", total / 60, total % 60));
      } else {
        transport().setTime("00:00");
      }
    }));
    poll.setCycleCount(Timeline.INDEFINITE);
    poll.play();
  }

  private void captureScreenshotLater(Stage stage, String path, double delaySeconds) {
    Timeline shot = new Timeline(new KeyFrame(Duration.seconds(delaySeconds), e -> {
      // Snapshot the scene content directly — immune to overlapping windows.
      var image = stage.getScene().snapshot(null);
      int w = (int) image.getWidth();
      int h = (int) image.getHeight();
      int[] pixels = new int[w * h];
      image.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(),
          pixels, 0, w);
      new Thread(() -> {
        try {
          var awt = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
          awt.setRGB(0, 0, w, h, pixels, 0, w);
          javax.imageio.ImageIO.write(awt, "png", new java.io.File(path));
          System.out.println("[screenshot] saved " + path);
        } catch (Exception ex) {
          ex.printStackTrace();
        }
        Platform.exit();
      }, "screenshot").start();
    }));
    shot.play();
  }

  /* ------------------------------------------------------------------
   * File menu actions
   * ------------------------------------------------------------------ */

  /**
   * The header panel owns the dialogs; the root controller owns the effects.
   */
  private void handleMenuAction(String action, java.nio.file.Path file) {
    switch (action) {
      case AppHeaderPanelController.OPEN_TAB -> tabPanelController.openTab(file);
      case AppHeaderPanelController.IMPORT_GP -> importGuitarPro(file);
      case AppHeaderPanelController.SAVE_PROJECT -> saveProject(file);
      case AppHeaderPanelController.LOAD_PROJECT -> loadProject(file);
      case AppHeaderPanelController.EXPORT_WAV -> exportWav(file);
      default -> ctx.status("UNKNOWN MENU ACTION: " + action);
    }
  }

  private void importGuitarPro(java.nio.file.Path file) {
    try {
      dev.guitar.tabplayer.tab.gp.GpImport.GpSong song =
          dev.guitar.tabplayer.tab.gp.GpImport.importFile(file);
      tabPanelController.replaceImportedTab(song.ascii(), songMeterMap(song));
      guitarPanelController.setBpm(song.bpm());
      ctx.status("IMPORTED " + file.getFileName().toString().toUpperCase()
          + " — " + song.trackName() + " @ " + song.bpm() + " BPM");
    } catch (Exception e) {
      ctx.status("GP IMPORT FAILED: " + e.getMessage());
    }
  }

  /** Per-bar time signatures as a sparse bar-index map for the compiler. */
  private java.util.Map<Integer, int[]> songMeterMap(dev.guitar.tabplayer.tab.gp.GpImport.GpSong song) {
    java.util.Map<Integer, int[]> map = new java.util.HashMap<>();
    for (int i = 0; i < song.meters().size(); i++) {
      map.put(i, song.meters().get(i));
    }
    return map;
  }

  private void saveProject(java.nio.file.Path file) {
    try {
      ProjectFile.save(file, new ProjectFile.Project(
          tabPanelController.tabText(),
          guitarPanelController.getBpm(),
          guitarPanelController.getTuning(),
          ctx.driveProperty().get(), ctx.toneProperty().get(),
          ctx.reverbProperty().get(), ctx.masterProperty().get(),
          ctx.boostEnabledProperty().get(), ctx.boostGainProperty().get()));
      ctx.status("PROJECT SAVED " + file.getFileName().toString().toUpperCase());
    } catch (Exception e) {
      ctx.status("PROJECT SAVE FAILED: " + e.getMessage());
    }
  }

  private void loadProject(java.nio.file.Path file) {
    try {
      ProjectFile.Project project = ProjectFile.load(file);
      // The listeners fire as the panel state changes, so set the console
      // first (tuning/fx) and the tab last — one final compile.
      guitarPanelController.setBpm(project.bpm());
      ctx.setFxValues(project.drive(), project.tone(), project.reverb(), project.master());
      ctx.setBoostValues(project.boostEnabled(), project.boostGain());
      guitarPanelController.selectTuning(project.tuning());
      tabPanelController.replaceTabText(project.tab());
      ctx.status("PROJECT LOADED " + file.getFileName().toString().toUpperCase());
    } catch (Exception e) {
      ctx.status("PROJECT LOAD FAILED: " + e.getMessage());
    }
  }

  private void exportWav(java.nio.file.Path file) {
    if (!ctx.engineReady()) {
      ctx.status("ENGINE NOT READY — EXPORT UNAVAILABLE");
      return;
    }
    TabParser.TabTimeline timeline = playback.compileAndPublish(tabPanelController.tabText());
    if (timeline.commands().isEmpty()) {
      ctx.status("NOTHING TO EXPORT.");
      return;
    }
    ctx.status("RENDERING " + timeline.commands().size() + " EVENTS OFFLINE...");
    Thread t = new Thread(() -> {
      boolean ok = ctx.engine().renderSequenceToWav(file, (float) timeline.durationSeconds(),
          timeline.commands());
      javafx.application.Platform.runLater(() -> ctx.status(
          ok ? "EXPORTED " + timeline.commands().size() + " EVENTS TO " + file.toAbsolutePath()
             : "EXPORT FAILED"));
    }, "offline-render");
    t.setDaemon(true);
    t.start();
  }

  /* ------------------------------------------------------------------
   * Playback actions
   * ------------------------------------------------------------------ */

  private void startPlayback() {
    playback.play(tabPanelController.tabText(), guitarPanelController.getBpm());
  }

  private void stopPlayback() {
    playback.stop();
  }

  /**
   * A (re)compiled timeline arrives from the playback service: lane labels
   * are derived from the selected tuning.
   */
  private void onTimelinePublished(TabParser.TabTimeline timeline) {
    tabPanelController.showTimeline(timeline, laneLabels());
  }

  /**
   * Re-publishes the current timeline with fresh lane labels — called when
   * the tuning changes so the score updates immediately, not just at the
   * next compile.
   */
  private void refreshScoreLabels() {
    TabParser.TabTimeline timeline = playback.currentTimeline();
    if (timeline != null) {
      tabPanelController.showTimeline(timeline, laneLabels());
    }
  }

  private String[] laneLabels() {
    Tuning tuning = guitarPanelController.getTuning();
    String[] labels = new String[6];
    for (int s = 0; s < 6; s++) {
      labels[s] = tuning != null ? AppContext.noteName(tuning.freqs[s]) : "--";
    }
    return labels;
  }
}
