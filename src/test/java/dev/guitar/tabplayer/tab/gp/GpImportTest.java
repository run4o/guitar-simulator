package dev.guitar.tabplayer.tab.gp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip: builds a minimal .gp5 file byte-by-byte (GP5.1 layout) and
 * asserts the importer renders the expected ASCII tab.
 */
@Tag("phase4")
class GpImportTest {

  @TempDir
  Path tempDir;

  @Test
  void parsesMinimalGp5File() throws IOException {
    Path file = tempDir.resolve("test.gp5");
    Files.write(file, gp5Fixture(2, 0, null, 4, 4));

    GpImport.GpSong song = GpImport.importFile(file);

    assertEquals(120, song.bpm());
    assertEquals("Test Song", song.title());
    assertEquals("RHYTHM GTR", song.trackName());
    String ascii = song.ascii();
    // first bar: fret 3 sustains across its quarter (tab convention), dead
    // note at column 6 after one separating column
    assertTrue(ascii.contains("e|33333 x"), "expected 'e|33333 x' but got:\n" + ascii);
    // second bar: all rests (empty cells between the two pipes)
    assertTrue(ascii.contains("|                |"), "expected empty second bar:\n" + ascii);
    // header comment carries the title
    assertTrue(ascii.startsWith("# GP import: Test Song"), ascii);
  }

  @Test
  void rendersAllBarsAcrossSystems() throws IOException {
    // Regression: the generator used to overwrite every system with the last
    // one, so songs with more than 4 bars collapsed to their final bars.
    Path file = tempDir.resolve("sixbars.gp5");
    Files.write(file, gp5Fixture(6, 0, null, 4, 4));

    GpImport.GpSong song = GpImport.importFile(file);

    long systemCount = song.ascii().lines().filter(l -> l.startsWith("E|")).count();
    assertEquals(2, systemCount, "6 bars must render as two 4-bar systems:\n" + song.ascii());
  }

  @Test
  void scalesOverfullBarInsteadOfDroppingNotes() throws IOException {
    // Regression: bars whose beats exceed 4 quarters had all excess notes
    // dropped; they must be scaled into the grid instead.
    Path file = tempDir.resolve("overfull.gp5");
    Files.write(file, gp5Fixture(1, 9, null, 4, 4));

    GpImport.GpSong song = GpImport.importFile(file);

    String eLine = song.ascii().lines().filter(l -> l.startsWith("e|")).findFirst().orElse("");
    for (int fret = 1; fret <= 9; fret++) {
      assertTrue(eLine.contains(Integer.toString(fret)),
          "fret " + fret + " dropped from an overfull bar:\n" + song.ascii());
    }
  }

  @Test
  void sizesBarsToTheirTimeSignature() throws IOException {
    // 3/4 -> 12 sixteenth-columns; 7/8 -> 14
    Path waltz = tempDir.resolve("waltz.gp5");
    Files.write(waltz, gp5Fixture(2, 0, null, 3, 4));
    String ascii = GpImport.importFile(waltz).ascii();
    for (String line : ascii.lines().filter(l -> l.startsWith("e|")).toList()) {
      assertEquals(12, line.indexOf('|', 2) - 2, "3/4 bar grid:\n" + ascii);
    }

    Path seven = tempDir.resolve("seven.gp5");
    Files.write(seven, gp5Fixture(2, 0, null, 7, 8));
    String sevenAscii = GpImport.importFile(seven).ascii();
    for (String line : sevenAscii.lines().filter(l -> l.startsWith("e|")).toList()) {
      assertEquals(14, line.indexOf('|', 2) - 2, "7/8 bar grid:\n" + sevenAscii);
    }
  }

  @Test
  void sustainsHeldNotesToTheBarEnd() throws IOException {
    // one quarter note, nothing after it: the digit fills the whole bar
    Path file = tempDir.resolve("sustain.gp5");
    Files.write(file, gp5Fixture(1, 0, new int[]{5}, 4, 4));

    String ascii = GpImport.importFile(file).ascii();
    String eLine = ascii.lines().filter(l -> l.startsWith("E|")).findFirst().orElse("");
    assertTrue(eLine.contains("5555555555555555"), "held note must fill the bar:\n" + ascii);
  }

