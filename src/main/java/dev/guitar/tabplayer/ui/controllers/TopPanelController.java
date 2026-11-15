package dev.guitar.tabplayer.ui.controllers;

import javafx.fxml.FXML;

/**
 * Controller for the top strip. Pure layout (two included panels), but keeps
 * a handle so the root controller can reach the nested transport controller.
 */
public class TopPanelController {

  @FXML
  private TransportPanelController transportPanelController;

  public TransportPanelController transport() {
    return transportPanelController;
  }
}
