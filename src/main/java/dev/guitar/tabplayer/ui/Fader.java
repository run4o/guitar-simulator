package dev.guitar.tabplayer.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * Vertical channel fader: dark inset track, cyan fill below the thumb,
 * steel thumb cap. Generic linear range; click or drag anywhere to set.
 */
public class Fader extends Region {

    private final double min;
    private final double max;
    private final DoubleProperty value = new SimpleDoubleProperty();
    private final Region track = new Region();
    private final Region fill = new Region();
    private final Region thumb = new Region();
    private double pressY;
    private double pressValue;

    public Fader(double min, double max, double initial) {
        this.min = min;
        this.max = max;
        value.set(initial);
        track.getStyleClass().add("fader-track");
        fill.getStyleClass().add("fader-fill");
        thumb.getStyleClass().add("fader-thumb");
        track.setManaged(false);
        fill.setManaged(false);
        thumb.setManaged(false);
        getChildren().addAll(track, fill, thumb);
        setPrefSize(44, 160);
        setMinSize(34, 100);
        value.addListener((o, ov, nv) -> requestLayout());
        setOnMousePressed(this::updateFrom);
        setOnMouseDragged(this::updateFrom);
        setOnScroll(e -> value.set(Knob.scrollValue(e.getDeltaY(), value.get(), min, max)));
        setCursor(Cursor.HAND);
    }

    public DoubleProperty valueProperty() {
        return value;
    }

    public double getValue() {
        return value.get();
    }

    private void updateFrom(MouseEvent e) {
        double frac = fracFromY(e.getY(), getHeight());
        value.set(min + frac * (max - min));
    }

    /**
     * Interaction mapping: the top of the track is maximum, the bottom is
     * minimum (matching the on-screen fader layout).
     */
    static double fracFromY(double y, double height) {
        double usable = height - 24;
        if (usable <= 1) {
            return 0;
        }
        double fromTop = (y - 12) / usable;
        return Math.max(0, Math.min(1, 1.0 - fromTop));
    }

    private double frac() {
        return Math.max(0, Math.min(1, (value.get() - min) / (max - min)));
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double usable = h - 24;
        double f = frac();
        double x = w / 2 - 3;
        track.resizeRelocate(x, 12, 6, usable);
        fill.resizeRelocate(x, 12 + (1 - f) * usable, 6, f * usable);
        thumb.resizeRelocate(w / 2 - 15, 12 + (1 - f) * usable - 7, 30, 14);
    }
}
