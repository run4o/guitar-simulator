package dev.guitar.tabplayer.tab;

import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ASCII tab parser and timing compiler (Phase 3).
 *
 * <p>Parsing model:
 * <ul>
 *   <li>String lines are detected by their markers ({@code e|, B|, G|, D|, A|, E|});
 *       consecutive string lines form a system, duplicate labels start a new system.</li>
 *   <li>Each line is scanned into tokens: fret digits, articulation markers
 *       (h/p/t, / \ s, b target, r release), palm-mute regions (PM...PM),
 *       dead notes (x), bar lines (|), harmonics (( ) &lt; &gt;).</li>
 *   <li>Events on the same column across strings form chords and receive a
 *       low-to-high strum stagger.</li>
 * </ul>
 *
 * <p>Timing model (v1 heuristic — ASCII tabs do not encode timing explicitly):
 * <ol>
 *   <li>If a system has at least two bar lines and the median bar width is at
 *       least 8 columns, one bar is assumed to last 4 beats (4/4) at the given
 *       BPM: tick = 4·beat / barColumns.</li>
 *   <li>Otherwise a uniform density is assumed: one column = one sixteenth
 *       note. BPM is user-adjustable, which re-scales everything.</li>
 * </ol>
 *
 * <p>Output: a sorted timeline of engine {@link RenderCommand}s (usable for
 * both offline WAV rendering and real-time scheduling) plus the total duration
 * and human-readable warnings.
 */
public final class TabParser {

  public static final double DEFAULT_BPM = 120.0;

  private static final double CHORD_STAGGER_SECONDS = 0.012;
  private static final double RING_OUT_SECONDS = 2.5;
  private static final int MAX_FRET = 24;
  private static final int MIN_BAR_COLUMNS = 8;

  /**
   * Compiled result: engine-ready events + duration + warnings.
   */
  public record TabEvent(RenderCommand command, int charPos) {}

  /**
   * One bar's position on the compiled timeline (for score-view rendering).
   */
  public record BarTime(double startSeconds, double endSeconds, int systemIndex, int barIndex) {}

  /**
   * A bar (or bar range) as columns within one parsed system — the unit for block ops.
   */
  public record BarSpan(TabSystem system, int barIndex, int startCol, int endColEx) {}

  /**
   * Public structural view of one parsed system (for block ops + the score view).
   */
  public record TabSystem(int index, int[] stringIndices, char[] labels, int[] contentOffsets, int[] contentLengths,
                          int width) {}

  public record TabTimeline(List<TabEvent> events, List<RenderCommand> commands, List<BarTime> barTimes,
                            double durationSeconds, List<String> warnings, List<int[]> timeSignatures) {}

  private enum Kind {
    NOTE,
    GHOST,
    ACCENT,
    HAMMER,
    PULL,
    TAP,
    SLIDE,
    BEND,
    BEND_RELEASE,
    VIBRATO,
    DEAD,
    BAR,
    HARMONIC
  }

  private record Token(int column, Kind kind, int fret) {}

  private static final class StringTrack {
    final int stringIndex;
    final List<Token> tokens = new ArrayList<>();
    final List<Integer> barColumns = new ArrayList<>();
    final List<int[]> palmMuteRanges = new ArrayList<>();
    int sourceOffset; // absolute char index in the source text where content starts
    int contentLength; // length of this line's content
    char label;        // original label character (e/B/G/D/A/E)

    StringTrack(int stringIndex) {
      this.stringIndex = stringIndex;
    }

