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
    REPEAT_START,
    REPEAT_END,
    DEAD,
    BAR,
    HARMONIC,
    TREMOLO,
    GRACE
  }

  private record Token(int column, Kind kind, int fret) {}

  private static final class StringTrack {
    final int stringIndex;
    final List<Token> tokens = new ArrayList<>();
    final List<Integer> barColumns = new ArrayList<>();
    final List<int[]> palmMuteRanges = new ArrayList<>();
    // alternate-ending marks as {owning divider column, ending bitmask}
    final List<int[]> endingMarks = new ArrayList<>();
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
    double bpm; // 0 = inherit the compile BPM
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

    // expand |: ... :|xN sections; alternate endings ([1], [2] marks) choose
    // which ending bars each pass plays, so a pass no longer replays the
    // whole section including every ending
    Map<Integer, int[]> expandedSigs = timeSignatures == null ? null : new TreeMap<>(timeSignatures);
    int runningBars = 0;
    for (SystemBlock system : systems) {
      RepeatInfo info = expandRepeats(system, warnings);
      int barCount = barRanges(system).size();
      if (info != null && expandedSigs != null) {
        int added = barCount - info.originalBars();
        int base = runningBars;
        // pass-1 bars keep their indices; the structure's other original
        // bars re-appear at their chosen pass (inserted below); everything
        // after the structure shifts by the number of inserted bars
        int[] passOneArr = info.passOrigLocal()[0];
        int bodyFirst = passOneArr.length > 0 ? passOneArr[0] : info.originalBars();
        Set<Integer> passOne = new HashSet<>();
        for (int local : passOneArr) {
          passOne.add(local);
        }
        Map<Integer, int[]> shifted = new TreeMap<>();
        for (Map.Entry<Integer, int[]> e : expandedSigs.entrySet()) {
          int idx = e.getKey();
          if (idx < base) {
            shifted.put(idx, e.getValue());
          } else {
            int local = idx - base;
            if (local < bodyFirst) {
              shifted.put(idx, e.getValue());
            } else if (local < info.originalBars()) {
              if (passOne.contains(local)) {
                shifted.put(idx, e.getValue());
              }
            } else {
              shifted.put(idx + added, e.getValue());
            }
          }
        }
        int off = bodyFirst + passOneArr.length;
        for (int k = 1; k < info.passOrigLocal().length; k++) {
          for (int local : info.passOrigLocal()[k]) {
            int[] meter = expandedSigs.get(base + local);
            if (meter != null) {
              shifted.put(base + off, meter);
            }
            off++;
          }
        }
        expandedSigs = shifted;
      }
      runningBars += barCount;
    }
    timeSignatures = expandedSigs;

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
      // repeat expansion can push copies past the literal line width; the
      // system's clock must cover them or the next system overlaps them
      int effWidth = system.width;
      for (StringTrack track : system.tracks) {
        if (track == null) {
          continue;
        }
        if (!track.tokens.isEmpty()) {
          effWidth = Math.max(effWidth, track.tokens.get(track.tokens.size() - 1).column() + 1);
        }
        if (!track.barColumns.isEmpty()) {
          effWidth = Math.max(effWidth, track.barColumns.get(track.barColumns.size() - 1) + 1);
        }
      }
      time += effWidth * tick;
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
    if (!bars.isEmpty()) {
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
    double pendingSystemBpm = 0.0;

    int lineStartOffset = 0;
    for (String line : text.split("\\R", -1)) {
      Matcher matcher = LABEL_LINE.matcher(line);
      if (!matcher.matches()) {
        if (line.trim().startsWith("@")) {
          // tempo directive: the following system plays at that BPM
          try {
            pendingSystemBpm = Double.parseDouble(line.trim().substring(1));
          } catch (NumberFormatException ignored) {
            warnings.add("Tempo directive ignored: " + line.trim());
          }
        }
        current = null;
        usedInRun.clear();
      } else {
        int index = stringIndexFor(matcher.group(1), warnings);
        if (index >= 0) {
          if (current == null || usedInRun.contains(index)) {
            current = new SystemBlock();
            current.bpm = pendingSystemBpm;
            pendingSystemBpm = 0.0;
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
      } else if (c == 'G') {
        // grace note: the next note is a soft ornament (distinct from a
        // lowercase 'g' ghost, which is a full note played quietly)
        track.tokens.add(new Token(i, Kind.GRACE, -1));
        i++;
      } else if (c == '*') {
        // accent: next note plucks at full strength
        track.tokens.add(new Token(i, Kind.ACCENT, -1));
        i++;
      } else if (c == 'T') {
        // tremolo picking: the next run of same digits re-plucks per digit
        track.tokens.add(new Token(i, Kind.TREMOLO, -1));
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
        if (i + 1 < n && content.charAt(i + 1) == ':') {
          // |: repeat section start
          track.tokens.add(new Token(i, Kind.REPEAT_START, -1));
          track.barColumns.add(i);
          i += 2;
        } else {
          track.barColumns.add(i);
          i++;
        }
      } else if (c == ':') {
        if (i + 1 < n && content.charAt(i + 1) == '|') {
          // :| repeat section end, optionally with an xN total-play count
          int plays = 2;
          int j = i + 2;
          if (j < n && (content.charAt(j) == 'x' || content.charAt(j) == 'X')) {
            int digits = digitsAt(content, j + 1);
            if (digits > 0) {
              plays = Integer.parseInt(content, j + 1, j + 1 + digits, 10);
              j += 1 + digits;
            }
          }
          track.tokens.add(new Token(i, Kind.REPEAT_END, plays));
          track.barColumns.add(i + 1);
          i = j;
        } else if (i == 0) {
          // lead '|:' artifact: the label line consumed the divider, so the
          // repeat section opens at the very start of this system
          track.tokens.add(new Token(0, Kind.REPEAT_START, -1));
          i++;
        } else {
          warnings.add("Unrecognized character ':' ignored");
          i++;
        }
      } else if ((c == 'P' || c == 'p') && i + 1 < n && (content.charAt(i + 1) == 'M' || content.charAt(i + 1) ==
                                                                                         'm')) {
        if (palmMuteStart < 0) {
          palmMuteStart = i;
        } else {
          track.palmMuteRanges.add(new int[]{palmMuteStart, i});
          palmMuteStart = -1;
        }
        i += 2;
      } else if (c == '[') {
        // alternate-ending mark [N], [N,M], [N-M] attached to the divider
        // that opened the preceding bar: the bar it opens closes ending(s) N
        int[] mark = readEndingMark(content, i, warnings);
        i = mark[0];
        if (mark[1] != 0) {
          if (!track.barColumns.isEmpty()) {
            track.endingMarks.add(new int[]{track.barColumns.get(track.barColumns.size() - 1), mark[1]});
          } else {
            warnings.add("Alternate ending mark before any bar divider ignored");
          }
        }
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
   * Parses an alternate-ending mark "[1]", "[1,3]" or "[1-3]" at `from`.
   * Returns {index past the closing bracket, ending bitmask}; the mask is 0
   * when the text is not a usable mark (annotation, empty or out of range).
   */
  private static int[] readEndingMark(String s, int from, Set<String> warnings) {
    int n = s.length();
    int j = from + 1;
    int mask = 0;
    int prev = 0; // value before a '-' range dash (0 = none)
    int value = 0;
    boolean haveValue = false;
    boolean bad = false;
    while (j < n && s.charAt(j) != ']' && !bad) {
      char c = s.charAt(j);
      if (c >= '0' && c <= '9') {
        value = value * 10 + (c - '0');
        haveValue = true;
      } else if (c == ',' || c == '-') {
        if (haveValue) {
          if (c == '-') {
            prev = value;
          } else {
            mask = addEnding(mask, value, warnings);
          }
          value = 0;
          haveValue = false;
        } else {
          bad = true;
        }
      } else {
        bad = true;
      }
      j++;
    }
    if (bad || j >= n || (!haveValue && prev == 0)) {
      warnings.add("Unrecognized alternate ending mark ignored");
      // skip to the closing bracket when there is one, to avoid cascading
      // warnings from annotation text
      int close = s.indexOf(']', j);
      return new int[]{close >= 0 ? close + 1 : j, 0};
    }
    if (haveValue) {
      if (prev > 0) {
        for (int v = prev; v <= value; v++) {
          mask = addEnding(mask, v, warnings);
        }
      } else {
        mask = addEnding(mask, value, warnings);
      }
    }
    if (mask == 0) {
      warnings.add("Alternate ending mark ignored (no usable ending numbers)");
      return new int[]{j + 1, 0};
    }
    return new int[]{j + 1, mask};
  }

  private static int addEnding(int mask, int ending, Set<String> warnings) {
    if (ending < 1 || ending > 31) {
      warnings.add("Alternate ending number " + ending + " out of range ignored");
      return mask;
    }
    return mask | (1 << (ending - 1));
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
    double beat = 60.0 / (system.bpm > 0.0 ? system.bpm : bpm);
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

  /**
   * Repeat-section expansion result (local to its system). passOrigLocal
   * holds, per pass, the original local bar indices the pass plays.
   */
  private record RepeatInfo(int sectionStartLocal, int originalBars, int[][] passOrigLocal) {
  }

  /**
   * Expands |: section :|xN in place: each pass plays the section body plus
   * the ending chosen for that pass ([1]/[2] divider marks; ending k plays
   * on pass k, passes past the last ending repeat it). The body's tokens,
   * bar dividers and palm-mute ranges are duplicated per pass; content
   * after the repeat structure shifts to stay behind it. Returns null when
   * the system has no complete repeat pair.
   */
  private static RepeatInfo expandRepeats(SystemBlock system, Set<String> warnings) {
    int originalBars = barRanges(system).size();
    Token startTok = null;
    Token endTok = null;
    outer:
    for (StringTrack track : system.tracks) {
      if (track == null) {
        continue;
      }
      Token localStart = null;
      for (Token t : track.tokens) {
        if (t.kind() == Kind.REPEAT_START) {
          localStart = t;
        } else if (t.kind() == Kind.REPEAT_END && localStart != null) {
          startTok = localStart;
          endTok = t;
          break outer;
        }
      }
    }
    boolean anyMarks = false;
    for (StringTrack track : system.tracks) {
      if (track != null && !track.endingMarks.isEmpty()) {
        anyMarks = true;
        break;
      }
    }
    if (startTok == null || endTok == null) {
      if (anyMarks) {
        warnings.add("Alternate ending without a repeat section ignored");
      }
      return null;
    }
    int c1 = startTok.column();
    int c2 = endTok.column();

    // system-wide dividers and ending masks (the mark lines agree; union)
    TreeSet<Integer> dividers = new TreeSet<>();
    TreeMap<Integer, Integer> masks = new TreeMap<>(); // divider col -> ending bitmask
    for (StringTrack track : system.tracks) {
      if (track == null) {
        continue;
      }
      dividers.addAll(track.barColumns);
      for (int[] mark : track.endingMarks) {
        masks.merge(mark[0], mark[1], (a, b) -> a | b);
      }
    }
    // only marks between the repeat start and the section's last closing
    // divider belong to this structure
    int lastEndBar = c2 + 1;
    for (StringTrack track : system.tracks) {
      if (track == null) {
        continue;
      }
      for (Token t : track.tokens) {
        if (t.kind() == Kind.REPEAT_END) {
          lastEndBar = Math.max(lastEndBar, t.column() + 1);
        }
      }
    }
    final int maskLimit = lastEndBar;
    masks.keySet().removeIf(d -> d <= c1 || d > maskLimit || !dividers.contains(d));

    // ending groups: a run of dividers with the same mask plays one ending;
    // a group's bars run to the next group's opening divider
    List<Integer> masked = new ArrayList<>(masks.keySet());
    List<Integer> groupStart = new ArrayList<>();
    List<Integer> groupEnd = new ArrayList<>();   // closing divider of the group's last bar
    List<Integer> groupMask = new ArrayList<>();
    for (int idx = 0; idx < masked.size(); idx++) {
      int d = masked.get(idx);
      int m = masks.get(d);
      if (!groupStart.isEmpty() && groupMask.get(groupMask.size() - 1) == m
          && groupEnd.get(groupEnd.size() - 1) == d) {
        // the same ending continues with the next marked bar
        groupEnd.set(groupEnd.size() - 1, idx + 1 < masked.size() ? masked.get(idx + 1) : afterLast(d, dividers, system.width));
      } else {
        groupStart.add(d);
        groupEnd.add(idx + 1 < masked.size() ? masked.get(idx + 1) : afterLast(d, dividers, system.width));
        groupMask.add(m);
      }
    }

    int bodyEnd = groupStart.isEmpty() ? c2 + 1 : groupStart.get(0);
    int bodyLen = bodyEnd - c1;
    int plays = Math.max(2, endTok.fret());
    int maxEnding = 0;
    for (int m : groupMask) {
      maxEnding = Math.max(maxEnding, 32 - Integer.numberOfLeadingZeros(m));
    }
    plays = Math.max(plays, maxEnding);

    // per-pass layout: pass 1 keeps the original columns; pass k starts
    // where the previous pass ends
    int[] passStart = new int[plays];
    int[] passLen = new int[plays];
    int[] passGroup = new int[plays];
    int acc = 0;
    for (int k = 0; k < plays; k++) {
      passStart[k] = c1 + acc;
      // pass 1 always plays the first ending group; later passes pick by
      // their pass number
      passGroup[k] = groupStart.isEmpty() ? -1 : (k == 0 ? 0 : chooseGroup(groupMask, k + 1));
      int g = passGroup[k];
      passLen[k] = bodyLen + (g >= 0 ? groupEnd.get(g) - groupStart.get(g) : 0);
      acc += passLen[k];
    }
    int lastEnd = groupEnd.isEmpty() ? c2 + 1 : groupEnd.get(groupEnd.size() - 1);
    int firstGroup = passGroup[0];
    int trailingShift = passStart[plays - 1] + passLen[plays - 1] - lastEnd;

    for (StringTrack track : system.tracks) {
      if (track == null) {
        continue;
      }
      // tokens: originals stay for the pre-section content, the body and
      // the pass-1 group; other groups move to their chosen pass
      List<Token> rebuilt = new ArrayList<>();
      for (Token t : track.tokens) {
        Kind kind = t.kind();
        int col = t.column();
        if (kind == Kind.REPEAT_START || kind == Kind.REPEAT_END) {
          rebuilt.add(t); // structural markers exist once
        } else if (col < c1) {
          rebuilt.add(t);
        } else if (col < bodyEnd) {
          rebuilt.add(t);
          for (int k = 1; k < plays; k++) {
            rebuilt.add(new Token(col + passStart[k] - c1, kind, t.fret()));
          }
        } else {
          int g = groupIndexAt(groupStart, groupEnd, col);
          if (g >= 0) {
            int gStart = groupStart.get(g);
            if (g == firstGroup) {
              rebuilt.add(t);
            }
            for (int k = 1; k < plays; k++) {
              if (passGroup[k] == g) {
                rebuilt.add(new Token(col + passStart[k] + bodyLen - gStart, kind, t.fret()));
              }
            }
          } else {
            rebuilt.add(new Token(col + trailingShift, kind, t.fret()));
          }
        }
      }
      rebuilt.sort(Comparator.comparingInt(Token::column));
      track.tokens.clear();
      track.tokens.addAll(rebuilt);

      // bar dividers follow the same regions (bars close at their divider)
      List<Integer> rebuiltBars = new ArrayList<>();
      for (int col : track.barColumns) {
        if (col <= c1 || col <= bodyEnd) {
          rebuiltBars.add(col);
          for (int k = 1; k < plays; k++) {
            rebuiltBars.add(col + passStart[k] - c1);
          }
        } else {
          int g = groupIndexAtBar(groupStart, groupEnd, col);
          if (g >= 0) {
            int gStart = groupStart.get(g);
            if (g == firstGroup) {
              rebuiltBars.add(col);
            }
            for (int k = 1; k < plays; k++) {
              if (passGroup[k] == g) {
                rebuiltBars.add(col + passStart[k] + bodyLen - gStart);
              }
            }
          } else {
            rebuiltBars.add(col + trailingShift);
          }
        }
      }
      rebuiltBars.sort(Comparator.comparingInt(Integer::intValue));
      track.barColumns.clear();
      track.barColumns.addAll(rebuiltBars);

      // palm-mute ranges ride with the tokens they cover
      List<int[]> rebuiltPm = new ArrayList<>();
      for (int[] range : track.palmMuteRanges) {
        int s = range[0];
        if (s < c1 || s < bodyEnd) {
          rebuiltPm.add(range);
          for (int k = 1; k < plays; k++) {
            rebuiltPm.add(new int[]{range[0] + passStart[k] - c1, range[1] + passStart[k] - c1});
          }
        } else {
          int g = groupIndexAt(groupStart, groupEnd, s);
          if (g >= 0) {
            int gStart = groupStart.get(g);
            if (g == firstGroup) {
              rebuiltPm.add(range);
            }
            for (int k = 1; k < plays; k++) {
              if (passGroup[k] == g) {
                rebuiltPm.add(new int[]{range[0] + passStart[k] + bodyLen - gStart,
                    range[1] + passStart[k] + bodyLen - gStart});
              }
            }
          } else {
            rebuiltPm.add(new int[]{range[0] + trailingShift, range[1] + trailingShift});
          }
        }
      }
      track.palmMuteRanges.clear();
      track.palmMuteRanges.addAll(rebuiltPm);
    }

    // original bar-index ranges per pass, for the time-signature map
    List<Integer> divList = new ArrayList<>(dividers);
    int divCount = divList.size();
    int rank0 = 0;
    for (int d : divList) {
      if (d < c1) {
        rank0++;
      } else {
        break;
      }
    }
    int bodyFirst = dividers.contains(c1) ? rank0 + 1 : rank0;
    int bodyLast = bodyEnd < system.width && dividers.contains(bodyEnd) ? rankOf(divList, bodyEnd) : divCount;
    int[][] passOrigLocal = new int[plays][];
    for (int k = 0; k < plays; k++) {
      List<Integer> idx = new ArrayList<>();
      for (int b = bodyFirst; b <= bodyLast; b++) {
        idx.add(b);
      }
      int g = passGroup[k];
      if (g >= 0) {
        int gFirst = rankOf(divList, groupStart.get(g)) + 1;
        int gEnd = groupEnd.get(g);
        int gLast = gEnd < system.width && dividers.contains(gEnd) ? rankOf(divList, gEnd) : divCount;
        for (int b = gFirst; b <= gLast; b++) {
          idx.add(b);
        }
      }
      passOrigLocal[k] = idx.stream().mapToInt(Integer::intValue).toArray();
    }
    return new RepeatInfo(rank0, originalBars, passOrigLocal);
  }

  /** The divider after `col`, or the system width when `col` is the last. */
  private static int afterLast(int col, TreeSet<Integer> dividers, int width) {
    Integer next = dividers.higher(col);
    return next != null ? next : width;
  }

  /** The ending group whose column window contains `col`, or -1 (trailing). */
  private static int groupIndexAt(List<Integer> groupStart, List<Integer> groupEnd, int col) {
    for (int g = 0; g < groupStart.size(); g++) {
      if (col >= groupStart.get(g) && col < groupEnd.get(g)) {
        return g;
      }
    }
    return -1;
  }

  /** Bar-divider variant: a bar belongs to the group it closes, (start, end]. */
  private static int groupIndexAtBar(List<Integer> groupStart, List<Integer> groupEnd, int col) {
    for (int g = 0; g < groupStart.size(); g++) {
      if (col > groupStart.get(g) && col <= groupEnd.get(g)) {
        return g;
      }
    }
    return -1;
  }

  /**
   * The ending group pass `pass` plays: the first group whose mask contains
   * the pass number, else the first group for a later ending, else the last.
   */
  private static int chooseGroup(List<Integer> groupMask, int pass) {
    int bit = 1 << (pass - 1);
    for (int g = 0; g < groupMask.size(); g++) {
      if ((groupMask.get(g) & bit) != 0) {
        return g;
      }
    }
    for (int g = 0; g < groupMask.size(); g++) {
      if (Integer.lowestOneBit(groupMask.get(g)) > bit) {
        return g;
      }
    }
    return groupMask.size() - 1;
  }

  private static int rankOf(List<Integer> divList, int col) {
    return divList.indexOf(col);
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
                cmd.stringIndex(), cmd.fret(), cmd.a(), cmd.b(), cmd.tag());
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
    boolean pendingSlide = false; // the articulation was a slide: glide to the target
    boolean pendingHarmonic = false; // the note is wrapped in harmonic markers
    boolean pendingGrace = false; // the note is a grace ornament
    boolean pendingTremolo = false; // the note re-plucks (tremolo run)
    int tremoloDigit = -1;
    int tremoloLastCol = Integer.MIN_VALUE;
    boolean seenNote = false;
    int currentFret = -1;
    Set<Integer> trillCols = new HashSet<>();
    findTrills(track.tokens, trillCols);

    for (Token token : track.tokens) {
      double time = start + token.column() * tick;
      int charPos = track.sourceOffset + token.column();
      switch (token.kind()) {
        case NOTE -> {
          boolean legato = pendingArticulation != null && seenNote;
          boolean palm = track.inPalmMute(token.column());
          int type = legato ? RenderCommandType.LEGATO
              : pendingHarmonic ? RenderCommandType.HARMONIC : RenderCommandType.PLUCK;
          // view-only articulation tag (h/p/t/slide/trill/tremolo/grace)
          int tag = RenderCommand.TAG_NONE;
          if (legato) {
            tag = switch (pendingArticulation) {
              case HAMMER -> RenderCommand.TAG_HAMMER;
              case PULL -> RenderCommand.TAG_PULL;
              case TAP -> RenderCommand.TAG_TAP;
              default -> RenderCommand.TAG_SLIDE; // SLIDE
            };
          }
          if (pendingTremolo) {
            if (tremoloDigit < 0) {
              tremoloDigit = token.fret();
            }
            if (token.fret() == tremoloDigit
                && (tremoloLastCol == Integer.MIN_VALUE || token.column() == tremoloLastCol + 1)) {
              tag = RenderCommand.TAG_TREMOLO;
              tremoloLastCol = token.column();
            } else {
              pendingTremolo = false;
            }
          }
          if (trillCols.contains(token.column())) {
            tag = RenderCommand.TAG_TRILL;
          }
          if (pendingGrace) {
            tag = RenderCommand.TAG_GRACE;
          }
          // slides glide audibly (~0.15 s); h/p/t jump fast; grace notes
          // pluck quietly
          float strength = (float) (pendingVelocity != 0.0 ? pendingVelocity
              : legato ? (pendingSlide ? 0.15 : 0.0) : pendingGrace ? 0.45 : 0.9);
          add(byColumn, token.column(), new TabEvent(
              new RenderCommand((float) time, type, track.stringIndex, token.fret(), strength,
                  palm ? 1.0f : 0.0f, tag), charPos));
          currentFret = token.fret();
          seenNote = true;
          pendingArticulation = null;
          pendingSlide = false;
          pendingVelocity = 0.0;
          pendingHarmonic = false;
          pendingGrace = false;
        }
        case GHOST -> pendingVelocity = 0.45;
        case GRACE -> {
          pendingGrace = true;
          pendingVelocity = 0.45;
        }
        case ACCENT -> pendingVelocity = 1.0;
        case TREMOLO -> {
          pendingTremolo = true;
          tremoloDigit = -1;
          tremoloLastCol = Integer.MIN_VALUE;
        }
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
        case HAMMER, PULL, TAP, SLIDE -> {
          pendingArticulation = token.kind();
          pendingSlide = token.kind() == Kind.SLIDE;
        }
        case DEAD -> add(byColumn, token.column(),
            new TabEvent(new RenderCommand((float) time, RenderCommandType.MUTE, track.stringIndex, 0, 0.0f, 0.0f),
                charPos));
        case VIBRATO -> add(byColumn, token.column(), new TabEvent(
            new RenderCommand((float) time, RenderCommandType.VIBRATO, track.stringIndex, 0,
                1.0f, 0.0f), charPos));
        case HARMONIC -> pendingHarmonic = true;
        case BAR -> { }
      }
    }
  }

  /**
   * Marks trill runs: h/p alternating between the same two frets
   * (e.g. 5h7p5h7p5). The tagged notes render with a 'tr' label instead of
   * per-note h/p letters.
   */
  private static void findTrills(List<Token> tokens, Set<Integer> out) {
    for (int i = 0; i + 4 < tokens.size(); i++) {
      if (tokens.get(i).kind() != Kind.NOTE) {
        continue;
      }
      Kind k1 = tokens.get(i + 1).kind();
      if (k1 != Kind.HAMMER && k1 != Kind.PULL) {
        continue;
      }
      if (tokens.get(i + 2).kind() != Kind.NOTE) {
        continue;
      }
      Kind k2 = tokens.get(i + 3).kind();
      if (k2 != (k1 == Kind.HAMMER ? Kind.PULL : Kind.HAMMER)) {
        continue;
      }
      if (tokens.get(i + 4).kind() != Kind.NOTE || tokens.get(i + 4).fret() != tokens.get(i).fret()) {
        continue;
      }
      int a = tokens.get(i).fret();
      int b = tokens.get(i + 2).fret();
      List<Integer> run = new ArrayList<>();
      run.add(tokens.get(i + 2).column());
      run.add(tokens.get(i + 4).column());
      int j = i + 5;
      Kind expect = k1;
      int expectFret = b;
      while (j + 1 < tokens.size() && tokens.get(j).kind() == expect
          && tokens.get(j + 1).kind() == Kind.NOTE && tokens.get(j + 1).fret() == expectFret) {
        run.add(tokens.get(j + 1).column());
        expect = expect == Kind.HAMMER ? Kind.PULL : Kind.HAMMER;
        expectFret = expectFret == a ? b : a;
        j += 2;
      }
      // a single h->p return is an ordinary hammer/pull phrase; a trill
      // keeps alternating
      if (run.size() >= 3) {
        out.addAll(run);
      }
      i = j - 1;
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
    static final int VIBRATO = 6;
    static final int HARMONIC = 7;
  }
}
