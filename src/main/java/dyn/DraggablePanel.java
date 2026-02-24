package dyn;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;

public abstract class DraggablePanel extends Pane {

    private double dragX, dragY;

    public DraggablePanel() {
        setStyle("-fx-border-color: black; -fx-background-color: white;");
        enableDrag();
        enableResize();
        addCloseButton();
    }

    private boolean isButtonTarget(MouseEvent e) {
        Node target = (Node) e.getTarget();
        while (target != null && target != this) {
            if (target instanceof ButtonBase) return true;
            target = target.getParent();
        }
        return false;
    }

    private void enableDrag() {
        this.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.isPrimaryButtonDown() && !isButtonTarget(e) && getCursor() != Cursor.SE_RESIZE) {
                dragX = e.getSceneX() - getLayoutX();
                dragY = e.getSceneY() - getLayoutY();
                e.consume();
            }
        });

        this.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (e.isPrimaryButtonDown() && getCursor() != Cursor.SE_RESIZE && !isButtonTarget(e)) {
                relocate(e.getSceneX() - dragX, e.getSceneY() - dragY);
                e.consume();
            }
        });
    }

    private void enableResize() {
        setOnMouseMoved(e -> {
            if (e.getX() > getWidth() - 10 && e.getY() > getHeight() - 10)
                setCursor(Cursor.SE_RESIZE);
            else
                setCursor(Cursor.DEFAULT);
        });

        setOnMouseDragged(e -> {
            if (getCursor() == Cursor.SE_RESIZE) {
                double newWidth = Math.max(100, e.getX());
                double newHeight = Math.max(50, e.getY());
                setPrefSize(newWidth, newHeight);
            }
        });
    }

    private void addCloseButton() {
        Button closeBtn = new Button("X");
        closeBtn.setStyle("-fx-font-size: 10; -fx-padding: 2 5; "
                + "-fx-background-color: #cc3333; -fx-text-fill: white; -fx-cursor: hand;");
        closeBtn.setViewOrder(-1); // render on top of siblings
        closeBtn.setOnAction(e -> {
            if (getParent() instanceof Pane) {
                ((Pane) getParent()).getChildren().remove(this);
            }
        });
        getChildren().add(closeBtn);

        // Keep button pinned to top-right corner
        closeBtn.layoutXProperty().bind(widthProperty().subtract(25));
        closeBtn.setLayoutY(3);
    }
}
