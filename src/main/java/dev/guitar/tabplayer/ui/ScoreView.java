package dev.guitar.tabplayer.ui;

import java.util.function.BiConsumer;

import dev.guitar.tabplayer.engine.GuitarEngine.RenderCommand;
import dev.guitar.tabplayer.tab.TabParser;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.geometry.VPos;

/**
 * Score/cue view: renders the compiled timeline — what the engine will actually
 * play — as six string lanes on a real-time axis, with a bar-number ruler,
 * beat grid, sweeping playback cursor, and click-to-select bar blocks.
 *
 * Lane labels are a live display derived from the selected tuning; they are
 * deliberately decoupled from the ASCII text's marker letters.
 */
public class ScoreView extends Canvas {

    public static final double GUTTER = 56;   // used when labels are drawn in-canvas
    public static final double PPS = 140;     // pixels per second
    public static final double HEADER_H = 24;
    public static final double PADDING = 6;
    private static final double EDGE_L = 2;   // lane stripe inset when labels are external

    private static final Color BG = Color.web("#0A0C14");
    private static final Color LANE_A = Color.web("#151823");
    private static final Color LANE_B = Color.web("#111321");
    private static final Color EDGE = Color.web("#2A2E40");
    private static final Color GRID = Color.rgb(255, 255, 255, 0.05);
    private static final Color LABEL = Color.web("#8B8E99");
    private static final Color CYAN = Color.web("#00BCD4");
    private static final Color RED = Color.web("#E01212");
    private static final Color SILVER = Color.web("#EFEFEF");
    private static final Color INK = Color.web("#0F111A");

    private TabParser.TabTimeline timeline;
    private final String[] laneLabels = {"-", "-", "-", "-", "-", "-"};
    private final boolean externalLabels; // true: labels live in a fixed column outside the scroll pane
    private double cursorSeconds = -1;
    private int selFirst = -1;
    private int selLast = -1;
    private double minContentWidth = 900;
    private double contentSeconds = 0;
    private BiConsumer<Integer, Boolean> onBarClick; // (global bar index, extend selection)

    public ScoreView() {
        this(false);
    }

    public ScoreView(boolean externalLabels) {
        super(900, 300);
        this.externalLabels = externalLabels;
        widthProperty().addListener((o, ov, nv) -> draw());
        heightProperty().addListener((o, ov, nv) -> draw());
        setOnMouseClicked(this::onClick);
        draw();
    }

    public void setOnBarClick(BiConsumer<Integer, Boolean> handler) {
        onBarClick = handler;
    }

    public void setLaneLabels(String[] labels) {
        for (int i = 0; i < 6 && i < labels.length; i++) {
            laneLabels[i] = labels[i];
        }
        draw();
    }

    public void setTimeline(TabParser.TabTimeline newTimeline) {
        timeline = newTimeline;
        // view ends shortly after the last event — the ring-out tail is dead space
        double lastEvent = 0;
        if (newTimeline != null && !newTimeline.commands().isEmpty()) {
            lastEvent = newTimeline.commands().get(newTimeline.commands().size() - 1).timeOffset();
        }
        contentSeconds = newTimeline == null ? 0 : Math.min(newTimeline.durationSeconds(), lastEvent + 0.8);
        updateWidth();
        draw();
    }

    /** The pane may be wider than the content — lanes then stretch to fill it. */
    public void setMinContentWidth(double viewportWidth) {
        minContentWidth = viewportWidth;
        updateWidth();
    }

    private void updateWidth() {
        double content = gutterX() + contentSeconds * PPS + 30;
        setWidth(Math.max(minContentWidth, content));
    }

    public void setCursorSeconds(double seconds) {
        if (seconds != cursorSeconds) {
            cursorSeconds = seconds;
            draw();
        }
    }

    public void setSelection(int first, int last) {
        selFirst = first;
        selLast = last;
        draw();
    }

    private void onClick(MouseEvent e) {
        if (timeline == null || onBarClick == null) {
            return;
        }
        double time = (e.getX() - gutterX()) / PPS;
        for (int i = 0; i < timeline.barTimes().size(); i++) {
            TabParser.BarTime bt = timeline.barTimes().get(i);
            if (time >= bt.startSeconds() && time <= bt.endSeconds()) {
                onBarClick.accept(i, e.isShiftDown());
                return;
            }
        }
    }

    /** Lane-stripe origin: 0 when the label gutter is a separate fixed column. */
    private double gutterX() {
        return externalLabels ? 0.0 : GUTTER;
    }

    private double laneHeight() {
        return Math.max(16, (getHeight() - HEADER_H - 2 * PADDING) / 6.0);
    }

