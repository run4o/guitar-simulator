# AGENTS.md — Development Process

Rules every contributor (human or AI agent) must follow when changing this project.

## The workflow (mandatory, in order)

1. **Write tests first.** Every change — new feature, bug fix, or refactor —
   comes with tests that prove it works. No change ships without test
   coverage.
2. **Validate the change.** Run the full test suite:
   ```
   scripts\test.cmd
   ```
   It builds the native DLL, then compiles and runs every test. For UI
   changes, additionally launch the app and verify visually:
   ```
   scripts\run-ui.cmd
   ```
   (`-Dguitar.screenshot=build\<name>.png@<seconds>` captures a PNG and exits.)
3. **Fix failures before moving on.** If any test fails, the fix is the next
   task — do not stack new work on a red suite, do not skip or disable tests
   to make them pass, and do not weaken assertions. Either the code or the
   test is wrong; find out which and fix it. Re-run until the whole suite is
   green.
4. **Commit and push.** Once tests pass, commit the change with a clear
   message and push to remote:
   ```
   git add <files>
   git commit -m "<what and why>"
   git push origin master
   ```

One change = one test cycle = one commit. Do not batch unrelated changes into
a single commit.

## Environment

- Portable toolchain is set up by `scripts\dev-env.cmd` (JDK 26, Maven,
  MinGW). All build commands go through the `scripts\*.cmd` wrappers; do not
  assume `mvn`/`javac` are on PATH.
- Tests touching the native engine require `scripts\test.cmd` (it builds
  `guitar_engine.dll` first).
- Maven is invoked as `mvn -B` via the wrappers; the project is non-modular
  (classpath launcher, main class `dev.guitar.tabplayer.ui.UiLauncher`).

## Project layout

```
src/main/java/dev/guitar/tabplayer/
  app/        PlayerApp (entry), AppContext (engine boot, shared state), ProjectFile
  playback/   PlaybackService (bar-streaming sequencer)
  model/      Tuning
  engine/     GuitarEngine (native bridge)
  tab/        TabParser, TabBlocks, gp/GpImport (Guitar Pro 3/4/5 importer)
  ui/         UiLauncher, controls/ (custom JavaFX controls), controllers/ (per-panel FXML controllers)
src/main/resources/dev/guitar/tabplayer/ui/   FXML panels, theme.css, fonts
```

## Conventions

- UI is FXML-first: one FXML file + one root controller per panel; panels
  expose small APIs and all cross-panel wiring lives in `RootController`.
- The engine command queue is single-producer (SPSC): all engine pushes go
  through `AppContext.commands()`.
- New parser behavior gets a fixture-based test (see `GpImportTest` for the
  byte-by-byte fixture pattern; real-world validation files come from the
  PyGuitarPro corpus).
