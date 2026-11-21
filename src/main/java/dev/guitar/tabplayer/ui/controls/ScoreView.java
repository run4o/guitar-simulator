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
  private static final double TAIL_CAP = 80; // px where the sustain taper ends

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
      // next-note times per string (cell widths) — built once per draw;
      // vibrato/bend do not end a note's visual sustain
      java.util.Map<Integer, java.util.List<Double>> timesByString = new java.util.HashMap<>();
      for (TabParser.TabEvent event : timeline.events()) {
        int type = event.command().type();
        if (type == RenderCommandType.VIBRATO || type == RenderCommandType.BEND) {
          continue;
        }
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
      java.util.List<Double> times = timesByString.get(cmd.stringIndex());
      switch (cmd.type()) {
        case RenderCommandType.PLUCK -> {
          boolean palm = cmd.b() >= 0.5f;
          boolean grace = cmd.tag() == RenderCommand.TAG_GRACE;
          boolean ghost = grace || Math.abs(cmd.a() - 0.45f) < 0.02f;
          boolean accent = !ghost && cmd.a() >= 0.95f;
          drawSustainCell(g, x, laneCenter, cellW, cellH, onsetW, String.valueOf(cmd.fret()),
              palm || ghost, palm);
          if (palm) {
            drawTag(g, "PM", x + 1, laneCenter - cellH / 2 - 7, SILVER);
          }
          if (accent) {
            drawTag(g, ">", x + onsetW - 7, laneCenter - cellH / 2 - 7, SILVER);
          }
          if (grace) {
            drawTag(g, "g", x + onsetW - 4, laneCenter - cellH / 2 - 7, Color.web("#9FE8F2"));
          }
          if (cmd.tag() == RenderCommand.TAG_TREMOLO) {
            // two tremolo beams above the re-plucked note
            g.setStroke(Color.web("#9FE8F2"));
            g.setLineWidth(1.2);
            double top = laneCenter - cellH / 2;
            g.strokeLine(x + 1, top - 2, x + 9, top - 8);
            g.strokeLine(x + 7, top - 2, x + 15, top - 8);
          }
        }
        case RenderCommandType.LEGATO -> {
          boolean slide = cmd.tag() == RenderCommand.TAG_SLIDE
              || (cmd.tag() == RenderCommand.TAG_NONE && Math.abs(cmd.a() - 0.15f) < 0.01f);
          // legato keeps its outline (vs the plucked solid block) with a
          // hairline tail for the hold
          g.setStroke(CYAN);
          g.setLineWidth(1.5);
          g.strokeRoundRect(x, laneCenter - cellH / 2, onsetW, cellH, 3, 3);
          if (cellW > onsetW + 2) {
            g.setStroke(Color.rgb(0, 188, 212, 0.45));
            g.setLineWidth(1);
            g.strokeLine(x + onsetW, laneCenter, x + cellW, laneCenter);
          }
          g.setFill(CYAN);
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(String.valueOf(cmd.fret()), x + onsetW / 2, laneCenter);
          // a slur over the approach marks h/p/t (or a glide for slides)
          double prev = previousTime(times, cmd.timeOffset());
          if (prev >= 0 && !slide) {
            double px = xForTime(prev);
            double midX = (px + x) / 2.0;
            g.setStroke(CYAN);
            g.setLineWidth(1.2);
            strokeCurve(g, px + 2, laneCenter - cellH / 2 - 2, midX, laneCenter - cellH / 2 - 9,
                x - 2, laneCenter - cellH / 2 - 2);
          }
          String art = switch (cmd.tag()) {
            case RenderCommand.TAG_TRILL -> "tr";
            case RenderCommand.TAG_HAMMER -> "h";
            case RenderCommand.TAG_PULL -> "p";
            case RenderCommand.TAG_TAP -> "T";
            default -> null;
          };
          if (art != null) {
            drawTag(g, art, x + onsetW + 2, laneCenter - cellH / 2 - 7, Color.web("#9FE8F2"));
          }
          if (slide && prev >= 0) {
            double px = xForTime(prev);
            g.setStroke(CYAN);
            g.setLineWidth(1.5);
            g.strokeLine(px + 3, laneCenter - cellH / 2 - 4, x - 2, laneCenter);
            g.fillPolygon(new double[]{x - 7, x - 1, x - 7},
                new double[]{laneCenter - 4, laneCenter, laneCenter + 4}, 3);
          }
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
          // the bend curve rides over the gap to the next note
          double next = nextTime(times, cmd.timeOffset());
          double endX = next >= 0 ? xForTime(next) : x + 22;
          g.setStroke(Color.web("#E01212"));
          g.setLineWidth(1.5);
          strokeCurve(g, x + 5, laneCenter, (x + endX) / 2, laneCenter - 12,
              Math.min(endX, x + 34), laneCenter - 6);
          g.setFont(Font.font("Share Tech Mono", 11));
          g.setTextBaseline(VPos.CENTER);
          int semis = Math.round(cmd.a());
          g.fillText((semis >= 0 ? "+" : "") + semis, x + 12, laneCenter - 16);
        }
        case RenderCommandType.HARMONIC -> {
          g.setStroke(Color.web("#9FE8F2"));
          g.setLineWidth(1.5);
          g.strokeRoundRect(x, laneCenter - cellH / 2, onsetW, cellH, 3, 3);
          g.setFill(Color.web("#9FE8F2"));
          g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
          g.setTextBaseline(VPos.CENTER);
          g.fillText("<" + cmd.fret() + ">", x + onsetW / 2 - 4, laneCenter);
        }
        case RenderCommandType.MUTE -> {
          g.setStroke(SILVER);
          g.setLineWidth(1.5);
          g.strokeLine(x - 4, laneCenter - 4, x + 4, laneCenter + 4);
          g.strokeLine(x - 4, laneCenter + 4, x + 4, laneCenter - 4);
        }
        case RenderCommandType.VIBRATO -> {
          // wave over the note the marker follows
          double onset = previousTime(times, cmd.timeOffset());
          if (onset >= 0) {
            double fromX = xForTime(onset);
            g.setStroke(Color.web("#9FE8F2"));
            g.setLineWidth(1.2);
            double waveY = laneCenter - cellH / 2 - 4;
            for (double wx = fromX; wx < x - 4; wx += 8) {
              double seg = Math.min(8, x - 4 - wx);
              strokeCurve(g, wx, waveY + 3, wx + seg / 2, waveY - 3, wx + seg, waveY + 3);
            }
          }
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

      // structural marks: tempo chip, repeat barlines, ending chips
      TabParser.BarMarks mark = bar < timeline.barMarks().size()
          ? timeline.barMarks().get(bar) : null;
      if (mark != null) {
        if (mark.bpm() > 0) {
          g.setFill(SILVER);
          g.setFont(Font.font("Share Tech Mono", 11));
          g.setTextBaseline(VPos.CENTER);
          g.fillText("@" + (int) Math.round(mark.bpm()), x + 44, yTop + 16);
        }
        if (mark.endingMask() != 0) {
          String text = "[" + endingText(mark.endingMask()) + "]";
          g.setFill(Color.web("#9FE8F2"));
          g.setFont(Font.font("Share Tech Mono", 11));
          g.setTextBaseline(VPos.CENTER);
          g.fillText(text, x + layout.barPx - 30, yTop + 12);
        }
        double barStartX = layout.xForCol(layout.colForBar(bar));
        double barEndX = barStartX + layout.barPx;
        if (mark.repeatStart()) {
          // |: : thin line, then a thick line to its left, dots in the lanes
          g.setStroke(EDGE);
          g.setLineWidth(1);
          g.strokeLine(barStartX - 5, yTop + HEADER_H, barStartX - 5, rowBottom);
          g.setStroke(CYAN);
          g.setLineWidth(3);
          g.strokeLine(barStartX, yTop + HEADER_H, barStartX, rowBottom);
          g.setFill(CYAN);
          g.fillOval(barStartX - 11, yTop + HEADER_H + 8, 4, 4);
          g.fillOval(barStartX - 11, rowBottom - 12, 4, 4);
        }
        if (mark.repeatEndPlays() > 0) {
          // :|xN : thick line then thin, dots, and the play count
          g.setStroke(CYAN);
          g.setLineWidth(3);
          g.strokeLine(barEndX - 2, yTop + HEADER_H, barEndX - 2, rowBottom);
          g.setStroke(EDGE);
          g.setLineWidth(1);
          g.strokeLine(barEndX + 2, yTop + HEADER_H, barEndX + 2, rowBottom);
          g.setFill(CYAN);
          g.fillOval(barEndX + 6, yTop + HEADER_H + 8, 4, 4);
          g.fillOval(barEndX + 6, rowBottom - 12, 4, 4);
          g.setFill(SILVER);
          g.setFont(Font.font("Share Tech Mono", 11));
          g.setTextBaseline(VPos.CENTER);
          String count = mark.repeatEndPlays() > 2 ? "x" + mark.repeatEndPlays() : "";
          g.fillText(":|" + count, barEndX - 6, yTop + 12);
        }
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
  /** Ending bitmask as compact text: "1", "1-3" or "1,3". */
  private static String endingText(int mask) {
    StringBuilder text = new StringBuilder();
    for (int e = 1; e <= 31; e++) {
      if ((mask & (1 << (e - 1))) != 0) {
        if (text.length() > 0) {
          text.append(',');
        }
        text.append(e);
      }
    }
    return text.toString();
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

  /** Bar containing the given absolute time (bars can differ in length). */
  private int eventBar(double timeOffset) {
    if (timeline == null || timeline.barTimes().isEmpty()) {
      return 0;
    }
    int lo = 0;
    int hi = layout.barCount - 1;
    int ans = Math.max(0, hi);
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      TabParser.BarTime bt = timeline.barTimes().get(mid);
      if (timeOffset < bt.startSeconds()) {
        hi = mid - 1;
      } else if (timeOffset >= bt.endSeconds() && mid < layout.barCount - 1) {
        lo = mid + 1;
      } else {
        ans = mid;
        break;
      }
    }
    return ans;
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
   * Sustain cell: a solid onset block with the digit, a taper that fades
   * over the next stretch, and a hairline to the note's real end. Long
   * holds repeat the digit (the tab's held-note convention) so the block
   * never reads as one blank slab.
   */
  private void drawSustainCell(GraphicsContext g, double x, double laneCenter, double cellW,
      double cellH, double onsetW, String label, boolean hollow, boolean dimmed) {
    double top = laneCenter - cellH / 2;
    if (hollow) {
      g.setStroke(dimmed ? Color.web("#0E7A8A") : CYAN);
      g.setLineWidth(1.2);
      g.strokeRoundRect(x, top, onsetW, cellH, 3, 3);
    } else {
      g.setFill(dimmed ? Color.web("#0E7A8A") : CYAN);
      g.fillRoundRect(x, top, onsetW, cellH, 3, 3);
    }
    if (cellW > onsetW + 4) {
      double taperEnd = Math.min(cellW, onsetW + TAIL_CAP);
      for (double tx = onsetW; tx < taperEnd; tx += 3) {
        double alpha = 0.5 * (1 - (tx - onsetW) / (taperEnd - onsetW));
        g.setFill(Color.rgb(0, 188, 212, Math.max(0.06, alpha)));
        g.fillRect(x + tx, top, 3, cellH);
      }
      if (cellW > taperEnd + 2) {
        g.setStroke(Color.rgb(0, 188, 212, 0.4));
        g.setLineWidth(1);
        g.strokeLine(x + taperEnd, laneCenter, x + cellW, laneCenter);
      }
    }
    if (cellW > 48) {
      // the digit repeats across long holds, like the tab source
      g.setFill(Color.rgb(255, 255, 255, 0.4));
      g.setFont(Font.font("Share Tech Mono", 10));
      g.setTextBaseline(VPos.CENTER);
      for (double dx = onsetW + 26; dx < cellW - 8; dx += 28) {
        g.fillText(label, x + dx, laneCenter);
      }
    }
    g.setFill(hollow || dimmed ? SILVER : INK);
    g.setFont(Font.font("Share Tech Mono", cellH >= 18 ? 12 : 10));
    g.setTextBaseline(VPos.CENTER);
    g.fillText(label, x + onsetW / 2, laneCenter);
  }

  /** Small label floating above a cell (PM, accents, articulation tags). */
  private void drawTag(GraphicsContext g, String text, double x, double y, Color color) {
    g.setFill(color);
    g.setFont(Font.font("Share Tech Mono", 10));
    g.setTextBaseline(VPos.TOP);
    g.fillText(text, x, y);
    g.setTextBaseline(VPos.CENTER);
  }

  /** The note time just before `t` on a string, or -1. */
  private double previousTime(java.util.List<Double> times, double t) {
    if (times == null) {
      return -1;
    }
    double best = -1;
    for (double time : times) {
      if (time < t - 0.001) {
        best = time;
      } else {
        break;
      }
    }
    return best;
  }

  /** The note time just after `t` on a string, or -1. */
  private double nextTime(java.util.List<Double> times, double t) {
    if (times != null) {
      for (double time : times) {
        if (time > t + 0.001) {
          return time;
        }
      }
    }
    return -1;
  }

  /** Canvas x of an absolute time (same per-bar mapping the events use). */
  private double xForTime(double t) {
    int bar = eventBar(t);
    TabParser.BarTime bt = timeline.barTimes().get(bar);
    double barPps = layout.barPx / barSecondsPx(bar);
    return layout.xForCol(layout.colForBar(bar)) + (t - bt.startSeconds()) * barPps;
  }

  /** Quadratic bezier (x0,y0 control cx,cy end x1,y1) drawn as short segments. */
  private void strokeCurve(GraphicsContext g, double x0, double y0, double cx, double cy,
      double x1, double y1) {
    double px = x0;
    double py = y0;
    for (int i = 1; i <= 8; i++) {
      double s = i / 8.0;
      double inv = 1 - s;
      double qx = inv * inv * x0 + 2 * inv * s * cx + s * s * x1;
      double qy = inv * inv * y0 + 2 * inv * s * cy + s * s * y1;
      g.strokeLine(px, py, qx, qy);
      px = qx;
      py = qy;
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
    static final int VIBRATO = 6;
    static final int HARMONIC = 7;
  }
}
