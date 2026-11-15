package dev.guitar.tabplayer.ui.controllers;

import java.util.function.Consumer;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;

/**
 * Transport panel: PLAY/STOP, LOOP and the time LCD. Actions and change
 * callbacks are injected by the root controller. (The BPM setting lives in
 * the guitar panel, next to MODEL and TUNE.)
 */
public class TransportPanelController {

  @FXML
  private Pane playLed;
  @FXML
  private Button playBtn;
  @FXML
  private Button stopBtn;
  @FXML
  private CheckBox loopBox;
  @FXML
  private Label timeLcd;

  private Runnable onPlay;
  private Runnable onStop;
  private Consumer<Boolean> onLoopChange;

  @FXML
  private void initialize() {
    loopBox.selectedProperty().addListener((o, ov, nv) -> {
      if (onLoopChange != null) {
        onLoopChange.accept(nv);
      }
    });
    playBtn.setOnAction(e -> {
      if (onPlay != null) {
        onPlay.run();
      }
    });
    stopBtn.setOnAction(e -> {
      if (onStop != null) {
        onStop.run();
      }
    });
  }

  public void setActions(Runnable onPlay, Runnable onStop) {
    this.onPlay = onPlay;
    this.onStop = onStop;
  }

  public void setLoopListener(Consumer<Boolean> onLoopChange) {
    this.onLoopChange = onLoopChange;
  }

  public void setTime(String text) {
    timeLcd.setText(text);
  }

  public void setPlaying(boolean playing) {
    playLed.getStyleClass().setAll(playing ? new String[]{"led", "led-red"} : new String[]{"led"});
  }
}
