package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Temporary: renders audition WAVs into build/ for listening evaluation. */
class AuditionRenderTest {

  private static final float[] DROP_C = {65.41f, 98.00f, 130.81f, 174.61f, 220.00f, 293.66f};

  private static void dropCPowerChords(List<RenderCommand> commands) {
    // retune to drop C (C2 G2 C3 F3 A3 D4)
    for (int s = 0; s < 6; ++s) {
      commands.add(new RenderCommand(0f, GuitarEngine.EVENT_SET_TUNING, s, 0, DROP_C[s], 0f));
    }
    // flowing one-finger power chords: root on string 0 + fifth on
    // string 1 at the same fret, all palm-muted except the ring-out
    float[][] hits = {
        {0.00f, 0f}, {0.25f, 0f}, {0.50f, 5f}, {0.75f, 0f},
        {1.00f, 0f}, {1.25f, 3f}, {1.50f, 5f}};
    for (float[] hit : hits) {
      boolean mute = hit[0] < 1.5f;
      commands.add(new RenderCommand(hit[0], GuitarEngine.EVENT_PLUCK, 0,
              (int) hit[1], 1.0f, mute ? 1f : 0f));
      commands.add(new RenderCommand(hit[0], GuitarEngine.EVENT_PLUCK, 1,
              (int) hit[1], 0.9f, mute ? 1f : 0f));
    }
  }

  private void render(String name, int ampModel, int guitarModel, float drive,
                      boolean cabinet, boolean gate, float sustain) throws Exception {
    List<RenderCommand> commands = new ArrayList<>();
    commands.add(new RenderCommand(0f, GuitarEngine.EVENT_SET_MODEL, -1, guitarModel, 0f, 0f));
    dropCPowerChords(commands);
    Path wav = Path.of("build", name + ".wav").toAbsolutePath();
    try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
      org.junit.jupiter.api.Assertions.assertTrue(engine.init(44100, 256));
      engine.setAmpModel(ampModel);
      engine.setFx(drive, 0.8f, 0f, 1f);
      engine.setCabinet(cabinet, 1.0f);
      engine.setGate(gate, 0.25f, 0.3f);
      engine.setSustain(sustain);
      org.junit.jupiter.api.Assertions.assertTrue(engine.renderSequenceToWav(wav, 3.0f, commands));
    }
    System.out.println("[audition] " + wav);
  }

  @Test
  void renderAuditions() throws Exception {
    render("audition-0-dry-acoustic", 0, 0, 0f, false, false, 1f);
    render("audition-1-clean-acoustic", 0, 0, 1f, false, false, 1f);
    render("audition-2-crunch-hb", 1, 2, 0.5f, false, false, 1f);
    render("audition-3-metal-hb-d0", 2, 2, 0f, false, false, 1f);
    render("audition-4-metal-hb-d1", 2, 2, 1f, false, false, 1f);
    render("audition-5-metal-cab-gate", 2, 2, 0.7f, true, true, 1f);
    render("audition-6-clean-sustain4x", 0, 0, 0.6f, false, false, 4f);
    render("audition-7-metal-sustain4x", 2, 2, 0.7f, true, true, 4f);
  }
}
