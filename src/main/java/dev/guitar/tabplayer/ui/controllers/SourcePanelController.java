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
      -- Technique showcase: h/p/t, slides, bends, vibrato, harmonics, muting --
      e|--5-h-7-p-5---t-9-b12-|-(12)~~~~~~--/14------|
      B|----------------------|----------------------|
      G|----------------------|----------------------|
      D|----------------------|----------------------|
      A|----------------------|----------------------|
      E|--0-0-0-0--PM-0-0-PM--|----------------------|

      @150
      e|:--3-3-3-3-|[1]--5-5-5-5-:|[2]--7-7-7-7-|
      B|:----------|[1]----------:|[2]----------|
      G|:----------|[1]----------:|[2]----------|
      D|:----------|[1]----------:|[2]----------|
      A|:----------|[1]----------:|[2]----------|
      E|:----------|[1]----------:|[2]----------|

      e|--5h7p5h7p5--T4444--g-2-5-*5-x-3-|
      B|---------------------------------|
      G|---------------------------------|
      D|---------------------------------|
      A|---------------------------------|
      E|---------------------------------|
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
