package dev.guitar.tabplayer.ui.controllers;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import javafx.fxml.FXML;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * App header panel: product plate + the File menu (open / save project /
 * export / import). The controller owns the file dialogs and hands the chosen
 * file to a handler installed by the root controller.
 */
public class AppHeaderPanelController {

  /** Action ids handed to the root controller together with the chosen file. */
  public static final String OPEN_TAB = "open-tab";
  public static final String SAVE_PROJECT = "save-project";
  public static final String LOAD_PROJECT = "load-project";
  public static final String EXPORT_WAV = "export-wav";
  public static final String IMPORT_GP = "import-gp";

  @FXML
  private MenuBar menuBar;

  private BiConsumer<String, Path> actionHandler;

  public void setActionHandler(BiConsumer<String, Path> actionHandler) {
    this.actionHandler = actionHandler;
  }

  @FXML
  private void initialize() {
    Menu file = new Menu("FILE");

    MenuItem openTab = item("Open Tab…", "Ctrl+O");
    openTab.setOnAction(e -> chooseAndDispatch(OPEN_TAB, "Open ASCII tab",
        new FileChooser.ExtensionFilter("Tab files", "*.txt", "*.tab"),
        new FileChooser.ExtensionFilter("All files", "*.*")));

    MenuItem importGp = item("Import Guitar Pro…", null);
    importGp.setOnAction(e -> chooseAndDispatch(IMPORT_GP, "Import Guitar Pro",
        new FileChooser.ExtensionFilter("Guitar Pro files", "*.gp3", "*.gp4", "*.gp5"),
        new FileChooser.ExtensionFilter("All files", "*.*")));

    MenuItem loadProject = item("Load Project…", "Ctrl+L");
    loadProject.setOnAction(e -> chooseAndDispatch(LOAD_PROJECT, "Load project",
        new FileChooser.ExtensionFilter("Project files", "*.gtp"),
        new FileChooser.ExtensionFilter("All files", "*.*")));

    MenuItem saveProject = item("Save Project…", "Ctrl+S");
    saveProject.setOnAction(e -> chooseAndDispatch(SAVE_PROJECT, "Save project",
        new FileChooser.ExtensionFilter("Project files", "*.gtp")));

    MenuItem exportWav = item("Export WAV…", "Ctrl+E");
    exportWav.setOnAction(e -> chooseAndDispatch(EXPORT_WAV, "Export WAV",
        new FileChooser.ExtensionFilter("WAV audio", "*.wav")));

    file.getItems().addAll(openTab, importGp, loadProject, saveProject, exportWav);
    menuBar.getMenus().add(file);
  }

  private MenuItem item(String label, String accelerator) {
    MenuItem item = new MenuItem(label);
    if (accelerator != null) {
      item.setAccelerator(javafx.scene.input.KeyCombination.keyCombination(accelerator));
    }
    return item;
  }

  private void chooseAndDispatch(String action, String title,
      FileChooser.ExtensionFilter... filters) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    chooser.getExtensionFilters().addAll(filters);
    Window window = menuBar.getScene() != null ? menuBar.getScene().getWindow() : null;
    Path file = action.equals(SAVE_PROJECT) || action.equals(EXPORT_WAV)
        ? saveTarget(chooser, window, action)
        : openTarget(chooser, window);
    if (file != null && actionHandler != null) {
      actionHandler.accept(action, file);
    }
  }

  private Path openTarget(FileChooser chooser, Window window) {
    var file = chooser.showOpenDialog(window);
    return file != null ? file.toPath() : null;
  }

  private Path saveTarget(FileChooser chooser, Window window, String action) {
    if (action.equals(EXPORT_WAV)) {
      chooser.setInitialFileName("render.wav");
    } else {
      chooser.setInitialFileName("project.gtp");
    }
    var file = chooser.showSaveDialog(window);
    return file != null ? file.toPath() : null;
  }
}
