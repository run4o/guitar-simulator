package dev.guitar.tabplayer.ui.controls;

/**
 * Pure geometry for the multi-row score view: bars wrap into rows (3+ visible
 * at once, scrolled vertically), N columns per row chosen from the available
 * width. All math is here so it can be unit-tested without JavaFX.
 */
public final class ScoreLayout {

  /** Width of the in-canvas lane-label gutter. */
  public static final double GUTTER = 56;
  /** Height of one bar row (header + 6 compact lanes). */
  public static final double ROW_HEIGHT = 160;
  /** Narrowest column a bar may get (bars-per-row shrinks below this). */
  public static final double MIN_BAR_PX = 150;
  private static final int MIN_COLS = 3;
  private static final int MAX_COLS = 6;
  private static final double RIGHT_PAD = 12;

  public final int barCount;
  public final double barSeconds;
  public final int barsPerRow;
  public final double barPx;
  public final double canvasWidth;
  public final double canvasHeight;
  public final double scrollY;

  private ScoreLayout(int barCount, double barSeconds, double canvasWidth,
      double canvasHeight, double scrollY) {
    this.barCount = barCount;
    this.barSeconds = barSeconds;
    this.canvasWidth = canvasWidth;
    this.canvasHeight = canvasHeight;
    this.scrollY = scrollY;
    double usable = Math.max(240, canvasWidth - GUTTER - RIGHT_PAD);
    // columns adapt to the available width AND the song length: a 2-bar demo
    // spreads across the full width, a 100-bar song caps at 6 columns
    int cap = Math.min(MAX_COLS, Math.max(1, barCount));
    int floorCols = Math.min(MIN_COLS, Math.max(1, barCount));
    this.barsPerRow = Math.max(floorCols, Math.min(cap, (int) (usable / MIN_BAR_PX)));
    this.barPx = usable / barsPerRow;
  }

  public static ScoreLayout compute(int barCount, double barSeconds, double canvasWidth,
      double canvasHeight, double scrollY) {
    return new ScoreLayout(barCount, barSeconds, canvasWidth, canvasHeight, scrollY);
  }

  public int rowCount() {
    return Math.max(1, (barCount + barsPerRow - 1) / barsPerRow);
  }

  /** Virtual content height: the whole song's rows (drives the scroll range). */
  public double totalHeight() {
    return rowCount() * ROW_HEIGHT + 2 * 6;
  }

  public int rowForBar(int bar) {
    if (bar < 0 || bar >= barCount) {
      return -1;
    }
    return bar / barsPerRow;
  }

  public int colForBar(int bar) {
    if (bar < 0 || bar >= barCount) {
      return -1;
    }
    return bar % barsPerRow;
  }

  /** Left edge of the given column within a row. */
  public double xForCol(int col) {
    return GUTTER + col * barPx;
  }

  public double rowTop(int row) {
    return row * ROW_HEIGHT;
  }

  /** The row containing playback time `t` (seconds from song start). */
  public int rowForTime(double t) {
    int bar = (int) Math.floor(t / barSeconds);
    return rowForBar(bar);
  }

  /** Pixel x of playback time `t` within its row. */
  public double xForTime(double t) {
    int bar = Math.min(barCount - 1, Math.max(0, (int) Math.floor(t / barSeconds)));
    double frac = t / barSeconds - bar;
    return xForCol(colForBar(bar)) + frac * barPx;
  }

  /**
   * Bar index at canvas-local coordinates, or -1 when the point is outside
   * the bars area / past the last bar.
   */
  public int barAt(double x, double y) {
    if (x < GUTTER || y < 0) {
      return -1;
    }
    int row = (int) Math.floor((y + scrollY) / ROW_HEIGHT);
    int col = (int) Math.floor((x - GUTTER) / barPx);
    int bar = row * barsPerRow + col;
    return (bar >= 0 && bar < barCount) ? bar : -1;
  }

  /**
   * Rows intersecting the viewport (for culling): first row inclusive,
   * last row exclusive-plus-margin.
   */
  public int firstVisibleRow() {
    return Math.max(0, (int) Math.floor(scrollY / ROW_HEIGHT) - 1);
  }

  public int lastVisibleRowEx() {
    return Math.min(rowCount(),
        (int) Math.ceil((scrollY + canvasHeight) / ROW_HEIGHT) + 1);
  }

  /** Scroll offset that centers the given row (clamped into range). */
  public double scrollYForRow(int row) {
    double max = Math.max(0, totalHeight() - canvasHeight);
    double top = rowTop(row) - (canvasHeight - ROW_HEIGHT) / 2.0;
    return Math.max(0, Math.min(max, top));
  }
}
