package dev.guitar.tabplayer.tab.gp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Guitar Pro 3/4/5 (.gp3/.gp4/.gp5) binary importer: parses the file and
 * renders it as ASCII tab, feeding the regular {@code TabParser} pipeline so
 * the score view, bar operations, editing and playback all work unchanged.
 * <p>
 * Byte layout follows the reverse-engineered format (as implemented in
 * alphaTab's Gp3To5Importer): little-endian ints, length-prefixed strings.
 * v1 scope: first 6-string non-percussion track, fretted notes, dead notes,
 * rests; one tempo (the song header). Everything else is parsed past (to keep
 * the stream aligned) and surfaced as a warning comment at the top of the
 * generated tab.
 */
public final class GpImport {

  public record GpSong(String ascii, int bpm, String title, String trackName, List<String> warnings,
      List<int[]> meters) {
  }

  private GpImport() {
  }

  public static GpSong importFile(Path file) throws IOException {
    Parser parser = new Parser(Files.readAllBytes(file));
    return parser.parse();
  }

  /* ------------------------------------------------------------------
   * Byte reader (little-endian)
   * ------------------------------------------------------------------ */

  private static final class Reader {
    private final byte[] data;
    private int pos;

    Reader(byte[] data) {
      this.data = data;
    }

    int position() {
      return pos;
    }

    int u1() {
      if (pos >= data.length) {
        throw new IllegalArgumentException("unexpected end of file at offset " + pos);
      }
      return data[pos++] & 0xFF;
    }

    int s1() {
      return (byte) u1();
    }

    int u2() {
      return u1() | (u1() << 8);
    }

    int u4() {
      return u1() | (u1() << 8) | (u1() << 16) | (u1() << 24);
    }

    void skip(int n) {
      if (pos + n > data.length) {
        throw new IllegalArgumentException("unexpected end of file at offset " + pos);
      }
      pos += n;
    }

    byte[] bytes(int n) {
      if (pos + n > data.length) {
        throw new IllegalArgumentException("unexpected end of file at offset " + pos);
      }
      byte[] out = new byte[n];
      System.arraycopy(data, pos, out, 0, n);
      pos += n;
      return out;
    }

    /** u4 total, u1 length, raw[length] (Guitar Pro "string int unused"). */
    String stringIntUnused() {
      u4();
      int len = u1();
      return new String(bytes(len), StandardCharsets.UTF_8);
    }

    /** u4 total(=len+1), u1 unused, raw[total-1] ("string int byte"). */
    String stringIntByte() {
      int total = u4();
      if (total < 1) {
        throw new IllegalArgumentException("corrupt string (length " + total + ") at offset " + pos);
      }
      skip(1);
      int len = total - 1;
      return new String(bytes(len), StandardCharsets.UTF_8);
    }

    /** u4 length, raw[length] ("string int"). */
    String stringInt() {
      int len = u4();
      return new String(bytes(len), StandardCharsets.UTF_8);
    }

    /** u1 length, raw, padding to `fixed` bytes total. */
    String stringByteLength(int fixed) {
      int len = u1();
      byte[] field = bytes(fixed);
      return new String(field, 0, Math.min(len, fixed), StandardCharsets.UTF_8);
    }
  }

  /* ------------------------------------------------------------------
   * Parser state
   * ------------------------------------------------------------------ */

  private static final int MAX_BARS = 1000;
  private static final int MAX_TRACKS = 100;
  private static final int MAX_BEATS = 200;
  private static final int MAX_BEND_POINTS = 256;
  private static final int MAX_NOTICE_LINES = 1000;

  /** A parsed note event, in the app's string index space (0 = low E). */
  private static final class NoteEvent {
    final int stringIndex;
    int fret;
    final boolean dead;
    boolean hammerPull;   // legato origin: h/p before the next note on this string
    int slideOut;         // 0 none, 1 up '/', 2 down '\'
    double bendSemitones; // 0 = none
    boolean palmMute;
    boolean tap;
    boolean vibrato;
    boolean harmonic;
    double velocity;      // 0 = default pluck strength; else ghost/accent/dynamic
    int trillFret = -1;   // trill alternation fret (-1 = none)
    double trillStep;     // trill note length in quarters
    boolean tremolo;      // tremolo picking: the run re-plucks per digit
    int graceFret = -1;   // grace note before the main note (-1 = none)
    double graceStep;     // grace note length in quarters
    int col;              // placed column (set by the generator)

    NoteEvent(int stringIndex, int fret, boolean dead) {
      this.stringIndex = stringIndex;
      this.fret = fret;
      this.dead = dead;
    }
  }

  private static final class Beat {
    final List<NoteEvent> notes = new ArrayList<>();
    boolean dotted;
    boolean empty;
    boolean tap;
    int tupletNumerator = 1;
    int tupletDenominator = 1;
    int durationValue; // the raw GP duration byte (-2..4)
  }

  private static final class MasterBar {
    int numerator = 4;
    int denominator = 4;
  }

  private static final class Track {
    final String name;
    final boolean percussion;
    final int stringCount;
    final int[] tunings; // midi numbers, low -> high
    final List<List<Beat>> bars = new ArrayList<>(); // [barIndex] -> beats (voice 0)

    Track(String name, boolean percussion, int stringCount, int[] tunings) {
      this.name = name;
      this.percussion = percussion;
      this.stringCount = stringCount;
      this.tunings = tunings;
    }
  }

  private static final class Parser {
    private final Reader r;
    private final List<String> warnings = new ArrayList<>();
    private int version;
    private boolean warnedArticulationSkip;
    private boolean warnedGraceSkip;
    private int bpm = 120;
    private String title = "";
    private String artist = "";
    private int barCount;
    private int trackCount;
    private final List<MasterBar> masterBars = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();

    Parser(byte[] data) {
      r = new Reader(data);
    }

    private void warn(String message) {
      if (!warnings.contains(message)) {
        warnings.add(message);
      }
    }

    private void guard(int value, int max, String label) {
      if (value < 0 || value > max) {
        throw new IllegalArgumentException("corrupt file: " + label + " = " + value);
      }
    }

    GpSong parse() {
      readVersion();
      readScoreInformation();
      if (version < 500) {
        r.u1(); // global triplet feel (bool)
      }
      if (version >= 400) {
        readLyrics();
      }
      if (version >= 510) {
        r.skip(19); // rse master settings
      }
      if (version >= 500) {
        readPageSetup();
        r.stringIntByte(); // tempo label
      }
      bpm = r.u4();
      if (version >= 510) {
        r.u1(); // hide tempo
      }
      r.u4(); // key signature + octave
      if (version >= 400) {
        r.u1(); // octave extra
      }
      readPlaybackInfos();
      if (version >= 500) {
        for (int i = 0; i < 19; i++) {
          r.u2(); // directions
        }
        r.skip(4);
      }
      barCount = r.u4();
      guard(barCount, MAX_BARS, "bar count");
      trackCount = r.u4();
      guard(trackCount, MAX_TRACKS, "track count");

      readMasterBars();
      readTracks();
      readBars();

      return buildSong();
    }

    /* -- header ---------------------------------------------------- */

    private void readVersion() {
      String raw = r.stringByteLength(30);
      if (!raw.startsWith("FICHIER GUIT")) {
        throw new IllegalArgumentException("not a Guitar Pro 3/4/5 file (header: " + raw.trim() + ")");
      }
      String rest = raw.substring("FICHIER GUIT".length()); // rest starts with "ARE/AR PRO vX.YY"
      int v = rest.indexOf("PRO v");
      if (v < 0) {
        throw new IllegalArgumentException("unknown Guitar Pro version header: " + raw.trim());
      }
      String number = rest.substring(v + 5).trim();
      if (number.startsWith("L")) {
        number = number.substring(1); // "L4.06" variant
      }
      int dot = number.indexOf('.');
      version = 100 * Integer.parseInt(number.substring(0, dot))
          + Integer.parseInt(number.substring(dot + 1)); // minor is always two digits (5.10 -> 10)
    }

    private void readScoreInformation() {
      title = r.stringIntUnused();
      r.stringIntUnused(); // subtitle
      r.stringIntUnused(); // album
      artist = r.stringIntUnused();
      r.stringIntUnused(); // words
      if (version >= 500) {
        r.stringIntUnused(); // music
      }
      r.stringIntUnused(); // copyright
      r.stringIntUnused(); // tab
      r.stringIntUnused(); // instructions
      int noticeLines = r.u4();
      guard(noticeLines, MAX_NOTICE_LINES, "notice line count");
      for (int i = 0; i < noticeLines; i++) {
        r.stringIntUnused();
      }
    }

    private void readLyrics() {
      r.u4(); // lyrics track (1-based)
      for (int i = 0; i < 5; i++) {
        r.u4(); // start bar (1-based)
        r.stringInt();
      }
    }

    private void readPageSetup() {
      r.skip(28); // page metrics
      r.u2(); // header/footer flags
      for (int i = 0; i < 10; i++) {
        r.stringIntByte(); // 10 header/footer templates
      }
    }

    private void readPlaybackInfos() {
      for (int port = 0; port < 4; port++) {
        for (int channel = 0; channel < 16; channel++) {
          r.u4(); // program
          r.skip(6); // volume, balance, chorus, reverb, phase, tremolo
          r.skip(2); // gp3 blanks
        }
      }
    }

    /* -- master bars ------------------------------------------------ */

    private void readMasterBars() {
      for (int i = 0; i < barCount; i++) {
        readMasterBar(i);
      }
    }

    private void readMasterBar(int index) {
      MasterBar bar = new MasterBar();
      MasterBar previous = masterBars.isEmpty() ? null : masterBars.get(masterBars.size() - 1);
      if (previous != null) {
        bar.numerator = previous.numerator;
        bar.denominator = previous.denominator;
      }
      int flags = r.u1();
      if ((flags & 0x01) != 0) {
        bar.numerator = r.u1();
      }
      if ((flags & 0x02) != 0) {
        bar.denominator = r.u1();
      }
      if ((flags & 0x04) != 0) {
        // repeat start (ignored in v1)
      }
      if ((flags & 0x08) != 0) {
        r.u1(); // repeat count (+1 for <500, ignored in v1)
      }
      if ((flags & 0x10) != 0 && version < 500) {
        r.u1(); // alternate endings mask (pre-GP5)
      }
      if ((flags & 0x20) != 0) {
        r.stringIntByte(); // section text
        r.skip(4); // section color
      }
      if ((flags & 0x40) != 0) {
        r.s1(); // key signature
        r.u1(); // key type
      }
      if (version >= 500 && (flags & 0x03) != 0) {
        r.skip(4); // time signature beams
      }
      if (version >= 500) {
        r.u1(); // alternate endings (GP5)
        r.u1(); // triplet feel
        r.u1(); // unknown
      }
      if (bar.numerator != 4 || bar.denominator != 4) {
        warn("time signature " + bar.numerator + "/" + bar.denominator
            + " — bars rendered as 4/4 (bar lengths are normalized)");
      }
      masterBars.add(bar);
    }

    /* -- tracks ------------------------------------------------------ */

    private void readTracks() {
      for (int i = 0; i < trackCount; i++) {
        tracks.add(readTrack());
      }
    }

    private Track readTrack() {
      int flags = r.u1();
      String name = r.stringByteLength(40);
      boolean percussion = (flags & 0x01) != 0;
      int stringCount = r.u4();
      guard(stringCount, 8, "string count");
      int[] tunings = new int[stringCount];
      for (int i = 0; i < 7; i++) {
        int tuning = r.u4();
        if (i < stringCount) {
          tunings[i] = tuning;
        }
      }
      r.u4(); // port
      r.u4(); // channel
      r.u4(); // effect channel
      r.skip(4); // fret count
      r.u4(); // capo
      r.skip(4); // color

      if (version >= 500) {
        r.u1(); // staff flags
        r.u1(); // midi auto flags
        r.u1(); // rse accentuation
        r.u1(); // bank
        r.u1(); // human playing
        r.u4(); // clef mode
        r.u4(); // unknown
        r.u4(); // unknown
        r.skip(10);
        r.u1(); // unknown
        r.u1(); // unknown
        r.skip(16); // rse bank
        if (version >= 510) {
          r.skip(4); // 3-band eq
          r.stringIntByte(); // effect name
          r.stringIntByte(); // effect category
        }
      }
      return new Track(name, percussion, stringCount, tunings);
    }

    /* -- bars -> voices -> beats -> notes ---------------------------- */

    private void readBars() {
      for (int b = 0; b < barCount; b++) {
        for (int t = 0; t < trackCount; t++) {
          Track track = tracks.get(t);
          List<Beat> voice = readBar(track);
          track.bars.add(voice);
        }
      }
    }

    /** Reads one bar chunk for a track and returns its first voice's beats. */
    private List<Beat> readBar(Track track) {
      if (version >= 500) {
        r.u1(); // voice header
        List<Beat> first = readVoice(track);
        readVoice(track); // second voice (usually empty)
        return first;
      }
      return readVoice(track);
    }

    private List<Beat> readVoice(Track track) {
      int beatCount = r.u4();
      if (beatCount == 0) {
        return new ArrayList<>();
      }
      guard(beatCount, MAX_BEATS, "beat count");
      List<Beat> beats = new ArrayList<>(beatCount);
      for (int i = 0; i < beatCount; i++) {
        beats.add(readBeat(track));
      }
      return beats;
    }

    private Beat readBeat(Track track) {
      Beat beat = new Beat();
      int flags = r.u1();
      beat.dotted = (flags & 0x01) != 0;
      if ((flags & 0x40) != 0) {
        int type = r.u1();
        beat.empty = (type & 0x02) == 0;
      }
      beat.durationValue = r.s1();
      if ((flags & 0x20) != 0) {
        int tupletNumerator = r.u4();
        switch (tupletNumerator) {
          case 3 -> {
            beat.tupletNumerator = 3;
            beat.tupletDenominator = 2;
          }
          case 5, 6, 7 -> {
            beat.tupletNumerator = tupletNumerator;
            beat.tupletDenominator = 4;
          }
          case 9, 10, 11, 12, 13 -> {
            beat.tupletNumerator = tupletNumerator;
            beat.tupletDenominator = 8;
          }
          default -> warn("unusual tuplet (" + tupletNumerator + ") ignored");
        }
      }
      if ((flags & 0x02) != 0) {
        readChord();
      }
      if ((flags & 0x04) != 0) {
        r.stringIntUnused(); // beat text
      }
      if ((flags & 0x08) != 0) {
        readBeatEffects(beat);
      }
      if ((flags & 0x10) != 0) {
        readMixTableChange();
      }
      int stringFlags = r.u1();
      for (int i = 6; i >= 0; i--) {
        if ((stringFlags & (1 << i)) != 0) {
          NoteEvent note = readNote(track, 6 - i, beat.tap);
          if (note != null) {
            beat.notes.add(note);
          }
        }
      }
      if (version >= 500) {
        int flags2 = r.u2();
        if ((flags2 & 0x800) != 0) {
          r.u1(); // break secondary beams
        }
      }
      return beat;
    }

    private void readChord() {
      if (version >= 500) {
        r.skip(17);
        r.stringByteLength(21); // name
        r.skip(4);
        r.u4(); // first fret
        for (int i = 0; i < 7; i++) {
          r.u4(); // fret
        }
        int barres = r.u1();
        r.skip(5); // barre frets
        r.skip(26);
      } else if (r.u1() != 0) {
        if (version >= 400) {
          r.skip(16);
          r.stringByteLength(21); // name
          r.skip(4);
          r.u4(); // first fret
          for (int i = 0; i < 7; i++) {
            r.u4(); // fret
          }
          r.u1(); // barres
          r.skip(5);
          r.skip(26);
        } else {
          r.skip(25);
          r.stringByteLength(34); // name
          r.u4(); // first fret
          for (int i = 0; i < 6; i++) {
            r.u4(); // fret
          }
          r.skip(36);
        }
      } else {
        int strings = version >= 406 ? 7 : 6;
        r.stringIntByte(); // name
        int firstFret = r.u4();
        if (firstFret > 0) {
          for (int i = 0; i < strings; i++) {
            r.u4(); // fret
          }
        }
      }
    }

    private void readBeatEffects(Beat beat) {
      int flags = r.u1();
      int flags2 = version >= 400 ? r.u1() : 0;
      if ((flags & 0x20) != 0) {
        int slap = r.s1(); // 1 = tap, 2 = slap, 3 = pop
        beat.tap = slap == 1;
        if (version < 400) {
          r.skip(4);
        }
      }
      if (flags2 != 0 && (flags2 & 0x04) != 0) {
        readTremoloBar();
      }
      if ((flags & 0x40) != 0) {
        if (version < 500) {
          r.u1(); // stroke down
          r.u1(); // stroke up
        } else {
          r.u1(); // stroke up
          r.u1(); // stroke down
        }
      }
      if (flags2 != 0 && (flags2 & 0x02) != 0) {
        r.s1(); // pick stroke
      }
      if (version < 400 && ((flags & 0x04) != 0 || (flags & 0x08) != 0)) {
        warn("harmonics are rendered as normal notes for now");
      }
    }

    private void readTremoloBar() {
      r.u1(); // type
      r.u4(); // value
      int pointCount = r.u4();
      guard(pointCount, MAX_BEND_POINTS, "tremolo bar points");
      for (int i = 0; i < pointCount; i++) {
        r.u4(); // offset
        r.u4(); // value
        r.u1(); // vibrato
      }
    }

    private void readMixTableChange() {
      r.s1(); // instrument
      if (version >= 500) {
        r.skip(16); // rse bank
      }
      int volume = r.s1();
      int balance = r.s1();
      int chorus = r.s1();
      int reverb = r.s1();
      int phaser = r.s1();
      int tremolo = r.s1();
      if (version >= 500) {
        r.stringIntByte(); // tempo name
      }
      int tempo = r.u4();
      if (volume >= 0) {
        r.u1();
      }
      if (balance >= 0) {
        r.u1();
      }
      if (chorus >= 0) {
        r.u1();
      }
      if (reverb >= 0) {
        r.u1();
      }
      if (phaser >= 0) {
        r.u1();
      }
      if (tremolo >= 0) {
        r.u1();
      }
      if (tempo >= 0) {
        r.s1(); // duration
        if (version >= 510) {
          r.u1(); // hide tempo
        }
        warn("tempo change to " + tempo + " BPM ignored — one tempo per import");
      }
      if (version >= 400) {
        r.u1(); // mix table flags
      }
      if (version >= 500) {
        r.s1(); // wah type
      }
      if (version >= 510) {
        r.stringIntByte(); // unknown
        r.stringIntByte(); // unknown
      }
    }

    /**
     * Reads one note. Returns null when it should not be rendered (no fret,
     * tie destination) — the stream is always consumed fully.
     */
    private NoteEvent readNote(Track track, int gpStringIndex, boolean tap) {
      int flags = r.u1();
      boolean dead = false;
      boolean tie = false;
      double velocity = 0.0; // 0 = default pluck strength
      if ((flags & 0x20) != 0) {
        int noteType = r.u1();
        dead = noteType == 3;
        tie = noteType == 2;
      }
      if ((flags & 0x01) != 0 && version < 500) {
        r.skip(2); // duration + tuplet
      }
      if ((flags & 0x10) != 0) {
        int dynamic = r.s1(); // 1 = PPP .. 8 = FFF
        velocity = dynamic <= 3 ? 0.45 : dynamic >= 6 ? 1.0 : 0.0;
      }
      int fret = -1;
      if ((flags & 0x20) != 0) {
        fret = r.s1();
        if (fret < 0) {
          dead = true;
          fret = 0;
        }
      }
      if ((flags & 0x80) != 0) {
        r.skip(2); // left/right hand fingers
      }
      NoteEvent note = new NoteEvent(5 - gpStringIndex, fret, dead);
      note.tap = tap;
      if ((flags & 0x04) != 0) {
        velocity = 0.45; // ghost note
      } else if ((flags & 0x02) != 0) {
        velocity = 1.0; // heavy accent
      }
      note.velocity = velocity;
      if (version >= 500) {
        if ((flags & 0x01) != 0) {
          r.skip(8); // duration percent (float64 LE)
        }
        r.u1(); // flags2 (accidental swap)
      }
      if ((flags & 0x08) != 0) {
        readNoteEffects(note);
      }

      if (gpStringIndex >= 6) {
        warn("7-string notes skipped (6-line tab)");
        return null;
      }
      if (!dead && fret < 0) {
        return null; // no fret -> Guitar Pro does not show it either
      }
      if (tie) {
        warn("tied notes collapsed to ring-outs");
        return null;
      }
      note.fret = dead ? -1 : fret; // GP 0 = high e -> app 5; GP 5 = low E -> app 0
      return note;
    }

    private void readNoteEffects(NoteEvent note) {
      int flags = r.u1();
      int flags2 = version >= 400 ? r.u1() : 0;
      if ((flags & 0x01) != 0) {
        note.bendSemitones = readBend();
      }
      if ((flags & 0x02) != 0) {
        note.hammerPull = true; // hammer-on/pull-off origin
      }
      if ((flags & 0x10) != 0) {
        readGrace(note);
      }
      if (flags2 != 0 && (flags2 & 0x04) != 0) {
        note.tremolo = true; // tremolo picking: speed byte read below
        r.u1();
      }
      if (flags2 != 0 && (flags2 & 0x08) != 0) {
        int type = r.s1(); // slide
        if (version >= 500) {
          if ((type & 4) != 0) {
            note.slideOut = 2; // out-down
          } else if ((type & (1 | 2 | 8)) != 0) {
            note.slideOut = 1; // shift / legato / out-up -> up token
          }
        } else if (type == 1 || type == 2 || type == 4) {
          note.slideOut = 1;
        } else if (type == 3) {
          note.slideOut = 2;
        }
      }
      if (flags2 != 0 && (flags2 & 0x10) != 0) {
        readArtificialHarmonic(note);
      }
      if (flags2 != 0 && (flags2 & 0x20) != 0) {
        int trillFret = r.u1();
        int speed = r.u1();
        note.trillFret = trillFret;
        note.trillStep = effectStepQuarters(speed);
      }
      if (flags2 != 0 && (flags2 & 0x02) != 0) {
        note.palmMute = true;
      }
      if (flags2 != 0 && (flags2 & 0x40) != 0) {
        note.vibrato = true;
      }
    }

    /** @return the bend's maximum semitones (raw bend values are 25 per semitone) */
    private double readBend() {
      r.u1(); // type
      r.u4(); // value
      int pointCount = r.u4();
      guard(pointCount, MAX_BEND_POINTS, "bend points");
      double maxSemitones = 0;
      for (int i = 0; i < pointCount; i++) {
        r.u4(); // offset
        int value = r.u4();
        maxSemitones = Math.max(maxSemitones, Math.abs(value) / 25.0);
        r.u1(); // vibrato
      }
      return maxSemitones;
    }

    private void readGrace(NoteEvent note) {
      int fret = r.s1();
      r.s1(); // dynamic
      r.s1(); // transition
      int duration = r.u1();
      if (version >= 500) {
        r.u1(); // grace flags
      }
      if (fret >= 0) {
        note.graceFret = fret;
        note.graceStep = effectStepQuarters(duration);
      }
    }

    /** Duration-value byte (0..4 -> eighth..64th) as quarters, with a 16th fallback. */
    private double effectStepQuarters(int speed) {
      return switch (speed) {
        case 0 -> 0.5;
        case 1 -> 0.25;
        case 2 -> 0.125;
        case 3 -> 0.0625;
        case 4 -> 0.03125;
        default -> 0.25;
      };
    }

    private void readArtificialHarmonic(NoteEvent note) {
      int type = r.u1();
      if (version >= 500) {
        if (type == 2) {
          r.skip(3); // tone, key, octave
        } else if (type == 3) {
          r.u1(); // fret
        }
      }
      note.harmonic = true;
    }

    /* -- ASCII generation -------------------------------------------- */

    /**
     * Picks the first non-percussion 6-string track and lays its first voice
     * out on a per-meter column grid (16 columns for 4/4).
     */
    private GpSong buildSong() {
      Track selected = null;
      for (Track t : tracks) {
        if (!t.percussion && t.stringCount == 6) {
          selected = t;
          break;
        }
      }
      if (selected == null) {
        for (Track t : tracks) {
          if (!t.percussion) {
            selected = t;
            break;
          }
        }
      }
      if (selected == null) {
        throw new IllegalArgumentException("no playable track found");
      }
      if (selected.stringCount != 6) {
        warn("track has " + selected.stringCount + " strings — mapped onto 6 lines");
      }
      int[] standard = {40, 45, 52, 57, 60, 64}; // E2 A2 D3 G3 B3 E4
      for (int s = 0; s < Math.min(6, selected.stringCount); s++) {
        if (selected.tunings[s] != 0 && selected.tunings[s] != standard[s]) {
          warn("custom tuning ignored — imported as STANDARD");
          break;
        }
      }

      StringBuilder out = new StringBuilder();
      out.append("# GP import: ").append(title.isEmpty() ? "untitled" : title)
          .append(artist.isEmpty() ? "" : " — " + artist)
          .append(" · track: ").append(selected.name)
          .append(" · ").append(bpm).append(" BPM\n");
      for (String w : warnings) {
        out.append("# ").append(w).append('\n');
      }

      // time signatures: warn when they can't be honored exactly
      java.util.Set<String> meters = new java.util.HashSet<>();
      for (MasterBar mb : masterBars) {
        meters.add(mb.numerator + "/" + mb.denominator);
      }
      if (meters.size() > 1) {
        warn("mixed time signatures (" + String.join(", ", meters)
            + ") — all bars play at equal lengths");
      } else if (!meters.contains("4/4")) {
        warn("time signature " + meters.iterator().next() + " — bars play as 4 beats");
      }

      int barIndex = 0;
      // Pass 1: resolve every bar's placements (sustain fills + separation)
      // to find the uniform render width — the tab parser normalizes time by
      // the median bar width, so all bars must render equally wide.
      int barWidth = 0;
      for (int bar = 0; bar < barCount; bar++) {
        MasterBar mb = masterBars.get(bar);
        List<Beat> beats = selected.bars.size() > bar ? selected.bars.get(bar) : List.of();
        barWidth = Math.max(barWidth, renderBarInto(null, beats, mb));
      }
      while (barIndex < barCount) {
        int barsInLine = Math.min(4, barCount - barIndex);
        StringBuilder[] lines = new StringBuilder[6];
        for (int s = 0; s < 6; s++) {
          lines[s] = new StringBuilder("EADGBe".charAt(s) + "|");
        }
        for (int b = 0; b < barsInLine; b++) {
          MasterBar mb = masterBars.get(barIndex + b);
          List<Beat> beats = selected.bars.size() > barIndex + b
              ? selected.bars.get(barIndex + b)
              : List.of();
          char[][] cells = new char[6][barWidth];
          for (int s = 0; s < 6; s++) {
            java.util.Arrays.fill(cells[s], ' ');
          }
          renderBarInto(cells, beats, mb);
          for (int s = 0; s < 6; s++) {
            lines[s].append(cells[s]).append('|');
          }
        }
        for (int s = 0; s < 6; s++) {
          out.append(lines[s]).append('\n');
        }
        out.append('\n');
        barIndex += barsInLine;
      }

      List<int[]> perBarMeters = new ArrayList<>();
      for (MasterBar mb : masterBars) {
        perBarMeters.add(new int[]{mb.numerator, mb.denominator});
      }
      return new GpSong(out.toString().stripTrailing(), Math.max(40, Math.min(300, bpm)),
          title, selected.name, List.copyOf(warnings), List.copyOf(perBarMeters));
    }

    /**
     * Renders one bar into `cells` (may be null for the measuring pass). The
     * grid scale comes from the bar's time signature (columns = numerator x
     * 16 / denominator, so 4/4 = 16 sixteenth-columns). Notes repeat their
     * digit across their duration (tab convention for held notes), up to the
     * next event on the same string minus one separating column. Notes on
     * the same string always keep at least one empty column between tokens
     * (the tab grammar reads adjacent digits as one fret). Returns the
     * column count the bar's content needs.
     */
    private int renderBarInto(char[][] cells, List<Beat> beats, MasterBar mb) {
      boolean measure = cells == null;
      int cols = Math.max(8, mb.numerator * 16 / mb.denominator);
      double quartersAvailable = cols / 4.0;
      if (measure) {
        cells = new char[6][cols + 32]; // headroom for separation pushes
      }
      for (int s = 0; s < 6; s++) {
        java.util.Arrays.fill(cells[s], ' ');
      }
      // content longer than the meter (common in hand-made files) is scaled
      // into the grid instead of dropping the excess notes
      double total = 0;
      for (Beat beat : beats) {
        total += durationQuarters(beat);
      }
      double scale = total > quartersAvailable ? (double) (cols - 1) / total
          : cols / quartersAvailable;

      // per-string placed notes in time order
      @SuppressWarnings("unchecked")
      List<NoteEvent>[] byString = new List[6];
      for (int s = 0; s < 6; s++) {
        byString[s] = new ArrayList<>();
      }
      double pos = 0;
      for (Beat beat : beats) {
        double quarters = durationQuarters(beat);
        for (NoteEvent note : beat.notes) {
          if (note.stringIndex < 0 || note.stringIndex > 5) {
            continue;
          }
          List<NoteEvent> line = byString[note.stringIndex];
          if (note.trillFret >= 0 && note.trillFret != note.fret) {
            // hammer/pull alternation trill: digits separated by h/p markers,
            // one empty column between pairs (grid-quantized speed)
            int base = (int) Math.round(pos * scale);
            int span = (int) Math.round(quarters * scale);
            List<NoteEvent> trillNotes = new ArrayList<>();
            int cur = base;
            int k = 0;
            while (cur < base + span) {
              int fret = k % 2 == 0 ? note.fret : note.trillFret;
              int len = Integer.toString(fret).length();
              if (cur + len > base + span) {
                break;
              }
              NoteEvent t = new NoteEvent(note.stringIndex, fret, false);
              t.col = cur;
              t.hammerPull = true; // corrected for the last one below
              t.velocity = note.velocity;
              trillNotes.add(t);
              cur += len + 2; // marker column + one empty separator
              k++;
            }
            if (!trillNotes.isEmpty()) {
              trillNotes.get(trillNotes.size() - 1).hammerPull = false;
              line.addAll(trillNotes);
            }
          } else {
            if (note.graceFret >= 0) {
              // ornamental pre-note two columns before the main note (its own
              // ghost marker occupies the gap), stealing no time
              int graceCol = note.col - (Integer.toString(note.graceFret).length() + 1);
              if (graceCol >= 0) {
                NoteEvent g = new NoteEvent(note.stringIndex, note.graceFret, false);
                g.col = graceCol;
                g.velocity = 0.45;
                line.add(g);
              } else if (!warnedGraceSkip) {
                warnedGraceSkip = true;
                warn("grace note omitted (no room before the beat)");
              }
            }
            note.col = (int) Math.round(pos * scale);
            line.add(note);
          }
        }
        pos += quarters;
      }

      int extent = 0;
      boolean warnedSkip = warnedArticulationSkip;
      for (int s = 0; s < 6; s++) {
        List<NoteEvent> placed = byString[s];
        int prevEnd = -1; // exclusive column after the last glyph/fill/token
        for (int k = 0; k < placed.size(); k++) {
          NoteEvent note = placed.get(k);
          boolean dead = note.dead;
          String glyph = dead ? "x" : Integer.toString(note.fret);
          int start = prevEnd >= 0 ? Math.max(note.col, prevEnd + 1) : Math.max(note.col, 0);
          // pre-note markers: velocity (g/*), harmonic (<), tremolo (T) or
          // tap (t), 1 char + one separating column; higher wins
          char pre = note.velocity != 0.0 ? (note.velocity < 0.9 ? 'g' : '*')
              : note.harmonic ? '<' : note.tremolo ? 'T' : note.tap ? 't' : 0;
          if (pre != 0 && start - (prevEnd + 1) >= 2 && start >= 2) {
            cells[s][start - 2] = pre;
          }
          for (int c = start; c < start + glyph.length() && c < cells[s].length; c++) {
            cells[s][c] = glyph.charAt(c - start);
          }
          int last = start + glyph.length() - 1;

          // inter-note articulation token between this note and the next:
          // hammer/pull, slide, bend (priority order). Tokens suppress the
          // sustain fill — the note rings into the target instead.
          NoteEvent next = k + 1 < placed.size() ? placed.get(k + 1) : null;
          String token = null;
          if (!dead && note.bendSemitones != 0) {
            token = "b" + (note.fret + (int) Math.round(note.bendSemitones));
          } else if (!dead && note.hammerPull && next != null && !next.dead) {
            token = next.fret >= note.fret ? "h" : "p";
          } else if (!dead && note.slideOut != 0) {
            token = note.slideOut == 2 ? "\\" : "/";
          }
          if (token != null && next != null) {
            int tokenStart = next.col - 1 - token.length();
            if (tokenStart <= last) {
              tokenStart = next.col - token.length(); // adjacent fallback (no separating space)
            }
            if (tokenStart > last) {
              for (int c = tokenStart; c < tokenStart + token.length() && c < cells[s].length; c++) {
                cells[s][c] = token.charAt(c - tokenStart);
              }
              last = tokenStart + token.length() - 1;
            } else if (!warnedSkip) {
              warnedArticulationSkip = warnedSkip = true;
              warn("articulation tokens omitted — bar too dense");
            }
          } else if (!dead && glyph.length() == 1 && !note.palmMute) {
            // sustain fill: repeat the digit across the note's duration,
            // reserving room for the next note's markers (last cell of the
            // meter is reserved for the harmonic's closing '>')
            int reserve = next != null
                && (next.tap || next.palmMute || next.velocity != 0.0 || next.tremolo
                    || next.harmonic) ? 4 : 2;
            int fillEnd = next != null ? Math.min(next.col - reserve, cols - 1)
                : note.harmonic ? cols - 2 : cols - 1;
            for (int c = start + 1; c <= fillEnd; c++) {
              cells[s][c] = glyph.charAt(0);
            }
            last = Math.max(last, fillEnd);
          }

          // post-note markers: the harmonic's closing '>', then vibrato
          if (!dead && note.harmonic && last + 1 < cells[s].length) {
            cells[s][last + 1] = '>';
            last = last + 1;
          } else if (note.vibrato && !dead && last + 1 < cells[s].length) {
            cells[s][last + 1] = '~';
            last = last + 1;
          }

          // palm-mute region markers around runs of muted notes: the opener
          // sits before the run's first note, the closer after its last
          boolean runStart = k == 0 || !placed.get(k - 1).palmMute;
          boolean runEnd = next == null || !next.palmMute;
          if (note.palmMute && runStart && prevEnd <= start - 3 && start >= 3) {
            cells[s][start - 3] = 'P';
            cells[s][start - 2] = 'M';
            prevEnd = start - 1;
          } else if (note.palmMute && runStart && !warnedSkip) {
            warnedArticulationSkip = warnedSkip = true;
            warn("articulation tokens omitted — bar too dense");
          }
          if (note.palmMute && runEnd && last + 3 < cells[s].length) {
            cells[s][last + 2] = 'P';
            cells[s][last + 3] = 'M';
            prevEnd = last + 4;
          }
          prevEnd = Math.max(prevEnd, last + 1);
          extent = Math.max(extent, prevEnd);
        }
      }
      return Math.max(extent, cols);
    }

    private double durationQuarters(Beat beat) {
      double quarters = switch (beat.durationValue) {
        case -2 -> 4.0;
        case -1 -> 2.0;
        case 0 -> 1.0;
        case 1 -> 0.5;
        case 2 -> 0.25;
        case 3 -> 0.125;
        case 4 -> 0.0625;
        default -> {
          warn("unknown beat duration (" + beat.durationValue + ") treated as quarter");
          yield 1.0;
        }
      };
      if (beat.dotted) {
        quarters *= 1.5;
      }
      if (beat.tupletDenominator > 1) {
        quarters *= (double) beat.tupletDenominator / beat.tupletNumerator;
      }
      return quarters;
    }
  }
}
