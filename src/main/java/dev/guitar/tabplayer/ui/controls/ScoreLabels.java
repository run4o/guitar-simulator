package dev.guitar.tabplayer.ui.controls;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Fixed lane-label column for the score view: sits outside the horizontal
 * scroll pane so the string names (derived from the selected tuning) never
 * scroll away. Mirrors ScoreView's lane geometry (same height, same constants).
 */
public class ScoreLabels extends Canvas {

  private static final Color BG = Color.web("#0A0C14");
  private static final Color LANE_A = Color.web("#151823");
  private static final Color LANE_B = Color.web("#111321");
  private static final Color LABEL = Color.web("#8B8E99");

  private final String[] labels = {"-", "-", "-", "-", "-", "-"};

  public ScoreLabels() {
    super(56, 300);
    widthProperty().addListener((o, ov, nv) -> draw());
    heightProperty().addListener((o, ov, nv) -> draw());
    draw();
  }

  public void setLabels(String[] newLabels) {
    for (int i = 0; i < 6 && i < newLabels.length; i++) {
      labels[i] = newLabels[i];
    }
    draw();
  }

  private double laneHeight() {
    return Math.max(16, (getHeight() - ScoreView.HEADER_H - 2 * ScoreView.PADDING) / 6.0);
  }

  private double laneTop(int lane) {
    return ScoreView.HEADER_H + ScoreView.PADDING + lane * laneHeight();
  }

  private void draw() {
    GraphicsContext g = getGraphicsContext2D();
    double w = getWidth();
    double h = getHeight();
    g.clearRect(0, 0, w, h);
    g.setFill(BG);
    g.fillRect(0, 0, w, h);

    double lh = laneHeight();
    g.setTextBaseline(VPos.CENTER);
    for (int lane = 0; lane < 6; lane++) {
      double top = laneTop(lane);
      g.setFill(lane % 2 == 0 ? LANE_A : LANE_B);
      g.fillRect(0, top, w - 2, lh - 2);
      g.setFill(LABEL);
      g.setFont(Font.font("Share Tech Mono", 13));
      g.fillText(labels[lane], 12, top + lh / 2.0);
    }
  }
}
