package dev.guitar.tabplayer;

import dev.guitar.tabplayer.engine.GuitarEngine;
import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import dev.guitar.tabplayer.tab.TabParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 3 verification: structure detection, articulation tokens, timing
 * inference, and an end-to-end parse -> offline render -> pitch check.
 */
@Tag("phase3")
class Phase3ParserTest {

    private static final float SR = 44100.0f;

    @TempDir
    Path tempDir;

    /* ---- helpers ---------------------------------------------------- */

    private static List<RenderCommand> ofType(TabParser.TabTimeline timeline, int type) {
        return timeline.commands().stream()
                .filter(c -> c.type() == type)
                .toList();
    }

    private static RenderCommand firstOf(TabParser.TabTimeline timeline, int type, int stringIndex) {
        return timeline.commands().stream()
                .filter(c -> c.type() == type && c.stringIndex() == stringIndex)
                .findFirst()
                .orElse(null);
    }

    private static void assertNear(double actual, double expected, double tolerance, String label) {
        if (Math.abs(actual - expected) > tolerance) {
            fail(label + ": expected ~" + expected + " ±" + tolerance + " but got " + actual);
        }
    }

    /* ---- structure --------------------------------------------------- */

    @Test
    void parsesStandardSixStringSystem() {
        String tab = """
                e|-----------------3-----|
                B|-------------1---------|
                G|---------0-------------|
                D|-----2-----------------|
                A|---0-------------------|
                E|-0-----------------0---|
                """;
        TabParser.TabTimeline timeline = TabParser.compile(tab);
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        assertEquals(7, plucks.size(), "7 notes expected");
        // spot-check frets: high E fret 3, B fret 1, G open, D fret 2, A open, low E open and fret 0 again
        assertTrue(plucks.stream().anyMatch(c -> c.stringIndex() == 5 && c.fret() == 3));
        assertTrue(plucks.stream().anyMatch(c -> c.stringIndex() == 4 && c.fret() == 1));
        assertTrue(plucks.stream().anyMatch(c -> c.stringIndex() == 3 && c.fret() == 0));
        assertTrue(plucks.stream().anyMatch(c -> c.stringIndex() == 2 && c.fret() == 2));
        assertTrue(plucks.stream().anyMatch(c -> c.stringIndex() == 1 && c.fret() == 0));
        assertEquals(2, plucks.stream().filter(c -> c.stringIndex() == 0).count());
    }

    @Test
    void duplicateLabelsStartANewSystem() {
        String tab = """
                e|--0--|
                e|--3--|
                """;
        TabParser.TabTimeline timeline = TabParser.compile(tab);
        assertEquals(2, ofType(timeline, GuitarEngine.EVENT_PLUCK).size());
        RenderCommand first = timeline.commands().get(0);
        RenderCommand second = timeline.commands().get(1);
        // second system's note must start after the first system's width * tick
        assertTrue(second.timeOffset() > first.timeOffset() + 0.5,
                "second system starts later: " + first.timeOffset() + " vs " + second.timeOffset());
    }

    /* ---- chords + stagger -------------------------------------------- */

    @Test
    void sameColumnChordGetsLowToHighStagger() {
        String tab = """
                e|-0---|
                B|-0---|
                G|-1---|
                D|-2---|
                A|-2---|
                E|-0---|
                """;
        TabParser.TabTimeline timeline = TabParser.compile(tab);
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        assertEquals(6, plucks.size());
        for (RenderCommand cmd : plucks) {
            assertNear(cmd.timeOffset(), 1 * 0.125 + 0.012 * cmd.stringIndex(), 0.002,
                    "stagger time for string " + cmd.stringIndex());
        }
        // low string must sound first
        double lowTime = plucks.stream().filter(c -> c.stringIndex() == 0).findFirst().orElseThrow().timeOffset();
        double highTime = plucks.stream().filter(c -> c.stringIndex() == 5).findFirst().orElseThrow().timeOffset();
        assertTrue(highTime > lowTime, "strum must go low to high");
    }

    /* ---- articulations ------------------------------------------------ */

