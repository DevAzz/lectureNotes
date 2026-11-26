package org.example;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

final class DesktopDialogs {
    static void notice(Window owner, String message) {
        var alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("Конспекты лекций");
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    static boolean confirm(Window owner, String title, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static boolean form(Window owner, String title, Node content) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static String editSegment(Window owner, LocalProcessing.Segment segment) {
        var text = new TextArea(segment.text());
        text.setWrapText(true);
        text.setPrefRowCount(5);
        text.setPrefColumnCount(50);
        if (!form(owner, "Изменить реплику · " + RecordingPlayer.timestamp(segment.start()), text)) return null;
        String edited = text.getText().strip();
        if (edited.isEmpty()) {
            notice(owner, "Введите текст реплики.");
            return null;
        }
        return edited;
    }

    static GridPane fields() {
        var form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(8));
        return form;
    }

    static void field(GridPane form, int row, String title, Node input) {
        var label = new Label(title);
        label.setLabelFor(input);
        form.addRow(row, label, input);
        GridPane.setHgrow(input, Priority.ALWAYS);
    }
}
