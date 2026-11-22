package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Temporary: renders audition WAVs into build/ for listening evaluation. */
class AuditionRenderTest {

  private static void chug(List<RenderCommand> commands) {
    // low-string chug pattern: three hits with palm-style chokes
    commands.add(new RenderCommand(0.00f, GuitarEngine.EVENT_PLUCK, 0, 0, 1.0f, 0f));
    commands.add(new RenderCommand(0.28f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
    commands.add(new RenderCommand(0.40f, GuitarEngine.EVENT_PLUCK, 0, 0, 1.0f, 0f));
    commands.add(new RenderCommand(0.68f, GuitarEngine.EVENT_MUTE, 0, 0, 0f, 0f));
    commands.add(new RenderCommand(0.80f, GuitarEngine.EVENT_PLUCK, 0, 0, 1.0f, 0f));
    commands.add(new RenderCommand(1.20f, GuitarEngine.EVENT_PLUCK, 5, 7, 0.9f, 0f));
  }

  private void render(String name, int ampModel, int guitarModel, float drive,
                      boolean cabinet, boolean gate) throws Exception {
    List<RenderCommand> commands = new ArrayList<>();
    commands.add(new RenderCommand(0f, GuitarEngine.EVENT_SET_MODEL, -1, guitarModel, 0f, 0f));
    chug(commands);
    Path wav = Path.of("build", name + ".wav").toAbsolutePath();
    try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
      org.junit.jupiter.api.Assertions.assertTrue(engine.init(44100, 256));
      engine.setAmpModel(ampModel);
      engine.setFx(drive, 0.8f, 0f, 1f);
      engine.setCabinet(cabinet, 1.0f);
      engine.setGate(gate, 0.25f, 0.3f);
      org.junit.jupiter.api.Assertions.assertTrue(engine.renderSequenceToWav(wav, 2.0f, commands));
    }
    System.out.println("[audition] " + wav);
  }

  @Test
  void renderAuditions() throws Exception {
    render("audition-0-dry-acoustic", 0, 0, 0f, false, false);
    render("audition-1-clean-acoustic", 0, 0, 1f, false, false);
    render("audition-2-crunch-hb", 1, 2, 0.5f, false, false);
    render("audition-3-metal-hb-d0", 2, 2, 0f, false, false);
    render("audition-4-metal-hb-d1", 2, 2, 1f, false, false);
    render("audition-5-metal-cab-gate", 2, 2, 0.7f, true, true);
  }
}