    private double laneTop(int lane) {
        return HEADER_H + PADDING + lane * laneHeight();
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        double lh = laneHeight();
        double x0 = gutterX();

        // lane stripes + vertically centered labels
        g.setTextBaseline(VPos.CENTER);
        for (int lane = 0; lane < 6; lane++) {
            double top = laneTop(lane);
            g.setFill(lane % 2 == 0 ? LANE_A : LANE_B);
            g.fillRect(x0, top, w - x0 - EDGE_L, lh - 2);
            if (!externalLabels) {
                g.setFill(LABEL);
                g.setFont(Font.font("Share Tech Mono", 13));
                g.fillText(laneLabels[lane], 12, top + lh / 2.0);
            }
        }

        if (timeline == null) {
            return;
        }

        // bar separators at bar STARTS (plus one closing line), beat grid inside
        int lastBar = timeline.barTimes().size() - 1;
        for (int i = 0; i < timeline.barTimes().size(); i++) {
            TabParser.BarTime bt = timeline.barTimes().get(i);
            double startX = x0 + bt.startSeconds() * PPS;
            g.setStroke(EDGE);
            g.setLineWidth(1);
            g.strokeLine(startX, HEADER_H, startX, h - PADDING);
            double beat = (bt.endSeconds() - bt.startSeconds()) / 4.0;
            g.setStroke(GRID);
            for (int k = 1; k < 4; k++) {
                double bx = x0 + (bt.startSeconds() + k * beat) * PPS;
                g.strokeLine(bx, HEADER_H, bx, h - PADDING);
            }
        }
        if (lastBar >= 0) {
            double endX = x0 + timeline.barTimes().get(lastBar).endSeconds() * PPS;
            g.setStroke(EDGE);
            g.strokeLine(endX, HEADER_H, endX, h - PADDING);
        }

        // selection overlay
        if (selFirst >= 0 && selLast >= 0 && selFirst < timeline.barTimes().size()
                && selLast < timeline.barTimes().size()) {
            int f = Math.min(selFirst, selLast);
            int l = Math.max(selFirst, selLast);
            double sx0 = x0 + timeline.barTimes().get(f).startSeconds() * PPS;
            double sx1 = x0 + timeline.barTimes().get(l).endSeconds() * PPS;
            g.setFill(Color.rgb(0, 188, 212, 0.15));
            g.fillRect(sx0, HEADER_H, sx1 - sx0, h - HEADER_H - PADDING);
            g.setStroke(CYAN);
            g.strokeRect(sx0, HEADER_H, sx1 - sx0, h - HEADER_H - PADDING);
        }

        // events
        for (TabParser.TabEvent event : timeline.events()) {
            RenderCommand cmd = event.command();
            double x = x0 + cmd.timeOffset() * PPS;
            double laneCenter = laneTop(cmd.stringIndex()) + lh / 2.0;
            switch (cmd.type()) {
                case RenderCommandType.PLUCK -> {
                    g.setFill(CYAN);
                    g.fillRoundRect(x - 6, laneCenter - 5, 12, 10, 3, 3);
                }
                case RenderCommandType.LEGATO -> {
                    g.setStroke(CYAN);
                    g.setLineWidth(1.5);
                    g.strokeRoundRect(x - 6, laneCenter - 5, 12, 10, 3, 3);
                }
                case RenderCommandType.BEND -> {
                    g.setFill(RED);
                    if (cmd.a() >= 0) {
                        g.fillPolygon(new double[]{x - 5, x + 5, x},
                                new double[]{laneCenter + 5, laneCenter + 5, laneCenter - 5}, 3);
                    } else {
                        g.fillPolygon(new double[]{x - 5, x + 5, x},
                                new double[]{laneCenter - 5, laneCenter - 5, laneCenter + 5}, 3);
                    }
                }
                case RenderCommandType.MUTE -> {
                    g.setStroke(SILVER);
                    g.setLineWidth(1.5);
                    g.strokeLine(x - 4, laneCenter - 4, x + 4, laneCenter + 4);
                    g.strokeLine(x - 4, laneCenter + 4, x + 4, laneCenter - 4);
                }
                default -> { }
            }
        }

        // bar-number ruler: one numbered square at each bar start
        for (int i = 0; i < timeline.barTimes().size(); i++) {
            TabParser.BarTime bt = timeline.barTimes().get(i);
            double x = x0 + bt.startSeconds() * PPS + 2;
            boolean selected = i >= Math.min(selFirst, selLast) && i <= Math.max(selFirst, selLast)
                    && selFirst >= 0;
            g.setFill(selected ? SILVER : CYAN);
            g.fillRoundRect(x, 4, 18, HEADER_H - 10, 3, 3);
            g.setFill(INK);
            g.setFont(Font.font("Share Tech Mono", 12));
            g.setTextBaseline(VPos.CENTER);
            g.fillText(String.valueOf(i + 1), x + 9, 4 + (HEADER_H - 10) / 2.0);
        }

        // playback cursor
        if (cursorSeconds >= 0) {
            double x = x0 + cursorSeconds * PPS;
            g.setStroke(CYAN);
            g.setLineWidth(1.5);
            g.strokeLine(x, HEADER_H, x, h - PADDING);
        }
    }

    /** Numeric constants mirroring GuitarEngine.EVENT_* (avoids a doc dependency). */
    private static final class RenderCommandType {
        static final int PLUCK = 0;
        static final int LEGATO = 1;
        static final int BEND = 2;
        static final int MUTE = 3;
    }
}
