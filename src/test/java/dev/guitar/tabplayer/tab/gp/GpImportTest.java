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
    d.writeByte(0); // voice header
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
    for (int i = 1; i < bars; i++) {
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

