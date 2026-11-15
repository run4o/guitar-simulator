package dev.guitar.tabplayer.ui;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import dev.guitar.tabplayer.tab.TabBlocks;
import dev.guitar.tabplayer.tab.TabParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Phase 4 UI: studio-console tab player.
 *
 * Meter bridge (VU + time + tuning), CRT tab display, per-string channel
 * faders, FX knobs, tape transport. Behaviors unchanged: parse -> schedule ->
 * real-time playback with cursor highlighting, loop, offline WAV export.
 *
 * Threading: ALL engine event pushes go through a single-threaded executor
 * (the lock-free queue is SPSC — exactly one producer).
 */
public class PlayerApp extends Application {

    private static final long SR = 44100;

    private static final String SAMPLE_TAB = """
            e|------------------|------------------|
            B|------------------|------------------|
            G|------------------|------------------|
            D|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            A|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            E|--0--0--0--0--0-3-|--0--0--0--0--0-3-|
            """;

    private enum Tuning {
        STANDARD("STANDARD", new double[]{82.41, 110.00, 146.83, 196.00, 246.94, 329.63}),
        DROP_D("DROP D", new double[]{73.42, 110.00, 146.83, 196.00, 246.94, 329.63}),
        DROP_C("DROP C", new double[]{65.41, 98.00, 130.81, 174.61, 220.00, 293.66}),
        HALF_STEP("HALF-STEP DOWN", scale(0.5)),
        OPEN_G("OPEN G", new double[]{73.42, 98.00, 146.83, 196.00, 246.94, 293.66}),
        DADGAD("DADGAD", new double[]{73.42, 110.00, 146.83, 196.00, 220.00, 293.66});

        final String label;
        final double[] freqs;

        Tuning(String label, double[] freqs) {
            this.label = label;
            this.freqs = freqs;
        }

        @Override public String toString() {
            return label;
        }

        private static double[] scale(double semitones) {
            double factor = Math.pow(2.0, -semitones / 12.0);
            double[] out = new double[6];
            for (int i = 0; i < 6; i++) {
                out[i] = STANDARD.freqs[i] * factor;
            }
            return out;
        }
    }

    /** -Dguitar.autoplay=true: play the demo tab at launch (debug/CI verification). */
    private static final boolean AUTOPLAY = "true".equalsIgnoreCase(System.getProperty("guitar.autoplay"));

    private GuitarEngine engine;
    private boolean engineReady;
    private ExecutorService commands; // the single producer for the engine queue

    private TextArea tabArea;
    private TextArea statusArea;
    private TextField titleField;
    private ScoreView scoreView;
    private ScoreLabels scoreLabels;
    private javafx.scene.control.ScrollPane scoreScroll;
    private TabParser.TabTimeline currentTimeline;
    private boolean sourceMode;
    private TabBlocks.BarBlock blockClipboard;
    private int selFirst = -1;
    private int selLast = -1;
    private Spinner<Integer> bpmSpinner;
    private ComboBox<Tuning> tuningBox;
    private CheckBox loopBox;
    private Knob driveKnob;
    private Knob toneKnob;
    private Knob reverbKnob;
    private Fader masterFader;
    private Fader[] stringFaders;
    private Label[] stringLabels;
    private VuMeter vuMeter;
    private Label timeLcd;
    private Pane playLed;

    private volatile boolean playing;
    private volatile boolean deviceRunning;
    private boolean suppressEditStop; // ignore the initial programmatic setText
    private volatile long playStartFrame;
    private long lastMeterSecond;

