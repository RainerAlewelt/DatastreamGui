package dyn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.io.File;

public class LayoutManager {

    private static final String LAYOUT_FILE = "layout.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void save(Pane workspace) {
        save(workspace, LAYOUT_FILE);
    }

    public static void save(Pane workspace, String filename) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode panels = mapper.createArrayNode();

            for (javafx.scene.Node n : workspace.getChildren()) {
                ObjectNode p = mapper.createObjectNode();

                if (n instanceof PlotPanel) {
                    PlotPanel plot = (PlotPanel) n;
                    p.put("type", "plot");
                    p.put("x", plot.getLayoutX());
                    p.put("y", plot.getLayoutY());
                    p.put("width", plot.getWidth());
                    p.put("height", plot.getHeight());

                    ArrayNode vars = mapper.createArrayNode();
                    for (String s : plot.seriesMap.keySet()) {
                        ObjectNode varNode = mapper.createObjectNode();
                        varNode.put("name", s);
                        Color c = plot.colorMap.get(s);
                        if (c != null) varNode.put("color", NumericPanel.toHex(c));
                        vars.add(varNode);
                    }
                    p.set("variables", vars);

                } else if (n instanceof BarPanel) {
                    BarPanel bar = (BarPanel) n;
                    p.put("type", "bar");
                    p.put("x", bar.getLayoutX());
                    p.put("y", bar.getLayoutY());
                    p.put("width", bar.getWidth());
                    p.put("height", bar.getHeight());
                    p.put("variable", bar.variableName != null ? bar.variableName : "");
                    p.put("color", NumericPanel.toHex(bar.getColor()));

                } else if (n instanceof NumericPanel) {
                    NumericPanel num = (NumericPanel) n;
                    p.put("type", "numeric");
                    p.put("x", num.getLayoutX());
                    p.put("y", num.getLayoutY());
                    p.put("width", num.getWidth());
                    p.put("height", num.getHeight());
                    p.put("variable", num.variableName != null ? num.variableName : "");
                    p.put("color", NumericPanel.toHex(num.getColor()));

                } else {
                    continue;
                }

                panels.add(p);
            }

            root.set("panels", panels);
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filename), root);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void load(Pane workspace, DataStream stream) {
        load(workspace, stream, LAYOUT_FILE);
    }

    public static void load(Pane workspace, DataStream stream, String filename) {
        try {
            File f = new File(filename);
            if (!f.exists()) return;

            ObjectNode root = (ObjectNode) mapper.readTree(f);
            ArrayNode panels = (ArrayNode) root.get("panels");

            for (int i = 0; i < panels.size(); i++) {
                ObjectNode pNode = (ObjectNode) panels.get(i);
                String type = pNode.get("type").asText();
                double x = pNode.get("x").asDouble();
                double y = pNode.get("y").asDouble();
                double w = pNode.get("width").asDouble();
                double h = pNode.get("height").asDouble();

                if (type.equals("plot")) {
                    PlotPanel plot = new PlotPanel();
                    plot.relocate(x, y);
                    plot.setPrefSize(w, h);

                    ArrayNode vars = (ArrayNode) pNode.get("variables");
                    if (vars != null) {
                        for (int j = 0; j < vars.size(); j++) {
                            com.fasterxml.jackson.databind.JsonNode varEntry = vars.get(j);
                            if (varEntry.isObject()) {
                                String varName = varEntry.get("name").asText();
                                Color c = varEntry.has("color")
                                        ? Color.web(varEntry.get("color").asText()) : null;
                                plot.addVariable(varName, c);
                            } else {
                                // backwards-compat: plain string
                                plot.addVariable(varEntry.asText());
                            }
                        }
                    }

                    workspace.getChildren().add(plot);
                    final PlotPanel plotFinal = plot;
                    if (stream != null) {
                        stream.addListener(data -> javafx.application.Platform.runLater(
                                () -> plotFinal.update(data)));
                    }

                } else if (type.equals("bar")) {
                    String varName = pNode.has("variable") ? pNode.get("variable").asText() : null;
                    BarPanel bar = new BarPanel(varName);
                    bar.relocate(x, y);
                    bar.setPrefSize(w, h);
                    if (pNode.has("color")) bar.setColor(Color.web(pNode.get("color").asText()));

                    workspace.getChildren().add(bar);
                    final BarPanel barFinal = bar;

                    if (varName != null && !varName.isEmpty() && stream != null) {
                        stream.addListener(data -> javafx.application.Platform.runLater(
                                () -> barFinal.update(data.getOrDefault(varName, 0.0))));
                    }

                } else if (type.equals("numeric")) {
                    String varName = pNode.has("variable") ? pNode.get("variable").asText() : null;
                    NumericPanel n = new NumericPanel(varName);
                    n.relocate(x, y);
                    n.setPrefSize(w, h);
                    if (pNode.has("color")) n.setColor(Color.web(pNode.get("color").asText()));

                    workspace.getChildren().add(n);
                    final NumericPanel nFinal = n;

                    if (varName != null && !varName.isEmpty() && stream != null) {
                        stream.addListener(data -> javafx.application.Platform.runLater(
                                () -> nFinal.update(data.getOrDefault(varName, 0.0))));
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