    @Test
    void ghostAndAccentMarkersSetPluckVelocity() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--g3-*5-3-|\n");
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        assertEquals(3, plucks.size());
        assertNear(plucks.get(0).a(), 0.45, 0.001, "ghost velocity");
        assertNear(plucks.get(1).a(), 1.0, 0.001, "accent velocity");
        assertNear(plucks.get(2).a(), 0.9, 0.001, "default velocity");
    }

    @Test
    void hammerOnMarkerProducesLegato() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--3h5--|\n");
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        List<RenderCommand> legatos = ofType(timeline, GuitarEngine.EVENT_LEGATO);
        assertEquals(1, plucks.size());
        assertEquals(1, legatos.size());
        assertEquals(3, plucks.get(0).fret());
        assertEquals(5, legatos.get(0).fret());
        assertNear(legatos.get(0).timeOffset(), 4 * 0.125, 0.002, "hammer-on time");
    }

    @Test
    void bendWithReleaseProducesTwoBendEvents() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--5b7r5--|\n");
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        List<RenderCommand> bends = ofType(timeline, GuitarEngine.EVENT_BEND);
        assertEquals(1, plucks.size());
        assertEquals(5, plucks.get(0).fret());
        assertEquals(2, bends.size());
        assertEquals(2.0f, bends.get(0).a(), 1e-4); // 5 -> 7 = +2 semitones
        assertEquals(-2.0f, bends.get(1).a(), 1e-4); // release back to 5
        assertTrue(bends.get(1).timeOffset() > bends.get(0).timeOffset());
    }

    @Test
    void slideBetweenNotesProducesLegato() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--5/7--|\n");
        List<RenderCommand> legatos = ofType(timeline, GuitarEngine.EVENT_LEGATO);
        assertEquals(1, legatos.size());
        assertEquals(7, legatos.get(0).fret());
    }

    @Test
    void slideInWithoutPriorNoteIsPlucked() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--/5--|\n");
        assertEquals(0, ofType(timeline, GuitarEngine.EVENT_LEGATO).size(),
                "no legato when the string has no ringing energy");
        assertEquals(1, ofType(timeline, GuitarEngine.EVENT_PLUCK).size(),
                "slide-in after silence must be plucked");
    }

    @Test
    void palmMuteRegionAppliesToPlucks() {
        TabParser.TabTimeline timeline = TabParser.compile("E|-PM-3-3-3-PM--5-|\n");
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        assertEquals(4, plucks.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0f, plucks.get(i).b(), 1e-4, "note " + i + " must be palm muted");
        }
        assertEquals(0.0f, plucks.get(3).b(), 1e-4, "note after region must be open");
    }

    @Test
    void deadNoteBecomesMute() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--3-x-5--|\n");
        List<RenderCommand> mutes = ofType(timeline, GuitarEngine.EVENT_MUTE);
        assertEquals(1, mutes.size());
        assertEquals(5, mutes.get(0).stringIndex(), "'e' line is the high E string (index 5)");
    }

    /* ---- timing ------------------------------------------------------- */

    @Test
    void barLineTimingOverridesUniformDensity() {
        // Bars of 20 columns: at 90 BPM a bar lasts 4 * 60/90 = 2.667s
        // -> tick = 2.667 / 20 = 0.1333s (uniform-density fallback would be 0.1667s)
        String tab = """
                e|----0-3-5-----------|----5-3-0-----------|
                E|--------------------|--------------------|
                """;
        TabParser.TabTimeline timeline = TabParser.compile(tab, 90.0);
        RenderCommand first = firstOf(timeline, GuitarEngine.EVENT_PLUCK, 5);
        // bars sit 21 columns apart -> tick = 4 beats / 21 columns
        assertNear(first.timeOffset(), 4 * (4 * 60.0 / 90.0) / 21.0, 0.002, "bar-based tick");
    }

    @Test
    void uniformDensityFallbackIsSixteenthPerColumn() {
        TabParser.TabTimeline timeline = TabParser.compile("e|---0---|\n", 120.0);
        RenderCommand cmd = firstOf(timeline, GuitarEngine.EVENT_PLUCK, 5);
        assertNear(cmd.timeOffset(), 3 * (60.0 / 120.0 / 4.0), 0.002, "sixteenth-per-column");
    }

    @Test
    void multiSystemTimeAccumulates() {
        String tab = """
                e|--0--|
                e|--3--|
                """;
        TabParser.TabTimeline timeline = TabParser.compile(tab, 120.0);
        assertEquals(2, timeline.commands().size());
        // system width = len("--0--|") = 6 cols -> system 2 starts at 6 * 0.125 = 0.75
        assertNear(timeline.commands().get(1).timeOffset(), 0.75 + 2 * 0.125, 0.002,
                "second system offset");
    }

    /* ---- robustness ---------------------------------------------------- */

    @Test
    void garbageInputYieldsEmptyTimelineWithoutCrash() {
        TabParser.TabTimeline timeline = TabParser.compile("this is not a tab\njust some text\n");
        assertTrue(timeline.commands().isEmpty());
        assertTrue(timeline.durationSeconds() > 0);
    }

    @Test
    void unknownCharactersAreWarnedButParsingContinues() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--q-3--|\n");
        assertEquals(1, ofType(timeline, GuitarEngine.EVENT_PLUCK).size());
        assertTrue(timeline.warnings().stream().anyMatch(w -> w.contains("q")),
                "warning about 'q' expected");
    }

    @Test
    void outOfRangeFretIsClampedWithWarning() {
        // mixed digit runs are one fret number: 2424 overflows and clamps.
        // ("99" alone would now read as a sustained fret 9 — tab sustain
        // convention.)
        TabParser.TabTimeline timeline = TabParser.compile("e|--2424--|\n");
        RenderCommand pluck = firstOf(timeline, GuitarEngine.EVENT_PLUCK, 5);
        assertEquals(24, pluck.fret());
        assertTrue(timeline.warnings().stream().anyMatch(w -> w.contains("out of range")));
    }

    @Test
    void lowercaseAmbiguousLabelsAreMappedWithWarning() {
        TabParser.TabTimeline timeline = TabParser.compile("""
                e|--0--|
                b|--1--|
                """);
        assertTrue(timeline.warnings().stream().anyMatch(w -> w.contains("Lowercase")));
        assertEquals(2, timeline.commands().size());
    }

    @Test
    void vibratoEmitsVibratoCommand() {
        TabParser.TabTimeline timeline = TabParser.compile("e|--3~~5--(7)--|\n");
        List<RenderCommand> plucks = ofType(timeline, GuitarEngine.EVENT_PLUCK);
        // 3, 5, and 7 all plucked; harmonics tolerated with a warning
        assertEquals(3, plucks.size());
        // the ~ applies to the ringing fret-3 note: vibrato commands, no warning
        List<RenderCommand> vibratos = ofType(timeline, GuitarEngine.EVENT_VIBRATO);
        assertEquals(2, vibratos.size());
        assertEquals(5, vibratos.get(0).stringIndex());
        assertTrue(timeline.warnings().stream().noneMatch(w -> w.contains("Vibrato")));
        assertTrue(timeline.warnings().stream().anyMatch(w -> w.contains("Harmonics")));
    }

    /* ---- end-to-end: parse -> render -> pitch -------------------------- */

    @Test
    void parsedTabRendersAtExpectedPitches() throws Exception {
        String tab = "e|---0--------x-----3--------|\n";
        TabParser.TabTimeline timeline = TabParser.compile(tab, 120.0);
        // tick = 0.125s: note0 at 0.375s, mute at 1.5s, fret-3 note at 2.25s
        Path wav = tempDir.resolve("parsed.wav");
        try (GuitarEngine engine = new GuitarEngine(GuitarEngine.defaultLibraryPath())) {
            assertTrue(engine.init((int) SR, 256));
            assertTrue(engine.renderSequenceToWav(wav, (float) (timeline.durationSeconds() + 1.0),
                    timeline.commands()));
        }
        WavAnalysis.Wav parsed = WavAnalysis.read(wav);
        // high E open = 329.63 Hz, fret 3 = 392.0 Hz
        assertNear(WavAnalysis.detectPitch(parsed, 0.45, 0.7), 329.63f, 8.0f, "first note pitch");
        assertNear(WavAnalysis.detectPitch(parsed, 2.45, 3.1), 392.0f, 8.0f, "second note pitch");
    }

    @Test
    void repeatedDigitRunsAreSustainedNotesNotRePlucks() {
        // tab convention: holding a digit across columns sustains the note —
        // the run must yield ONE pluck, not a re-attack per column, and not a
        // giant fret number
        TabParser.TabTimeline timeline = TabParser.compile("""
                e|-----------------|-----------------|
                B|-----------------|-----------------|
                G|-----------------|-----------------|
                D|-----------------|-----------------|
                A|-----------------|-----------------|
                E|33333333---------|-----------------|
                """, 120);

        List<RenderCommand> plucks = ofType(timeline, 0); // PLUCK
        long onLowE = plucks.stream().filter(c -> c.stringIndex() == 0).count();
        assertEquals(1, onLowE, "a sustained digit run must produce exactly one pluck");
        RenderCommand pluck = firstOf(timeline, 0, 0);
        assertEquals(3, pluck.fret(), "the run's fret comes from the repeated digit");
    }

    @Test
    void mixedDigitRunsStayFretNumbers() {
        // different adjacent digits remain one multi-digit fret number
        TabParser.TabTimeline timeline = TabParser.compile("""
                e|-----------------|-----------------|
                B|-----------------|-----------------|
                G|-----------------|-----------------|
                D|-----------------|-----------------|
                A|-----------------|-----------------|
                E|12---------------|-----------------|
                """, 120);

        RenderCommand pluck = firstOf(timeline, 0, 0);
        assertEquals(12, pluck.fret(), "'12' is fret twelve");
    }

    @Test
    void importedTimeSignaturesTravelWithTheTimeline() {
        var meters = new java.util.HashMap<Integer, int[]>();
        meters.put(0, new int[]{7, 8});
        meters.put(1, new int[]{3, 4});
        TabParser.TabTimeline timeline = TabParser.compile("""
                e|-----------------|-----------------|-----------------|
                B|-----------------|-----------------|-----------------|
                G|-----------------|-----------------|-----------------|
                D|--2--2--2--2--2-5|--2--2--2--2--2-5|--2--2--2--2--2-5|
                A|--2--2--2--2--2-5|--2--2--2--2--2-5|--2--2--2--2--2-5|
                E|--0--0--0--0--0-3|--0--0--0--0--0-3|--0--0--0--0--0-3|
                """, 120, meters);

        assertEquals(3, timeline.timeSignatures().size());
        assertEquals(7, timeline.timeSignatures().get(0)[0]);
        assertEquals(8, timeline.timeSignatures().get(0)[1]);
        assertEquals(3, timeline.timeSignatures().get(1)[0]);
        assertEquals(4, timeline.timeSignatures().get(1)[1]);
        // bars beyond the sparse map default to 4/4
        assertEquals(4, timeline.timeSignatures().get(2)[0]);
        assertEquals(4, timeline.timeSignatures().get(2)[1]);
    }
}
