package dyn;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modal connection dialog with Unicast and Multicast tabs.
 * Each tab includes a XidML file chooser so the user can specify
 * the stream's parameter metadata before connecting.
 * The Multicast tab supports scanning for active IENA streams before connecting.
 */
public class ConnectionWindow {

    private final Stage dialog;
    private final Consumer<DataStream> onConnect;

    /** Table row model for discovered streams. */
    public static class StreamRow {
        private final SimpleStringProperty key;
        private final SimpleStringProperty sourceIp;
        private final SimpleIntegerProperty packetCount;
        private final SimpleStringProperty rate;
        private final int rawKey;

        public StreamRow(DataStream.StreamInfo info) {
            this.key = new SimpleStringProperty(info.keyHex());
            this.sourceIp = new SimpleStringProperty(info.sourceIp());
            this.packetCount = new SimpleIntegerProperty(info.packetCount());
            this.rate = new SimpleStringProperty(String.format("%.1f/s", info.packetsPerSecond()));
            this.rawKey = info.key();
        }

        public String getKey() { return key.get(); }
        public StringProperty keyProperty() { return key; }
        public String getSourceIp() { return sourceIp.get(); }
        public StringProperty sourceIpProperty() { return sourceIp; }
        public int getPacketCount() { return packetCount.get(); }
        public SimpleIntegerProperty packetCountProperty() { return packetCount; }
        public String getRate() { return rate.get(); }
        public StringProperty rateProperty() { return rate; }
        public int getRawKey() { return rawKey; }
    }

    public ConnectionWindow(Stage owner, Consumer<DataStream> onConnect) {
        this.onConnect = onConnect;

        dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle("Connect to Data Stream");
        dialog.setResizable(true);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().addAll(createUnicastTab(), createMulticastTab());

        Scene scene = new Scene(tabPane, 550, 550);
        dialog.setScene(scene);
    }

    public void show() {
        dialog.showAndWait();
    }

    // ===== XidML file chooser helper (shared by both tabs) =====

    /**
     * Create a XidML file selection row: label + text field + browse button + preview label.
     * Returns an array: [HBox row, TextField pathField, Label previewLabel].
     */
    private Object[] createXidMLChooser() {
        Label lbl = new Label("XidML File:");
        TextField pathField = new TextField();
        pathField.setPromptText("(optional) Browse for .xidml file");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button browseBtn = new Button("Browse...");
        browseBtn.setMinWidth(80);

        Label previewLabel = new Label();
        previewLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11;");
        previewLabel.setWrapText(true);

        browseBtn.setOnAction(ev -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select XidML File");
            fc.setInitialDirectory(new File(System.getProperty("user.dir")));
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("XidML Files", "*.xidml", "*.xml"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = fc.showOpenDialog(dialog);
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
                // Preview the parameters
                try {
                    List<DataStream.ParameterInfo> params = DataStream.loadXidML(file.getAbsolutePath());
                    StringBuilder sb = new StringBuilder();
                    sb.append(params.size()).append(" parameters: ");
                    for (int i = 0; i < params.size(); i++) {
                        if (i > 0) sb.append(", ");
                        if (i >= 8) { sb.append("..."); break; }
                        sb.append(params.get(i).name());
                    }
                    previewLabel.setStyle("-fx-text-fill: #339933; -fx-font-size: 11;");
                    previewLabel.setText(sb.toString());
                } catch (Exception ex) {
                    previewLabel.setStyle("-fx-text-fill: #cc3333; -fx-font-size: 11;");
                    previewLabel.setText("Error: " + ex.getMessage());
                }
            }
        });

