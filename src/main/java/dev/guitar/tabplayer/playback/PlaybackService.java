package dev.guitar.tabplayer.playback;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.tab.TabParser;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * Bar-streaming sequencer, extracted from the old monolithic PlayerApp:
 * schedules ONE bar at a time ~0.6 s ahead. Looping wraps bar-to-bar (no
 * ring-out ghost bar), a BPM change recompiles and applies from the next bar,
 * and STOP drops at most one queued bar.
 * <p>
 * UI coupling is inverted: the service pushes recompiled timelines and
 * play-state changes to listeners (set by the root controller) instead of
 * touching nodes directly. Cursor anchor state stays here and is read by the
 * root controller's 50 ms meter poll.
 * <p>
 * Threading: ALL engine event pushes go through the context's single-threaded
 * executor (the lock-free queue is SPSC — exactly one producer).
 */
public final class PlaybackService {

  private static final long SR = AppContext.SR;

  private final AppContext ctx;
  private final boolean autoplay;

  private Consumer<TabParser.TabTimeline> timelineListener = t -> { };
  private Consumer<Boolean> stateListener = playing -> { };

  private volatile boolean playing;
  private volatile boolean loop = true;

  // Bar-streaming sequencer state (cursor anchors are read by the 50 ms poll)
  private volatile TabParser.TabTimeline currentTimeline;
  private volatile java.util.Map<Integer, int[]> meters; // imported per-bar time signatures
  private volatile String playingText;   // tab snapshot taken at play start (text can't change mid-play)
  private volatile int sequencerBpm = 120;
  private volatile boolean bpmChangePending;
  private volatile long cursorAnchorFrame;
  private volatile double cursorAnchorSeconds;
  private volatile long nextBarFrame;
  private volatile double nextBarSeconds;
  private volatile boolean nextBarPending;
  private volatile double cursorEndSeconds = Double.MAX_VALUE;
  private long lastMeterSecond;

  public PlaybackService(AppContext ctx) {
    this.ctx = ctx;
    this.autoplay = ctx.autoplay();
  }

  public void setTimelineListener(Consumer<TabParser.TabTimeline> listener) {
    timelineListener = listener;
  }

  /** Called on the FX thread whenever play state changes. */
  public void setStateListener(Consumer<Boolean> listener) {
    stateListener = listener;
  }

  /**
   * Starts playback of the given tab text at the given tempo.
   * Returns false when the engine is down, already playing, or the tab is empty.
   */
  public boolean play(String text, int bpm) {
    if (!ctx.engineReady() || playing) {
      return false;
    }
    if (!ctx.deviceRunning() && !ctx.engine().start()) {
      ctx.status("NO AUDIO DEVICE AVAILABLE — CANNOT PLAY");
      return false;
    }
    ctx.setDeviceRunning(true);
    playingText = text;
    sequencerBpm = bpm;
    TabParser.TabTimeline timeline = compileData();
    currentTimeline = timeline;
    publish(timeline);
    if (timeline.commands().isEmpty()) {
      ctx.status("NOTHING TO PLAY. " + timeline.warnings().size() + " WARNING(S).");
      timeline.warnings().forEach(w -> ctx.status("  " + w));
      return false;
    }
    ctx.status("PLAYING " + timeline.commands().size() + " EVENTS @ " + sequencerBpm + " BPM" +
        (timeline.warnings().isEmpty() ? "" : " (" + timeline.warnings().size() + " WARNINGS)"));
    if (autoplay) {
      System.out.println(
          "[autoplay] scheduled " + timeline.commands().size() + " events, duration " +
          timeline.durationSeconds() + "s");
    }

    playing = true;
    stateListener.accept(true);
    Thread t = new Thread(this::runPlayback, "sequencer");
    t.setDaemon(true);
    t.start();
    return true;
  }

  public void stop() {
    if (!playing) {
      return;
    }
    playing = false;
    stateListener.accept(false);
    ctx.commands().execute(() -> {
      // Drop the entire pre-queued iteration so STOP is instant, then mute.
      ctx.engine().resetTransport();
      for (int s = 0; s < 6; s++) {
        ctx.engine().mute(s);
      }
    });
  }

  /** Quiet shutdown: no state listener callback, no engine commands. */
  public void halt() {
    playing = false;
  }

  public boolean isPlaying() {
    return playing;
  }

  public TabParser.TabTimeline currentTimeline() {
    return currentTimeline;
  }

  /**
   * Compiles the given tab text at the live tempo and publishes it to the UI.
   */
  public TabParser.TabTimeline compileAndPublish(String text) {
    return compileAndPublish(text, null);
  }

  /** @param meters sparse per-bar time signatures carried over from an import */
  public TabParser.TabTimeline compileAndPublish(String text, java.util.Map<Integer, int[]> meters) {
    this.meters = meters;
    TabParser.TabTimeline timeline = TabParser.compile(text, sequencerBpm, meters);
    currentTimeline = timeline;
    publish(timeline);
    return timeline;
  }

  /**
   * Publishes a (re)compiled timeline to the UI from any thread.
   */
  private void publish(TabParser.TabTimeline timeline) {
    Runnable publish = () -> timelineListener.accept(timeline);
    if (Platform.isFxApplicationThread()) {
      publish.run();
    } else {
      Platform.runLater(publish);
    }
  }

