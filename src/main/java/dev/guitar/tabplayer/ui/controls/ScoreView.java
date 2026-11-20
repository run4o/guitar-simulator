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
 * Score/cue view, multi-row edition: bars wrap into stacked rows (a sheet of
 * tab lines), vertically scrolled. The canvas is VIEWPORT-sized and only the
 * rows intersecting the viewport are drawn (culling), so even hour-long songs
 * stay inside the GPU texture limits.
 */
public class ScoreView extends Canvas {

  private static final Color BG = Color.web("#0A0C14");
  private static final Color LANE_A = Color.web("#151823");
  private static final Color LANE_B = Color.web("#111321");
  private static final Color EDGE = Color.web("#2A2E40");
  private static final Color GRID = Color.rgb(255, 255, 255, 0.05);
  private static final Color LABEL = Color.web("#8B8E99");
  private static final Color CYAN = Color.web("#00BCD4");
  private static final Color SILVER = Color.web("#EFEFEF");
  private static final Color INK = Color.web("#0F111A");

  private static final int HEADER_H = 24;
  private static final int PADDING = 6;
  private static final double EDGE_L = 2;

  private TabParser.TabTimeline timeline;
  private final String[] laneLabels = {"-", "-", "-", "-", "-", "-"};
  private double cursorSeconds = -1;
  private int selFirst = -1;
  private int selLast = -1;
  private ScoreLayout layout = ScoreLayout.compute(0, 1, 900, 300, 0);
  private BiConsumer<Integer, Boolean> onBarClick; // (global bar index, extend selection)

  public ScoreView() {
    setOnMouseClicked(this::onClick);
    draw();
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
    refreshLayout();
    draw();
  }

  /** Viewport-driven canvas size (the canvas is never larger than this). */
  public void setCanvasSize(double width, double height) {
    setWidth(Math.max(100, width));
    setHeight(Math.max(100, height));
    refreshLayout();
    draw();
  }

