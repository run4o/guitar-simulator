package dev.guitar.tabplayer.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.guitar.tabplayer.tab.TabParser.BarSpan;
import dev.guitar.tabplayer.tab.TabParser.TabSystem;

/**
 * Bar-block operations on ASCII tab text: copy / cut / paste / duplicate /
 * delete / clear. A "bar" is a column range spanning all string lines of one
 * system (delimited by '|', or fixed 16-column blocks when a tab has no bar
 * lines). All operations are pure string surgery on the original text —
 * non-tab lines (lyrics etc.) are never touched.
 */
public final class TabBlocks {

    /** One string line of a copied block: which string it belongs to + its columns. */
    public record ClipLine(int stringIndex, char label, String content) {}

    /** A copied block, ready to paste (system clipboard text via {@link #blockToText}). */
    public record BarBlock(List<ClipLine> lines) {
        public int width() {
            int w = 0;
            for (ClipLine line : lines) {
                w = Math.max(w, line.content().length());
            }
            return w;
        }

        public String contentFor(int stringIndex) {
            for (ClipLine line : lines) {
                if (line.stringIndex() == stringIndex) {
                    return line.content();
                }
            }
            return null;
        }
    }

    private static final char FILL = '-';

    private TabBlocks() {}

    /* ------------------------------------------------------------------
     * Copy
     * ------------------------------------------------------------------ */

    public static BarBlock copyBar(String text, BarSpan span) {
        List<ClipLine> lines = new ArrayList<>();
        TabSystem sys = span.system();
        int barWidth = span.endColEx() - span.startCol();
        for (int t = 0; t < sys.stringIndices().length; t++) {
            int stringIndex = sys.stringIndices()[t];
            int offset = sys.contentOffsets()[t];
            int length = sys.contentLengths()[t];
            String content = substringOrPad(text, offset, length, span.startCol(), span.endColEx());
            lines.add(new ClipLine(stringIndex, sys.labels()[t], content));
        }
        lines.sort(Comparator.comparingInt(ClipLine::stringIndex));
        return new BarBlock(List.copyOf(lines));
    }