  /** A tempo change while playing applies from the next bar. */
  public void requestBpm(int bpm) {
    sequencerBpm = bpm;
    bpmChangePending = true;
  }

  public void setLoop(boolean loop) {
    this.loop = loop;
  }

  /**
   * The playback cursor position in seconds, derived from the audio sample
   * clock. Also promotes the bar-anchor state as bars cross. -1 when stopped.
   */
  public double cursorSeconds(long clock, long sr) {
    if (!playing) {
      return -1;
    }
    if (nextBarPending && clock >= nextBarFrame) {
      // the audio crossed into the next scheduled bar: promote the anchor
      cursorAnchorFrame = nextBarFrame;
      cursorAnchorSeconds = nextBarSeconds;
      nextBarPending = false;
    }
    double cursor = Math.max(0, cursorAnchorSeconds + (clock - cursorAnchorFrame) / (double) sr);
    if (cursor > cursorEndSeconds) {
      cursor = cursorEndSeconds;
    }
    return cursor;
  }

  /**
   * Bar-streaming sequencer loop: schedules bars inside the lookahead window
   * and parks until the song end (one-shot) or STOP.
   */
  private void runPlayback() {
    TabParser.TabTimeline timeline = currentTimeline;
    List<TabParser.BarTime> bars = timeline.barTimes();
    List<TabParser.TabEvent> events = timeline.events();
    int barCount = bars.size();
    long lookahead = 600L * SR / 1000;
    long loopGap = 150L * SR / 1000; // small musical breath between loops

    long now = ctx.engine().sampleClock();
    long scheduleFrame = now + SR / 3; // count-in before bar 1
    int nextBarIdx = 0;
    cursorAnchorFrame = scheduleFrame;
    cursorAnchorSeconds = bars.get(0).startSeconds();
    cursorEndSeconds = Double.MAX_VALUE;
    nextBarPending = false;
    long songEndFrame = -1;

    try {
      while (playing) {
        long clock = ctx.engine().sampleClock();

        // schedule ahead: push every bar that starts within the lookahead
        while (playing && scheduleFrame - clock <= lookahead) {
          // live BPM: recompile before scheduling the next bar
          if (bpmChangePending) {
            bpmChangePending = false;
            currentTimeline = compileData();
            publish(currentTimeline);
            timeline = currentTimeline;
            bars = timeline.barTimes();
            events = timeline.events();
            barCount = bars.size();
            nextBarIdx = Math.min(nextBarIdx, barCount - 1);
          }

          final int b = nextBarIdx;
          final TabParser.BarTime bar = bars.get(b);
          final long frame = scheduleFrame;
          scheduleBar(events, bar, frame);

          // the poll's cursor jumps to this bar's musical position at `frame`
          nextBarFrame = frame;
          nextBarSeconds = bar.startSeconds();
          nextBarPending = true;

          long barFrames = (long) ((bar.endSeconds() - bar.startSeconds()) * SR);
          if (!loop && b == barCount - 1) {
            cursorEndSeconds = bar.endSeconds();
            songEndFrame = frame + barFrames;
            break; // one-shot: stop scheduling
          }
          scheduleFrame = frame + barFrames;
          nextBarIdx = b + 1;
          if (nextBarIdx >= barCount) {
            nextBarIdx = 0;
            scheduleFrame += loopGap; // breath between loops
          }
        }

        if (!playing) {
          break;
        }
        // one-shot: end of the last bar reached
        if (songEndFrame >= 0 && clock >= songEndFrame + SR / 5) {
          Platform.runLater(this::stop);
          break;
        }
        if (autoplay && clock / SR > lastMeterSecond) {
          lastMeterSecond = clock / SR;
          System.out.println("[autoplay] clock=" + clock + " frames");
        }
        Thread.sleep(40);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (autoplay) {
      System.out.println("[autoplay] sequencer done, final clock=" + ctx.engine().sampleClock());
    }
  }

  /**
   * Pushes one bar's events, mapped from its musical start to `frame`.
   */
  private void scheduleBar(List<TabParser.TabEvent> events, TabParser.BarTime bar, long frame) {
    ctx.commands().execute(() -> {
      for (TabParser.TabEvent event : events) {
        double t = event.command().timeOffset();
        if (t < bar.startSeconds() - 1e-6 || t >= bar.endSeconds() - 1e-6) {
          continue;
        }
        long at = frame + (long) ((t - bar.startSeconds()) * SR);
        if (!ctx.engine().pushEvent(at, event.command().type(), event.command().stringIndex(),
            event.command().fret(), event.command().a(), event.command().b())) {
          Platform.runLater(() -> ctx.status("EVENT QUEUE FULL — SOME EVENTS DROPPED"));
          break; // queue full
        }
      }
    });
  }

  private TabParser.TabTimeline compileData() {
    return TabParser.compile(playingText != null ? playingText : "", sequencerBpm, meters);
  }

  /**
   * The event active at the given absolute frame position, or -1.
   */
  public static int eventIndexAt(List<TabParser.TabEvent> events, long framePos) {
    int result = -1;
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).command().timeOffset() * SR <= framePos) {
        result = i;
      } else {
        break;
      }
    }
    return result;
  }
}
