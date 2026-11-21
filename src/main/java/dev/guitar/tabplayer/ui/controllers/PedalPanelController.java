package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.ui.controls.Knob;
import dev.guitar.tabplayer.ui.controls.PedalBox;
import javafx.fxml.FXML;
import javafx.scene.layout.HBox;

/**
 * Pedalboard panel: one stompbox tile per pedal, each bound to its engine
 * parameters in the app context (mirrors the DRIVE/TONE/REVERB wiring).
 */
public class PedalPanelController {

  @FXML
  private HBox pedalRow;

  private AppContext ctx;

  public void init(AppContext ctx) {
    this.ctx = ctx;

    // BOOST: clean linear gain, 0..+18 dB
    Knob gain = new Knob(0, 18, 6, 44);
    PedalBox boost = new PedalBox("BOOST");
    boost.addKnob(gain, "GAIN");
    boost.enabledProperty().bindBidirectional(ctx.boostEnabledProperty());
    gain.valueProperty().bindBidirectional(ctx.boostGainProperty());
    pedalRow.getChildren().add(boost);
  }
}
