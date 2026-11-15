package dev.guitar.tabplayer.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * Rotary knob control (studio-console FX section): drag vertically to turn,
 * double-click to reset to the default value. Canvas-drawn in the app palette.
 */
public class Knob extends Canvas {

    private static final double START_ANGLE = 135.0; // degrees, clockwise from +x
    private static final double SWEEP = 270.0;
    private static final Color STEEL_LIGHT = Color.web("#5A5B5B");
    private static final Color STEEL_DARK = Color.web("#2E2F2E");
    private static final Color EDGE = Color.web("#2A2E40");
    private static final Color CYAN = Color.web("#00BCD4");
    private static final Color SILVER = Color.web("#EFEFEF");

    private final double min;
    private final double max;
    private final double defaultValue;
    private final DoubleProperty value = new SimpleDoubleProperty();
    private double pressY;
    private double pressValue;

    public Knob(double min, double max, double initial, double size) {
        super(size, size);
        this.min = min;
        this.max = max;
        this.defaultValue = initial;
        value.set(initial);
        value.addListener((o, ov, nv) -> draw());
        setOnMousePressed(e -> {
            pressY = e.getSceneY();
            pressValue = value.get();
        });
        setOnMouseDragged(e -> {
            double dy = pressY - e.getSceneY();
            value.set(Math.max(min, Math.min(max, pressValue + dy / 150.0 * (max - min))));
        });
        setOnScroll(e -> value.set(scrollValue(e.getDeltaY(), value.get(), min, max)));
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                value.set(defaultValue);
            }
        });
        setCursor(Cursor.HAND);
        draw();
    }

    /**
     * Wheel-step mapping shared by knob and fader: one notch (deltaY ±40)
     * moves the value by 1/20th of the range; smooth wheels scale proportionally.
     */
    static double scrollValue(double deltaY, double current, double min, double max) {
        double step = (max - min) / 20.0;
        return Math.max(min, Math.min(max, current + (deltaY / 40.0) * step));
    }

    public DoubleProperty valueProperty() {
        return value;
    }

    public double getValue() {
        return value.get();
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.clearRect(0, 0, w, h);

        double cx = w / 2;
        double cy = h / 2;
        double r = Math.min(w, h) * 0.40;
        double frac = (value.get() - min) / (max - min);

        // tick marks around the sweep (5 major ticks, kept quiet)
        g.setStroke(Color.web("#33374A"));
        g.setLineWidth(1.2);
        for (int i = 0; i <= 4; i++) {
            double a = Math.toRadians(START_ANGLE + SWEEP * i / 4.0);
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            g.strokeLine(cx + cos * (r + 4), cy + sin * (r + 4),
                    cx + cos * (r + 7), cy + sin * (r + 7));
        }

        // cyan value arc
        g.setStroke(CYAN);
        g.setLineWidth(2.5);
        g.setLineCap(StrokeLineCap.ROUND);
        if (frac > 0.002) {
            g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, START_ANGLE, SWEEP * frac, ArcType.OPEN);
        }

        // body
        RadialGradient steel = new RadialGradient(0, 0, 0.35, 0.35, 1.0, true, CycleMethod.NO_CYCLE,
                new Stop(0, STEEL_LIGHT), new Stop(1, STEEL_DARK));
        g.setFill(steel);
        g.fillOval(cx - r + 2, cy - r + 2, 2 * r - 4, 2 * r - 4);
        g.setStroke(EDGE);
        g.setLineWidth(1.5);
        g.strokeOval(cx - r + 2, cy - r + 2, 2 * r - 4, 2 * r - 4);

        // pointer
        double a = Math.toRadians(START_ANGLE + SWEEP * frac);
        g.setStroke(SILVER);
        g.setLineWidth(2.5);
        g.strokeLine(cx + Math.cos(a) * r * 0.30, cy + Math.sin(a) * r * 0.30,
                cx + Math.cos(a) * r * 0.85, cy + Math.sin(a) * r * 0.85);
    }
}
