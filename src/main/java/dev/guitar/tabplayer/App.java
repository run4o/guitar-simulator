package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import dev.guitar.tabplayer.tab.TabParser;

import java.nio.file.Path;

/**
 * End-to-end demo: an ASCII tab is parsed and compiled to an event timeline,
 * then played through the real-time engine (offline WAV fallback if no
 * device is available). Run via scripts/run.cmd.
 */
public final class App {

    private static final long SR = 44100;

    private static final String SAMPLE_TAB = """
            -- Sample riff (power chords, parsed & played by the engine) --
            e|------------------|------------------|
            B|------------------|------------------|
            G|------------------|------------------|
            D|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            A|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            E|--0--0--0--0--0-3-|--0--0--0--0--0-3-|
            """;

    public static void main(String[] args) throws InterruptedException {
        TabParser.TabTimeline timeline = TabParser.compile(SAMPLE_TAB, 120.0);
        System.out.println("parsed tab: " + timeline.commands().size() + " events, "
                + String.format("%.2f", timeline.durationSeconds()) + "s, "
                + timeline.warnings().size() + " warnings");
        timeline.warnings().forEach(w -> System.out.println("  warning: " + w));
        if (timeline.commands().isEmpty()) {
            System.out.println("Nothing to play.");
            return;
        }

        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            if (!engine.init((int) SR, 256)) {
                System.out.println("engine init failed");
                return;
            }

            if (engine.start()) {
                long startFrame = engine.sampleClock() + SR / 3; // ~330 ms lookahead
                for (RenderCommand cmd : timeline.commands()) {
                    long at = startFrame + (long) (cmd.timeOffset() * SR);
                    engine.pushEvent(at, cmd.type(), cmd.stringIndex(), cmd.fret(), cmd.a(), cmd.b());
                }
                Thread.sleep((long) (timeline.durationSeconds() * 1000) + 500);
                System.out.println("played " + engine.sampleClock() + " frames in real time");
            } else {
                Path wav = Path.of("build", "demo.wav");
                if (engine.renderSequenceToWav(wav, (float) timeline.durationSeconds(),
                        timeline.commands())) {
                    System.out.println("No audio device — offline render written to " + wav.toAbsolutePath());
                }
            }
            engine.shutdown();
        }
    }
}
