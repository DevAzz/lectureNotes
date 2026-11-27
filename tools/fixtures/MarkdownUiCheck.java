package org.example;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Проверка редактора и просмотрщика Markdown вкладки «Конспект». */
public final class MarkdownUiCheck {
    private static final String SAMPLE = """
            # Основы дифференциальных уравнений

            Вводный **абзац** с *курсивом*, `кодом` и ~~зачёркнутым~~ текстом.

            ## Определения
            - первое
            - второе
              - вложенный пункт
            - [x] сделано
            - [ ] не сделано

            1. один
            2. два

            > Цитата спикера
            > вторая строка

            | Термин | Определение |
            | --- | --- |
            | Уравнение | связь |
            | Решение | функция |

            ```text
            y' = k y
            ```

            См. [00:01:01](#t=61500) и https://example.com.

            ---
            Конец
            """;

    static void require(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]).toAbsolutePath();
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            viewChecks();
            editorChecks();
            noteTabChecks(home);
        } finally {
            Platform.runLater(Platform::exit);
        }
        System.out.println("Markdown editor and viewer checks passed");
    }

    private static void fx(Runnable action) throws Exception {
        var future = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        future.get(60, TimeUnit.SECONDS);
    }

    private static int count(Node root, String selector) {
        return root.lookupAll(selector).size();
    }

    private static void viewChecks() throws Exception {
        var seeked = new AtomicLong(-1);
        fx(() -> {
            var view = new MarkdownView();
            view.setOnSeek(seeked::set);
            var stage = new Stage();
            var scene = new Scene(view, 900, 700);
            scene.getStylesheets().add(stylesheet());
            stage.setScene(scene);
            stage.show();
            view.setMarkdown(SAMPLE);
            view.applyCss();
            view.layout();
            require(count(view, ".md-h1") >= 1, "heading level 1");
            require(count(view, ".md-h2") >= 1, "heading level 2");
            var heading = (javafx.scene.text.TextFlow) view.lookup(".md-h1");
            var headingText = new StringBuilder();
            for (var child : heading.getChildren()) headingText.append(((Text) child).getText());
            require(headingText.toString().contains("Основы дифференциальных уравнений"), "heading text: " + headingText);
            require(count(view, ".md-strong") >= 1, "bold text");
            require(count(view, ".md-em") >= 1, "italic text");
            require(count(view, ".md-code") >= 1, "inline code");
            require(count(view, ".md-del") >= 1, "strikethrough");
            require(count(view, ".md-item") == 7, "list items, found " + count(view, ".md-item"));
            require(count(view, ".md-task") == 2, "task items");
            require(count(view, ".md-task-done") == 1, "completed task");
            require(count(view, ".md-quote") == 1, "quote block");
            require(count(view, ".md-table") == 1, "table");
            require(count(view, ".md-th") == 2, "table headers");
            require(count(view, ".md-td") == 4, "table cells");
            require(count(view, ".md-code-block") == 1, "fenced code");
            require(count(view, ".md-rule") == 1, "thematic break");
            require(count(view, ".md-link") == 1, "links, found " + count(view, ".md-link"));
            var time = view.lookup(".md-time");
            if (!(time instanceof Text label)) throw new AssertionError("timecode link");
            require(label.getText().contains("00:01:01"), "timecode label: " + label.getText());
            require(label.getOnMouseClicked() != null, "timecode handler");
            label.getOnMouseClicked().handle(null);
            stage.close();
        });
        require(seeked.get() == 61_500, "timecode seek value: " + seeked.get());
    }

    private static String stylesheet() {
        var url = MarkdownView.class.getResource("/app.css");
        require(url != null, "app.css is on the classpath");
        return url.toExternalForm();
    }

    private static Button tool(Node root, String id) {
        if (!(root.lookup("#" + id) instanceof Button button)) throw new AssertionError("tool not found: " + id);
        return button;
    }

    private static ToggleButton mode(Node root, String id) {
        if (!(root.lookup("#" + id) instanceof ToggleButton toggle)) throw new AssertionError("mode not found: " + id);
        return toggle;
    }

    private static void editorChecks() throws Exception {
        fx(() -> {
            var area = new TextArea();
            var editor = new MarkdownEditor(area, () -> 61_500);
            var stage = new Stage();
            var scene = new Scene(editor, 1100, 620);
            scene.getStylesheets().add(stylesheet());
            stage.setScene(scene);
            stage.show();
            require(count(editor, ".md-tool") >= 12, "formatting toolbar");
            require(count(editor, ".md-group") == 5, "ribbon groups");
            var captions = new StringBuilder();
            for (var node : editor.lookupAll(".md-group-caption")) captions.append(((Label) node).getText()).append(' ');
            require(captions.toString().contains("Формат") && captions.toString().contains("Вставка"),
                    "ribbon group captions: " + captions);
            require(tool(editor, "md-bold").getText().isEmpty(), "tool buttons show icons, not words");
            require(tool(editor, "md-bold").getTooltip().getText().contains("Ctrl+B"), "tooltip keeps the shortcut");
            require(tool(editor, "md-bold").getAccessibleText().equals("Жирный"), "tool keeps an accessible name");
            require(editor.lookup(".note-toolbar").lookupAll(".md-mode").isEmpty(), "the ribbon has no view buttons");
            var view = editor.lookup(".note-view");
            require(view != null && view.getParent().getStyleClass().contains("note-footer"),
                    "the view switch sits in the footer, away from the editing tools");
            require(count(view, ".md-mode") == 3, "three view modes");

            var toolbar = editor.lookup(".note-toolbar");
            require(toolbar instanceof FlowPane, "toolbar that wraps to the next row");
            var flow = (FlowPane) toolbar;
            flow.resize(520, 80);
            flow.layout();
            require(flow.getChildren().stream().anyMatch(node -> node.getLayoutY() > 1),
                    "toolbar groups move to the next row when the window is narrow");

            area.setText("обычный текст");
            area.selectRange(0, area.getLength());
            tool(editor, "md-bold").fire();
            require("**обычный текст**".equals(area.getText()), "bold wrap: " + area.getText());
            area.selectRange(0, area.getLength());
            tool(editor, "md-bold").fire();
            require("обычный текст".equals(area.getText()), "bold unwrap: " + area.getText());

            area.setText("Заголовок раздела");
            area.selectRange(0, area.getLength());
            tool(editor, "md-bullet").fire();
            require("- Заголовок раздела".equals(area.getText()), "list prefix: " + area.getText());
            area.selectRange(0, area.getLength());
            tool(editor, "md-quote").fire();
            require("> Заголовок раздела".equals(area.getText()), "quote prefix: " + area.getText());
            area.selectRange(0, area.getLength());
            tool(editor, "md-quote").fire();
            require("Заголовок раздела".equals(area.getText()), "quote removed: " + area.getText());

            area.setText("Пункт один");
            area.positionCaret(area.getLength());
            area.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            require("Пункт один\n".equals(area.getText()), "plain Enter is untouched: " + area.getText());

            area.setText("- Пункт один");
            area.positionCaret(area.getLength());
            area.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            require("- Пункт один\n- ".equals(area.getText()), "list continues: " + area.getText());

            area.setText("1. Первый");
            area.positionCaret(area.getLength());
            area.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            require("1. Первый\n2. ".equals(area.getText()), "numbered list continues: " + area.getText());

            area.clear();
            area.positionCaret(0);
            tool(editor, "md-timecode").fire();
            require(area.getText().contains("[00:01:01](#t=61500)"), "timecode insert: " + area.getText());

            area.setText(SAMPLE);
            mode(editor, "md-view-preview").fire();
            editor.refreshPreview();
            var split = (SplitPane) editor.lookup(".split-pane");
            require(split.getItems().size() == 1 && split.getItems().getFirst() != area, "preview only mode");
            require(count(editor, ".md-h1") >= 1, "preview renders markdown");
            require(tool(editor, "md-bold").isDisabled() && tool(editor, "md-bullet").isDisabled()
                    && tool(editor, "md-timecode").isDisabled(), "editing tools rest in the preview mode");
            mode(editor, "md-view-split").fire();
            require(split.getItems().size() == 2, "split mode");
            require(!tool(editor, "md-bold").isDisabled(), "editing tools return with the source field");
            mode(editor, "md-view-edit").fire();
            require(split.getItems().size() == 1 && split.getItems().getFirst() == area, "edit mode");
            stage.close();
        });
    }

    private static javafx.scene.layout.Pane notePanel;
    private static LectureDocuments documents;

    private static void noteTabChecks(Path home) throws Exception {
        try (var library = new LectureLibrary(home)) {
            String id = library.createLecture("Математика", "Дифференциальные уравнения", "Тема", "Фокус");
            library.saveTranscript(id, List.of(new LocalProcessing.Segment(61_500, 64_000, "Первая реплика.")));
            library.saveNote(id, null, SAMPLE, false);
            var item = library.lecture(id);
            fx(() -> {
                var player = new RecordingPlayer(message -> { });
                documents = new LectureDocuments(new Stage(), player, library, () -> { });
                var panel = documents.notePanel();
                notePanel = panel;
                var stage = new Stage();
                var scene = new Scene(panel, 1150, 660);
                scene.getStylesheets().add(stylesheet());
                stage.setScene(scene);
                stage.show();
                documents.select(item, 0);
            });
            Thread.sleep(1200);
            var captured = new java.io.ByteArrayOutputStream();
            var previous = System.err;
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            try {
                fx(() -> {
                    notePanel.applyCss();
                    notePanel.layout();
                    require(count(notePanel, ".md-h1") >= 1, "saved note renders in the tab");
                    require(count(notePanel, ".md-time") == 1, "saved note keeps the timecode link");
                    require(count(notePanel, ".md-table") == 1, "saved note keeps the table");
                    var area = (TextArea) notePanel.lookup(".note-source");
                    require(area != null && area.getText().contains("# Основы"), "editor keeps the raw markdown");
                    require(area.isEditable(), "editor stays editable for the opened lecture");
                });
            } finally {
                System.setErr(previous);
            }
            var log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
            require(!log.contains("CSS Error"), "app.css parses without errors: " + log);
            fx(documents::close);
        }
    }
}
