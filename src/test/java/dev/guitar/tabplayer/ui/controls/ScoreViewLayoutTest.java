package dev.guitar.tabplayer.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.guitar.tabplayer.tab.TabParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The score layout must use the TRUE bar length from the compiled bar times.
 * Deriving it from the display length (which includes the ring-out tail)
 * drifts the event -> bar mapping along the song and visually loses notes.
 */
@Tag("phase4")
class ScoreViewLayoutTest {

  private static final String TWO_BAR_TAB = """
      e|------------------|------------------|
      B|------------------|------------------|
      G|------------------|------------------|
      D|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
      A|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
      E|--0--0--0--0--0-3-|--0--0--0--0--0-3-|
      """;

  @Test
  void layoutBarSecondsComesFromBarTimesNotDisplayLength() {
    ScoreView view = new ScoreView();
    TabParser.TabTimeline timeline = TabParser.compile(TWO_BAR_TAB, 120);
    view.setTimeline(timeline);

    TabParser.BarTime bt = timeline.barTimes().get(0);
    double trueBarSeconds = bt.endSeconds() - bt.startSeconds();
    assertEquals(trueBarSeconds, view.layout().barSeconds, 1e-9);

    // the drift case: display length (min(duration, lastEvent + 0.8)) is
    // NOT the bar length — the old code divided it by bar count
    double displayLength = Math.min(timeline.durationSeconds(),
        timeline.commands().get(timeline.commands().size() - 1).timeOffset() + 0.8);
    assertTrue(Math.abs(displayLength / timeline.barTimes().size() - trueBarSeconds) > 1e-6,
        "test precondition: display length differs from the true bar length");
  }
}
