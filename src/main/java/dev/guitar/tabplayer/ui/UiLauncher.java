package dev.guitar.tabplayer.ui;

/**
 * Entry point for the UI. Kept separate from {@link PlayerApp} (which extends
 * javafx.application.Application) because launching an Application subclass
 * directly from the classpath triggers JavaFX's missing-module-path check.
 */
public final class UiLauncher {
    public static void main(String[] args) {
        PlayerApp.main(args);
    }
}