  /** Minimal 3-bar GP5 with a tempo change (120 -> 150 BPM) inside bar 2. */
  private static byte[] gp5TempoFixture() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    fixedString(d, "FICHIER GUITAR PRO v5.10", 30);
    infoString(d, "Test Song");
    infoString(d, "");
    infoString(d, "");
    infoString(d, "Test Artist");
    infoString(d, "");
    infoString(d, "");
    infoString(d, "");
    infoString(d, "");
    infoString(d, "");
    writeIntLE(d, 0);
    writeIntLE(d, 1);
    for (int i = 0; i < 5; i++) {
      writeIntLE(d, 0);
      writeIntLE(d, 0);
    }
    d.write(new byte[19]);
    d.write(new byte[28]);
    writeShortLE(d, 0);
    for (int i = 0; i < 10; i++) {
      writeIntLE(d, 1);
      d.writeByte(0);
    }
    writeIntLE(d, 1);
    d.writeByte(0);
    writeIntLE(d, 120);
    d.writeByte(0);
    writeIntLE(d, 0);
    d.writeByte(0);
    for (int i = 0; i < 64; i++) {
      writeIntLE(d, 0);
      d.write(new byte[8]);
    }
    for (int i = 0; i < 19; i++) {
      writeShortLE(d, -1);
    }
    d.write(new byte[4]);
    writeIntLE(d, 3); // bars
    writeIntLE(d, 1); // tracks
    // master bars: bar 0 declares 4/4
    d.writeByte(0x03);
    d.writeByte(4);
    d.writeByte(4);
    d.write(new byte[4]);
    d.writeByte(0);
    d.writeByte(0);
    d.writeByte(0);
    for (int i = 1; i < 3; i++) {
      d.writeByte(0x00);
      d.writeByte(0);
      d.writeByte(0);
      d.writeByte(0);
    }
    // track (same layout as gp5Fixture)
    d.writeByte(0x00);
    fixedString(d, "RHYTHM GTR", 40);
    writeIntLE(d, 6);
    int[] tunings = {40, 45, 52, 57, 60, 64, 0};
    for (int t : tunings) {
      writeIntLE(d, t);
    }
    writeIntLE(d, 1);
    writeIntLE(d, 1);
    writeIntLE(d, 1);
    writeIntLE(d, 24);
    writeIntLE(d, 0);
    d.write(new byte[4]);
    d.writeByte(0x01);
    d.write(new byte[4]);
    writeIntLE(d, 0);
    writeIntLE(d, 0);
    writeIntLE(d, 0);
    d.write(new byte[10]);
    d.writeByte(0);
    d.writeByte(0);
    d.write(new byte[16]);
    d.write(new byte[4]);
    writeIntLE(d, 1);
    d.writeByte(0);
    writeIntLE(d, 1);
    d.writeByte(0);
    // bar chunks
    for (int bar = 0; bar < 3; bar++) {
      d.writeByte(0); // voice header
      writeIntLE(d, 4); // beats
      for (int beat = 0; beat < 4; beat++) {
        boolean tempoBeat = bar == 1 && beat == 0;
        if (tempoBeat) {
          d.writeByte(0x10); // flags: mix table
          d.writeByte(0); // duration: quarter
        } else {
          d.writeByte(0x40); // flags: beat type present (empty beat)
          d.writeByte(0x00); // beat type: empty
          d.writeByte(0); // duration: quarter
        }
        if (tempoBeat) {
          // mix table change: instrument, rse bank, params, tempo name,
          // tempo 150, duration, hide, flags, wah, 2 strings
          d.writeByte(-1); // instrument
          d.write(new byte[16]);
          d.write(new byte[]{-1, -1, -1, -1, -1, -1}); // volume..tremolo
          writeIntLE(d, 1); // tempo name length
          d.writeByte(0);
          writeIntLE(d, 150); // tempo
          d.writeByte(0); // duration
          d.writeByte(0); // hide tempo
          d.writeByte(0); // flags
          d.writeByte(-1); // wah
          writeIntLE(d, 1); // unknown string 1
          d.writeByte(0);
          writeIntLE(d, 1); // unknown string 2
          d.writeByte(0);
          d.writeByte(0x40); // string flags: high e
          d.writeByte(0x20); // note flags: type + fret
          d.writeByte(1);
          d.writeByte(3); // fret
          d.writeByte(0); // note flags2
        } else {
          d.writeByte(0x00); // string flags: none
        }
        writeShortLE(d, 0); // beat flags2
      }
      writeIntLE(d, 0); // second voice
    }
    d.flush();
    return out.toByteArray();
  }

  @Test
  void importsTempoChange() throws IOException {
    Path file = tempDir.resolve("tempo.gp5");
    Files.write(file, gp5TempoFixture());
    GpImport.GpSong song = GpImport.importFile(file);
    String ascii = song.ascii();
    assertTrue(ascii.contains("@150"), "tempo directive missing:\n" + ascii);

    // bar 1 plays at 120 BPM (2.0 s), bar 2 at 150 BPM: the pluck lands at 2.0
    var timeline = dev.guitar.tabplayer.tab.TabParser.compile(ascii, song.bpm());
    var plucks = timeline.commands().stream()
        .filter(c -> c.type() == 0 && c.fret() == 3).toList();
    assertEquals(1, plucks.size());
    assertTrue(Math.abs(plucks.get(0).timeOffset() - 2.0) < 0.01,
        "tempo-change pluck time: " + plucks.get(0).timeOffset());
  }

  @Test
  void importsArticulations() throws IOException {
    // 11 bars: hammer origin, bend +4, palm mute, tap, ghost, accent, trill,
    // tremolo picking, grace note, vibrato, harmonic
    Path file = tempDir.resolve("articulations.gp5");
    Files.write(file, gp5Fixture(11, 0, null, 4, 4, true));
    String ascii = GpImport.importFile(file).ascii();

    // bar 1: hammer origin -> 'h' before the fret-5 target
    assertTrue(ascii.contains("h 5"), "hammer token missing:\n" + ascii);
    // bar 2: bend +4 on fret 3 -> absolute target 7
    assertTrue(ascii.contains("b7 5"), "bend token missing:\n" + ascii);
    // bar 3: palm mute region around the fret-5 note
    assertTrue(ascii.contains("PM 5 PM"), "palm mute markers missing:\n" + ascii);
    // bar 4: tap marker before the fret-5 note
    assertTrue(ascii.contains("t 5"), "tap token missing:\n" + ascii);
    // bar 5: ghost note marker, bar 6: accent marker
    assertTrue(ascii.contains("g 5"), "ghost marker missing:\n" + ascii);
    assertTrue(ascii.contains("* 5"), "accent marker missing:\n" + ascii);
    // bar 7: trill alternation between frets 3 and 7 (hammer up, pull down)
    assertTrue(ascii.contains("3h 7p 3") || ascii.contains("3p 7h 3"),
        "trill alternation missing:\n" + ascii);
    // bar 8: tremolo picking marker before the fret-5 run
    assertTrue(ascii.contains("T 55555555"), "tremolo run missing:\n" + ascii);
    // bar 9: grace note fret 2 two columns before the fret-5 main note
    assertTrue(ascii.contains("g 2 5"), "grace note missing:\n" + ascii);
    // bar 10: vibrato marker at the end of the fret-3 sustain
    assertTrue(ascii.contains("3~ 5") || ascii.contains("3~5"),
        "vibrato marker missing:\n" + ascii);
    // bar 11: harmonic markers around the fret-5 note
    assertTrue(ascii.contains("< 5"), "harmonic marker missing:\n" + ascii);

    // compiled: legato (hammer + tap + trill), bend with relative semitones,
    // a palm-muted pluck, velocity-strength plucks, and tremolo/trill/grace
    // expansions
    var timeline = dev.guitar.tabplayer.tab.TabParser.compile(ascii, 120);
    var commands = timeline.commands();
    assertTrue(commands.stream().anyMatch(c -> c.type() == 1 && c.stringIndex() == 5 && c.fret() == 5),
        "hammer/tap must compile to LEGATO on the high e string");
    assertTrue(commands.stream().anyMatch(c -> c.type() == 2 && Math.abs(c.a() - 4) < 0.01),
        "bend must compile with +4 semitones");
    assertTrue(commands.stream().anyMatch(c -> c.type() == 0 && c.stringIndex() == 5 && c.fret() == 5 && c.b() == 1.0f),
        "palm-mute region must reach the fret-5 pluck");
    assertTrue(commands.stream().anyMatch(c -> c.type() == 0 && c.stringIndex() == 5 && c.fret() == 5
            && Math.abs(c.a() - 0.45f) < 0.01f),
        "ghost note must pluck at reduced strength");
    assertTrue(commands.stream().anyMatch(c -> c.type() == 0 && c.stringIndex() == 5 && c.fret() == 5
            && Math.abs(c.a() - 1.0f) < 0.01f),
        "accent note must pluck at full strength");
    // tremolo: the run 55555555 must re-pluck (>= 4 plucks of fret 5)
    assertTrue(commands.stream().filter(c -> c.type() == 0 && c.fret() == 5).count() >= 4,
        "tremolo picking must re-pluck the run");
    // trill: alternating legato frets 3 and 7
    assertTrue(commands.stream().anyMatch(c -> c.type() == 1 && c.fret() == 7),
        "trill alternation must produce legato notes on the trill fret");
    // grace: a quiet pluck of the grace fret just before the main note
    assertTrue(commands.stream().anyMatch(c -> c.type() == 0 && c.fret() == 2
            && Math.abs(c.a() - 0.45f) < 0.01f),
        "grace note must pluck the grace fret at reduced strength");
    // vibrato: a vibrato command on the high e string
    assertTrue(commands.stream().anyMatch(c -> c.type() == 6 && c.stringIndex() == 5),
        "vibrato marker must compile to a VIBRATO command");
    // harmonic: a HARMONIC pluck of the node fret
    assertTrue(commands.stream().anyMatch(c -> c.type() == 7 && c.fret() == 5),
        "harmonic marker must compile to a HARMONIC command");
    assertTrue(timeline.warnings().stream().noneMatch(w -> w.contains("out of range")));
  }

  @Test
  void separatesAdjacentChugNotes() throws IOException {
    // Regression: 16th-note chugs landed on consecutive grid columns, merging
    // into one giant digit run ("7000500070003") that overflowed the tab
    // parser's fret parsing and aborted the whole import.
    Path file = tempDir.resolve("chugs.gp5");
    int[] chugs = {7, 0, 0, 0, 5, 0, 0, 0, 7, 0, 0, 0, 3, 0, 0, 0};
    Files.write(file, gp5Fixture(2, 0, chugs, 4, 4));

    GpImport.GpSong song = GpImport.importFile(file);

    String ascii = song.ascii();
    // the generated tab must compile without throwing and without fret
    // overflows (adjacent digits must not merge into giant fret numbers)
    var timeline = dev.guitar.tabplayer.tab.TabParser.compile(ascii, song.bpm());
    assertTrue(timeline.warnings().stream().noneMatch(w -> w.contains("out of range")),
        "chugs must not merge into overflowing fret runs: warnings = " + timeline.warnings());
    // the chugs stay separated (one space between different notes)
    assertTrue(ascii.contains("7 0 0 0 5"), "chugs must stay separated:\n" + ascii);
  }

  /* ------------------------------------------------------------------ */

  /**
   * Builds a minimal GP5.1 file with `bars` measures and 1 track in the given
   * time signature. When `overfullFrets` > 0, bar 0 contains that many quarter
   * notes (frets 1..N on the high e string) whose total duration exceeds the
   * bar. When `chugFrets` is non-null, bar 0 contains that many sixteenth
   * notes with those frets on the low E string instead.
   */
  private static byte[] gp5Fixture(int bars, int overfullFrets, int[] chugFrets,
      int numerator, int denominator) throws IOException {
    return gp5Fixture(bars, overfullFrets, chugFrets, numerator, denominator, false);
  }

  private static byte[] gp5Fixture(int bars, int overfullFrets, int[] chugFrets,
      int numerator, int denominator, boolean withArticulations) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);

    // version (30 bytes)
    fixedString(d, "FICHIER GUITAR PRO v5.10", 30);
    // score information: gp_string_int_unused x9 (music is GP5+) + notices
    infoString(d, "Test Song");
    infoString(d, ""); // subtitle
    infoString(d, ""); // album
    infoString(d, "Test Artist");
    infoString(d, ""); // words
    infoString(d, ""); // music
    infoString(d, ""); // copyright
    infoString(d, ""); // tab
    infoString(d, ""); // instructions
    writeIntLE(d, 0); // notice line count
    // lyrics (GP4+): track + 5 lines
    writeIntLE(d, 1);
    for (int i = 0; i < 5; i++) {
      writeIntLE(d, 0);
      writeIntLE(d, 0); // empty text
    }
    // rse master settings (GP5.1+): 19 bytes
    d.write(new byte[19]);
    // page setup: 28 metrics + u2 flags + 10 templates
    d.write(new byte[28]);
    writeShortLE(d, 0);
    for (int i = 0; i < 10; i++) {
      writeIntLE(d, 1); // length_with_byte = 1 (empty)
      d.writeByte(0); // unused
    }
    // tempo label + tempo + hide tempo
    writeIntLE(d, 1);
    d.writeByte(0);
    writeIntLE(d, 120); // tempo
    d.writeByte(0); // hide tempo
    // key signature + octave
    writeIntLE(d, 0);
    d.writeByte(0);
    // playback channels: 4 ports x 16 channels x (program + 6 bytes + 2 blanks)
    for (int i = 0; i < 64; i++) {
      writeIntLE(d, 0); // program
      d.write(new byte[8]);
    }
    // directions (GP5+): 19 shorts + 4 bytes
    for (int i = 0; i < 19; i++) {
      writeShortLE(d, -1);
    }
    d.write(new byte[4]);
    // counts
    writeIntLE(d, bars); // bars
    writeIntLE(d, 1); // tracks

    // master bar 0: time signature declared
    d.writeByte(0x03);
    d.writeByte(numerator);
    d.writeByte(denominator);
    d.write(new byte[4]); // time sig beams
    d.writeByte(0); // alternate endings
    d.writeByte(0); // triplet feel
    d.writeByte(0); // unknown
    // master bars 1..n: nothing declared
    for (int i = 1; i < bars; i++) {
      d.writeByte(0x00);
      d.writeByte(0); // alternate endings
      d.writeByte(0); // triplet feel
      d.writeByte(0); // unknown
    }

    // track
    d.writeByte(0x00); // flags: not percussion
    fixedString(d, "RHYTHM GTR", 40);
    writeIntLE(d, 6); // string count
    int[] tunings = {40, 45, 52, 57, 60, 64, 0};
    for (int t : tunings) {
      writeIntLE(d, t);
    }
    writeIntLE(d, 1); // port
    writeIntLE(d, 1); // channel
    writeIntLE(d, 1); // effect channel
    writeIntLE(d, 24); // fret count
    writeIntLE(d, 0); // capo
    d.write(new byte[4]); // color
    d.writeByte(0x01); // staff flags
    d.write(new byte[4]); // midi auto, rse accent, bank, human
    writeIntLE(d, 0); // clef mode
    writeIntLE(d, 0); // unknown
    writeIntLE(d, 0); // unknown
    d.write(new byte[10]);
    d.writeByte(0);
    d.writeByte(0);
    d.write(new byte[16]); // rse bank
    d.write(new byte[4]); // 3-band eq
    writeIntLE(d, 1);
    d.writeByte(0); // effect name (empty)
    writeIntLE(d, 1);
    d.writeByte(0); // effect category (empty)

    // bar 0, track 0: GP5 voice header + 2 voices
    if (withArticulations) {
      // eleven bars, one half-note each on the high e string; beat 1 carries
      // a per-bar articulation origin (hammer, bend, trill, vibrato), beat 2
      // carries palm mute, tap, ghost, accent, tremolo, grace and harmonic
      for (int i = 0; i < 11; i++) {
        d.writeByte(0); // voice header
        writeIntLE(d, 2); // beats
        // beat 1: half note, fret 3, carrying the hammer/bend articulation
        boolean beat1Effects = i <= 1 || i == 6 || i == 9;
        d.writeByte(0x00); // beat flags
        d.writeByte(-1); // duration: half
        d.writeByte(0x40); // string flags: high e
        d.writeByte(beat1Effects ? 0x28 : 0x20); // note flags (+ effects)
        d.writeByte(1); // note type: regular
        d.writeByte(3); // fret
        d.writeByte(0); // note flags2
        if (i == 0) {
          // hammer/pull origin: effFlags1 0x02 -> 'h' before beat 2's note
          d.writeByte(0x02);
          d.writeByte(0x00);
        } else if (i == 1) {
          // bend +4: effFlags1 0x01 + bend block -> 'b7' before beat 2's note
          d.writeByte(0x01);
          d.writeByte(0x00);
          d.writeByte(1); // bend type
          writeIntLE(d, 100); // value
          writeIntLE(d, 1); // point count
          writeIntLE(d, 30); // offset
          writeIntLE(d, 100); // value (100 / 25 = 4 semitones)
          d.writeByte(0); // vibrato
        } else if (i == 6) {
          // trill to fret 7: effFlags2 0x20 + fret/speed bytes
          d.writeByte(0x00);
          d.writeByte(0x20);
          d.writeByte(7); // trill fret
          d.writeByte(2); // speed
        } else if (i == 9) {
          // vibrato: effFlags2 0x40
          d.writeByte(0x00);
          d.writeByte(0x40);
        }
        writeShortLE(d, 0); // beat flags2
        // beat 2: half note, fret 5, with the bar's palm mute / tap / tremolo / grace
        boolean tap = i == 3;
        boolean palm = i == 2;
        boolean beat2Effects = palm || i == 7 || i == 8 || i == 10;
        d.writeByte(tap ? 0x08 : 0x00); // beat flags: effects for the tap
        d.writeByte(-1); // duration: half
        if (tap) {
          // beat effects: slap 1 = tap
          d.writeByte(0x20); // effect flags1: slap present
          d.writeByte(0x00); // effect flags2
          d.writeByte(1); // slap = tap
        }
        d.writeByte(0x40); // string flags: high e
        int noteFlags = beat2Effects ? 0x28 : i == 4 ? 0x24 : i == 5 ? 0x22 : 0x20;
        d.writeByte(noteFlags); // note flags (+ ghost 0x04 / accent 0x02 / effects 0x08)
        d.writeByte(1); // note type: regular
        d.writeByte(5); // fret
        d.writeByte(0); // note flags2
        if (palm) {
          // palm mute: effFlags2 0x02
          d.writeByte(0x00);
          d.writeByte(0x02);
        } else if (i == 7) {
          // tremolo picking: effFlags2 0x04 + speed byte
          d.writeByte(0x00);
          d.writeByte(0x04);
          d.writeByte(2); // speed
        } else if (i == 8) {
          // grace note fret 2: effFlags1 0x10 + grace block
          d.writeByte(0x10);
          d.writeByte(0x00);
          d.writeByte(2); // grace fret
          d.writeByte(6); // grace dynamic
          d.writeByte(0); // transition
          d.writeByte(2); // duration
          d.writeByte(0); // grace flags (GP5)
        } else if (i == 10) {
          // artificial harmonic: effFlags2 0x10 + type byte (1 = natural-ish)
          d.writeByte(0x00);
          d.writeByte(0x10);
          d.writeByte(1);
        }
        writeShortLE(d, 0); // beat flags2
        writeIntLE(d, 0); // second voice: 0 beats
      }
    }
    if (!withArticulations) {
      d.writeByte(0); // voice header (bar 0's chunk, before the beat data)
    }
    if (chugFrets != null) {
      writeIntLE(d, chugFrets.length); // beats
      for (int fret : chugFrets) {
        d.writeByte(0x00); // flags
        d.writeByte(2); // duration: sixteenth
        d.writeByte(0x02); // string flags: string 5 (bit 1) = low E
        d.writeByte(0x20); // note flags: type + fret
        d.writeByte(1); // note type: regular
        d.writeByte(fret); // fret
        d.writeByte(0); // note flags2
        writeShortLE(d, 0); // beat flags2
      }
      writeIntLE(d, 0); // second voice: 0 beats
    } else if (overfullFrets > 0) {
      writeIntLE(d, overfullFrets); // beats
      for (int i = 0; i < overfullFrets; i++) {
        d.writeByte(0x00); // flags
        d.writeByte(0); // duration: quarter
        d.writeByte(0x40); // string flags: high e
        d.writeByte(0x20); // note flags: type + fret
        d.writeByte(1); // note type: regular
        d.writeByte(i + 1); // fret
        d.writeByte(0); // note flags2
        writeShortLE(d, 0); // beat flags2
      }
      writeIntLE(d, 0); // second voice: 0 beats
    } else {
      writeIntLE(d, 3); // beats
      // beat 1: quarter note, fret 3 on high e
      d.writeByte(0x00); // flags
      d.writeByte(0); // duration: quarter
      d.writeByte(0x40); // string flags: string 0 (bit 6) = high e
      d.writeByte(0x20); // note flags: type + fret
      d.writeByte(1); // note type: regular
      d.writeByte(3); // fret
      d.writeByte(0); // note flags2
      writeShortLE(d, 0); // beat flags2
      // beat 2: eighth rest
      d.writeByte(0x40); // flags: beat type present
      d.writeByte(0x00); // type 0 = empty
      d.writeByte(1); // duration: eighth
      d.writeByte(0x00); // string flags: none
      writeShortLE(d, 0); // beat flags2
      // beat 3: sixteenth dead note on high e at 1.5 quarters -> column 6
      d.writeByte(0x00);
      d.writeByte(2); // duration: sixteenth
      d.writeByte(0x40); // string flags
      d.writeByte(0x20);
      d.writeByte(3); // note type: dead
      d.writeByte(0); // fret
      d.writeByte(0); // note flags2
      writeShortLE(d, 0); // beat flags2
      writeIntLE(d, 0); // second voice: 0 beats
    }

    // bars 1..n, track 0: one empty voice + empty second voice
    // (skipped for the articulation fixture, which writes all its bars)
    for (int i = withArticulations ? bars : 1; i < bars; i++) {
      d.writeByte(0); // voice header
      writeIntLE(d, 1); // beats
      d.writeByte(0x40);
      d.writeByte(0x00);
      d.writeByte(0); // duration: quarter
      d.writeByte(0x00); // string flags
      writeShortLE(d, 0); // beat flags2
      writeIntLE(d, 0); // second voice
    }

    d.flush();
    return out.toByteArray();
  }

  private static void writeIntLE(DataOutputStream d, int v) throws IOException {
    d.writeByte(v & 0xFF);
    d.writeByte((v >>> 8) & 0xFF);
    d.writeByte((v >>> 16) & 0xFF);
    d.writeByte((v >>> 24) & 0xFF);
  }

  private static void writeShortLE(DataOutputStream d, int v) throws IOException {
    d.writeByte(v & 0xFF);
    d.writeByte((v >>> 8) & 0xFF);
  }

  private static void fixedString(DataOutputStream d, String value, int fixed) throws IOException {
    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
    d.writeByte(raw.length);
    d.write(raw);
    d.write(new byte[fixed - raw.length]);
  }

  private static void infoString(DataOutputStream d, String value) throws IOException {
    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
    writeIntLE(d, raw.length + 1); // total length including the length byte
    d.writeByte(raw.length);
    d.write(raw);
  }
}

