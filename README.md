# Guitar Tab Player

Standalone desktop app that parses ASCII guitar tabs and synthesizes plucked-string audio
via physical modeling (Karplus-Strong) — no pre-recorded samples.

**Stack:** Java 26 + JavaFX (UI, later phase) · C++20 + CMake (synthesis engine) ·
FFM (`java.lang.foreign`) bridge · Maven build · `miniaudio` for audio output (Phase 2).

See `C:\Users\Marto\.opencode\plan\guitar-tab-player-plan.md` for the full architecture plan.

## Project layout

```
pom.xml                 Maven build (Java side)
native/                 C++ engine sources (built into guitar_engine.dll)
  CMakeLists.txt
  include/guitar_engine.h   C ABI — the contract with the Java side
  src/guitar_engine.cpp     Phase 0: stubs. Phase 2: KS synthesis + event queue
src/main/java/...       Java: App (smoke test) + engine/GuitarEngine (FFM bindings)
src/test/java/...       JUnit bridge smoke tests
scripts/                dev-env / build-native / test / run helper scripts
build/native/           CMake build output (DLL) — not committed
```

## Toolchain (portable, installed outside the project)

- JDK 26: `C:\Users\Marto\.jdks\openjdk-26.0.2`
- Maven 3.9.16: `D:\Tools\apache-maven-3.9.16`
- MinGW-w64 (GCC 16.2, includes CMake 4.4 + Ninja): `D:\Tools\mingw64`

`scripts/dev-env.cmd` puts all of them on PATH for a session.

## Usage

```bat
scripts\build-native.cmd   :: build native/guitar_engine.dll -> build\native\
scripts\test.cmd           :: build DLL + run JUnit bridge tests (mvn test)
scripts\run.cmd            :: build all + run the console demo app
scripts\run-ui.cmd         :: build all + launch the JavaFX tab player UI
scripts\package.cmd        :: jpackage app-image -> build\installer\GuitarTabPlayer\
```

## Status

- [x] **Phase 0** — Maven + CMake wired; Java → FFM → C++ bridge; JUnit smoke tests
- [x] **Phase 1** — Karplus-Strong `PluckedString` (allpass fractional delay, T60 decay,
      palm mute, hammer-on, bend/slide); offline WAV render API; 9 verification tests
      (pitch via autocorrelation, decay curves, articulations)
- [x] **Phase 2** — miniaudio device output, lock-free SPSC timestamped event queue,
      sample-accurate scheduling, sample clock, stereo pan; real-time demo app
- [x] **Phase 3** — ASCII tab parser: string/system detection, articulation tokens
      (h/p/t, slides, bend+release, PM regions, dead notes, harmonics-tolerated),
      chord stagger, bar-based & uniform-density timing inference, 18 tests incl.
      an end-to-end parse → render → pitch-verification test
- [x] **Phase 4** — JavaFX studio-console UI (`ui/PlayerApp` + `theme.css`):
      score/cue view (`ui/ScoreView`) rendering the compiled timeline with dynamic
      tuning-derived lane labels, playback cursor and click-to-select bar blocks;
      title field + bar-block operations (COPY/CUT/PASTE/DUP/DEL/CLEAR,
      `tab/TabBlocks`) round-tripping through the system clipboard; collapsible
      ASCII source editor; meter bridge (time/BPM/tuning); vertical output VU
      next to the master fader; 6 per-string channel faders; FX knobs; tape
      transport with record LED; loop with lookahead rescheduling; WAV export.
      All engine pushes funnel through a single producer thread (SPSC rule)
- [x] **Phase 5** — C++ master FX (drive/tanh, tone LP, Schroeder reverb, master)
      + per-string channel gains + output level metering on both live and offline
      paths (`Phase5FxTest`); fully static GCC runtime in the DLL;
      `scripts/package.cmd` builds a jpackage app-image
      (`build/installer/GuitarTabPlayer/GuitarTabPlayer.exe`) bundling runtime,
      fat jar, and native DLL — smoke-tested

## Debug flags

| Flag | Effect |
| :--- | :--- |
| `-Dguitar.autoplay=true` | Plays the demo tab immediately at launch |
| `-Dguitar.screenshot=path` | Captures the UI to a PNG after 2 s, then exits |
| `-Dguitar.engine.lib=path` | Loads the native engine from an explicit path |
| `-Dguitar.tuning=DROP_C` | Preselects a tuning at launch (name or label) |
| `-Dguitar.fx=0.5,1,0.25,1` | Preselects FX at launch: drive,tone,reverb,master |
| `-Dguitar.view=source` | Starts with the source editor visible instead of the score |

The status line shows a **UI build stamp** (`UI BUILD <date> · engine <version>`) at
launch — if behavior ever looks stale, that stamp identifies which build is running.