    boolean inPalmMute(int column) {
      for (int[] range : palmMuteRanges) {
        if (column >= range[0] && column < range[1]) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class SystemBlock {
    final StringTrack[] tracks = new StringTrack[6];
    int width;
  }

  /**
   * Label before the first '|': case matters (e = high E, E = low E).
   */
  public static final Pattern LABEL_LINE = Pattern.compile("^\\s*([eEbBgGdDaA])\\s*\\|(.*)$");

  private TabParser() {}

  public static TabTimeline compile(String text) {
    return compile(text, DEFAULT_BPM);
  }

  public static TabTimeline compile(String text, double bpm) {
    return compile(text, bpm, null);
  }

  /**
   * @param timeSignatures sparse bar-index -> {numerator, denominator} map
   *     (null or missing bars default to 4/4); used by the score view's
   *     meter display. Bars beyond the map default too.
   */
  public static TabTimeline compile(String text, double bpm, java.util.Map<Integer, int[]> timeSignatures) {
    if (bpm <= 0) {
      bpm = DEFAULT_BPM;
    }
    Set<String> warnings = new LinkedHashSet<>();
    List<SystemBlock> systems = parseSystems(text, warnings);

    List<TabEvent> events = new ArrayList<>();
    List<BarTime> barTimes = new ArrayList<>();
    double time = 0.0;
    for (int sysIndex = 0; sysIndex < systems.size(); sysIndex++) {
      SystemBlock system = systems.get(sysIndex);
      double tick = tickForSystem(system, bpm);
      int barIndex = 0;
      for (int[] range : barRanges(system)) {
        barTimes.add(new BarTime(time + range[0] * tick, time + range[1] * tick, sysIndex, barIndex++));
      }
      compileSystem(system, time, tick, events, warnings);
      time += system.width * tick;
    }
    events.sort(Comparator.comparingDouble(e -> e.command().timeOffset()));
    List<RenderCommand> commands = new ArrayList<>(events.size());
    for (TabEvent event : events) {
      commands.add(event.command());
    }
    double duration = commands.isEmpty() ? 1.0 : commands.get(commands.size() - 1).timeOffset() + RING_OUT_SECONDS;
    // dense per-bar time signatures (imported meters; 4/4 elsewhere)
    List<int[]> timeSigs = new ArrayList<>();
    for (int i = 0; i < barTimes.size(); i++) {
      int[] sig = timeSignatures != null ? timeSignatures.get(i) : null;
      timeSigs.add(sig != null && sig.length == 2 && sig[0] > 0 && sig[1] > 0
          ? new int[]{sig[0], sig[1]} : new int[]{4, 4});
    }
    return new TabTimeline(List.copyOf(events), List.copyOf(commands), List.copyOf(barTimes), duration,
        List.copyOf(warnings), List.copyOf(timeSigs));
  }

  /* ------------------------------------------------------------------
   * Structure + bar spans (public API for block ops and the score view)
   * ------------------------------------------------------------------ */

  /**
   * Parses the text into public system views.
   */
  public static List<TabSystem> systems(String text) {
    List<SystemBlock> parsed = parseSystems(text, new LinkedHashSet<>());
    List<TabSystem> out = new ArrayList<>();
    for (int i = 0; i < parsed.size(); i++) {
      SystemBlock sys = parsed.get(i);
      List<Integer> indices = new ArrayList<>();
      for (int s = 0; s < 6; s++) {
        if (sys.tracks[s] != null) {
          indices.add(s);
        }
      }
      int[] stringIndices = indices.stream().mapToInt(Integer::intValue).toArray();
      char[] labels = new char[stringIndices.length];
      int[] offsets = new int[stringIndices.length];
      int[] lengths = new int[stringIndices.length];
      for (int t = 0; t < stringIndices.length; t++) {
        StringTrack track = sys.tracks[stringIndices[t]];
        labels[t] = track.label;
        offsets[t] = track.sourceOffset;
        lengths[t] = track.contentLength;
      }
      out.add(new TabSystem(i, stringIndices, labels, offsets, lengths, sys.width));
    }
    return out;
  }

  private static int contentLength(SystemBlock sys, int stringIndex) {
    // content length is not stored on tokens; recover from source offsets is
    // impossible here, so callers only need a lower bound — width is authoritative.
    return sys.width;
  }

  /**
   * All bars across systems, in play order.
   */
  public static List<BarSpan> barSpans(String text) {
    List<TabSystem> systems = systems(text);
    List<SystemBlock> parsed = parseSystems(text, new LinkedHashSet<>());
    List<BarSpan> spans = new ArrayList<>();
    for (int i = 0; i < parsed.size() && i < systems.size(); i++) {
      int barIndex = 0;
      for (int[] range : barRanges(parsed.get(i))) {
        spans.add(new BarSpan(systems.get(i), barIndex++, range[0], range[1]));
      }
    }
    return spans;
  }

  /**
   * Union of bar separator columns for a system, then bar column ranges.
   */
  private static List<int[]> barRanges(SystemBlock system) {
    TreeSet<Integer> bars = new TreeSet<>();
    for (StringTrack track : system.tracks) {
      if (track != null) {
        bars.addAll(track.barColumns);
      }
    }
    List<int[]> ranges = new ArrayList<>();
    if (bars.size() >= 2) {
      int start = 0;
      for (int bar : bars) {
        ranges.add(new int[]{start, bar + 1}); // include the separator
        start = bar + 1;
      }
      if (start < system.width) {
        ranges.add(new int[]{start, system.width});
      }
    } else {
      // no (or one) bar lines: fixed 16-column blocks
      for (int start = 0; start < system.width; start += 16) {
        ranges.add(new int[]{start, Math.min(start + 16, system.width)});
      }
    }
    if (ranges.isEmpty() && system.width > 0) {
      ranges.add(new int[]{0, system.width});
    }
    return ranges;
  }

  /**
   * Map a label character to a string index (E=0..e=5), or -1 when unknown.
   */
  public static int stringIndexOf(char label) {
    switch (label) {
      case 'E':
        return 0;
      case 'A':
        return 1;
      case 'D':
        return 2;
      case 'G':
        return 3;
      case 'B':
        return 4;
      case 'e':
        return 5;
      case 'a':
        return 1;
      case 'd':
        return 2;
      case 'g':
        return 3;
      case 'b':
        return 4;
      default:
        return -1;
    }
  }

  /* ------------------------------------------------------------------
   * Structure detection
   * ------------------------------------------------------------------ */

  private static List<SystemBlock> parseSystems(String text, Set<String> warnings) {
    List<SystemBlock> systems = new ArrayList<>();
    SystemBlock current = null;
    Set<Integer> usedInRun = new HashSet<>();

    int lineStartOffset = 0;
    for (String line : text.split("\\R", -1)) {
      Matcher matcher = LABEL_LINE.matcher(line);
      if (!matcher.matches()) {
        current = null;
        usedInRun.clear();
      } else {
        int index = stringIndexFor(matcher.group(1), warnings);
        if (index >= 0) {
          if (current == null || usedInRun.contains(index)) {
            current = new SystemBlock();
            systems.add(current);
            usedInRun.clear();
          }
          usedInRun.add(index);
          StringTrack track = new StringTrack(index);
          current.tracks[index] = track;
          String content = matcher.group(2);
          track.label = matcher.group(1).charAt(0);
          track.sourceOffset = lineStartOffset + matcher.start(2);
          track.contentLength = content.length();
          scanLine(content, track, warnings);
          current.width = Math.max(current.width, content.length());
        }
      }
      // advance to the start of the next line (accounting for \r\n, \n, \r, ...)
      lineStartOffset += line.length();
      if (lineStartOffset < text.length()) {
        char c = text.charAt(lineStartOffset);
        if (c == '\r' && lineStartOffset + 1 < text.length() && text.charAt(lineStartOffset + 1) == '\n') {
          lineStartOffset += 2;
        } else {
          lineStartOffset += 1;
        }
      }
    }

    systems.removeIf(system -> {
      for (StringTrack track : system.tracks) {
        if (track != null && !track.tokens.isEmpty()) {
          return false;
        }
      }
      return true;
    });
    return systems;
  }

  private static int stringIndexFor(String label, Set<String> warnings) {
    switch (label) {
      case "E":
        return 0;
      case "A":
        return 1;
      case "D":
        return 2;
      case "G":
        return 3;
      case "B":
        return 4;
      case "e":
        return 5;
      case "a":
        warnings.add("Lowercase '" + label + "' treated as the A string");
        return 1;
      case "d":
        warnings.add("Lowercase '" + label + "' treated as the D string");
        return 2;
      case "g":
        warnings.add("Lowercase '" + label + "' treated as the G string");
        return 3;
      case "b":
        warnings.add("Lowercase '" + label + "' treated as the B string");
        return 4;
      default:
        return -1;
    }
  }

  /* ------------------------------------------------------------------
   * Token scanning
   * ------------------------------------------------------------------ */

  private static void scanLine(String content, StringTrack track, Set<String> warnings) {
    int n = content.length();
    int i = 0;
    int palmMuteStart = -1;
    boolean tremoloPending = false;
    while (i < n) {
      char c = content.charAt(i);
      if (c >= '0' && c <= '9') {
        int j = i;
        while (j < n && content.charAt(j) >= '0' && content.charAt(j) <= '9') {
          j++;
        }
        // A run of ONE repeated digit is a sustained note (tab convention:
        // hold the digit for the run's length) — a single pluck at the run
        // start, the rest just holds the columns. Mixed runs are multi-digit
        // fret numbers (e.g. "12").
        boolean sameDigit = true;
        for (int k = i + 1; k < j; k++) {
          if (content.charAt(k) != c) {
            sameDigit = false;
            break;
          }
        }
        int fret;
        if (sameDigit) {
          fret = c - '0';
        } else {
          fret = Integer.parseInt(content, i, j, 10);
          if (fret > MAX_FRET) {
            warnings.add("Fret " + fret + " out of range, clamped to " + MAX_FRET);
            fret = MAX_FRET;
          }
        }
        if (tremoloPending && sameDigit) {
          // tremolo picking: the run re-plucks once per digit instead of
          // holding a single sustain
          for (int k = i; k < j; k++) {
            track.tokens.add(new Token(k, Kind.NOTE, fret));
          }
          tremoloPending = false;
        } else {
          track.tokens.add(new Token(i, Kind.NOTE, fret));
        }
        i = j;
      } else if (c == 'b') {
        int target = readImmediateNumber(content, i + 1);
        if (target >= 0) {
          track.tokens.add(new Token(i, Kind.BEND, target));
          i += 1 + digitsAt(content, i + 1);
        } else {
          track.tokens.add(new Token(i, Kind.BEND, -1));
          i++;
        }
      } else if (c == 'r') {
        int target = readImmediateNumber(content, i + 1);
        if (target >= 0) {
          track.tokens.add(new Token(i, Kind.BEND_RELEASE, target));
          i += 1 + digitsAt(content, i + 1);
        } else {
          warnings.add("Bend release 'r' without target ignored");
          i++;
        }
      } else if (c == 'h' || c == 'p' || c == 't' || c == '/' || c == '\\' || c == 's') {
        Kind kind = switch (c) {
          case 'h' -> Kind.HAMMER;
          case 'p' -> Kind.PULL;
          case 't' -> Kind.TAP;
          default -> Kind.SLIDE;
        };
        track.tokens.add(new Token(i, kind, -1));
        i++;
      } else if (c == 'g') {
        // ghost note: next note plucks at reduced strength
        track.tokens.add(new Token(i, Kind.GHOST, -1));
        i++;
      } else if (c == '*') {
        // accent: next note plucks at full strength
        track.tokens.add(new Token(i, Kind.ACCENT, -1));
        i++;
      } else if (c == 'T') {
        // tremolo picking: the next run of same digits re-plucks per digit
        tremoloPending = true;
        i++;
      } else if (c == '~') {
        track.tokens.add(new Token(i, Kind.VIBRATO, -1));
        i++;
      } else if (c == 'x' || c == 'X') {
        track.tokens.add(new Token(i, Kind.DEAD, -1));
        i++;
      } else if (c == '(' || c == '<') {
        track.tokens.add(new Token(i, Kind.HARMONIC, -1));
        i++;
      } else if (c == ')' || c == '>') {
        i++;
      } else if (c == '|') {
        track.barColumns.add(i);
        i++;
      } else if ((c == 'P' || c == 'p') && i + 1 < n && (content.charAt(i + 1) == 'M' || content.charAt(i + 1) ==
                                                                                         'm')) {
        if (palmMuteStart < 0) {
          palmMuteStart = i;
        } else {
          track.palmMuteRanges.add(new int[]{palmMuteStart, i});
          palmMuteStart = -1;
        }
        i += 2;
      } else if (c == '-' || c == ' ') {
        i++;
      } else {
        warnings.add("Unrecognized character '" + c + "' ignored");
        i++;
      }
    }
    if (palmMuteStart >= 0) {
      track.palmMuteRanges.add(new int[]{palmMuteStart, n});
    }
  }

  private static int digitsAt(String s, int from) {
    int j = from;
    while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
      j++;
    }
    return j - from;
  }

  /**
   * Returns the number immediately at `from`, or -1 if there is none.
   */
  private static int readImmediateNumber(String s, int from) {
    if (from >= s.length() || s.charAt(from) < '0' || s.charAt(from) > '9') {
      return -1;
    }
    int end = from + digitsAt(s, from);
    return Integer.parseInt(s, from, end, 10);
  }

  /* ------------------------------------------------------------------
   * Timing + event compilation
   * ------------------------------------------------------------------ */

  private static double tickForSystem(SystemBlock system, double bpm) {
    double beat = 60.0 / bpm;
    TreeSet<Integer> bars = new TreeSet<>();
    for (StringTrack track : system.tracks) {
      if (track != null) {
        bars.addAll(track.barColumns);
      }
    }
    if (bars.size() >= 2) {
      List<Integer> gaps = new ArrayList<>();
      Integer prev = null;
      for (int bar : bars) {
        if (prev != null) {
          gaps.add(bar - prev);
        }
        prev = bar;
      }
      gaps.sort(Integer::compareTo);
      int median = gaps.get(gaps.size() / 2);
      if (median >= MIN_BAR_COLUMNS) {
        return 4 * beat / median; // one bar = 4 beats (4/4 assumed)
      }
    }
    return beat / 4.0; // uniform density: one column = one sixteenth
  }

  private static void compileSystem(SystemBlock system, double start, double tick, List<TabEvent> out,
      Set<String> warnings) {
    Map<Integer, List<TabEvent>> byColumn = new TreeMap<>();
    for (int s = 0; s < 6; s++) {
      StringTrack track = system.tracks[s];
      if (track == null) {
        continue;
      }
      compileTrack(track, start, tick, byColumn, warnings);
    }

    for (List<TabEvent> group : byColumn.values()) {
      Set<Integer> strings = new HashSet<>();
      for (TabEvent event : group) {
        strings.add(event.command().stringIndex());
      }
      if (group.size() >= 2 && strings.size() >= 2) {
        // chord: stagger low-to-high
        List<TabEvent> adjusted = new ArrayList<>(group.size());
        for (TabEvent event : group) {
          RenderCommand cmd = event.command();
          int type = cmd.type();
          if (type == RenderCommandType.PLUCK || type == RenderCommandType.LEGATO) {
            cmd = new RenderCommand(cmd.timeOffset() + (float) (CHORD_STAGGER_SECONDS * cmd.stringIndex()), cmd.type(),
                cmd.stringIndex(), cmd.fret(), cmd.a(), cmd.b());
            adjusted.add(new TabEvent(cmd, event.charPos()));
          } else {
            adjusted.add(event);
          }
        }
        out.addAll(adjusted);
      } else {
        out.addAll(group);
      }
    }
  }

  private static void compileTrack(StringTrack track, double start, double tick, Map<Integer, List<TabEvent>> byColumn,
      Set<String> warnings) {
    Kind pendingArticulation = null;
    double pendingVelocity = 0.0; // 0 = default pluck strength
    boolean seenNote = false;
    int currentFret = -1;

    for (Token token : track.tokens) {
      double time = start + token.column() * tick;
      int charPos = track.sourceOffset + token.column();
      switch (token.kind()) {
        case NOTE -> {
          boolean legato = pendingArticulation != null && seenNote;
          boolean palm = track.inPalmMute(token.column());
          int type = legato ? RenderCommandType.LEGATO : RenderCommandType.PLUCK;
          float strength = (float) (pendingVelocity != 0.0 ? pendingVelocity : legato ? 0.0 : 0.9);
          add(byColumn, token.column(), new TabEvent(
              new RenderCommand((float) time, type, track.stringIndex, token.fret(), strength,
                  palm ? 1.0f : 0.0f), charPos));
          currentFret = token.fret();
          seenNote = true;
          pendingArticulation = null;
          pendingVelocity = 0.0;
        }
        case GHOST -> pendingVelocity = 0.45;
        case ACCENT -> pendingVelocity = 1.0;
        case BEND -> {
          if (token.fret() >= 0 && currentFret >= 0) {
            add(byColumn, token.column(), new TabEvent(
                new RenderCommand((float) time, RenderCommandType.BEND, track.stringIndex, 0,
                    token.fret() - currentFret, 0.0f), charPos));
            currentFret = token.fret();
          } else {
            warnings.add("Bend without target ignored");
          }
        }
        case BEND_RELEASE -> {
          if (token.fret() >= 0 && currentFret >= 0) {
            add(byColumn, token.column(), new TabEvent(
                new RenderCommand((float) time, RenderCommandType.BEND, track.stringIndex, 0,
                    token.fret() - currentFret, 0.0f), charPos));
            currentFret = token.fret();
          }
        }
        case HAMMER, PULL, TAP, SLIDE -> pendingArticulation = token.kind();
        case DEAD -> add(byColumn, token.column(),
            new TabEvent(new RenderCommand((float) time, RenderCommandType.MUTE, track.stringIndex, 0, 0.0f, 0.0f),
                charPos));
        case VIBRATO, HARMONIC, BAR -> {
          if (token.kind() == Kind.VIBRATO) {
            warnings.add("Vibrato (~) is not supported yet and is ignored");
          } else if (token.kind() == Kind.HARMONIC) {
            warnings.add("Harmonics are rendered as normal notes for now");
          }
        }
      }
    }
  }

  private static void add(Map<Integer, List<TabEvent>> byColumn, int column, TabEvent event) {
    byColumn.computeIfAbsent(column, k -> new ArrayList<>(2)).add(event);
  }

  /**
   * Numeric constants mirroring GuitarEngine.EVENT_* (avoids a compile-time dependency cycle in docs).
   */
  private static final class RenderCommandType {
    static final int PLUCK = 0;
    static final int LEGATO = 1;
    static final int BEND = 2;
    static final int MUTE = 3;
  }
}
