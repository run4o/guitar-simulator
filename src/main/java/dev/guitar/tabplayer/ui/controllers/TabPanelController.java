package dev.guitar.tabplayer.ui.controllers;

import dev.guitar.tabplayer.app.AppContext;
import dev.guitar.tabplayer.playback.PlaybackService;
import dev.guitar.tabplayer.tab.TabBlocks;
import dev.guitar.tabplayer.tab.TabParser;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javafx.fxml.FXML;

/**
 * The tab panel: its header (title + bar operations + view toggle) on top and
 * the visualization area below — the graphical score or the ASCII source, both
 * living in one StackPane so switching is a pixel-exact replacement. Owns the
 * bar-block operations, the bar selection and the caret highlight.
 */
public class TabPanelController {

  @FXML
  private SheetHeaderController sheetHeaderPanelController;
  @FXML
  private ScorePanelController scorePanelController;
  @FXML
  private SourcePanelController sourcePanelController;

  private SheetHeaderController sheetHeaderController;

  private AppContext ctx;
  private PlaybackService playback;

  private boolean sourceMode;
  private TabBlocks.BarBlock blockClipboard;
  private int selFirst = -1;
  private int selLast = -1;
  private int lastHighlightIdx = -2;
  private Runnable onEditStop;

  public void init(AppContext ctx, PlaybackService playback) {
    this.ctx = ctx;
    this.playback = playback;
    sheetHeaderController = sheetHeaderPanelController;
    sheetHeaderController.setOps(this::blockCopy, this::blockCut, this::blockPaste,
        this::blockDuplicate, this::blockDelete, this::blockClear, this::toggleSourceView);
    sourcePanelController.setOnEditStop(() -> {
      if (playback.isPlaying() && onEditStop != null) {
        onEditStop.run();
      }
    });
    scorePanelController.setOnBarClick(this::onScoreBarClick);
  }

  /** Injected by the root controller: user edits stop playback. */
  public void setOnEditStop(Runnable onEditStop) {
    this.onEditStop = onEditStop;
  }

  /* ------------------------------------------------------------------
   * Rendering + cursor
   * ------------------------------------------------------------------ */

  public String tabText() {
    return sourcePanelController.getText();
  }

  public void compileCurrent() {
    playback.compileAndPublish(tabText());
  }

  /**
   * The 50 ms poll pushes the playback cursor here; the score view follows it
   * and, in source mode, the ASCII caret highlight sweeps along.
   */
  public void updateCursor(double cursor) {
    scorePanelController.setCursor(cursor);
    if (cursor >= 0 && sourceMode) {
      TabParser.TabTimeline timeline = playback.currentTimeline();
      if (timeline != null) {
        int idx = PlaybackService.eventIndexAt(timeline.events(), (long) (cursor * AppContext.SR));
        if (idx != lastHighlightIdx) {
          lastHighlightIdx = idx;
          if (idx >= 0) {
            int len = tabText().length();
            int charPos = timeline.events().get(idx).charPos();
            if (charPos < len) {
              sourcePanelController.highlightRange(charPos, Math.min(charPos + 2, len));
            }
          }
        }
      }
    }
  }

  /**
   * Publishes a (re)compiled timeline: lane labels (from the current tuning),
   * the score render and the warning badge.
   */
  public void showTimeline(TabParser.TabTimeline timeline, String[] laneLabels) {
    scorePanelController.setLaneLabels(laneLabels);
    scorePanelController.setTimeline(timeline);
    sheetHeaderController.setWarnings(timeline.warnings());
  }

  public void deselectCaret() {
    sourcePanelController.deselect();
  }

  /* ------------------------------------------------------------------
   * View switching
   * ------------------------------------------------------------------ */

  /**
   * Swaps the center area between the score view and the ASCII source editor.
   * Both live in one StackPane at full size; toggling visibility is an
   * exact-size replacement.
   */
  public void toggleSourceView() {
    sourceMode = !sourceMode;
    scorePanelController.setViewVisible(!sourceMode);
    sourcePanelController.setViewVisible(sourceMode);
    sheetHeaderController.setViewToggleText(sourceMode ? "VIEW: SCORE" : "VIEW: SOURCE");
    if (sourceMode) {
      sourcePanelController.focus();
    }
    ctx.status(sourceMode ? "SOURCE EDITOR — EDITS RECOMPILE THE SCORE" : "SCORE VIEW");
  }

