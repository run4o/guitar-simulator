package dev.guitar.tabplayer.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Geometry math of the multi-row score sheet: columns-per-row selection,
 * row/column mapping, hit testing and the visible-row range for culling.
 */
@Tag("phase4")
class ScoreLayoutTest {

  @Test
  void columnsClampBetweenThreeAndSix() {
    // narrow viewport: usable ~240 -> 1 column raw, clamped up to 3
    ScoreLayout narrow = ScoreLayout.compute(100, 100, 320, 400, 0);
    assertEquals(3, narrow.barsPerRow);

    // wide viewport: far more than 6 fit, clamped down to 6
    ScoreLayout wide = ScoreLayout.compute(100, 100, 1600, 400, 0);
    assertEquals(6, wide.barsPerRow);

    // mid viewport: exactly in the 3..6 window
    ScoreLayout mid = ScoreLayout.compute(100, 100, 900, 400, 0);
    assertTrue(mid.barsPerRow >= 3 && mid.barsPerRow <= 6);
  }

  @Test
  void barsMapToRowsAndColumns() {
    ScoreLayout layout = ScoreLayout.compute(11, 44, 1200, 400, 0);
    int perRow = layout.barsPerRow;
    assertEquals(0, layout.rowForBar(0));
    assertEquals(0, layout.rowForBar(perRow - 1));
    assertEquals(1, layout.rowForBar(perRow));
    assertEquals(0, layout.colForBar(perRow));
    assertEquals(1, layout.colForBar(perRow + 1));
    assertEquals(perRow - 1, layout.colForBar(perRow - 1));
    assertEquals(-1, layout.rowForBar(-1));
    assertEquals(-1, layout.rowForBar(11));
  }

  @Test
  void hitTestResolvesBarIndexes() {
    ScoreLayout layout = ScoreLayout.compute(11, 44, 1200, 400, 0);
    // first bar's area (inside the gutter, first row)
    assertEquals(0, layout.barAt(ScoreLayout.GUTTER + 10, 50));
    // second row starts at row 1 * ROW_HEIGHT
    assertEquals(layout.barsPerRow, layout.barAt(ScoreLayout.GUTTER + 10,
        ScoreLayout.ROW_HEIGHT + 50));
    // left of the bars area -> -1
    assertEquals(-1, layout.barAt(ScoreLayout.GUTTER - 5, 50));
    // past the last bar -> -1
    assertEquals(-1, layout.barAt(ScoreLayout.GUTTER + 10,
        layout.rowTop(layout.rowCount()) + 10));
  }

  @Test
  void visibleRowsCoverTheViewportForCulling() {
    ScoreLayout layout = ScoreLayout.compute(111, 111, 1200, 400, 0);
    // scrolled two rows down: only rows around it are reported
    ScoreLayout scrolled = ScoreLayout.compute(111, 111, 1200, 400, 320);
    assertEquals(1, scrolled.firstVisibleRow());
    int first = scrolled.firstVisibleRow();
    int last = scrolled.lastVisibleRowEx();
    assertTrue(last > first, "last visible row must be after first");
    assertTrue(last <= scrolled.rowCount() + 1);
    assertEquals(0, layout.firstVisibleRow());
  }

  @Test
  void totalHeightAndRowScrollClamp() {
    ScoreLayout layout = ScoreLayout.compute(11, 44, 1200, 400, 0);
    assertEquals(layout.rowCount() * ScoreLayout.ROW_HEIGHT + 12, layout.totalHeight(), 1e-9);
    // clamped into [0, totalHeight - canvasHeight]
    assertTrue(layout.scrollYForRow(0) >= 0);
    ScoreLayout scrolled = ScoreLayout.compute(11, 44, 1200, 400, 200);
    double target = scrolled.scrollYForRow(0);
    assertTrue(target >= 0 && target <= Math.max(0, scrolled.totalHeight() - 400));
  }

  @Test
  void cursorTimeResolvesToRowAndX() {
    // 8 bars over 32 seconds -> 4 s per bar; with 6 columns, bars 0..5 are
    // row 0 and bar 6 starts row 1
    ScoreLayout layout = ScoreLayout.compute(8, 32, 1200, 400, 0);
    assertEquals(0, layout.rowForTime(2));
    assertEquals(1, layout.rowForTime(24.5)); // bar 6 -> row 1
    // x inside the second bar of row 0: past the first column's width
    double x = layout.xForTime(4.5);
    assertTrue(x > layout.xForCol(0) + layout.barPx * 0.9, "x = " + x);
    assertTrue(x < layout.xForCol(1) + layout.barPx);
  }
}