    @Override
    public void start(Stage stage) {
        try {
            engine = new GuitarEngine(GuitarEngine.defaultLibraryPath());
            engineReady = engine.init((int) SR, 256);
        } catch (RuntimeException e) {
            engineReady = false;
            e.printStackTrace();
        }
        commands = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-commands");
            t.setDaemon(true);
            return t;
        });
        if (engineReady) {
            // Open the audio device once at launch: removes Play latency and
            // starts the sample clock the playback cursor relies on.
            deviceRunning = engine.start();
        }

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #0F111A;");

        /* ---- header + meter bridge ---- */

        Pane powerLed = led("led-cyan");
        HBox header = new HBox(10,
                new Label("GUITAR TAB PLAYER"),
                new Label("GT-6 SYNTH"),
                region(),
                powerLed);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 4, 8, 4));
        header.getChildren().get(0).getStyleClass().add("plate-title");
        header.getChildren().get(1).getStyleClass().add("model-tag");
        HBox.setHgrow(header.getChildren().get(2), Priority.ALWAYS);

        vuMeter = new VuMeter(14, 190, true); // vertical, next to the master fader
        timeLcd = new Label("00:00");
        timeLcd.getStyleClass().add("lcd");
        Label bpmTag = new Label("BPM");
        bpmTag.getStyleClass().add("model-tag");

        bpmSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(40, 300, 120));
        bpmSpinner.setPrefWidth(90);
        bpmSpinner.setEditable(true);

        tuningBox = new ComboBox<>();
        tuningBox.getItems().addAll(Tuning.values());
        tuningBox.getSelectionModel().selectFirst();
        tuningBox.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                updateFaderLabels(newV);
            }
            applyTuning(newV);
        });

        HBox meterBridge = new HBox(14, region(), timeLcd, bpmTag, bpmSpinner, tuningBox);
        meterBridge.getStyleClass().add("panel");
        meterBridge.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(meterBridge.getChildren().get(0), Priority.ALWAYS);
        HBox.setMargin(timeLcd, new Insets(0, 4, 0, 0));

        VBox top = new VBox(8, header, meterBridge);
        root.setTop(top);

        /* ---- title bar: free song-title field + block operations ---- */

        titleField = new TextField("Demo riff: power chords + lead");
        titleField.getStyleClass().add("title-field");
        HBox.setHgrow(titleField, Priority.ALWAYS);

        Button copyBtn = opButton("COPY", this::blockCopy);
        Button cutBtn = opButton("CUT", this::blockCut);
        Button pasteBtn = opButton("PASTE", this::blockPaste);
        Button dupBtn = opButton("DUP", this::blockDuplicate);
        Button delBtn = opButton("DEL", this::blockDelete);
        Button clearBtn = opButton("CLEAR", this::blockClear);
        Button editToggle = opButton("EDIT SOURCE", this::toggleSourceView);

        HBox titleBar = new HBox(10, new Label("TITLE"), titleField,
                copyBtn, cutBtn, pasteBtn, dupBtn, delBtn, clearBtn, editToggle);
        titleBar.getStyleClass().add("panel");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().get(0).getStyleClass().add("model-tag");

        /* ---- CRT tab display ---- */

        suppressEditStop = true;
        tabArea = new TextArea(SAMPLE_TAB);
        suppressEditStop = false;
        tabArea.getStyleClass().add("crt");
        tabArea.setWrapText(true);
        VBox.setVgrow(tabArea, Priority.ALWAYS);
        // editing stops playback (positions would drift)
        tabArea.textProperty().addListener((obs, oldV, newV) -> {
            if (playing && !suppressEditStop) {
                stopPlayback();
                status("Edited — playback stopped");
            }
        });

        scoreView = new ScoreView(true);
        scoreView.setOnBarClick(this::onScoreBarClick);
        scoreLabels = new ScoreLabels();
        scoreScroll = new javafx.scene.control.ScrollPane(scoreView);
        scoreScroll.getStyleClass().add("score-scroll");
        scoreScroll.setFitToHeight(true);
        scoreScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scoreScroll.viewportBoundsProperty().addListener((o, ov, nv) -> {
            scoreView.setMinContentWidth(nv.getWidth() - 4);
            scoreView.setHeight(nv.getHeight() - 4);
            scoreLabels.setHeight(nv.getHeight() - 4);
        });
        VBox.setVgrow(scoreScroll, Priority.ALWAYS);

        // Default view is the score; EDIT SOURCE swaps the whole center to text.
        tabArea.setManaged(false);
        tabArea.setVisible(false);

        HBox scoreArea = new HBox(scoreLabels, scoreScroll);
        HBox.setHgrow(scoreScroll, Priority.ALWAYS);
        VBox.setVgrow(scoreArea, Priority.ALWAYS);

        VBox center = new VBox(8, titleBar, scoreArea, tabArea);
        VBox.setVgrow(scoreArea, Priority.ALWAYS);
        VBox.setVgrow(tabArea, Priority.ALWAYS);
        root.setCenter(center);

        /* ---- console row: transport | channels | fx | master ---- */

        playLed = led(null);
        Button playBtn = new Button("PLAY");
        playBtn.getStyleClass().add("play-btn");
        playBtn.setOnAction(e -> startPlayback());
        Button stopBtn = new Button("STOP");
        stopBtn.getStyleClass().add("btn-steel");
        stopBtn.setOnAction(e -> stopPlayback());
        loopBox = new CheckBox("LOOP");
        loopBox.setSelected(true);
        loopBox.getStyleClass().add("check-box");

        VBox transport = new VBox(6, playLed, playBtn, stopBtn, loopBox);
        transport.setAlignment(Pos.TOP_CENTER);

        Label divider1 = new Label();
        divider1.getStyleClass().add("console-divider");

        stringLabels = new Label[6];
        stringFaders = new Fader[6];
        HBox channels = new HBox(8);
        channels.setAlignment(Pos.TOP_CENTER);
        for (int s = 0; s < 6; s++) {
            final int idx = s;
            stringFaders[s] = new Fader(0, 2, 1.0);
            stringFaders[s].valueProperty().addListener((o, ov, nv) ->
                    commands.execute(() -> engine.setStringGain(idx, (float) nv.doubleValue())));
            Label name = new Label("--");
            name.getStyleClass().add("knob-label");
            stringLabels[s] = name;
            VBox ch = new VBox(4, stringFaders[s], name);
            ch.setAlignment(Pos.TOP_CENTER);
            channels.getChildren().add(ch);
        }
        updateFaderLabels(tuningBox.getValue());

        // Debug flag: preselect a tuning (-Dguitar.tuning=DROP_C etc.) — after the
        // console row exists so the listener finds the labels built.
        String tuningProp = System.getProperty("guitar.tuning");
        if (tuningProp != null) {
            for (Tuning t : Tuning.values()) {
                if (t.name().equalsIgnoreCase(tuningProp) || t.label.equalsIgnoreCase(tuningProp)) {
                    tuningBox.getSelectionModel().select(t);
                    break;
                }
            }
        }

        Label divider2 = new Label();
        divider2.getStyleClass().add("console-divider");

        driveKnob = new Knob(0, 1, 0, 58);
        toneKnob = new Knob(0, 1, 1, 58);
        reverbKnob = new Knob(0, 1, 0, 58);
        for (Knob k : new Knob[]{driveKnob, toneKnob, reverbKnob}) {
            k.valueProperty().addListener((o, ov, nv) -> applyFx());
        }
        HBox knobRow = new HBox(14);
        knobRow.setAlignment(Pos.TOP_CENTER);
        String[] fxNames = {"DRIVE", "TONE", "REVERB"};
        Knob[] knobs = {driveKnob, toneKnob, reverbKnob};
        for (int i = 0; i < 3; i++) {
            Label name = new Label(fxNames[i]);
            name.getStyleClass().add("knob-label");
            VBox fx = new VBox(2, knobs[i], name);
            fx.setAlignment(Pos.TOP_CENTER);
            fx.setPadding(new Insets(22, 0, 0, 0)); // align knob row against the fader strip
            knobRow.getChildren().add(fx);
        }

        Label divider3 = new Label();
        divider3.getStyleClass().add("console-divider");

        masterFader = new Fader(0, 2, 1.0);
        masterFader.setPrefSize(44, 190);
        masterFader.valueProperty().addListener((o, ov, nv) -> applyFx());
        Label masterLabel = new Label("MASTER");
        masterLabel.getStyleClass().add("knob-label");
        VBox master = new VBox(4, masterFader, masterLabel);
        master.setAlignment(Pos.TOP_CENTER);

        Label outLabel = new Label("OUT");
        outLabel.getStyleClass().add("knob-label");
        VBox outMeter = new VBox(4, vuMeter, outLabel);
        outMeter.setAlignment(Pos.TOP_CENTER);

        HBox console = new HBox(16, transport, divider1, channels, divider2, knobRow, divider3, master, outMeter);
        console.getStyleClass().add("panel");
        console.setAlignment(Pos.CENTER);

        statusArea = new TextArea("");
        statusArea.getStyleClass().add("status-lcd");
        statusArea.setEditable(false);
        statusArea.setPrefRowCount(2);

        VBox bottom = new VBox(8, console, statusArea);
        root.setBottom(bottom);

        stage.setTitle("Guitar Tab Player");
        stage.setScene(new Scene(root, 860, 720));
        stage.getScene().getStylesheets().add(getClass().getResource("/ui/theme.css").toExternalForm());
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        startMeterPoll();

        status(engineReady
                ? (deviceRunning
                    ? "READY — EDIT THE TAB, THEN PLAY"
                    : "ENGINE READY BUT NO AUDIO DEVICE — PLAYBACK UNAVAILABLE")
                : "ERROR: ENGINE FAILED TO INITIALIZE — SEE CONSOLE");
        if (engineReady) {
            commands.execute(this::applyTuningNow);
            compileCurrent(); // render the initial score view + lane labels
            if (AUTOPLAY) {
                Platform.runLater(this::startPlayback);
            }
            String shotPath = System.getProperty("guitar.screenshot");
            if (shotPath != null) {
                captureScreenshotLater(stage, shotPath);
            }
        }
    }

    /* ------------------------------------------------------------------
     * Meter poll + screenshot
     * ------------------------------------------------------------------ */

    private void startMeterPoll() {
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(50), e -> {
            if (!engineReady) {
                return;
            }
            vuMeter.setLevel(engine.getLevel());
            double cursorSec = playing
                    ? Math.max(0, (engine.sampleClock() - playStartFrame) / (double) SR) : -1;
            scoreView.setCursorSeconds(cursorSec);
            if (playing && cursorSec >= 0) {
                // keep the cursor in view while it sweeps (sticks to ~30% from the left)
                double cursorX = ScoreView.GUTTER + cursorSec * ScoreView.PPS;
                double viewport = scoreScroll.getViewportBounds().getWidth();
                double max = scoreScroll.getHmax();
                double target = Math.max(0, Math.min(max, cursorX - viewport * 0.3));
                scoreScroll.setHvalue(target);
            }
            if (playing) {
                long frames = engine.sampleClock() - playStartFrame;
                if (frames < 0) {
                    frames = 0;
                }
                long total = frames / SR;
                timeLcd.setText(String.format("%02d:%02d", total / 60, total % 60));
            } else {
                timeLcd.setText("00:00");
            }
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
    }

    private void captureScreenshotLater(Stage stage, String path) {
        Timeline shot = new Timeline(new KeyFrame(Duration.seconds(2.0), e -> {
            // Snapshot the scene content directly — immune to overlapping windows.
            var image = stage.getScene().snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            int[] pixels = new int[w * h];
            image.getPixelReader().getPixels(0, 0, w, h,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
            new Thread(() -> {
                try {
                    var awt = new java.awt.image.BufferedImage(w, h,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
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
     * Playback
     * ------------------------------------------------------------------ */

    private void startPlayback() {
        if (!engineReady || playing) {
            return;
        }
        if (!deviceRunning && !engine.start()) {
            status("NO AUDIO DEVICE AVAILABLE — CANNOT PLAY");
            return;
        }
        deviceRunning = true;
        TabParser.TabTimeline timeline = compileCurrent();
        if (timeline.commands().isEmpty()) {
            status("NOTHING TO PLAY. " + timeline.warnings().size() + " WARNING(S).");
            timeline.warnings().forEach(w -> status("  " + w));
            return;
        }
        status("PLAYING " + timeline.commands().size() + " EVENTS, "
                + String.format("%.2f", timeline.durationSeconds()) + "S @ "
                + bpmSpinner.getValue() + " BPM"
                + (timeline.warnings().isEmpty() ? "" : " (" + timeline.warnings().size() + " WARNINGS)"));
        if (AUTOPLAY) {
            System.out.println("[autoplay] scheduled " + timeline.commands().size()
                    + " events, duration " + timeline.durationSeconds() + "s");
        }

        playing = true;
        playLed.getStyleClass().setAll("led", "led-red");
        Thread t = new Thread(() -> runPlayback(timeline), "sequencer");
        t.setDaemon(true);
        t.start();
    }

    private void runPlayback(TabParser.TabTimeline timeline) {
        long start = engine.sampleClock() + SR / 3; // 330 ms lookahead
        playStartFrame = start;
        long durationFrames = Math.max(1, (long) (timeline.durationSeconds() * SR));
        long iterationEnd = start + durationFrames;
        List<TabParser.TabEvent> events = timeline.events();
        scheduleIteration(events, start);

        int cursorIndex = -2;
        while (playing) {
            long now = engine.sampleClock();
            long elapsed = now - start;
            if (elapsed >= 0) {
                long pos = loopBox.isSelected() ? elapsed % durationFrames : elapsed;
                int idx = eventIndexAt(events, pos);
                if (idx != cursorIndex) {
                    cursorIndex = idx;
                    if (idx >= 0) {
                        TabParser.TabEvent ev = events.get(idx);
                        Platform.runLater(() -> highlight(ev.charPos()));
                    }
                }
            }
            if (loopBox.isSelected() && now >= iterationEnd - 600L * SR / 1000) {
                final long at = iterationEnd;
                scheduleIteration(events, at);
                iterationEnd += durationFrames;
            }
            if (!loopBox.isSelected() && now >= iterationEnd) {
                Platform.runLater(this::stopPlayback);
                break;
            }
            if (AUTOPLAY && now / SR > lastMeterSecond) {
                lastMeterSecond = now / SR;
                System.out.println("[autoplay] clock=" + now + " frames");
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (AUTOPLAY) {
            System.out.println("[autoplay] sequencer done, final clock=" + engine.sampleClock());
        }
    }

    /** All pushes go through the single command executor (SPSC producer). */
    private void scheduleIteration(List<TabParser.TabEvent> events, long startFrame) {
        commands.execute(() -> {
            for (TabParser.TabEvent event : events) {
                RenderCommand cmd = event.command();
                long at = startFrame + (long) (cmd.timeOffset() * SR);
                if (!engine.pushEvent(at, cmd.type(), cmd.stringIndex(), cmd.fret(), cmd.a(), cmd.b())) {
                    Platform.runLater(() -> status("EVENT QUEUE FULL — SOME EVENTS DROPPED"));
                    break; // queue full
                }
            }
        });
    }

    private static int eventIndexAt(List<TabParser.TabEvent> events, long framePos) {
        int result = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).command().timeOffset() * SR <= framePos) {
                result = i;
            } else {
                break;
            }
        }
        return result;
    }

    private void stopPlayback() {
        if (!playing) {
            return;
        }
        playing = false;
        playStartFrame = 0;
        playLed.getStyleClass().setAll("led");
        commands.execute(() -> {
            for (int s = 0; s < 6; s++) {
                engine.mute(s);
            }
        });
        tabArea.deselect();
        status("STOPPED.");
    }

    private void highlight(int charPos) {
        int len = tabArea.getText().length();
        if (charPos < len) {
            tabArea.selectRange(charPos, Math.min(charPos + 2, len));
        }
    }

    /* ------------------------------------------------------------------
     * Actions
     * ------------------------------------------------------------------ */

    private TabParser.TabTimeline compileCurrent() {
        currentTimeline = TabParser.compile(tabArea.getText(), bpmSpinner.getValue());
        String[] labels = new String[6];
        Tuning tuning = tuningBox.getValue();
        for (int s = 0; s < 6; s++) {
            labels[s] = noteName(tuning.freqs[s]);
        }
        scoreView.setLaneLabels(labels);
        scoreLabels.setLabels(labels);
        scoreView.setTimeline(currentTimeline);
        return currentTimeline;
    }

    /* ------------------------------------------------------------------
     * Bar-block operations
     * ------------------------------------------------------------------ */

    private void onScoreBarClick(int barIndex, boolean extend) {
        if (extend && selFirst >= 0) {
            selLast = barIndex;
        } else {
            selFirst = selLast = barIndex;
        }
        scoreView.setSelection(selFirst, selLast);
    }

    /** Swaps the center area between the score view and the ASCII source editor. */
    private void toggleSourceView() {
        sourceMode = !sourceMode;
        scoreScroll.setManaged(!sourceMode);
        scoreScroll.setVisible(!sourceMode);
        tabArea.setManaged(sourceMode);
        tabArea.setVisible(sourceMode);
        if (sourceMode) {
            tabArea.requestFocus();
        }
        status(sourceMode ? "SOURCE EDITOR — EDITS RECOMPILE THE SCORE" : "SCORE VIEW");
    }

    /** The bar (or bar range) to operate on: score selection, else the caret's bar. */
    private TabParser.BarSpan activeSpan() {
        String text = tabArea.getText();
        List<TabParser.BarSpan> spans = TabParser.barSpans(text);
        if (spans.isEmpty()) {
            status("NO BARS TO OPERATE ON");
            return null;
        }
        if (selFirst < 0) {
            TabParser.BarSpan caretBar = TabBlocks.barAtChar(text, tabArea.getCaretPosition());
            return caretBar != null ? caretBar : spans.get(0);
        }
        int first = Math.min(selFirst, selLast);
        int last = Math.max(selFirst, selLast);
        if (spans.get(first).system().index() != spans.get(Math.min(last, spans.size() - 1)).system().index()) {
            status("SELECT BARS WITHIN ONE SYSTEM");
            return null;
        }
        last = Math.min(last, spans.size() - 1);
        return new TabParser.BarSpan(spans.get(first).system(), first,
                spans.get(first).startCol(), spans.get(last).endColEx());
    }

    private void setTabText(String newText) {
        suppressEditStop = true;
        tabArea.setText(newText);
        suppressEditStop = false;
        selFirst = selLast = -1;
        scoreView.setSelection(-1, -1);
        compileCurrent();
    }

    private void blockCopy() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        blockClipboard = TabBlocks.copyBar(tabArea.getText(), span);
        var selection = new java.awt.datatransfer.StringSelection(TabBlocks.blockToText(blockClipboard));
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        status("BAR COPIED");
    }

    private void blockCut() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        blockClipboard = TabBlocks.copyBar(tabArea.getText(), span);
        var selection = new java.awt.datatransfer.StringSelection(TabBlocks.blockToText(blockClipboard));
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        setTabText(TabBlocks.deleteBar(tabArea.getText(), span));
        status("BAR CUT");
    }

    private void blockPaste() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        TabBlocks.BarBlock block = blockClipboard;
        if (block == null) {
            try {
                String text = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (text != null) {
                    block = TabBlocks.blockFromText(text);
                }
            } catch (Exception ignored) {
                // fall through to the empty check
            }
        }
        if (block == null || block.lines().isEmpty()) {
            status("CLIPBOARD HAS NO TAB BLOCK");
            return;
        }
        setTabText(TabBlocks.pasteBar(tabArea.getText(), span, block, true));
        status("BAR PASTED");
    }

    private void blockDuplicate() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        setTabText(TabBlocks.duplicateBar(tabArea.getText(), span));
        status("BAR DUPLICATED");
    }

    private void blockDelete() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        setTabText(TabBlocks.deleteBar(tabArea.getText(), span));
        status("BAR DELETED");
    }

    private void blockClear() {
        TabParser.BarSpan span = activeSpan();
        if (span == null) {
            return;
        }
        setTabText(TabBlocks.clearBar(tabArea.getText(), span));
        status("BAR CLEARED");
    }

    private void applyTuning(Tuning tuning) {
        commands.execute(this::applyTuningNow);
    }

    private void applyTuningNow() {
        if (!engineReady) {
            return;
        }
        Tuning tuning = tuningBox.getValue();
        for (int s = 0; s < 6; s++) {
            engine.setStringTuning(s, (float) tuning.freqs[s]);
        }
    }

    private void applyFx() {
        if (!engineReady) {
            return;
        }
        engine.setFx((float) driveKnob.getValue(), (float) toneKnob.getValue(),
                (float) reverbKnob.getValue(), (float) masterFader.getValue());
    }

    private void loadFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open ASCII tab");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tab files", "*.txt", "*.tab"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        var file = chooser.showOpenDialog(tabArea.getScene() != null ? tabArea.getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        try {
            stopPlayback();
            suppressEditStop = true;
            tabArea.setText(Files.readString(file.toPath()));
            suppressEditStop = false;
            status("LOADED " + file.getName().toUpperCase());
        } catch (IOException e) {
            status("FAILED TO LOAD: " + e.getMessage().toUpperCase());
        }
    }

    private void exportWav() {
        if (!engineReady) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export WAV");
        chooser.setInitialFileName("render.wav");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav"));
        var file = chooser.showSaveDialog(tabArea.getScene() != null ? tabArea.getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        TabParser.TabTimeline timeline = compileCurrent();
        if (timeline.commands().isEmpty()) {
            status("NOTHING TO EXPORT.");
            return;
        }
        status("RENDERING " + timeline.commands().size() + " EVENTS OFFLINE...");
        Thread t = new Thread(() -> {
            boolean ok = engine.renderSequenceToWav(file.toPath(), (float) timeline.durationSeconds(),
                    timeline.commands());
            Platform.runLater(() -> status(ok
                    ? "EXPORTED " + timeline.commands().size() + " EVENTS TO " + file.getAbsolutePath()
                    : "EXPORT FAILED"));
        }, "offline-render");
        t.setDaemon(true);
        t.start();
    }

    private void shutdown() {
        playing = false;
        commands.shutdownNow();
        if (engineReady) {
            engine.shutdown();
        }
        engine.close();
    }

    /* ------------------------------------------------------------------
     * UI helpers
     * ------------------------------------------------------------------ */

    /** Fader captions are derived from the tuning's open-string frequencies. */
    private void updateFaderLabels(Tuning tuning) {
        if (stringLabels == null || stringFaders == null) {
            return; // console row not built yet
        }
        for (int s = 0; s < 6; s++) {
            double freq = tuning.freqs[s];
            stringLabels[s].setText(noteName(freq));
            stringLabels[s].setTooltip(new Tooltip(String.format("%s  ·  %.2f Hz", noteName(freq), freq)));
        }
    }

    /** Scientific pitch notation from a frequency, e.g. 82.41 Hz -> "E2". */
    static String noteName(double freq) {
        if (freq <= 0) {
            return "--";
        }
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int midi = (int) Math.round(69.0 + 12.0 * Math.log(freq / 440.0) / Math.log(2.0));
        String name = names[((midi % 12) + 12) % 12];
        int octave = midi / 12 - 1;
        return name + octave;
    }

    private static Button opButton(String label, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("btn-steel");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Pane led(String onClass) {
        Pane led = new Pane();
        led.getStyleClass().add("led");
        if (onClass != null) {
            led.getStyleClass().add(onClass);
        }
        return led;
    }

    private static Region region() {
        Region r = new Region();
        r.setMinWidth(0);
        return r;
    }

    private void status(String line) {
        Platform.runLater(() -> {
            if (statusArea.getLength() > 2000) {
                statusArea.clear();
            }
            statusArea.appendText(line + "\n");
        });
    }

    public static void main(String[] args) {
        Application.launch(PlayerApp.class, args);
    }
}
