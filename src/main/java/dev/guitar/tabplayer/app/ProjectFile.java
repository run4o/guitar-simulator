package dev.guitar.tabplayer.app;

import dev.guitar.tabplayer.model.Tuning;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Project file (.gtp): the tab text plus the console state — tempo, tuning,
 * the four FX values and the pedalboard (generic "pedal.<name>.<param>"
 * keys, so pedals extend the file without format changes).
 */
public final class ProjectFile {

  private static final String VERSION_KEY = "version";
  private static final String TAB_KEY = "tab";
  private static final String BPM_KEY = "bpm";
  private static final String TUNING_KEY = "tuning";
  private static final String DRIVE_KEY = "fx.drive";
  private static final String TONE_KEY = "fx.tone";
  private static final String REVERB_KEY = "fx.reverb";
  private static final String MASTER_KEY = "fx.master";
  private static final String PEDAL_PREFIX = "pedal.";

  public record Project(String tab, int bpm, Tuning tuning,
      double drive, double tone, double reverb, double master,
      Map<String, String> pedals) {

    /** Pre-pedalboard format (empty pedal state). */
    public Project(String tab, int bpm, Tuning tuning,
        double drive, double tone, double reverb, double master) {
      this(tab, bpm, tuning, drive, tone, reverb, master, Map.of());
    }
  }

  private ProjectFile() {
  }

  public static void save(Path file, Project project) throws IOException {
    Properties props = new Properties();
    props.setProperty(VERSION_KEY, "1");
    props.setProperty(TAB_KEY, project.tab());
    props.setProperty(BPM_KEY, Integer.toString(project.bpm()));
    props.setProperty(TUNING_KEY, project.tuning().name());
    props.setProperty(DRIVE_KEY, Double.toString(project.drive()));
    props.setProperty(TONE_KEY, Double.toString(project.tone()));
    props.setProperty(REVERB_KEY, Double.toString(project.reverb()));
    props.setProperty(MASTER_KEY, Double.toString(project.master()));
    for (Map.Entry<String, String> e : new TreeMap<>(project.pedals()).entrySet()) {
      props.setProperty(PEDAL_PREFIX + e.getKey(), e.getValue());
    }
    try (OutputStream out = Files.newOutputStream(file)) {
      props.store(out, "Guitar Tab Player project");
    }
  }

  public static Project load(Path file) throws IOException {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    }
    Map<String, String> pedals = new TreeMap<>();
    for (String key : props.stringPropertyNames()) {
      if (key.startsWith(PEDAL_PREFIX)) {
        pedals.put(key.substring(PEDAL_PREFIX.length()), props.getProperty(key));
      }
    }
    return new Project(
        props.getProperty(TAB_KEY, ""),
        parseInt(props.getProperty(BPM_KEY), 120),
        parseTuning(props.getProperty(TUNING_KEY)),
        parseDouble(props.getProperty(DRIVE_KEY), 0),
        parseDouble(props.getProperty(TONE_KEY), 1),
        parseDouble(props.getProperty(REVERB_KEY), 0),
        parseDouble(props.getProperty(MASTER_KEY), 1),
        pedals);
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double parseDouble(String value, double fallback) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static Tuning parseTuning(String name) {
    if (name != null) {
      for (Tuning t : Tuning.values()) {
        if (t.name().equalsIgnoreCase(name)) {
          return t;
        }
      }
    }
    return Tuning.STANDARD;
  }
}
