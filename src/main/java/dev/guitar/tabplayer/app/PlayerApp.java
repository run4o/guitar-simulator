package dev.guitar.tabplayer.app;

import dev.guitar.tabplayer.ui.controllers.RootController;
import java.io.IOException;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Phase 4 UI: studio-console tab player, now FXML-driven.
 * <p>
 * The scene graph lives in /ui/*.fxml — one FXML + one controller per panel
 * (top strip, tab panel with score/source views, guitar/sound/volume panels).
 * {@link AppContext} boots the synthesis engine and {@link RootController}
 * wires the panel controllers together; playback logic lives in
 * {@link PlaybackService}.
 */
public class PlayerApp extends Application {

  @Override
  public void start(Stage stage) throws IOException {
    AppContext context = AppContext.boot();

    FXMLLoader loader = new FXMLLoader(getClass().getResource("/dev/guitar/tabplayer/ui/RootView.fxml"));
    Parent root = loader.load();
    RootController rootController = loader.getController();

    Scene scene = new Scene(root, 1020, 720);
    scene.getStylesheets().add(getClass().getResource("/dev/guitar/tabplayer/ui/theme.css").toExternalForm());
    stage.setTitle("Guitar Tab Player");
    stage.setScene(scene);
    stage.setOnCloseRequest(e -> rootController.handleClose());
    rootController.init(context, stage);
    stage.show();
  }

  public static void main(String[] args) {
    Application.launch(PlayerApp.class, args);
  }
}
