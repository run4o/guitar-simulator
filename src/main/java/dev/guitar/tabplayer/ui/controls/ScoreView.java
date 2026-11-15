package dev.guitar.tabplayer.ui.controls;

import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import dev.guitar.tabplayer.tab.TabParser;
import java.util.function.BiConsumer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Score/cue view: renders the compiled timeline — what the engine will actually
 * play — as six string lanes on a real-time axis, with a bar-number ruler,
 * beat grid, sweeping playback cursor, and click-to-select bar blocks.
 * <p>
 * Lane labels are a live display derived from the selected tuning; they are
 * deliberately decoupled from the ASCII text's marker letters.
 */
public class ScoreView extends Canvas {

  public static final double GUTTER = 56;   // used when labels are drawn in-canvas
  public static final double PPS = 140;     // pixels per second
  public static final double HEADER_H = 24;
  public static final double PADDING = 6;

  /**
   * The compact score strip's natural height (header + 6 x 30px lanes).
   */
  public static final double COMPACT_HEIGHT = HEADER_H + 2 * PADDING + 6 * 30;
  private static final double EDGE_L = 2;   // lane stripe inset when labels are external

  private static final Color BG = Color.web("#0A0C14");
  private static final Color LANE_A = Color.web("#151823");
  private static final Color LANE_B = Color.web("#111321");
  private static final Color EDGE = Color.web("#2A2E40");
  private static final Color GRID = Color.rgb(255, 255, 255, 0.05);
  private static final Color LABEL = Color.web("#8B8E99");
  private static final Color CYAN = Color.web("#00BCD4");
  private static final Color RED = Color.web("#E01212");
  private static final Color SILVER = Color.web("#EFEFEF");
  private static final Color INK = Color.web("#0F111A");

  private TabParser.TabTimeline timeline;
  private final String[] laneLabels = {"-", "-", "-", "-", "-", "-"};
  private final boolean externalLabels; // true: labels live in a fixed column outside the scroll pane
  private double cursorSeconds = -1;
  private int selFirst = -1;
  private int selLast = -1;
  private double minContentWidth = 900;
  private double contentSeconds = 0;
  private double pps = PPS; // effective scale: widens so fret digits never overlap
  private BiConsumer<Integer, Boolean> onBarClick; // (global bar index, extend selection)

  public ScoreView() {
    this(false);
  }

  public ScoreView(boolean externalLabels) {
    super(900, 300);
    this.externalLabels = externalLabels;
    widthProperty().addListener((o, ov, nv) -> draw());
    heightProperty().addListener((o, ov, nv) -> draw());
    setOnMouseClicked(this::onClick);
    draw();
  }

  /**
   * FXML factory: the score strip with its lane labels in a fixed column
   * outside the scroll pane (the externalLabels variant).
   */
  public static ScoreView fxLanes() {
    return new ScoreView(true);
  }

  public void setOnBarClick(BiConsumer<Integer, Boolean> handler) {
    onBarClick = handler;
  }

  public void setLaneLabels(String[] labels) {
    for (int i = 0; i < 6 && i < labels.length; i++) {
      laneLabels[i] = labels[i];
    }
    draw();
  }

  public void setTimeline(TabParser.TabTimeline newTimeline) {
    timeline = newTimeline;
    // view ends shortly after the last event — the ring-out tail is dead space
    double lastEvent = 0;
    if (newTimeline != null && !newTimeline.commands().isEmpty()) {
      lastEvent = newTimeline.commands().get(newTimeline.commands().size() - 1).timeOffset();
    }
    contentSeconds = newTimeline == null ? 0 : Math.min(newTimeline.durationSeconds(), lastEvent + 0.8);
    pps = effectivePps(newTimeline);
    updateWidth();
    draw();
  }

  /**
   * Pixels per second: the base scale, widened until consecutive notes on the
   * same string never overlap (fret digits must stay readable).
   */
  private static double effectivePps(TabParser.TabTimeline timeline) {
    if (timeline == null) {
      return PPS;
    }
    java.util.Map<Integer, java.util.List<Double>> byString = new java.util.HashMap<>();
    for (TabParser.TabEvent event : timeline.events()) {
      byString.computeIfAbsent(event.command().stringIndex(), k -> new java.util.ArrayList<>()).add(
          (double) event.command().timeOffset());
    }
    double minGap = Double.MAX_VALUE;
    for (java.util.List<Double> times : byString.values()) {
      java.util.Collections.sort(times);
      for (int i = 1; i < times.size(); i++) {
        double gap = times.get(i) - times.get(i - 1);
        if (gap > 0.001 && gap < minGap) {
          minGap = gap;
        }
      }
    }
    if (minGap == Double.MAX_VALUE) {
      return PPS;
    }
    return Math.min(600, Math.max(PPS, 26.0 / minGap));
  }