  public void setScrollY(double scrollY) {
    layout = ScoreLayout.compute(layout.barCount, layout.barSeconds,
        layout.canvasWidth, layout.canvasHeight, scrollY);
    draw();
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

  /** The row containing the playback cursor (for page-flip scrolling). */
  public int rowForCursor() {
    return cursorSeconds < 0 ? -1 : layout.rowForTime(cursorSeconds);
  }

  /** The active geometry (package-visible for tests). */
  ScoreLayout layout() {
    return layout;
  }

  /** Virtual content height of the whole song (drives the scroll range). */
  public double totalHeight() {
    return layout.totalHeight();
  }  /** Scroll offset that centers the cursor's row. */
  public double scrollYForCursor() {
    return cursorSeconds < 0 ? 0 : layout.scrollYForRow(layout.rowForTime(cursorSeconds));
  }

  private void refreshLayout() {
    int bars = timeline == null ? 0 : timeline.barTimes().size();
    // the TRUE bar length from the compiled bars — never derive it from the
    // display length (which includes the ring-out tail and would drift the
    // event -> bar mapping along the song)
    double barSeconds = 1.0;
    if (timeline != null && !timeline.barTimes().isEmpty()) {
      TabParser.BarTime bt = timeline.barTimes().get(0);
      barSeconds = bt.endSeconds() - bt.startSeconds();
    }
    layout = ScoreLayout.compute(bars, barSeconds, getWidth(), getHeight(), layout.scrollY);
  }

  private void onClick(MouseEvent e) {
    if (timeline == null || onBarClick == null) {
      return;
    }
    int bar = layout.barAt(e.getX(), e.getY());
    if (bar >= 0) {
      onBarClick.accept(bar, e.isShiftDown());
    }
  }

  /** Lane height within a compact row. */
  private double laneHeight() {
    return Math.max(10, (ScoreLayout.ROW_HEIGHT - HEADER_H - 2 * PADDING) / 6.0);
  }

  private void draw() {
    GraphicsContext g = getGraphicsContext2D();
    double w = getWidth();
    double h = getHeight();
    g.clearRect(0, 0, w, h);
    g.setFill(BG);
    g.fillRect(0, 0, w, h);

    if (timeline != null && !timeline.barTimes().isEmpty()) {
      // next-note times per string (cell widths) — built once per draw
      java.util.Map<Integer, java.util.List<Double>> timesByString = new java.util.HashMap<>();
      for (TabParser.TabEvent event : timeline.events()) {
        timesByString.computeIfAbsent(event.command().stringIndex(), k -> new java.util.ArrayList<>())
            .add((double) event.command().timeOffset());
      }
      for (java.util.List<Double> times : timesByString.values()) {
        java.util.Collections.sort(times);
      }
      int first = layout.firstVisibleRow();
      int last = layout.lastVisibleRowEx();
      int cursorRow = cursorSeconds >= 0 ? layout.rowForTime(cursorSeconds) : -1;
      for (int row = first; row < last; row++) {
        drawRow(g, row, w, timesByString, cursorRow == row);
      }
    }
  }

  private void drawRow(GraphicsContext g, int row, double w,
      java.util.Map<Integer, java.util.List<Double>> timesByString, boolean hasCursor) {
    double yTop = layout.rowTop(row) - layout.scrollY;
    double lh = laneHeight();
    double x0 = ScoreLayout.GUTTER;

    // lane stripes + gutter labels
    g.setTextBaseline(VPos.CENTER);
    for (int lane = 0; lane < 6; lane++) {
      double top = yTop + HEADER_H + PADDING + lane * lh;
      g.setFill(lane % 2 == 0 ? LANE_A : LANE_B);
      g.fillRect(0, top, w - EDGE_L, lh - 2);
      g.setFill(LABEL);
      g.setFont(Font.font("Share Tech Mono", 13));
      g.fillText(laneLabels[lane], 12, top + lh / 2.0);
    }

    int firstBar = row * layout.barsPerRow;
    int lastBar = Math.min(layout.barCount - 1, firstBar + layout.barsPerRow - 1);
    if (firstBar > lastBar) {
      return;
    }
    double rowBottom = yTop + ScoreLayout.ROW_HEIGHT - PADDING;

    // bar separators + beat grid
    for (int bar = firstBar; bar <= lastBar; bar++) {
      double startX = layout.xForCol(layout.colForBar(bar));
      double barPps = layout.barPx / barSecondsPx(bar); // px per second in this bar
      g.setStroke(EDGE);
      g.setLineWidth(1);
      g.strokeLine(startX, yTop + HEADER_H, startX, rowBottom);
      TabParser.BarTime bt = timeline.barTimes().get(bar);
      double beat = (bt.endSeconds() - bt.startSeconds()) / 4.0;
      g.setStroke(GRID);
      for (int k = 1; k < 4; k++) {
        double bx = startX + k * beat * barPps;
        g.strokeLine(bx, yTop + HEADER_H, bx, rowBottom);
      }
    }
    double closeX = layout.xForCol(layout.colForBar(lastBar)) + layout.barPx;
    g.setStroke(EDGE);
    g.strokeLine(closeX, yTop + HEADER_H, closeX, rowBottom);

    // selection overlay (may span several rows: clip to this row's bars)
    if (selFirst >= 0 && selLast >= 0) {
      int f = Math.min(selFirst, selLast);
      int l = Math.max(selFirst, selLast);
      f = Math.max(f, firstBar);
      l = Math.min(l, lastBar);
      if (f <= l) {
        double sx0 = layout.xForCol(layout.colForBar(f));
        double sx1 = layout.xForCol(layout.colForBar(l)) + layout.barPx;
        g.setFill(Color.rgb(0, 188, 212, 0.15));
        g.fillRect(sx0, yTop + HEADER_H, sx1 - sx0, rowBottom - yTop - HEADER_H);
        g.setStroke(CYAN);
        g.strokeRect(sx0, yTop + HEADER_H, sx1 - sx0, rowBottom - yTop - HEADER_H);
      }
    }

    // events — fret digits inside the cells (only this row's bars)
    for (TabParser.TabEvent event : timeline.events()) {
      RenderCommand cmd = event.command();
      int bar = eventBar(cmd.timeOffset());
      if (bar < firstBar || bar > lastBar) {
        continue;
      }
      TabParser.BarTime bt = timeline.barTimes().get(bar);
      double barPps = layout.barPx / barSecondsPx(bar); // px per second in this bar
      double x = layout.xForCol(layout.colForBar(bar))
          + (cmd.timeOffset() - bt.startSeconds()) * barPps;
      if (x >= w - EDGE_L) {
        continue;
      }
      double laneCenter = yTop + HEADER_H + PADDING + cmd.stringIndex() * lh + lh / 2.0;
      double cellW = cellWidth(cmd, bt, barPps, timesByString);
      double cellH = Math.min(lh - 4, 20.0);
      double onsetW = Math.min(cellW, 24.0); // the fret digit stays at the onset
      switch (cmd.type()) {
        case RenderCommandType.PLUCK -> {
          g.setFill(CYAN);
          g.fillRoundRect(x, laneCenter - cellH / 2, cellW, cellH, 3, 3);
          g.setFill(INK);
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(String.valueOf(cmd.fret()), x + onsetW / 2, laneCenter);
        }
        case RenderCommandType.LEGATO -> {
          g.setStroke(CYAN);
          g.setLineWidth(1.5);
          g.strokeRoundRect(x, laneCenter - cellH / 2, cellW, cellH, 3, 3);
          g.setFill(CYAN);
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(String.valueOf(cmd.fret()), x + onsetW / 2, laneCenter);
        }
        case RenderCommandType.BEND -> {
          g.setFill(Color.web("#E01212"));
          if (cmd.a() >= 0) {
            g.fillPolygon(new double[]{x, x + 9, x + 4.5},
                new double[]{laneCenter + 5, laneCenter + 5, laneCenter - 5}, 3);
          } else {
            g.fillPolygon(new double[]{x, x + 9, x + 4.5},
                new double[]{laneCenter - 5, laneCenter - 5, laneCenter + 5}, 3);
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

    // bar-number ruler: one themed square per bar start
    for (int bar = firstBar; bar <= lastBar; bar++) {
      double x = layout.xForCol(layout.colForBar(bar)) + 2;
      boolean selected = selFirst >= 0 && bar >= Math.min(selFirst, selLast)
          && bar <= Math.max(selFirst, selLast);
      g.setFill(selected ? Color.web("#7FE7F5") : CYAN);
      g.fillRoundRect(x, yTop + 4, 22, HEADER_H - 10, 3, 3);
      if (selected) {
        g.setStroke(SILVER);
        g.setLineWidth(1);
        g.strokeRoundRect(x, yTop + 4, 22, HEADER_H - 10, 3, 3);
      }
      g.setFill(INK);
      g.setFont(Font.font("Share Tech Mono", 12));
      g.setTextBaseline(VPos.CENTER);
      g.fillText(String.valueOf(bar + 1), x + 11, yTop + 4 + (HEADER_H - 10) / 2.0);

      // time signature, drawn at bar 1 and wherever the meter changes
      int[] sig = meterAt(bar);
      int[] prev = bar > 0 ? meterAt(bar - 1) : new int[]{-1, -1};
      if (sig[0] != prev[0] || sig[1] != prev[1]) {
        g.setFill(SILVER);
        g.setFont(Font.font("Share Tech Mono", 11));
        g.setTextBaseline(VPos.CENTER);
        g.fillText(String.valueOf(sig[0]), x + 28, yTop + 10);
        g.fillText(String.valueOf(sig[1]), x + 28, yTop + 21);
      }
    }

    // playback cursor
    if (hasCursor && cursorSeconds >= 0) {
      double x = layout.xForTime(cursorSeconds);
      g.setStroke(CYAN);
      g.setLineWidth(1.5);
      g.strokeLine(x, yTop + HEADER_H, x, rowBottom);
    }
  }

  /** Time signature of the given bar (defaults 4/4). */
  private int[] meterAt(int bar) {
    if (timeline == null || bar < 0 || bar >= timeline.timeSignatures().size()) {
      return new int[]{4, 4};
    }
    return timeline.timeSignatures().get(bar);
  }

  /** Seconds per bar (bars are uniform in the compiled timeline). */
  private double barSecondsPx(int bar) {
    TabParser.BarTime bt = timeline.barTimes().get(bar);
    double len = bt.endSeconds() - bt.startSeconds();
    return len > 0 ? len : layout.barSeconds;
  }

  /** Bar containing the given absolute time. */
  private int eventBar(double timeOffset) {
    int bar = (int) Math.floor(timeOffset / layout.barSeconds);
    return Math.max(0, Math.min(layout.barCount - 1, bar));
  }

  /**
   * Cell width for a note on its string: the note's full duration — to the
   * next event on that string or the bar end — so held notes render as
   * elongated cells (the tab's sustain convention), clamped to the bar.
   */
  private double cellWidth(RenderCommand cmd, TabParser.BarTime bt, double barPps,
      java.util.Map<Integer, java.util.List<Double>> timesByString) {
    double next = bt.endSeconds();
    java.util.List<Double> times = timesByString.get(cmd.stringIndex());
    if (times != null) {
      for (double time : times) {
        if (time > cmd.timeOffset() + 0.001) {
          next = Math.min(next, time);
          break; // sorted: first later note wins
        }
      }
    }
    return Math.max(10, (next - cmd.timeOffset()) * barPps - 2);
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
