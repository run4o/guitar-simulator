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

    // CHORUS: unison-wobble thickening
    Knob chorusRate = new Knob(0, 1, 0.5, 44);
    Knob chorusDepth = new Knob(0, 1, 0.5, 44);
    Knob chorusMix = new Knob(0, 1, 0.5, 44);
    PedalBox chorus = new PedalBox("CHORUS");
    chorus.addKnob(chorusRate, "RATE");
    chorus.addKnob(chorusDepth, "DEPTH");
    chorus.addKnob(chorusMix, "MIX");
    chorus.enabledProperty().bindBidirectional(ctx.chorusEnabledProperty());
    chorusRate.valueProperty().bindBidirectional(ctx.chorusRateProperty());
    chorusDepth.valueProperty().bindBidirectional(ctx.chorusDepthProperty());
    chorusMix.valueProperty().bindBidirectional(ctx.chorusMixProperty());
    pedalRow.getChildren().add(chorus);

    // FLANGER: regenerating jet comb
    Knob flRate = new Knob(0, 1, 0.5, 44);
    Knob flDepth = new Knob(0, 1, 0.6, 44);
    Knob flFb = new Knob(0, 0.9, 0.4, 44);
    Knob flMix = new Knob(0, 1, 0.5, 44);
    PedalBox flanger = new PedalBox("FLANGER");
    flanger.addKnob(flRate, "RATE");
    flanger.addKnob(flDepth, "DEPTH");
    flanger.addKnob(flFb, "FEEDBACK");
    flanger.addKnob(flMix, "MIX");
    flanger.enabledProperty().bindBidirectional(ctx.flangerEnabledProperty());
    flRate.valueProperty().bindBidirectional(ctx.flangerRateProperty());
    flDepth.valueProperty().bindBidirectional(ctx.flangerDepthProperty());
    flFb.valueProperty().bindBidirectional(ctx.flangerFeedbackProperty());
    flMix.valueProperty().bindBidirectional(ctx.flangerMixProperty());
    pedalRow.getChildren().add(flanger);

    // PHASER: LFO-swept all-pass notches (the watery swirl)
    Knob phRate = new Knob(0, 1, 0.5, 44);
    Knob phDepth = new Knob(0, 1, 0.5, 44);
    Knob phRegen = new Knob(0, 0.9, 0.4, 44);
    Knob phMix = new Knob(0, 1, 0.5, 44);
    PedalBox phaser = new PedalBox("PHASER");
    phaser.addKnob(phRate, "RATE");
    phaser.addKnob(phDepth, "DEPTH");
    phaser.addKnob(phRegen, "REGEN");
    phaser.addKnob(phMix, "MIX");
    phaser.enabledProperty().bindBidirectional(ctx.phaserEnabledProperty());
    phRate.valueProperty().bindBidirectional(ctx.phaserRateProperty());
    phDepth.valueProperty().bindBidirectional(ctx.phaserDepthProperty());
    phRegen.valueProperty().bindBidirectional(ctx.phaserRegenProperty());
    phMix.valueProperty().bindBidirectional(ctx.phaserMixProperty());
    pedalRow.getChildren().add(phaser);

    // TREMOLO: LFO volume pulse
    Knob tremRate = new Knob(0, 1, 0.5, 44);
    Knob tremDepth = new Knob(0, 1, 0.5, 44);
    PedalBox tremolo = new PedalBox("TREMOLO");
    tremolo.addKnob(tremRate, "RATE");
    tremolo.addKnob(tremDepth, "DEPTH");
    tremolo.enabledProperty().bindBidirectional(ctx.tremoloEnabledProperty());
    tremRate.valueProperty().bindBidirectional(ctx.tremoloRateProperty());
    tremDepth.valueProperty().bindBidirectional(ctx.tremoloDepthProperty());
    pedalRow.getChildren().add(tremolo);

    // VIBRATO: LFO pitch warble (the bent copy replaces the dry signal)
    Knob vibRate = new Knob(0, 1, 0.5, 44);
    Knob vibDepth = new Knob(0, 1, 0.5, 44);
    Knob vibMix = new Knob(0, 1, 1.0, 44);
    PedalBox vibrato = new PedalBox("VIBRATO");
    vibrato.addKnob(vibRate, "RATE");
    vibrato.addKnob(vibDepth, "DEPTH");
    vibrato.addKnob(vibMix, "MIX");
    vibrato.enabledProperty().bindBidirectional(ctx.vibratoEnabledProperty());
    vibRate.valueProperty().bindBidirectional(ctx.vibratoRateProperty());
    vibDepth.valueProperty().bindBidirectional(ctx.vibratoDepthProperty());
    vibMix.valueProperty().bindBidirectional(ctx.vibratoMixProperty());
    pedalRow.getChildren().add(vibrato);

    // COMPRESSOR: evens out the dynamics with make-up gain
    Knob compSustain = new Knob(0, 1, 0.5, 44);
    Knob compLevel = new Knob(0, 1, 0.5, 44);
    PedalBox compressor = new PedalBox("COMPRESSOR");
    compressor.addKnob(compSustain, "SUSTAIN");
    compressor.addKnob(compLevel, "LEVEL");
    compressor.enabledProperty().bindBidirectional(ctx.compEnabledProperty());
    compSustain.valueProperty().bindBidirectional(ctx.compSustainProperty());
    compLevel.valueProperty().bindBidirectional(ctx.compLevelProperty());
    pedalRow.getChildren().add(compressor);

    // EQ: 3-band tone shaping (0.5 = flat)
    Knob eqBass = new Knob(0, 1, 0.5, 44);
    Knob eqMid = new Knob(0, 1, 0.5, 44);
    Knob eqTreble = new Knob(0, 1, 0.5, 44);
    PedalBox eq = new PedalBox("EQ");
    eq.addKnob(eqBass, "BASS");
    eq.addKnob(eqMid, "MID");
    eq.addKnob(eqTreble, "TREBLE");
    eq.enabledProperty().bindBidirectional(ctx.eqEnabledProperty());
    eqBass.valueProperty().bindBidirectional(ctx.eqBassProperty());
    eqMid.valueProperty().bindBidirectional(ctx.eqMidProperty());
    eqTreble.valueProperty().bindBidirectional(ctx.eqTrebleProperty());
    pedalRow.getChildren().add(eq);

    // WAH: resonant band-pass sweep (RATE 0 = static rocker position)
    Knob wahRate = new Knob(0, 1, 0.0, 44);
    Knob wahCenter = new Knob(0, 1, 0.5, 44);
    Knob wahRes = new Knob(0, 1, 0.3, 44);
    PedalBox wah = new PedalBox("WAH");
    wah.addKnob(wahRate, "RATE");
    wah.addKnob(wahCenter, "CENTER");
    wah.addKnob(wahRes, "RESO");
    wah.enabledProperty().bindBidirectional(ctx.wahEnabledProperty());
    wahRate.valueProperty().bindBidirectional(ctx.wahRateProperty());
    wahCenter.valueProperty().bindBidirectional(ctx.wahCenterProperty());
    wahRes.valueProperty().bindBidirectional(ctx.wahResonanceProperty());
    pedalRow.getChildren().add(wah);

    // GATE: silence below the threshold
    Knob gateThr = new Knob(0, 1, 0.5, 44);
    Knob gateRel = new Knob(0, 1, 0.3, 44);
    PedalBox gate = new PedalBox("GATE");
    gate.addKnob(gateThr, "THRESH");
    gate.addKnob(gateRel, "RELEASE");
    gate.enabledProperty().bindBidirectional(ctx.gateEnabledProperty());
    gateThr.valueProperty().bindBidirectional(ctx.gateThresholdProperty());
    gateRel.valueProperty().bindBidirectional(ctx.gateReleaseProperty());
    pedalRow.getChildren().add(gate);

    // OCTAVE: analog octave doubling (MODE 0 = down, 1 = up)
    Knob octMode = new Knob(0, 1, 0.0, 44);
    Knob octMix = new Knob(0, 1, 0.7, 44);
    PedalBox octave = new PedalBox("OCTAVE");
    octave.addKnob(octMode, "MODE");
    octave.addKnob(octMix, "MIX");
    octave.enabledProperty().bindBidirectional(ctx.octEnabledProperty());
    octMode.valueProperty().bindBidirectional(ctx.octModeProperty());
    octMix.valueProperty().bindBidirectional(ctx.octMixProperty());
    pedalRow.getChildren().add(octave);
  }
}
