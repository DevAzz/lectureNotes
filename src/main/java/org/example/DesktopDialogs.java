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
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.List;

final class DesktopDialogs {
    private static final double MIN_TEXT_WIDTH = 280;
    private static final double MAX_TEXT_WIDTH = 520;

    static void notice(Window owner, String message) {
        var alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
        prepare(alert, owner, "Конспекты лекций");
        alert.getDialogPane().setContent(text(owner, message));
        alert.showAndWait();
    }

    static boolean confirm(Window owner, String title, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.OK, ButtonType.CANCEL);
        prepare(alert, owner, title);
        alert.getDialogPane().setContent(text(owner, message));
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static boolean form(Window owner, String title, Node content) {
        var dialog = new Dialog<ButtonType>();
        prepare(dialog, owner, title);
        bound(dialog, owner, content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Немодалное окно со списком кнопок внутри (например, окно «Предметы»). */
    static Dialog<Void> panel(Window owner, String title, Node content, ButtonType... types) {
        var dialog = new Dialog<Void>();
        prepare(dialog, owner, title);
        bound(dialog, owner, content);
        dialog.getDialogPane().getButtonTypes().addAll(types);
        return dialog;
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

    private static void prepare(Dialog<?> dialog, Window owner, String title) {
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
    }

    /**
     * Содержимое формы ограничивается шириной окна, поэтому длинные названия,
     * пути и промпты не растягивают диалог за пределы экрана.
     */
    private static void bound(Dialog<?> dialog, Window owner, Node content) {
        double width = textWidth(owner);
        if (content instanceof Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(width);
        }
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(width);
    }

    /**
     * Сообщение переносится по фиксированной ширине, поэтому окно никогда не оказывается шире экрана,
     * и кнопки подтверждения остаются видимыми при любом названии лекции или предмета.
     */
    private static Label text(Window owner, String message) {
        var label = new Label(message);
        label.getStyleClass().add("content-text");
        label.setWrapText(true);
        double width = textWidth(owner);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        return label;
    }

    private static double textWidth(Window owner) {
        double screen = screenWidth(owner);
        double base = owner == null || owner.getWidth() < 200 ? 420 : owner.getWidth() - 160;
        return Math.max(MIN_TEXT_WIDTH, Math.min(MAX_TEXT_WIDTH, Math.min(screen - 120, base)));
    }

    private static double screenWidth(Window owner) {
        List<Screen> screens = owner == null ? List.of()
                : Screen.getScreensForRectangle(owner.getX(), owner.getY(), 1, 1);
        var bounds = screens.isEmpty() ? Screen.getPrimary().getVisualBounds() : screens.getFirst().getVisualBounds();
        return bounds.getWidth();
    }
}