    /** Columns [from, to) of a line, padded with dashes beyond the line's end. */
    private static String substringOrPad(String text, int contentOffset, int contentLength,
                                         int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int col = from; col < to; col++) {
            int pos = contentOffset + col;
            sb.append(col < contentLength ? text.charAt(pos) : FILL);
        }
        return padToWidth(sb.toString(), to - from);
    }

    /**
     * Pads content to an exact column width. Dashes are inserted before a
     * trailing separator (never after it) so padded blocks keep their bar
     * line last and don't create phantom trailing bars.
     */
    static String padToWidth(String content, int width) {
        if (content.length() >= width) {
            return content;
        }
        int pad = width - content.length();
        if (content.endsWith("|")) {
            return content.substring(0, content.length() - 1) + String.valueOf(FILL).repeat(pad) + "|";
        }
        return content + String.valueOf(FILL).repeat(pad);
    }

    /* ------------------------------------------------------------------
     * Clipboard text round-trip
     * ------------------------------------------------------------------ */

    public static String blockToText(BarBlock block) {
        StringBuilder sb = new StringBuilder();
        for (ClipLine line : block.lines()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.label()).append('|').append(line.content());
        }
        return sb.toString();
    }

    public static BarBlock blockFromText(String text) {
        Set<String> warnings = new LinkedHashSet<>();
        List<ClipLine> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            var matcher = TabParser.LABEL_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            char label = matcher.group(1).charAt(0);
            int stringIndex = TabParser.stringIndexOf(label);
            if (stringIndex < 0) {
                continue;
            }
            lines.add(new ClipLine(stringIndex, label, matcher.group(2)));
        }
        return new BarBlock(List.copyOf(lines));
    }

    /* ------------------------------------------------------------------
     * Paste / delete / clear / duplicate
     * ------------------------------------------------------------------ */

    /** Inserts the block's columns before or after the target bar. */
    public static String pasteBar(String text, BarSpan target, BarBlock block, boolean after) {
        int insertCol = after ? target.endColEx() : target.startCol();
        int blockWidth = block.width();
        List<int[]> offsets = new ArrayList<>();   // insert position, desc-applied
        List<String> payloads = new ArrayList<>();
        TabSystem sys = target.system();
        for (int t = 0; t < sys.stringIndices().length; t++) {
            int stringIndex = sys.stringIndices()[t];
            int offset = sys.contentOffsets()[t];
            int length = sys.contentLengths()[t];
            String content = block.contentFor(stringIndex);
            if (content == null) {
                content = String.valueOf(FILL).repeat(blockWidth);
            }
            // pad the target line up to the insertion column, then insert the block
            int at = offset + Math.min(insertCol, length);
            String payload = (insertCol > length ? String.valueOf(FILL).repeat(insertCol - length) : "")
                    + padToWidth(content, blockWidth);
            offsets.add(new int[]{at});
            payloads.add(payload);
        }
        return applyInsertions(text, offsets, payloads);
    }

    public static String deleteBar(String text, BarSpan span) {
        List<int[]> ranges = new ArrayList<>(); // absolute [start, end) removals
        TabSystem sys = span.system();
        for (int t = 0; t < sys.stringIndices().length; t++) {
            int offset = sys.contentOffsets()[t];
            int length = sys.contentLengths()[t];
            int start = offset + Math.min(span.startCol(), length);
            int end = offset + Math.min(span.endColEx(), length);
            if (end > start) {
                ranges.add(new int[]{start, end});
            }
        }
        return applyRemovals(text, ranges);
    }

    public static String clearBar(String text, BarSpan span) {
        List<int[]> positions = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        TabSystem sys = span.system();
        for (int t = 0; t < sys.stringIndices().length; t++) {
            int offset = sys.contentOffsets()[t];
            int length = sys.contentLengths()[t];
            StringBuilder sb = new StringBuilder();
            for (int col = span.startCol(); col < span.endColEx() && col < length; col++) {
                char c = text.charAt(offset + col);
                sb.append(c == '|' ? '|' : FILL);
            }
            if (sb.length() > 0) {
                positions.add(new int[]{offset + span.startCol()});
                replacements.add(sb.toString());
            }
        }
        return applyReplacements(text, positions, replacements);
    }

    public static String duplicateBar(String text, BarSpan span) {
        return pasteBar(text, span, copyBar(text, span), true);
    }

    /** The bar containing the given absolute character position, or null. */
    public static BarSpan barAtChar(String text, int charPos) {
        for (BarSpan span : TabParser.barSpans(text)) {
            TabSystem sys = span.system();
            for (int t = 0; t < sys.stringIndices().length; t++) {
                int start = sys.contentOffsets()[t] + span.startCol();
                int end = sys.contentOffsets()[t] + Math.min(span.endColEx(), sys.contentLengths()[t] + 1);
                if (charPos >= start && charPos <= end) {
                    return span;
                }
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------
     * Offset-safe text editing (apply from the end so positions stay valid)
     * ------------------------------------------------------------------ */

    private static String applyRemovals(String text, List<int[]> ranges) {
        ranges.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (int[] range : ranges) {
            sb.delete(range[0], range[1]);
        }
        return sb.toString();
    }

    private static String applyInsertions(String text, List<int[]> offsets, List<String> payloads) {
        Integer[] order = new Integer[offsets.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingInt((Integer i) -> offsets.get(i)[0]).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (int i : order) {
            sb.insert(offsets.get(i)[0], payloads.get(i));
        }
        return sb.toString();
    }

    private static String applyReplacements(String text, List<int[]> offsets, List<String> payloads) {
        // replace [offset, offset + payload.length) — compute ends first, apply desc
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            ranges.add(new int[]{offsets.get(i)[0], offsets.get(i)[0] + payloads.get(i).length()});
        }
        ranges.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            // payloads were collected in ascending offset order; find the matching one
            for (int j = 0; j < offsets.size(); j++) {
                if (offsets.get(j)[0] == range[0]) {
                    sb.replace(range[0], range[1], payloads.get(j));
                    break;
                }
            }
        }
        return sb.toString();
    }
}
