package dev.guitar.tabplayer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.guitar.tabplayer.model.Tuning;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pedalboard + UI state persistence: the generic pedal.* map round-trips
 * through the .gtp file, and the pedal-drawer open/closed state rides in it.
 */
class PedalStateTest {

  @TempDir
  Path tempDir;

  /** Engineless context: properties work, engine sync no-ops. */
  private AppContext newContext() {
    return new AppContext(null, false, false, null, false);
  }

  @Test
  void pedalStateRoundTripsThroughLoadPedalState() {
    AppContext source = newContext();
    source.boostEnabledProperty().set(true);
    source.boostGainProperty().set(12.0);
    source.volEnabledProperty().set(true);
    source.volLevelProperty().set(0.4);
    source.pedalsOpenProperty().set(true);

    AppContext target = newContext();
    target.loadPedalState(source.pedalState());

    assertTrue(target.boostEnabledProperty().get());
    assertEquals(12.0, target.boostGainProperty().get(), 1e-9);
    assertTrue(target.volEnabledProperty().get());
    assertEquals(0.4, target.volLevelProperty().get(), 1e-9);
    assertTrue(target.pedalsOpenProperty().get(),
            "the drawer state must ride the pedal state map");
  }

  @Test
  void pedalDrawerDefaultsClosed() {
    AppContext fresh = newContext();
    assertFalse(fresh.pedalsOpenProperty().get(),
            "the pedal drawer starts closed");
    fresh.loadPedalState(Map.of()); // empty state must not open it
    assertFalse(fresh.pedalsOpenProperty().get());
  }

  @Test
  void ampModelRoundTripsAndDefaultsToClean() {
    AppContext fresh = newContext();
    assertEquals(0, fresh.ampModelProperty().get(), "fresh context is the CLEAN channel");
    fresh.ampModelProperty().set(2); // METAL
    AppContext target = newContext();
    target.loadPedalState(fresh.pedalState());
    assertEquals(2, target.ampModelProperty().get(),
            "the amp channel must ride the pedal state map");
  }

  @Test
  void pedalStateRoundTripsThroughTheProjectFile() throws Exception {
    AppContext source = newContext();
    source.pedalsOpenProperty().set(true);
    source.cabEnabledProperty().set(true);

    Path file = tempDir.resolve("state.gtp");
    ProjectFile.save(file, new ProjectFile.Project(
            "e|--0--|", 140, Tuning.STANDARD, 0.5, 1.0, 0.2, 0.9, source.pedalState()));

    ProjectFile.Project loaded = ProjectFile.load(file);
    AppContext target = newContext();
    target.loadPedalState(loaded.pedals());

    assertEquals(140, loaded.bpm());
    assertEquals(0.5, loaded.drive(), 1e-9);
    assertTrue(target.pedalsOpenProperty().get(),
            "the drawer open state must survive save/load");
    assertTrue(target.cabEnabledProperty().get());
  }
}
