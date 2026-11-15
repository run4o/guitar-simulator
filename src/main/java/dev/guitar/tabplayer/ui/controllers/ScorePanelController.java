package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.tab.TabParser;
import dev.guitar.tabplayer.ui.controls.ScoreView;
import java.util.function.BiConsumer;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;

/**
 * The graphical visualization panel: a vertically scrolled, multi-row score
 * sheet. The canvas is viewport-sized; a transparent spacer gives the scroll
 * pane its virtual content height and the canvas is pinned with a translate,
 * so only visible rows are ever drawn.
 */
public class ScorePanelController {

  @FXML
  private HBox scoreArea;
  @FXML
  private ScrollPane scoreScroll;
  @FXML
  private Group contentGroup;
  @FXML
  private Rectangle spacer;
  @FXML
  private ScoreView scoreView;

  private double canvasW = 900;
  private double canvasH = 300;

  @FXML
  private void initialize() {
    spacer.setMouseTransparent(true);
    scoreScroll.viewportBoundsProperty().addListener((o, ov, nv) -> {
      canvasW = Math.max(100, nv.getWidth());
      canvasH = Math.max(100, nv.getHeight());
      scoreView.setCanvasSize(canvasW, canvasH);
      refitSpacer();
      updateScroll();
    });
    scoreScroll.vvalueProperty().addListener((o, ov, nv) -> updateScroll());
  }

  private void refitSpacer() {
    spacer.setWidth(canvasW);
    spacer.setHeight(Math.max(canvasH, scoreView.totalHeight()));
  }

  private void updateScroll() {
    double max = Math.max(1, spacer.getHeight() - canvasH);
    double y = scoreScroll.getVvalue() * max;
    scoreView.setScrollY(y);
    scoreView.setTranslateY(y);
  }

  public void setOnBarClick(BiConsumer<Integer, Boolean> handler) {
    scoreView.setOnBarClick(handler);
  }

  public void setSelection(int first, int last) {
    scoreView.setSelection(first, last);
  }

  public void setLaneLabels(String[] labels) {
    scoreView.setLaneLabels(labels);
  }

  public void setTimeline(TabParser.TabTimeline timeline) {
    scoreView.setTimeline(timeline);
    refitSpacer();
    updateScroll();
  }

  /**
   * Moves the playback cursor and page-flips the scroll when the cursor
   * leaves the visible rows.
   */
  public void setCursor(double seconds) {
    scoreView.setCursorSeconds(seconds);
    if (seconds >= 0) {
      double current = scoreScroll.getVvalue()
          * Math.max(1, spacer.getHeight() - canvasH);
      double target = scoreView.scrollYForCursor();
      double rowH = 120;
      if (target < current - 1 || target > current + canvasH - rowH) {
        double max = Math.max(1, spacer.getHeight() - canvasH);
        scoreScroll.setVvalue(Math.max(0, Math.min(1, target / max)));
      }
    }
  }

  public void setViewVisible(boolean visible) {
    scoreArea.setVisible(visible);
  }
}
