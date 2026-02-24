package dyn;

import javafx.scene.control.Label;
import javafx.scene.paint.Color;

public class NumericPanel extends DraggablePanel {
    public final Label label = new Label();
    public String variableName;
    private Color color = Color.BLACK;

    public NumericPanel() {
        this(null);
    }

    public NumericPanel(String variableName) {
        this.variableName = variableName;
        setPrefSize(150,60);
        label.setStyle("-fx-font-size:18");
        getChildren().add(label);
    }

    public void setColor(Color c) {
        this.color = c;
        label.setStyle("-fx-font-size:18; -fx-text-fill: " + toHex(c) + ";");
    }

    public Color getColor() {
        return color;
    }

    public void update(double v) {
        if (variableName != null) {
            label.setText(variableName + ": " + String.format("%.2f", v));
        } else {
            label.setText(String.format("%.2f", v));
        }
    }

    static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }
}
