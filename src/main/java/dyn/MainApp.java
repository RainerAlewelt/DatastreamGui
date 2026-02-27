package dyn;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;


public class MainApp extends Application {

    private DataStream stream;
    private Pane workspace;

    @Override
    public void start(Stage stage) {

        workspace = new Pane();
        VBox controls = new VBox(10);
        controls.setPrefWidth(200);
        controls.setPadding(new javafx.geometry.Insets(10));

        // ====== Connection status area ======
        Label lblConn = new Label("Connection");
        lblConn.setStyle("-fx-font-weight: bold;");

        Label statusLabel = new Label("Disconnected");
        statusLabel.setStyle("-fx-text-fill: #cc3333;");
        statusLabel.setWrapText(true);

        Button btnConnect = new Button("Connect...");
        btnConnect.setMaxWidth(Double.MAX_VALUE);
        btnConnect.setStyle("-fx-background-color: #339933; -fx-text-fill: white;");

        Button btnDisconnect = new Button("Disconnect");
        btnDisconnect.setMaxWidth(Double.MAX_VALUE);
        btnDisconnect.setStyle("-fx-background-color: #cc3333; -fx-text-fill: white;");
        btnDisconnect.setVisible(false);
        btnDisconnect.setManaged(false);

        // ====== Variable selection (starts empty) ======
        Label lbl = new Label("Variable:");
        ComboBox<String> varCombo = new ComboBox<>();
        varCombo.setPromptText("Connect to see variables");
        varCombo.setMaxWidth(Double.MAX_VALUE);
        varCombo.setDisable(true);

        btnConnect.setOnAction(ev -> {
            ConnectionWindow cw = new ConnectionWindow(stage, ds -> {
                // Callback: a new DataStream has been created and started
                if (stream != null) stream.stop();
                stream = ds;
                rewireListeners();
                statusLabel.setText("Connected: " + ds.getConnectionInfo());
                statusLabel.setStyle("-fx-text-fill: #339933;");
                btnConnect.setVisible(false);
                btnConnect.setManaged(false);
                btnDisconnect.setVisible(true);
                btnDisconnect.setManaged(true);

                // Populate variable dropdown from stream metadata
                varCombo.getItems().clear();
                varCombo.getItems().addAll(ds.getVariableNames());
                varCombo.setDisable(false);
                varCombo.setPromptText("Select variable");
            });
            cw.show();
        });

        btnDisconnect.setOnAction(ev -> {
            if (stream != null) {
                stream.stop();
                stream = null;
            }
            statusLabel.setText("Disconnected");
            statusLabel.setStyle("-fx-text-fill: #cc3333;");
            btnDisconnect.setVisible(false);
            btnDisconnect.setManaged(false);
            btnConnect.setVisible(true);
            btnConnect.setManaged(true);

            // Clear and disable variable dropdown
            varCombo.getItems().clear();
            varCombo.setDisable(true);
            varCombo.setPromptText("Connect to see variables");
        });

        controls.getChildren().addAll(
                lblConn, statusLabel, btnConnect, btnDisconnect,
                new Separator());

        // Display type dropdown
        Label lblDisplay = new Label("Display as:");
        ComboBox<String> displayCombo = new ComboBox<>();
        displayCombo.setPromptText("Select display type");
        displayCombo.setMaxWidth(Double.MAX_VALUE);

        // Refreshes the display type dropdown with current plot list
        Runnable refreshDisplayOptions = () -> {
            String current = displayCombo.getValue();
            displayCombo.getItems().clear();
            displayCombo.getItems().add("Numerical Value");
            displayCombo.getItems().add("Horizontal Bar");
            displayCombo.getItems().add("New Line Plot");

            int plotIndex = 1;
            for (javafx.scene.Node n : workspace.getChildren()) {
                if (n instanceof PlotPanel pp) {
                    String vars = String.join(", ", pp.seriesMap.keySet());
                    displayCombo.getItems().add("Plot " + plotIndex + " [" + vars + "]");
                    plotIndex++;
                }
            }

            if (current != null && displayCombo.getItems().contains(current)) {
                displayCombo.setValue(current);
            }
        };

        refreshDisplayOptions.run();

        // Refresh when workspace children change (plots added/removed)
        workspace.getChildren().addListener(
                (javafx.collections.ListChangeListener<javafx.scene.Node>) c -> refreshDisplayOptions.run());

        // Color picker
        Label lblColor = new Label("Color:");
        ColorPicker colorPicker = new ColorPicker(Color.web("#3399ff"));
        colorPicker.setMaxWidth(Double.MAX_VALUE);

        // Add button
        Button btnAdd = new Button("Add");
        btnAdd.setMaxWidth(Double.MAX_VALUE);
        btnAdd.setOnAction(ev -> {
            String selectedVar = varCombo.getValue();
            String displayType = displayCombo.getValue();

            if (selectedVar == null || displayType == null) return;

            Color chosenColor = colorPicker.getValue();

            if (displayType.equals("Numerical Value")) {
                NumericPanel n = new NumericPanel(selectedVar);
                n.setColor(chosenColor);
                n.relocate(250, 300);
                workspace.getChildren().add(n);
                if (stream != null) {
                    stream.addListener(data -> Platform.runLater(
                            () -> n.update(data.getOrDefault(selectedVar, 0.0))));
                }

            } else if (displayType.equals("Horizontal Bar")) {
                BarPanel bar = new BarPanel(selectedVar);
                bar.setColor(chosenColor);
                bar.relocate(250, 300);
                workspace.getChildren().add(bar);
                if (stream != null) {
                    stream.addListener(data -> Platform.runLater(
                            () -> bar.update(data.getOrDefault(selectedVar, 0.0))));
                }

            } else if (displayType.equals("New Line Plot")) {
                PlotPanel plot = createNewPlot();
                plot.addVariable(selectedVar, chosenColor);
                refreshDisplayOptions.run();

            } else if (displayType.startsWith("Plot ")) {
                // Extract the plot index from "Plot N [...]"
                try {
                    int bracketPos = displayType.indexOf(" [");
                    int plotIdx = Integer.parseInt(displayType.substring(5, bracketPos)) - 1;
                    int currentIdx = 0;
                    for (javafx.scene.Node n : workspace.getChildren()) {
                        if (n instanceof PlotPanel) {
                            if (currentIdx == plotIdx) {
                                ((PlotPanel) n).addVariable(selectedVar, chosenColor);
                                break;
                            }
                            currentIdx++;
                        }
                    }
                    refreshDisplayOptions.run();
                } catch (Exception ex) {
                    // ignore malformed selection
                }
            }
        });

        controls.getChildren().addAll(lbl, varCombo, lblDisplay, displayCombo, lblColor, colorPicker, btnAdd);

        // --- Remove variable from plot section ---
        Separator sep = new Separator();
        Label lblRemove = new Label("Remove from Plot:");
        ComboBox<String> plotPickerCombo = new ComboBox<>();
        plotPickerCombo.setPromptText("Select plot");
        plotPickerCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> varInPlotCombo = new ComboBox<>();
        varInPlotCombo.setPromptText("Select variable");
        varInPlotCombo.setMaxWidth(Double.MAX_VALUE);

        // Refresh the plot picker with current plots
        Runnable refreshPlotPicker = () -> {
            String current = plotPickerCombo.getValue();
            plotPickerCombo.getItems().clear();
            int plotIndex = 1;
            for (javafx.scene.Node n : workspace.getChildren()) {
                if (n instanceof PlotPanel pp) {
                    String vars = String.join(", ", pp.seriesMap.keySet());
                    plotPickerCombo.getItems().add("Plot " + plotIndex + " [" + vars + "]");
                    plotIndex++;
                }
            }
            if (current != null && plotPickerCombo.getItems().contains(current)) {
                plotPickerCombo.setValue(current);
            } else {
                varInPlotCombo.getItems().clear();
            }
        };

        refreshPlotPicker.run();

        // When workspace children change, refresh plot picker
        workspace.getChildren().addListener(
                (javafx.collections.ListChangeListener<javafx.scene.Node>) c -> refreshPlotPicker.run());

        // When a plot is selected, show its variables
        plotPickerCombo.setOnAction(ev -> {
            varInPlotCombo.getItems().clear();
            String selected = plotPickerCombo.getValue();
            if (selected == null || !selected.startsWith("Plot ")) return;
            try {
                int bracketPos = selected.indexOf(" [");
                int plotIdx = Integer.parseInt(selected.substring(5, bracketPos)) - 1;
                int currentIdx = 0;
                for (javafx.scene.Node n : workspace.getChildren()) {
                    if (n instanceof PlotPanel) {
                        if (currentIdx == plotIdx) {
                            varInPlotCombo.getItems().addAll(((PlotPanel) n).seriesMap.keySet());
                            break;
                        }
                        currentIdx++;
                    }
                }
            } catch (Exception ex) { /* ignore */ }
        });

        Button btnRemoveVar = new Button("Remove Variable");
        btnRemoveVar.setMaxWidth(Double.MAX_VALUE);
        btnRemoveVar.setOnAction(ev -> {
            String selectedPlot = plotPickerCombo.getValue();
            String selectedVar = varInPlotCombo.getValue();
            if (selectedPlot == null || selectedVar == null) return;
            try {
                int bracketPos = selectedPlot.indexOf(" [");
                int plotIdx = Integer.parseInt(selectedPlot.substring(5, bracketPos)) - 1;
                int currentIdx = 0;
                for (javafx.scene.Node n : workspace.getChildren()) {
                    if (n instanceof PlotPanel) {
                        if (currentIdx == plotIdx) {
                            ((PlotPanel) n).removeVariable(selectedVar);
                            break;
                        }
                        currentIdx++;
                    }
                }
            } catch (Exception ex) { /* ignore */ }
            // Refresh both dropdowns
            refreshDisplayOptions.run();
            refreshPlotPicker.run();
            varInPlotCombo.getItems().clear();
            plotPickerCombo.setValue(null);
        });

        controls.getChildren().addAll(sep, lblRemove, plotPickerCombo, varInPlotCombo, btnRemoveVar);

        // Time window slider
        Label lblWindow = new Label("Time Window:");
        Slider timeWindowSlider = new Slider(10, 200, PlotPanel.TIME_WINDOW);
        timeWindowSlider.setShowTickLabels(true);
        timeWindowSlider.setShowTickMarks(true);
        timeWindowSlider.setMajorTickUnit(50);
        controls.getChildren().addAll(lblWindow, timeWindowSlider);

        timeWindowSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                PlotPanel.TIME_WINDOW = newVal.intValue());

