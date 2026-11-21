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
      -- Intro riff: 120 BPM, let ring, |: bars 2-5 :| with a 1/2 double ending --
      E|:----------------|----------------|----------------|[1,2]----------------:|
      A|:----7-------7---|----7-------7---|----3-------3---|[1,2]----3-------3---:|
      D|:7-------7-------|7-------7-------|3-------3-------|[1,2]3-------3-------:|
      G|:5-------5-------|5-------5-------|2-------2-------|[1,2]2-------2-------:|
      B|:----------------|----------------|----------------|[1,2]----------------:|
      e|:----------------|----------------|----------------|[1,2]----------------:|

      E|----------------|----------------|----------------|
      A|----7-------7---|----7-------7---|----3-------3---|
      D|7-------7-------|7-------7-------|3-------3-------|
      G|5-------5-------|5-------5-------|2-------2-------|
      B|----------------|----------------|----------------|
      e|----------------|----------------|----------------|
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
