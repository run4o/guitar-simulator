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

    // FUZZ: over-saturated buzzy near-square
    Knob fuzz = new Knob(0, 1, 0.7, 44);
    Knob fuzzVol = new Knob(0, 1, 0.5, 44);
    PedalBox fuzzBox = new PedalBox("FUZZ");
    fuzzBox.addKnob(fuzz, "FUZZ");
    fuzzBox.addKnob(fuzzVol, "VOLUME");
    fuzzBox.enabledProperty().bindBidirectional(ctx.fuzzEnabledProperty());
    fuzz.valueProperty().bindBidirectional(ctx.fuzzAmountProperty());
    fuzzVol.valueProperty().bindBidirectional(ctx.fuzzVolumeProperty());
    pedalRow.getChildren().add(fuzzBox);

    // OVERDRIVE: warm soft-clipping grit
    Knob odDrive = new Knob(0, 1, 0.5, 44);
    Knob odTone = new Knob(0, 1, 0.5, 44);
    PedalBox overdrive = new PedalBox("OVERDRIVE");
    overdrive.addKnob(odDrive, "DRIVE");
    overdrive.addKnob(odTone, "TONE");
    overdrive.enabledProperty().bindBidirectional(ctx.odEnabledProperty());
    odDrive.valueProperty().bindBidirectional(ctx.odDriveProperty());
    odTone.valueProperty().bindBidirectional(ctx.odToneProperty());
    pedalRow.getChildren().add(overdrive);

    // DISTORTION: heavy hard-clipped crunch
    Knob distDrive = new Knob(0, 1, 0.5, 44);
    Knob distTone = new Knob(0, 1, 0.5, 44);
    Knob distLevel = new Knob(0, 1, 0.5, 44);
    PedalBox distortion = new PedalBox("DISTORTION");
    distortion.addKnob(distDrive, "DRIVE");
    distortion.addKnob(distTone, "TONE");
    distortion.addKnob(distLevel, "LEVEL");
    distortion.enabledProperty().bindBidirectional(ctx.distEnabledProperty());
    distDrive.valueProperty().bindBidirectional(ctx.distDriveProperty());
    distTone.valueProperty().bindBidirectional(ctx.distToneProperty());
    distLevel.valueProperty().bindBidirectional(ctx.distLevelProperty());
    pedalRow.getChildren().add(distortion);

    // DELAY: repeats with feedback (time knob maps 0..1 -> 0.02..1.0 s)
    Knob delayTime = new Knob(0, 1, 0.4, 44);
    Knob delayFb = new Knob(0, 0.9, 0.4, 44);
    Knob delayMix = new Knob(0, 1, 0.5, 44);
    PedalBox delay = new PedalBox("DELAY");
    delay.addKnob(delayTime, "TIME");
    delay.addKnob(delayFb, "FEEDBACK");
    delay.addKnob(delayMix, "MIX");
    delay.enabledProperty().bindBidirectional(ctx.delayEnabledProperty());
    delayTime.valueProperty().bindBidirectional(ctx.delayTimeProperty());
    delayFb.valueProperty().bindBidirectional(ctx.delayFeedbackProperty());
    delayMix.valueProperty().bindBidirectional(ctx.delayMixProperty());
    pedalRow.getChildren().add(delay);
  }
}
