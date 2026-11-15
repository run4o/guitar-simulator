package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.model.Tuning;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.ScrollEvent;

/**
 * Guitar panel: the MODEL selector (pickup/synthesis model), the TUNE
 * selector (open-string tuning) and the BPM tempo setting. Model changes go
 * straight to the engine; tuning changes are surfaced to the root controller
 * (fader labels, engine tunings, score lane labels all follow); tempo changes
 * are surfaced to the playback service (they apply from the next bar).
 */
public class GuitarPanelController {

  @FXML
  private ComboBox<String> modelBox;
  @FXML
  private ComboBox<Tuning> tuningBox;
  @FXML
  private Spinner<Integer> bpmSpinner;

  private AppContext ctx;
  private Consumer<Tuning> tuningListener;
  private IntConsumer bpmListener;

  @FXML
  private void initialize() {
    modelBox.getItems().addAll("ACOUSTIC", "SINGLE COIL", "HUMBUCKER");
    modelBox.getSelectionModel().selectFirst();
    tuningBox.setItems(FXCollections.observableArrayList(Tuning.values()));
    tuningBox.getSelectionModel().selectFirst();
    modelBox.setMaxWidth(Double.MAX_VALUE);
    tuningBox.setMaxWidth(Double.MAX_VALUE);
    bpmSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(40, 300, 120));
    bpmSpinner.setMaxWidth(Double.MAX_VALUE);
    // wheel over the spinner steps the tempo (±1 BPM per notch), like the knobs
    bpmSpinner.addEventFilter(ScrollEvent.SCROLL, e -> {
      SpinnerValueFactory.IntegerSpinnerValueFactory factory =
          (SpinnerValueFactory.IntegerSpinnerValueFactory) bpmSpinner.getValueFactory();
      int step = e.getDeltaY() >= 0 ? 1 : -1;
      factory.setValue(Math.max(40, Math.min(300, factory.getValue() + step)));
      e.consume();
    });
    bpmSpinner.valueProperty().addListener((o, ov, nv) -> {
      if (nv != null && bpmListener != null) {
        bpmListener.accept(nv);
      }
    });
  }

  public void init(AppContext ctx) {
    this.ctx = ctx;
    modelBox.valueProperty().addListener((o, ov, nv) -> {
      if (nv != null) {
        final int model = modelBox.getSelectionModel().getSelectedIndex();
        ctx.commands().execute(() -> ctx.engine().setGuitarModel(model));
      }
    });
    tuningBox.valueProperty().addListener((o, ov, nv) -> {
      if (nv != null && tuningListener != null) {
        tuningListener.accept(nv);
      }
    });
  }

  public void setTuningListener(Consumer<Tuning> tuningListener) {
    this.tuningListener = tuningListener;
  }

  public void setBpmListener(IntConsumer bpmListener) {
    this.bpmListener = bpmListener;
  }

  public int getBpm() {
    return bpmSpinner.getValue();
  }

  /** Project load: set the spinner through its value factory. */
  public void setBpm(int bpm) {
    bpmSpinner.getValueFactory().setValue(Math.max(40, Math.min(300, bpm)));
  }

  public Tuning getTuning() {
    return tuningBox.getValue();
  }

  /** Debug flag: preselect a tuning (-Dguitar.tuning=DROP_C etc.). */
  public void selectTuningNamed(String name) {
    for (Tuning t : tuningBox.getItems()) {
      if (t.name().equalsIgnoreCase(name) || t.toString().equalsIgnoreCase(name)) {
        tuningBox.getSelectionModel().select(t);
        break;
      }
    }
  }

  /** Project load: select by enum. */
  public void selectTuning(Tuning tuning) {
    if (tuning != null && !tuning.equals(tuningBox.getValue())) {
      tuningBox.getSelectionModel().select(tuning);
    }
  }
}
