package dev.guitar.tabplayer.ui.controllers;

import java.util.List;
import java.util.function.Supplier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;

/**
 * Sheet header: the tab title, the bar-block operation buttons, the
 * score/source view toggle and the parse-warning badge. Button behavior is
 * owned by the tab panel, which injects the operations here.
 */
public class SheetHeaderController {

  @FXML
  private TextField titleField;
  @FXML
  private Button copyBtn;
  @FXML
  private Button cutBtn;
  @FXML
  private Button pasteBtn;
  @FXML
  private Button dupBtn;
  @FXML
  private Button delBtn;
  @FXML
  private Button clearBtn;
  @FXML
  private Button editToggle;
  @FXML
  private Pane warningBadge;

  /**
   * Wires the seven header actions (copy, cut, paste, dup, del, clear, view toggle).
   */
  public void setOps(Runnable copy, Runnable cut, Runnable paste, Runnable dup, Runnable del,
      Runnable clear, Runnable toggleView) {
    copyBtn.setOnAction(e -> copy.run());
    cutBtn.setOnAction(e -> cut.run());
    pasteBtn.setOnAction(e -> paste.run());
    dupBtn.setOnAction(e -> dup.run());
    delBtn.setOnAction(e -> del.run());
    clearBtn.setOnAction(e -> clear.run());
    editToggle.setOnAction(e -> toggleView.run());
  }

  public void setViewToggleText(String text) {
    editToggle.setText(text);
  }

  /**
   * Parse warnings surface as a small red badge on the sheet header.
   */
  public void setWarnings(List<String> warnings) {
    boolean has = warnings != null && !warnings.isEmpty();
    warningBadge.setVisible(has);
    warningBadge.setManaged(has);
    Tooltip.install(warningBadge, new Tooltip(
        has ? "Parse warnings:\n" + String.join("\n", warnings) : "No warnings"));
  }
}
