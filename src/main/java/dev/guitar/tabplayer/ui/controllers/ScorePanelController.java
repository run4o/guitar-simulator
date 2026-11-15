package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.tab.TabParser;
import dev.guitar.tabplayer.ui.controls.ScoreLabels;
import dev.guitar.tabplayer.ui.controls.ScoreView;
import java.util.function.BiConsumer;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;

/**
 * The graphical visualization panel: the fixed lane-label column plus the
 * horizontally scrolling score strip.
 */
public class ScorePanelController {

  @FXML
  private HBox scoreArea;
  @FXML
  private ScoreLabels scoreLabels;
  @FXML
  private ScrollPane scoreScroll;
  @FXML
  private ScoreView scoreView;

  @FXML
  private void initialize() {
    scoreScroll.viewportBoundsProperty().addListener((o, ov, nv) -> {
      scoreView.setMinContentWidth(nv.getWidth() - 4);
      scoreView.setHeight(Math.min(nv.getHeight() - 4, ScoreView.COMPACT_HEIGHT));
      scoreLabels.setHeight(Math.min(nv.getHeight() - 4, ScoreView.COMPACT_HEIGHT));
    });
  }

  /**
   * Moves the playback cursor and keeps it in view while it sweeps (sticks to
   * ~30% from the left). Negative values clear the cursor.
   */
  public void setCursor(double seconds) {
    scoreView.setCursorSeconds(seconds);
    if (seconds >= 0) {
      double cursorX = scoreView.cursorPixel();
      double viewport = scoreScroll.getViewportBounds().getWidth();
      double max = scoreScroll.getHmax();
      scoreScroll.setHvalue(Math.max(0, Math.min(max, cursorX - viewport * 0.3)));
    }
  }

  public void setOnBarClick(BiConsumer<Integer, Boolean> handler) {
    scoreView.setOnBarClick(handler);
  }

  public void setSelection(int first, int last) {
    scoreView.setSelection(first, last);
  }

  public void setLaneLabels(String[] labels) {
    scoreView.setLaneLabels(labels);
    scoreLabels.setLabels(labels);
  }

  public void setTimeline(dev.guitar.tabplayer.tab.TabParser.TabTimeline timeline) {
    scoreView.setTimeline(timeline);
  }

  public void setViewVisible(boolean visible) {
    scoreArea.setVisible(visible);
  }
}
