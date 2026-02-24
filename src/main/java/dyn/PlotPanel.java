package dyn;

import javafx.scene.chart.*;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import java.util.*;

public class PlotPanel extends DraggablePanel {

    public static int TIME_WINDOW = 50; // Default time window
    public final LineChart<Number,Number> chart;
    public final Map<String, XYChart.Series<Number,Number>> seriesMap = new HashMap<>();
    public final Map<String, Color> colorMap = new HashMap<>();
    private int t = 0;
    private static final int MAX_POINTS = 1000;
    private double lastX, lastY;

    public PlotPanel() {
        NumberAxis x = new NumberAxis(0, TIME_WINDOW, 5);
        NumberAxis y = new NumberAxis(0, 100, 10);
        x.setLabel("Time");
        y.setLabel("Value");

        chart = new LineChart<>(x,y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);

        getChildren().add(chart);
        chart.prefWidthProperty().bind(this.widthProperty());
        chart.prefHeightProperty().bind(this.heightProperty());

        setPrefSize(400,250);

        enableZoom();
        enablePan();
        enableVariableContextMenu();
    }

    public void addVariable(String name) {
        addVariable(name, null);
    }

    public void addVariable(String name, Color color) {
        if(seriesMap.containsKey(name)) return;
        XYChart.Series<Number,Number> s = new XYChart.Series<>();
        s.setName(name);
        seriesMap.put(name,s);
        if (color != null) {
            colorMap.put(name, color);
        }
        chart.getData().add(s);

        // Apply color CSS after the node is created by the chart
        if (color != null) {
            String hex = NumericPanel.toHex(color);
            s.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-stroke: " + hex + ";");
                }
            });
            if (s.getNode() != null) {
                s.getNode().setStyle("-fx-stroke: " + hex + ";");
            }
        }
    }

    public void removeVariable(String name) {
        XYChart.Series<Number,Number> s = seriesMap.remove(name);
        if(s!=null) chart.getData().remove(s);
    }

    public void update(Map<String,Double> values) {
        for (Map.Entry<String, XYChart.Series<Number, Number>> e : seriesMap.entrySet()) {
            Double v = values.get(e.getKey());
            if(v!=null){
                e.getValue().getData().add(new XYChart.Data<>(t,v));
                if(e.getValue().getData().size()>MAX_POINTS)
                    e.getValue().getData().remove(0);
            }
        }

        // Auto-scale Y-axis
        NumberAxis yAxis = (NumberAxis) chart.getYAxis();
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (XYChart.Series<Number, Number> s : seriesMap.values()) {
            for (XYChart.Data<Number, Number> d : s.getData()) {
                double val = d.getYValue().doubleValue();
                if (val < min) min = val;
                if (val > max) max = val;
            }
        }
        if (min < max) {
            yAxis.setLowerBound(min - 1);
            yAxis.setUpperBound(max + 1);
        }

        // Dynamic time window X-axis
        NumberAxis xAxis = (NumberAxis) chart.getXAxis();
        if (t > TIME_WINDOW) {
            xAxis.setLowerBound(t - TIME_WINDOW);
            xAxis.setUpperBound(t);
        } else {
            xAxis.setLowerBound(0);
            xAxis.setUpperBound(TIME_WINDOW);
        }

        t++;
    }

    private void enableVariableContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        chart.setOnContextMenuRequested(e -> {
            contextMenu.getItems().clear();
            for (String var : new ArrayList<>(seriesMap.keySet())) {
                MenuItem item = new MenuItem("Remove " + var);
                item.setOnAction(ev -> removeVariable(var));
                contextMenu.getItems().add(item);
            }
            if (!contextMenu.getItems().isEmpty()) {
                contextMenu.show(chart, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
    }

    private void enableZoom() {
        chart.setOnScroll(e -> {
            NumberAxis xAxis = (NumberAxis) chart.getXAxis();
            NumberAxis yAxis = (NumberAxis) chart.getYAxis();
            double factor = (e.getDeltaY() > 0) ? 0.9 : 1.1;
            double xRange = xAxis.getUpperBound() - xAxis.getLowerBound();
            double yRange = yAxis.getUpperBound() - yAxis.getLowerBound();
            double xCenter = (xAxis.getUpperBound() + xAxis.getLowerBound()) / 2;
            double yCenter = (yAxis.getUpperBound() + yAxis.getLowerBound()) / 2;
            xAxis.setLowerBound(xCenter - xRange * factor / 2);
            xAxis.setUpperBound(xCenter + xRange * factor / 2);
            yAxis.setLowerBound(yCenter - yRange * factor / 2);
            yAxis.setUpperBound(yCenter + yRange * factor / 2);
        });
    }

    private void enablePan() {
        chart.setOnMousePressed(e -> {
            if(e.getButton() == MouseButton.SECONDARY){
                lastX = e.getX();
                lastY = e.getY();
            }
        });

        chart.setOnMouseDragged(e -> {
            if(e.getButton() == MouseButton.SECONDARY){
                NumberAxis xAxis = (NumberAxis) chart.getXAxis();
                NumberAxis yAxis = (NumberAxis) chart.getYAxis();
                double dx = e.getX() - lastX;
                double dy = e.getY() - lastY;
                double xScale = (xAxis.getUpperBound() - xAxis.getLowerBound()) / chart.getWidth();
                double yScale = (yAxis.getUpperBound() - yAxis.getLowerBound()) / chart.getHeight();
                xAxis.setLowerBound(xAxis.getLowerBound() - dx * xScale);
                xAxis.setUpperBound(xAxis.getUpperBound() - dx * xScale);
                yAxis.setLowerBound(yAxis.getLowerBound() + dy * yScale);
                yAxis.setUpperBound(yAxis.getUpperBound() + dy * yScale);
                lastX = e.getX();
                lastY = e.getY();
            }
        });
    }
}
