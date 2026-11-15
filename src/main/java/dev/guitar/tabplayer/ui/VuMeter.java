package dev.guitar.tabplayer.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * LED-bar VU meter driven by the engine's real output level.
 * Horizontal (meter bridge) and vertical (next to the master fader) variants.
 */
public class VuMeter extends Canvas {

    private static final int SEGMENTS = 24;
    private static final Color CYAN = Color.web("#00BCD4");
    private static final Color RED = Color.web("#9B0808");
    private static final Color RED_LIT = Color.web("#E01212");
    private static final Color OFF = Color.web("#12141D");
    private static final Color RED_OFF = Color.web("#260A0A");

    private final boolean vertical;

    public VuMeter(double width, double height, boolean vertical) {
        super(width, height);
        this.vertical = vertical;
        setLevel(0);
    }

    /** level 0..1 (values above 1 light every segment, i.e. clip). */
    public void setLevel(double level) {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.clearRect(0, 0, w, h);

        double gap = 2;
        double segSize;
        if (vertical) {
            segSize = (h - (SEGMENTS - 1) * gap) / SEGMENTS;
        } else {
            segSize = (w - (SEGMENTS - 1) * gap) / SEGMENTS;
        }
        int lit = (int) Math.round(Math.max(0, Math.min(1, level)) * SEGMENTS);
        if (level > 1.0) {
            lit = SEGMENTS;
        }

        for (int i = 0; i < SEGMENTS; i++) {
            // fraction along the meter; i == SEGMENTS-1 is always the top / hot end
            double frac = i / (double) (SEGMENTS - 1);
            Color color;
            if (i < lit) {
                if (frac >= 0.85) {
                    color = RED_LIT;
                } else {
                    // brightness ramps up toward the hot end
                    color = Color.rgb(0, 188, 212, 0.45 + 0.55 * frac / 0.85);
                }
            } else {
                color = frac >= 0.85 ? RED_OFF : OFF;
            }
            g.setFill(color);
            if (vertical) {
                double y = h - (i + 1) * (segSize + gap) + gap; // segment 0 at the bottom
                g.fillRoundRect(0, y, w, segSize, 3, 3);
            } else {
                g.fillRoundRect(i * (segSize + gap), 0, segSize, h, 3, 3);
            }
        }
    }
}
