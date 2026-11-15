package dev.guitar.tabplayer.tab;

import dev.guitar.tabplayer.tab.TabParser.BarSpan;
import dev.guitar.tabplayer.tab.TabParser.TabSystem;
import dev.guitar.tabplayer.tab.TabBlocks.BarBlock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 verification: bar-block operations (copy/paste/duplicate/delete/clear)
 * on ASCII tab text.
 */
@Tag("phase4")
class TabBlocksTest {

    /** One system, two bars. */
    private static final String TAB = """
            e|------------------|------------------|
            B|------------------|------------------|
            G|------------------|------------------|
            D|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            A|--2--2--2--2--2-5-|--2--2--2--2--2-5-|
            E|--0--0--0--0--0-3-|--0--0--0--0--0-3-|
            """;

    /** Two systems (a repeated 2-bar riff). */
    private static final String TWO_SYSTEMS = TAB + TAB;

    private static List<BarSpan> bars() {
        return TabParser.barSpans(TAB);
    }

    @Test
    void singleSystemTabHasTwoBars() {
        assertEquals(2, bars().size());
        assertEquals(1, TabParser.systems(TAB).size());
        assertEquals(4, TabParser.barSpans(TWO_SYSTEMS).size());
    }

    @Test
    void copyBarExtractsAllSixLines() {
        BarBlock block = TabBlocks.copyBar(TAB, bars().get(0));
        assertEquals(6, block.lines().size());
        assertEquals("--0--0--0--0--0-3-|", block.contentFor(0));
        assertEquals('E', block.lines().get(0).label());
        assertEquals("------------------|", block.contentFor(5));
    }

    @Test
    void blockTextRoundTripsThroughClipboardFormat() {
        BarBlock block = TabBlocks.copyBar(TAB, bars().get(0));
        BarBlock parsed = TabBlocks.blockFromText(TabBlocks.blockToText(block));
        assertEquals(block, parsed);
        assertEquals(6, parsed.lines().size());
    }

    @Test
    void duplicateBarAppendsAnIdenticalBar() {
        String doubled = TabBlocks.duplicateBar(TAB, bars().get(0));
        List<BarSpan> after = TabParser.barSpans(doubled);
        assertEquals(3, after.size(), "duplicate must add one bar");
        BarBlock original = TabBlocks.copyBar(TAB, bars().get(0));
        BarBlock copy = TabBlocks.copyBar(doubled, after.get(1));
        assertEquals(original, copy, "the duplicated bar must equal the original");
    }

    @Test
    void deleteBarShortensEveryLine() {
        String edited = TabBlocks.deleteBar(TAB, bars().get(0));
        List<String> lines = edited.lines().toList();
        for (String line : lines) {
            assertTrue(line.matches("[eBGDAE]\\|.*"), "line labels preserved: " + line);
        }
        assertTrue(lines.get(3).endsWith("--2--2--2--2--2-5-|"),
                "remaining content must be the second bar: " + lines.get(3));
        // one remaining separator -> the fixed 16-column fallback applies (16 + 3)
        assertEquals(2, TabParser.barSpans(edited).size());
    }

    @Test
    void clearBarKeepsSeparatorsButRemovesNotes() {
        String cleared = TabBlocks.clearBar(TAB, bars().get(0));
        List<String> lines = cleared.lines().toList();
        assertTrue(lines.get(5).startsWith("E|------------------|"),
                "cleared bar must be all dashes, separators kept: " + lines.get(5));
        assertTrue(lines.get(3).contains("-5-|"), "second bar must be untouched: " + lines.get(3));
        assertEquals(2, TabParser.barSpans(cleared).size());
    }

    @Test
    void pasteInsertsBlockColumnsIntoTargetSystem() {
        BarBlock block = TabBlocks.copyBar(TAB, bars().get(0));
        String pasted = TabBlocks.pasteBar(TAB, bars().get(0), block, true);
        assertEquals(3, TabParser.barSpans(pasted).size());
        BarBlock firstBar = TabBlocks.copyBar(pasted, TabParser.barSpans(pasted).get(0));
        BarBlock secondBar = TabBlocks.copyBar(pasted, TabParser.barSpans(pasted).get(1));
        assertEquals(firstBar, secondBar, "pasted columns must match the source bar");
    }

    @Test
    void pasteIntoAnotherSystemWorks() {
        List<BarSpan> targets = TabParser.barSpans(TWO_SYSTEMS);
        assertEquals(4, targets.size());
        BarBlock block = TabBlocks.copyBar(TAB, targets.get(1)); // second bar of system 0
        String pasted = TabBlocks.pasteBar(TWO_SYSTEMS, targets.get(3), block, true); // after last bar
        List<BarSpan> after = TabParser.barSpans(pasted);
        assertEquals(5, after.size());
        assertSameColumns(TAB, block, TabBlocks.copyBar(pasted, after.get(4)));
    }

    @Test
    void blockFromExternalTextPadsShortLines() {
        String external = "e|------3-|\nE|--1------|\n";
        BarBlock block = TabBlocks.blockFromText(external);
        assertEquals(2, block.lines().size());
        assertEquals(10, block.width()); // contents include the trailing separators
        String pasted = TabBlocks.pasteBar(TAB, bars().get(1), block, true);
        List<BarSpan> after = TabParser.barSpans(pasted);
        assertEquals(3, after.size());
        BarBlock extracted = TabBlocks.copyBar(pasted, after.get(2));
        assertEquals("------3--|", extracted.contentFor(5)); // padded to block width (incl. separator)
        assertEquals("--1------|", extracted.contentFor(0));
    }

    @Test
    void barAtCharFindsTheBarUnderTheCaret() {
        BarSpan first = bars().get(0);
        int insideFirstBar = first.system().contentOffsets()[0] + 5;
        BarSpan found = TabBlocks.barAtChar(TAB, insideFirstBar);
        assertNotNull(found);
        assertSameColumns(first, found);
        assertNull(TabBlocks.barAtChar(TAB, 0), "position outside any string line finds nothing");
    }

    @Test
    void editingOperationsLeaveNonTabLinesAlone() {
        String withLyrics = "Riff A:\n" + TAB + "\nRiff B:\n";
        String edited = TabBlocks.duplicateBar(withLyrics, TabParser.barSpans(withLyrics).get(1));
        assertTrue(edited.startsWith("Riff A:\n"), "text before the tab must survive");
        assertTrue(edited.contains("Riff B:"), "text after the tab must survive");
        assertEquals(3, TabParser.barSpans(edited).size());
    }

    @Test
    void systemsExposeOriginalLabels() {
        List<TabSystem> systems = TabParser.systems(TAB);
        TabSystem sys = systems.get(0);
        assertEquals(6, sys.stringIndices().length);
        assertEquals('E', sys.labels()[0]);
        assertEquals('e', sys.labels()[5]);
        assertEquals(-1, TabParser.stringIndexOf('z'));
    }

    /* BarSpan/TabSystem carry arrays, so record equals is identity-based —
       compare the meaningful columns instead. */
    private static void assertSameColumns(String sourceText, BarBlock expected, BarBlock actual) {
        assertEquals(expected.width(), actual.width());
        for (int s = 0; s < 6; s++) {
            assertEquals(expected.contentFor(s), actual.contentFor(s),
                    "string " + s + " columns must match");
        }
    }

    private static void assertSameColumns(BarSpan expected, BarSpan actual) {
        assertEquals(expected.startCol(), actual.startCol());
        assertEquals(expected.endColEx(), actual.endColEx());
        assertEquals(expected.system().width(), actual.system().width());
    }
}
