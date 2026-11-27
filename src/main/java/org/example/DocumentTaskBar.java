package org.example;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.BiConsumer;

final class DocumentTaskBar extends HBox {
    enum Operation {
        NOTE("Формируем конспект", "Конспект готов", "Открыть конспект"),
        RECOGNITION("Распознаём речь", "Расшифровка готова", "Открыть расшифровку"),
        SPEAKERS("Размечаем спикеров", "Спикеры размечены", "Открыть расшифровку"),
        READING("Разбиваем текст на абзацы", "Текст для чтения готов", "Открыть текст");

        final String running;
        final String ready;
        final String action;

        Operation(String running, String ready, String action) {
            this.running = running;
            this.ready = ready;
            this.action = action;
        }
    }

    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label message = new Label();
    private final Button open = new Button();
    private final Button dismiss = new Button("Скрыть");
    private LectureLibrary.Lecture lecture;
    private Operation operation;

    DocumentTaskBar(BiConsumer<LectureLibrary.Lecture, Operation> openResult) {
        super(10);
        getStyleClass().add("document-task-bar");
        setAlignment(Pos.CENTER_LEFT);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        getChildren().addAll(spinner, message, open, dismiss);
        for (var node : getChildren()) node.managedProperty().bind(node.visibleProperty());
        managedProperty().bind(visibleProperty());
        setVisible(false);
        open.setOnAction(event -> openResult.accept(lecture, operation));
        dismiss.setOnAction(event -> setVisible(false));
    }

    void start(LectureLibrary.Lecture item, Operation task) {
        lecture = item;
        operation = task;
        showMessage(task.running);
        spinner.setVisible(true);
        open.setVisible(false);
        dismiss.setVisible(false);
        setVisible(true);
    }

    void finish(String error) {
        spinner.setVisible(false);
        showMessage(error == null ? operation.ready : error);
        open.setText(operation.action);
        open.setVisible(error == null);
        dismiss.setVisible(true);
    }

    void forget(String id) {
        if (lecture != null && lecture.id().equals(id)) setVisible(false);
    }

    private void showMessage(String status) {
        message.setText(status + " · «" + lecture.title() + "»");
        message.setTooltip(new Tooltip(message.getText()));
    }
}
