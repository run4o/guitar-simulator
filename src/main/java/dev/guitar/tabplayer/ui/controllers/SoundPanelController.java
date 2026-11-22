package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.model.Tuning;
import dev.guitar.tabplayer.ui.controls.Fader;
import dev.guitar.tabplayer.ui.controls.Knob;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

/**
 * Sound controller panel: the six per-string channel faders (gains) and the
 * DRIVE/TONE/REVERB knobs. Knobs are bound bidirectionally to the shared FX
 * values in the app context so the master fader in the volume panel composes
 * one engine setFx call with them.
 */
public class SoundPanelController {

  @FXML
  private Fader fader0;
  @FXML
  private Fader fader1;
  @FXML
  private Fader fader2;
  @FXML
  private Fader fader3;
  @FXML
  private Fader fader4;
  @FXML
  private Fader fader5;
  @FXML
  private Label stringLabel0;
  @FXML
  private Label stringLabel1;
  @FXML
  private Label stringLabel2;
  @FXML
  private Label stringLabel3;
  @FXML
  private Label stringLabel4;
  @FXML
  private Label stringLabel5;
  @FXML
  private Knob fxDrive;
  @FXML
  private Knob fxTone;
  @FXML
  private Knob fxReverb;
  @FXML
  private javafx.scene.control.ComboBox<String> ampBox;

  private AppContext ctx;
  private Fader[] stringFaders;
  private Label[] stringLabels;

  public void init(AppContext ctx) {
    this.ctx = ctx;
    stringFaders = new Fader[]{fader0, fader1, fader2, fader3, fader4, fader5};
    stringLabels = new Label[]{stringLabel0, stringLabel1, stringLabel2, stringLabel3,
        stringLabel4, stringLabel5};
    for (int s = 0; s < 6; s++) {
      final int idx = s;
      stringFaders[s].valueProperty().addListener(
          (o, ov, nv) -> ctx.commands().execute(() -> ctx.engine().setStringGain(idx,
              (float) nv.doubleValue())));
    }
    fxDrive.valueProperty().bindBidirectional(ctx.driveProperty());
    fxTone.valueProperty().bindBidirectional(ctx.toneProperty());
    fxReverb.valueProperty().bindBidirectional(ctx.reverbProperty());

    // Amp channel selector: index maps 1:1 onto the engine model (clean/crunch/metal)
    ampBox.getItems().addAll("CLEAN", "CRUNCH", "METAL");
    ampBox.getSelectionModel().select(ctx.ampModelProperty().get());
    ampBox.getSelectionModel().selectedIndexProperty().addListener(
        (o, ov, nv) -> ctx.ampModelProperty().set(nv.intValue()));
    ctx.ampModelProperty().addListener(
        (o, ov, nv) -> ampBox.getSelectionModel().select(nv.intValue()));
  }

  /**
   * Fader captions are derived from the tuning's open-string frequencies.
   */
  public void updateStringLabels(Tuning tuning) {
    if (stringLabels == null) {
      return; // panel not initialized yet
    }
    for (int s = 0; s < 6; s++) {
      double freq = tuning.freqs[s];
      stringLabels[s].setText(AppContext.noteName(freq));
      stringLabels[s].setTooltip(new Tooltip(
          String.format("%s  ·  %.2f Hz", AppContext.noteName(freq), freq)));
    }
  }
}