  /** Debug flag: start in source view (-Dguitar.view=source). */
  public void showSource() {
    if (sourceMode) {
      return;
    }
    sourceMode = true;
    scorePanelController.setViewVisible(false);
    sourcePanelController.setViewVisible(true);
    sheetHeaderController.setViewToggleText("VIEW: SCORE");
  }

  /* ------------------------------------------------------------------
   * Bar-block operations
   * ------------------------------------------------------------------ */

  private void onScoreBarClick(int barIndex, boolean extend) {
    if (extend && selFirst >= 0) {
      selLast = barIndex;
    } else {
      selFirst = selLast = barIndex;
    }
    scorePanelController.setSelection(selFirst, selLast);
  }

  /**
   * The bar (or bar range) to operate on: score selection, else the caret's bar.
   */
  private TabParser.BarSpan activeSpan() {
    String text = tabText();
    List<TabParser.BarSpan> spans = TabParser.barSpans(text);
    if (spans.isEmpty()) {
      ctx.status("NO BARS TO OPERATE ON");
      return null;
    }
    if (selFirst < 0) {
      TabParser.BarSpan caretBar = TabBlocks.barAtChar(text, sourcePanelController.caretPosition());
      return caretBar != null ? caretBar : spans.get(0);
    }
    int first = Math.min(selFirst, selLast);
    int last = Math.max(selFirst, selLast);
    if (spans.get(first).system().index() != spans.get(Math.min(last, spans.size() - 1)).system().index()) {
      ctx.status("SELECT BARS WITHIN ONE SYSTEM");
      return null;
    }
    last = Math.min(last, spans.size() - 1);
    return new TabParser.BarSpan(spans.get(first).system(), first, spans.get(first).startCol(),
        spans.get(last).endColEx());
  }

  private void setTabText(String newText) {
    sourcePanelController.setText(newText);
    selFirst = selLast = -1;
    scorePanelController.setSelection(-1, -1);
    compileCurrent();
  }

  private void blockCopy() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    blockClipboard = TabBlocks.copyBar(tabText(), span);
    var selection = new java.awt.datatransfer.StringSelection(TabBlocks.blockToText(blockClipboard));
    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    ctx.status("BAR COPIED");
  }

  private void blockCut() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    blockClipboard = TabBlocks.copyBar(tabText(), span);
    var selection = new java.awt.datatransfer.StringSelection(TabBlocks.blockToText(blockClipboard));
    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    setTabText(TabBlocks.deleteBar(tabText(), span));
    ctx.status("BAR CUT");
  }

  private void blockPaste() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    TabBlocks.BarBlock block = blockClipboard;
    if (block == null) {
      try {
        String text = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(
            java.awt.datatransfer.DataFlavor.stringFlavor);
        if (text != null) {
          block = TabBlocks.blockFromText(text);
        }
      } catch (Exception ignored) {
        // fall through to the empty check
      }
    }
    if (block == null || block.lines().isEmpty()) {
      ctx.status("CLIPBOARD HAS NO TAB BLOCK");
      return;
    }
    setTabText(TabBlocks.pasteBar(tabText(), span, block, true));
    ctx.status("BAR PASTED");
  }

  private void blockDuplicate() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    setTabText(TabBlocks.duplicateBar(tabText(), span));
    ctx.status("BAR DUPLICATED");
  }

  private void blockDelete() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    setTabText(TabBlocks.deleteBar(tabText(), span));
    ctx.status("BAR DELETED");
  }

  private void blockClear() {
    TabParser.BarSpan span = activeSpan();
    if (span == null) {
      return;
    }
    setTabText(TabBlocks.clearBar(tabText(), span));
    ctx.status("BAR CLEARED");
  }

  /** Public entry for menu wiring: swap the whole tab text and recompile. */
  public void replaceTabText(String newText) {
    setTabText(newText);
  }

  /**
   * Loads an ASCII tab file, replacing the current text (wired from the
   * app-header File menu).
   */
  public void openTab(java.nio.file.Path file) {
    try {
      if (playback.isPlaying()) {
        playback.stop();
      }
      sourcePanelController.setText(Files.readString(file));
      selFirst = selLast = -1;
      scorePanelController.setSelection(-1, -1);
      compileCurrent();
      ctx.status("LOADED " + file.getFileName().toString().toUpperCase());
    } catch (IOException e) {
      ctx.status("FAILED TO LOAD: " + e.getMessage().toUpperCase());
    }
  }
}