        // Save / Load layout buttons
        Button btnSave = new Button("Save Layout");
        Button btnLoad = new Button("Load Layout");
        Button btnExit = new Button("Exit");
        btnExit.setMaxWidth(Double.MAX_VALUE);
        btnExit.setStyle("-fx-background-color: #cc3333; -fx-text-fill: white;");
        btnExit.setOnAction(ev -> {
            TextInputDialog dialog = new TextInputDialog("layout.json");
            dialog.setTitle("Save Layout");
            dialog.setHeaderText("Save layout before exiting");
            dialog.setContentText("Filename:");
            dialog.showAndWait().ifPresent(filename -> {
                if (!filename.isBlank()) {
                    LayoutManager.save(workspace, filename);
                }
            });
            if (stream != null) stream.stop();
            Platform.exit();
        });

        controls.getChildren().addAll(btnSave, btnLoad, new Separator(), btnExit);

        btnSave.setOnAction(ev -> {
            TextInputDialog dialog = new TextInputDialog("layout.json");
            dialog.setTitle("Save Layout");
            dialog.setHeaderText("Save current layout");
            dialog.setContentText("Filename:");
            dialog.showAndWait().ifPresent(filename -> {
                if (!filename.isBlank()) {
                    LayoutManager.save(workspace, filename);
                }
            });
        });
        btnLoad.setOnAction(ev -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Load Layout");
            fc.setInitialDirectory(new File(System.getProperty("user.dir")));
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fc.showOpenDialog(stage);
            if (file != null) {
                workspace.getChildren().clear();
                LayoutManager.load(workspace, stream, file.getAbsolutePath());
            }
        });

        // Wrap controls in a ScrollPane for small screens
        ScrollPane controlsScroll = new ScrollPane(controls);
        controlsScroll.setFitToWidth(true);
        controlsScroll.setPrefWidth(220);
        controlsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        BorderPane root = new BorderPane();
        root.setLeft(controlsScroll);
        root.setCenter(workspace);

        Scene scene = new Scene(root, 1200, 700);
        stage.setScene(scene);
        stage.setTitle("Dynamic Multi-Variable Data GUI");
        stage.show();
    }

    /** Create a new PlotPanel and wire it to the current stream (if connected). */
    private PlotPanel createNewPlot() {
        PlotPanel plot = new PlotPanel();
        int offset = workspace.getChildren().size() * 20;
        plot.relocate(200 + offset, 50 + offset);
        workspace.getChildren().add(plot);
        if (stream != null) {
            stream.addListener(data -> Platform.runLater(() -> plot.update(data)));
        }
        return plot;
    }

    /**
     * Re-register listeners for all existing workspace panels on the current stream.
     * Called when (re)connecting so panels created before the connection still receive data.
     */
    private void rewireListeners() {
        if (stream == null) return;
        for (javafx.scene.Node n : workspace.getChildren()) {
            if (n instanceof PlotPanel plot) {
                stream.addListener(data -> Platform.runLater(() -> plot.update(data)));
            } else if (n instanceof BarPanel bar) {
                if (bar.variableName != null) {
                    stream.addListener(data -> Platform.runLater(
                            () -> bar.update(data.getOrDefault(bar.variableName, 0.0))));
                }
            } else if (n instanceof NumericPanel num) {
                if (num.variableName != null) {
                    stream.addListener(data -> Platform.runLater(
                            () -> num.update(data.getOrDefault(num.variableName, 0.0))));
                }
            }
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