  /**
   * Current horizontal scale in px/s.
   */
  public double pixelsPerSecond() {
    return pps;
  }

  /**
   * Pixel x of the playback cursor (used by the auto-scroll logic).
   */
  public double cursorPixel() {
    return gutterX() + Math.max(0, cursorSeconds) * pps;
  }

  /**
   * The pane may be wider than the content — lanes then stretch to fill it.
   */
  public void setMinContentWidth(double viewportWidth) {
    minContentWidth = viewportWidth;
    updateWidth();
  }

  private void updateWidth() {
    double content = gutterX() + contentSeconds * pps + 30;
    setWidth(Math.max(minContentWidth, content));
  }

  public void setCursorSeconds(double seconds) {
    if (seconds != cursorSeconds) {
      cursorSeconds = seconds;
      draw();
    }
  }

  public void setSelection(int first, int last) {
    selFirst = first;
    selLast = last;
    draw();
  }

  private void onClick(MouseEvent e) {
    if (timeline == null || onBarClick == null) {
      return;
    }
    double time = (e.getX() - gutterX()) / pps;
    for (int i = 0; i < timeline.barTimes().size(); i++) {
      TabParser.BarTime bt = timeline.barTimes().get(i);
      if (time >= bt.startSeconds() && time <= bt.endSeconds()) {
        onBarClick.accept(i, e.isShiftDown());
        return;
      }
    }
  }

  /**
   * Lane-stripe origin: 0 when the label gutter is a separate fixed column.
   */
  private double gutterX() {
    return externalLabels ? 0.0 : GUTTER;
  }

  /**
   * Cell width for a note at time t on its string: runs to the next note on
   * that string (so digits never overlap), clamped to a sane range.
   */
  private static double cellWidth(java.util.List<Double> times, double t, double pps) {
    if (times == null) {
      return 22;
    }
    double next = Double.MAX_VALUE;
    for (double time : times) {
      if (time > t + 0.001 && time < next) {
        next = time;
      }
    }
    double gapSeconds = next == Double.MAX_VALUE ? 0.6 : next - t;
    return Math.max(14, Math.min(26, gapSeconds * pps - 2));
  }

  private double laneHeight() {
    // compact: lanes cap at 30px so the score never stretches vertically
    return Math.min(30, Math.max(16, (getHeight() - HEADER_H - 2 * PADDING) / 6.0));
  }

  private double laneTop(int lane) {
    return HEADER_H + PADDING + lane * laneHeight();
  }

