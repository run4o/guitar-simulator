package dev.guitar.tabplayer.ui.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

/**
 * The text visualization panel: the ASCII tab source editor. The demo tab is
 * seeded here; programmatic replaces suppress the edit callback so bar
 * operations don't count as user edits.
 */
public class SourcePanelController {

  private static final String SAMPLE_TAB = """
      e|0~~~-------------------0~~~--0~~~------------------|
      B|1~~~-------------------1~~~--1~~~------------------|
      G|2~~~-------------------2~~~--2~~~------------------|
      D|2~~~---2~~~--3~~~------2~~~--2~~~--2~~~--3~~~--2~~~|
      A|0~~~---2~~~--3~~~------0~~~--2~~~--2~~~--3~~~--0~~~|
      E|-------0~~~--1~~~------0~~~--1~~~--0~~~--1~~~--0~~~|
      """;

  @FXML
  private TextArea tabArea;

  private boolean suppressEditStop; // ignore the initial programmatic setText
  private Runnable onEditStop;

  @FXML
  private void initialize() {
    tabArea.setText(SAMPLE_TAB);
    // user edits stop playback (positions would drift); programmatic
    // replacements go through setText which suppresses the callback
    tabArea.textProperty().addListener((obs, oldV, newV) -> {
      if (!suppressEditStop && onEditStop != null) {
        onEditStop.run();
      }
    });
  }

  public void setOnEditStop(Runnable onEditStop) {
    this.onEditStop = onEditStop;
  }

  public String getText() {
    return tabArea.getText();
  }

  public void setText(String text) {
    suppressEditStop = true;
    tabArea.setText(text);
    suppressEditStop = false;
  }

  public int caretPosition() {
    return tabArea.getCaretPosition();
  }

  public void highlightRange(int start, int end) {
    tabArea.selectRange(start, end);
  }

  public void deselect() {
    tabArea.deselect();
  }

  public void focus() {
    tabArea.requestFocus();
  }

  public void setViewVisible(boolean visible) {
    tabArea.setVisible(visible);
  }
}