        // Also validate when the text field changes manually
        pathField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank()) {
                previewLabel.setText("");
                return;
            }
            File f = new File(newVal.trim());
            if (f.isFile()) {
                try {
                    List<DataStream.ParameterInfo> params = DataStream.loadXidML(f.getAbsolutePath());
                    previewLabel.setStyle("-fx-text-fill: #339933; -fx-font-size: 11;");
                    previewLabel.setText(params.size() + " parameters loaded");
                } catch (Exception ex) {
                    previewLabel.setStyle("-fx-text-fill: #cc3333; -fx-font-size: 11;");
                    previewLabel.setText("Invalid XidML: " + ex.getMessage());
                }
            } else {
                previewLabel.setText("");
            }
        });

        HBox row = new HBox(8, lbl, pathField, browseBtn);
        row.setAlignment(Pos.CENTER_LEFT);

        return new Object[]{ row, pathField, previewLabel };
    }

    /**
     * Parse XidML from the given path field, or return null for default a-z parameters.
     * Shows errors on the errorLabel and returns null on failure.
     */
    private List<DataStream.ParameterInfo> parseXidMLOrDefault(TextField pathField, Label errorLabel) {
        String path = pathField.getText() == null ? "" : pathField.getText().trim();
        if (path.isEmpty()) {
            return null; // use default constructor (a-z)
        }
        try {
            List<DataStream.ParameterInfo> params = DataStream.loadXidML(path);
            if (params.isEmpty()) {
                errorLabel.setText("XidML file contains no parameters");
                return List.of(); // signal error with empty list
            }
            return params;
        } catch (Exception ex) {
            errorLabel.setText("Invalid XidML file: " + ex.getMessage());
            return List.of(); // signal error
        }
    }

    // ===== Unicast Tab =====

    private Tab createUnicastTab() {
        Tab tab = new Tab("Unicast");

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.TOP_LEFT);

        Label lblPort = new Label("Port:");
        TextField portField = new TextField("5000");
        portField.setMaxWidth(200);

        // XidML chooser
        Object[] xidml = createXidMLChooser();
        HBox xidmlRow = (HBox) xidml[0];
        TextField xidmlPath = (TextField) xidml[1];
        Label xidmlPreview = (Label) xidml[2];

        Button btnConnect = new Button("Connect");
        btnConnect.setStyle("-fx-background-color: #339933; -fx-text-fill: white;");
        btnConnect.setMaxWidth(200);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #cc3333;");

        btnConnect.setOnAction(ev -> {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                List<DataStream.ParameterInfo> params = parseXidMLOrDefault(xidmlPath, statusLabel);
                if (params != null && params.isEmpty()) return; // error already shown

                DataStream ds;
                if (params != null) {
                    ds = new DataStream(DataStream.Mode.UNICAST, port, null, -1, params);
                } else {
                    ds = new DataStream(DataStream.Mode.UNICAST, port, null, -1);
                }
                ds.start();
                onConnect.accept(ds);
                dialog.close();
            } catch (NumberFormatException ex) {
                statusLabel.setText("Invalid port number");
            }
        });

        content.getChildren().addAll(lblPort, portField, xidmlRow, xidmlPreview, btnConnect, statusLabel);
        tab.setContent(content);
        return tab;
    }

    // ===== Multicast Tab =====

    @SuppressWarnings("unchecked")
    private Tab createMulticastTab() {
        Tab tab = new Tab("Multicast");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        // --- Input fields ---
        Label lblGroup = new Label("Group(s):");
        TextField groupField = new TextField("239.1.1.1");
        groupField.setMaxWidth(300);

        Label lblPort = new Label("Port:");
        TextField portField = new TextField("5001");
        portField.setMaxWidth(200);

        Label lblScan = new Label("Scan duration (seconds):");
        TextField scanField = new TextField("5");
        scanField.setMaxWidth(80);

        // XidML chooser
        Object[] xidml = createXidMLChooser();
        HBox xidmlRow = (HBox) xidml[0];
        TextField xidmlPath = (TextField) xidml[1];
        Label xidmlPreview = (Label) xidml[2];

        // --- Scan button + progress ---
        Button btnScan = new Button("Scan for Streams");
        btnScan.setStyle("-fx-background-color: #336699; -fx-text-fill: white;");

        ProgressBar progressBar = new ProgressBar();
        progressBar.setVisible(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Label scanStatus = new Label();
        scanStatus.setStyle("-fx-text-fill: #666666;");

        // --- Results table ---
        TableView<StreamRow> table = new TableView<>();
        table.setPlaceholder(new Label("No streams discovered yet. Click 'Scan for Streams'."));
        table.setPrefHeight(180);

        TableColumn<StreamRow, String> colKey = new TableColumn<>("Key");
        colKey.setCellValueFactory(cd -> cd.getValue().keyProperty());
        colKey.setPrefWidth(80);

        TableColumn<StreamRow, String> colSrc = new TableColumn<>("Source IP");
        colSrc.setCellValueFactory(cd -> cd.getValue().sourceIpProperty());
        colSrc.setPrefWidth(120);

        TableColumn<StreamRow, Number> colPkts = new TableColumn<>("Packets");
        colPkts.setCellValueFactory(cd -> cd.getValue().packetCountProperty());
        colPkts.setPrefWidth(80);

        TableColumn<StreamRow, String> colRate = new TableColumn<>("Rate");
        colRate.setCellValueFactory(cd -> cd.getValue().rateProperty());
        colRate.setPrefWidth(80);

        table.getColumns().addAll(colKey, colSrc, colPkts, colRate);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        ObservableList<StreamRow> rows = FXCollections.observableArrayList();
        table.setItems(rows);

        // --- Connect buttons ---
        Button btnConnectSelected = new Button("Connect to Selected Stream");
        btnConnectSelected.setStyle("-fx-background-color: #339933; -fx-text-fill: white;");
        btnConnectSelected.setMaxWidth(Double.MAX_VALUE);
        btnConnectSelected.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                btnConnectSelected.setDisable(newVal == null));

        Separator orSep = new Separator();
        Label orLabel = new Label("--- or ---");
        orLabel.setStyle("-fx-text-fill: #999999;");
        orLabel.setMaxWidth(Double.MAX_VALUE);
        orLabel.setAlignment(Pos.CENTER);

        Button btnConnectNoScan = new Button("Connect Without Scanning");
        btnConnectNoScan.setMaxWidth(Double.MAX_VALUE);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #cc3333;");

        // --- Scan action ---
        btnScan.setOnAction(ev -> {
            int port;
            double duration;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid port number");
                return;
            }
            try {
                duration = Double.parseDouble(scanField.getText().trim());
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid scan duration");
                return;
            }

            String groupText = groupField.getText().trim();
            List<String> groups = Arrays.stream(groupText.split("[,;\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (groups.isEmpty()) {
                errorLabel.setText("Enter at least one multicast group");
                return;
            }

            errorLabel.setText("");
            btnScan.setDisable(true);
            progressBar.setVisible(true);
            progressBar.setProgress(-1); // indeterminate
            scanStatus.setText("Scanning for " + duration + "s...");
            rows.clear();

            Thread scanThread = new Thread(() -> {
                try {
                    List<DataStream.StreamInfo> results =
                            DataStream.discoverStreams(groups, port, duration);
                    Platform.runLater(() -> {
                        for (DataStream.StreamInfo info : results) {
                            rows.add(new StreamRow(info));
                        }
                        scanStatus.setText("Found " + results.size() + " stream(s)");
                        progressBar.setVisible(false);
                        btnScan.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        errorLabel.setText("Scan error: " + ex.getMessage());
                        progressBar.setVisible(false);
                        btnScan.setDisable(false);
                        scanStatus.setText("");
                    });
                }
            }, "StreamDiscovery");
            scanThread.setDaemon(true);
            scanThread.start();
        });

        // --- Connect Selected ---
        btnConnectSelected.setOnAction(ev -> {
            StreamRow selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            try {
                int port = Integer.parseInt(portField.getText().trim());
                String group = groupField.getText().trim().split("[,;\\s]+")[0];
                List<DataStream.ParameterInfo> params = parseXidMLOrDefault(xidmlPath, errorLabel);
                if (params != null && params.isEmpty()) return;

                DataStream ds;
                if (params != null) {
                    ds = new DataStream(DataStream.Mode.MULTICAST, port, group, selected.getRawKey(), params);
                } else {
                    ds = new DataStream(DataStream.Mode.MULTICAST, port, group, selected.getRawKey());
                }
                ds.start();
                onConnect.accept(ds);
                dialog.close();
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid port number");
            }
        });

        // --- Connect Without Scanning ---
        btnConnectNoScan.setOnAction(ev -> {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                String group = groupField.getText().trim().split("[,;\\s]+")[0];
                List<DataStream.ParameterInfo> params = parseXidMLOrDefault(xidmlPath, errorLabel);
                if (params != null && params.isEmpty()) return;

                DataStream ds;
                if (params != null) {
                    ds = new DataStream(DataStream.Mode.MULTICAST, port, group, -1, params);
                } else {
                    ds = new DataStream(DataStream.Mode.MULTICAST, port, group, -1);
                }
                ds.start();
                onConnect.accept(ds);
                dialog.close();
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid port number");
            }
        });

        content.getChildren().addAll(
                lblGroup, groupField,
                lblPort, portField,
                lblScan, scanField,
                xidmlRow, xidmlPreview,
                btnScan, progressBar, scanStatus,
                table,
                btnConnectSelected,
                orSep, orLabel,
                btnConnectNoScan,
                errorLabel
        );
        tab.setContent(content);
        return tab;
    }
}