  private void draw() {
    GraphicsContext g = getGraphicsContext2D();
    double w = getWidth();
    double h = getHeight();
    g.clearRect(0, 0, w, h);
    g.setFill(BG);
    g.fillRect(0, 0, w, h);

    double lh = laneHeight();
    double x0 = gutterX();

    // lane stripes + vertically centered labels
    g.setTextBaseline(VPos.CENTER);
    for (int lane = 0; lane < 6; lane++) {
      double top = laneTop(lane);
      g.setFill(lane % 2 == 0 ? LANE_A : LANE_B);
      g.fillRect(x0, top, w - x0 - EDGE_L, lh - 2);
      if (!externalLabels) {
        g.setFill(LABEL);
        g.setFont(Font.font("Share Tech Mono", 13));
        g.fillText(laneLabels[lane], 12, top + lh / 2.0);
      }
    }

    if (timeline == null) {
      return;
    }

    // bar separators at bar STARTS (plus one closing line), beat grid inside
    int lastBar = timeline.barTimes().size() - 1;
    for (int i = 0; i < timeline.barTimes().size(); i++) {
      TabParser.BarTime bt = timeline.barTimes().get(i);
      double startX = x0 + bt.startSeconds() * pps;
      g.setStroke(EDGE);
      g.setLineWidth(1);
      g.strokeLine(startX, HEADER_H, startX, h - PADDING);
      double beat = (bt.endSeconds() - bt.startSeconds()) / 4.0;
      g.setStroke(GRID);
      for (int k = 1; k < 4; k++) {
        double bx = x0 + (bt.startSeconds() + k * beat) * pps;
        g.strokeLine(bx, HEADER_H, bx, h - PADDING);
      }
    }
    if (lastBar >= 0) {
      double endX = x0 + timeline.barTimes().get(lastBar).endSeconds() * pps;
      g.setStroke(EDGE);
      g.strokeLine(endX, HEADER_H, endX, h - PADDING);
    }

    // selection overlay
    if (selFirst >= 0 && selLast >= 0 && selFirst < timeline.barTimes().size() &&
        selLast < timeline.barTimes().size()) {
      int f = Math.min(selFirst, selLast);
      int l = Math.max(selFirst, selLast);
      double sx0 = x0 + timeline.barTimes().get(f).startSeconds() * pps;
      double sx1 = x0 + timeline.barTimes().get(l).endSeconds() * pps;
      g.setFill(Color.rgb(0, 188, 212, 0.15));
      g.fillRect(sx0, HEADER_H, sx1 - sx0, h - HEADER_H - PADDING);
      g.setStroke(CYAN);
      g.strokeRect(sx0, HEADER_H, sx1 - sx0, h - HEADER_H - PADDING);
    }

    // events — fret digits inside the cells
    java.util.Map<Integer, java.util.List<Double>> timesByString = new java.util.HashMap<>();
    for (TabParser.TabEvent event : timeline.events()) {
      timesByString.computeIfAbsent(event.command().stringIndex(), k -> new java.util.ArrayList<>()).add(
          (double) event.command().timeOffset());
    }
    for (java.util.List<Double> times : timesByString.values()) {
      java.util.Collections.sort(times);
    }
    for (TabParser.TabEvent event : timeline.events()) {
      RenderCommand cmd = event.command();
      double x = x0 + cmd.timeOffset() * pps;
      double laneCenter = laneTop(cmd.stringIndex()) + lh / 2.0;
      double cellW = cellWidth(timesByString.get(cmd.stringIndex()), cmd.timeOffset(), pps);
      double cellH = Math.min(lh - 6, 20.0);
      switch (cmd.type()) {
        case RenderCommandType.PLUCK -> {
          g.setFill(CYAN);
          g.fillRoundRect(x, laneCenter - cellH / 2, cellW, cellH, 3, 3);
          g.setFill(INK);
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(String.valueOf(cmd.fret()), x + cellW / 2, laneCenter);
        }
        case RenderCommandType.LEGATO -> {
          g.setStroke(CYAN);
          g.setLineWidth(1.5);
          g.strokeRoundRect(x, laneCenter - cellH / 2, cellW, cellH, 3, 3);
          g.setFill(CYAN);
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(String.valueOf(cmd.fret()), x + cellW / 2, laneCenter);
        }
        case RenderCommandType.BEND -> {
          g.setFill(RED);
          if (cmd.a() >= 0) {
            g.fillPolygon(new double[]{x, x + 9, x + 4.5}, new double[]{laneCenter + 5, laneCenter + 5, laneCenter - 5},
                3);
          } else {
            g.fillPolygon(new double[]{x, x + 9, x + 4.5}, new double[]{laneCenter - 5, laneCenter - 5, laneCenter + 5},
                3);
          }
          g.setFont(Font.font("Share Tech Mono", 11));
          g.setTextBaseline(VPos.CENTER);
          int semis = Math.round(cmd.a());
          g.fillText((semis >= 0 ? "+" : "") + semis, x + 12, laneCenter);
        }
        case RenderCommandType.MUTE -> {
          g.setStroke(SILVER);
          g.setLineWidth(1.5);
          g.strokeLine(x - 4, laneCenter - 4, x + 4, laneCenter + 4);
          g.strokeLine(x - 4, laneCenter + 4, x + 4, laneCenter - 4);
        }
        default -> {
        }
      }
    }

    // bar-number ruler: one themed square at each bar start
    for (int i = 0; i < timeline.barTimes().size(); i++) {
      TabParser.BarTime bt = timeline.barTimes().get(i);
      double x = x0 + bt.startSeconds() * pps + 2;
      boolean selected = i >= Math.min(selFirst, selLast) && i <= Math.max(selFirst, selLast) && selFirst >= 0;
      g.setFill(selected ? Color.web("#7FE7F5") : CYAN);
      g.fillRoundRect(x, 4, 18, HEADER_H - 10, 3, 3);
      if (selected) {
        g.setStroke(SILVER);
        g.setLineWidth(1);
        g.strokeRoundRect(x, 4, 18, HEADER_H - 10, 3, 3);
      }
      g.setFill(INK);
      g.setFont(Font.font("Share Tech Mono", 12));
      g.setTextBaseline(VPos.CENTER);
      g.fillText(String.valueOf(i + 1), x + 9, 4 + (HEADER_H - 10) / 2.0);
    }

    // playback cursor
    if (cursorSeconds >= 0) {
      double x = x0 + cursorSeconds * pps;
      g.setStroke(CYAN);
      g.setLineWidth(1.5);
      g.strokeLine(x, HEADER_H, x, h - PADDING);
    }
  }

  /**
   * Numeric constants mirroring GuitarEngine.EVENT_* (avoids a doc dependency).
   */
  private static final class RenderCommandType {
    static final int PLUCK = 0;
    static final int LEGATO = 1;
    static final int BEND = 2;
    static final int MUTE = 3;
  }
}
