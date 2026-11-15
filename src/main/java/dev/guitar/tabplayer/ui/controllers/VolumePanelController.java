package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.ui.controls.Fader;
import dev.guitar.tabplayer.ui.controls.VuMeter;
import javafx.fxml.FXML;

/**
 * Volume panel: the master fader (bound to the shared master value in the app
 * context) and the engine-driven OUT VU meter.
 */
public class VolumePanelController {

  @FXML
  private Fader masterFader;
  @FXML
  private VuMeter vuMeter;

  public void init(AppContext ctx) {
    masterFader.valueProperty().bindBidirectional(ctx.masterProperty());
  }

  /** Driven by the root controller's 50 ms meter poll. */
  public void setVuLevel(double level) {
    vuMeter.setLevel(level);
  }
}
