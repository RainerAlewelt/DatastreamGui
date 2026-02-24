package dyn;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class BarPanel extends DraggablePanel {

    public String variableName;
    private final Label label = new Label();
    private final Region barFill = new Region();
    private final StackPane barTrack = new StackPane();
    private Color color = Color.web("#3399ff");

    private double minVal = 0;
    private double maxVal = 1;
    private double currentVal = 0;

    public BarPanel() {
        this(null);
    }

    public BarPanel(String variableName) {
        this.variableName = variableName;

        label.setStyle("-fx-font-size: 13; -fx-font-weight: bold;");
        label.setPadding(new Insets(2, 4, 0, 4));

        barTrack.setStyle("-fx-background-color: #e0e0e0; -fx-background-radius: 3;");
        barTrack.setMinHeight(20);
        barTrack.setPrefHeight(20);

        barFill.setStyle("-fx-background-color: #3399ff; -fx-background-radius: 3;");
        barFill.setMinHeight(20);
        barFill.setPrefHeight(20);
        barFill.setMaxWidth(0);

        barTrack.getChildren().add(barFill);
        StackPane.setAlignment(barFill, javafx.geometry.Pos.CENTER_LEFT);

        getChildren().addAll(label, barTrack);
        setPrefSize(220, 60);

        // Layout label on top, bar below
        label.setLayoutX(4);
        label.setLayoutY(2);

        layoutBoundsProperty().addListener((obs, o, n) -> layoutBar());
        widthProperty().addListener((obs, o, n) -> layoutBar());
        heightProperty().addListener((obs, o, n) -> layoutBar());
    }

    public void setColor(Color c) {
        this.color = c;
        barFill.setStyle("-fx-background-color: " + NumericPanel.toHex(c) + "; -fx-background-radius: 3;");
    }

    public Color getColor() {
        return color;
    }

    private void layoutBar() {
        double pad = 6;
        barTrack.setLayoutX(pad);
        barTrack.setLayoutY(24);
        barTrack.setPrefWidth(getWidth() - pad * 2);
        barTrack.setPrefHeight(Math.max(16, getHeight() - 34));
        updateFillWidth();
    }

    public void update(double v) {
        currentVal = v;

        // Expand observed range
        if (v < minVal) minVal = v;
        if (v > maxVal) maxVal = v;
        // Ensure a non-zero range
        if (maxVal - minVal < 1e-9) {
            maxVal = minVal + 1;
        }

        label.setText(String.format("%s: %.2f", variableName != null ? variableName : "?", v));
        updateFillWidth();
    }

    private void updateFillWidth() {
        double range = maxVal - minVal;
        double fraction = (range > 0) ? (currentVal - minVal) / range : 0;
        fraction = Math.max(0, Math.min(1, fraction));
        double trackWidth = barTrack.getPrefWidth();
        barFill.setMaxWidth(trackWidth * fraction);
        barFill.setPrefWidth(trackWidth * fraction);
    }
}
