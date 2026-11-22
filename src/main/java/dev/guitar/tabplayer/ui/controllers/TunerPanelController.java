package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.model.Tuning;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * Tuner panel: one button per string. Clicking mutes the other strings and
 * plucks the chosen string open, so a real guitar can be tuned by ear against
 * the synth's reference tone. Button captions follow the live tuning.
 */
public class TunerPanelController {

  @FXML
  private Label readout;
  @FXML
  private HBox stringButtons;

  private AppContext ctx;
  private final Button[] buttons = new Button[6];
  private Tuning tuning = Tuning.STANDARD;

  public void init(AppContext ctx) {
    this.ctx = ctx;
    for (int s = 0; s < 6; s++) {
      final int stringIndex = s;
      Button b = new Button("--");
      b.getStyleClass().add("btn-steel");
      b.setOnAction(e -> pluckOpen(stringIndex));
      buttons[s] = b;
      stringButtons.getChildren().add(b);
    }
    updateTuning(tuning);
  }

  /** Follows the live tuning (same source as the fader captions). */
  public void updateTuning(Tuning tuning) {
    if (buttons[0] == null || tuning == null) {
      return;
    }
    this.tuning = tuning;
    for (int s = 0; s < 6; s++) {
      double freq = tuning.freqs[s];
      String name = AppContext.noteName(freq);
      buttons[s].setText(name);
      buttons[s].setTooltip(new Tooltip(String.format("%s  ·  %.2f Hz", name, freq)));
    }
    readout.setText("PICK A STRING");
  }

  /**
   * Mutes everything ringing first, then plucks the open string — one clear
   * reference tone at a time.
   */
  private void pluckOpen(int stringIndex) {
    if (!ctx.engineReady() || !ctx.deviceRunning()) {
      readout.setText("NO AUDIO DEVICE");
      ctx.status("TUNER: NO AUDIO DEVICE AVAILABLE");
      return;
    }
    ctx.commands().execute(() -> {
      for (int s = 0; s < 6; s++) {
        ctx.engine().mute(s);
      }
      ctx.engine().pluck(stringIndex, 0, 0.8f, false);
    });
    double freq = tuning.freqs[stringIndex];
    readout.setText(String.format("%s  ·  %.2f Hz", AppContext.noteName(freq), freq));
    ctx.status("TUNER: PLUCKED STRING " + (stringIndex + 1) + " (" + tuning.label + ")");
  }
}
