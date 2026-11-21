package dev.guitar.tabplayer.ui.controls;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Stompbox tile for the pedalboard: an LED footswitch (click to bypass), a
 * name plate, and knob slots added per pedal. Built once per pedal and grown
 * as pedals land on the board.
 */
public class PedalBox extends VBox {

  private static final Color LED_OFF = Color.web("#2A2E40");
  private static final Color LED_ON = Color.web("#00E5A8");

  private final BooleanProperty enabled = new SimpleBooleanProperty(false);
  private final Circle led = new Circle(6);
  private final String title;

  public PedalBox(String title) {
    this.title = title;
    getStyleClass().add("pedal-box");
    setSpacing(2);
    setAlignment(Pos.TOP_CENTER);

    led.getStyleClass().add("pedal-led");
    led.setFill(LED_OFF);
    led.setOnMouseClicked(e -> enabled.set(!enabled.get()));
    led.setCursor(Cursor.HAND);
    HBox ledRow = new HBox(led);
    ledRow.setAlignment(Pos.CENTER);

    Label plate = new Label(title);
    plate.getStyleClass().add("pedal-name");
    HBox plateRow = new HBox(plate);
    plateRow.setAlignment(Pos.CENTER);

    getChildren().addAll(ledRow, plateRow);

    enabled.addListener((o, ov, on) -> led.setFill(on.booleanValue() ? LED_ON : LED_OFF));
  }

  /** Adds one knob slot (small rotary + caption) to the tile. */
  public void addKnob(Knob knob, String caption) {
    Label cap = new Label(caption);
    cap.getStyleClass().add("knob-label");
    VBox slot = new VBox(knob, cap);
    slot.setAlignment(Pos.TOP_CENTER);
    slot.setSpacing(2);
    getChildren().add(slot);
  }

  public BooleanProperty enabledProperty() {
    return enabled;
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public String getTitle() {
    return title;
  }
}
