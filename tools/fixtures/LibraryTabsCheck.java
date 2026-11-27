package org.example;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Проверка вкладок «Предметы» и «Настройки» и их связи с библиотекой. */
public final class LibraryTabsCheck {
    private static SubjectsPanel subjectsPanel;
    private static SettingsPanel settingsPanel;

    static void require(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
    }

    public static void main(String[] args) {
        int code = 0;
        try {
            run(Path.of(args[0]).toAbsolutePath());
        } catch (Throwable error) {
            error.printStackTrace();
            code = 1;
        }
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // Окно уже закрыто, код возврата важнее.
        }
        System.out.flush();
        System.exit(code);
    }

    private static void run(Path work) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try (var library = new LectureLibrary(work)) {
            seed(library, work);
            var captured = new java.io.ByteArrayOutputStream();
            var previous = System.err;
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            try {
                subjectsChecks(library);
                settingsChecks(library);
                taskBarChecks(work.resolve("tasks"));
            } catch (Throwable error) {
                System.setErr(previous);
                error.addSuppressed(new AssertionError("JavaFX log:\n"
                        + captured.toString(java.nio.charset.StandardCharsets.UTF_8)));
                throw error;
            } finally {
                System.setErr(previous);
                require(!captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("CSS Error"),
                        "app.css parses without errors");
            }
        }
        System.out.println("Subjects and settings tabs verified");
        System.out.flush();
    }

    private static void taskBarChecks(Path work) throws Exception {
        var library = new LectureLibrary(work);
        var lecture = library.lecture(library.createLecture("История", "Реформы", "", ""));
        String markdown = "Первый абзац.\n\n".repeat(90) + "Говорим о **производной** функции.";
        library.saveNote(lecture.id(), null, markdown, false);
        var window = new AtomicReference<Stage>();
        fx(() -> {
            new DesktopWindow(work, library, new LibraryLocation(work)).show();
            var stage = (Stage) Stage.getWindows().getLast();
            window.set(stage);
            var root = stage.getScene().getRoot();
            var bar = (DocumentTaskBar) root.lookup(".document-task-bar");
            var sections = (TabPane) root.lookup(".tab-pane");
            require(!bar.isManaged(), "empty task bar takes no space");
            bar.start(lecture, DocumentTaskBar.Operation.NOTE);
            sections.getSelectionModel().select(2);
            require(bar.isVisible() && bar.lookup(".progress-indicator").isVisible(), "task survives tab change");
            bar.finish(null);
            var open = bar.lookupAll(".button").stream().map(Button.class::cast)
                    .filter(b -> b.isVisible() && b.getText().equals("Открыть конспект")).findFirst().orElseThrow();
            open.fire();
            require(sections.getSelectionModel().getSelectedItem().getText().equals("Библиотека"), "library opened");
            var tabs = (TabPane) sections.getSelectionModel().getSelectedItem().getContent().lookup(".tab-pane");
            require(tabs.getSelectionModel().getSelectedItem().getText().equals("Конспект"), "note tab opened");
            bar.start(lecture, DocumentTaskBar.Operation.READING);
            bar.finish("Ошибка модели");
            require(!open.isVisible() && !bar.lookup(".progress-indicator").isVisible(), "failed task has no result");
        });
        awaitTrue(() -> {
            var source = (TextArea) window.get().getScene().lookup(".note-source");
            return source != null && source.getText().equals(markdown);
        }, "opened the document for the task's lecture");
        fx(() -> {
            var root = window.get().getScene().getRoot();
            var query = root.lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                    .map(TextField.class::cast).filter(field -> field.getPromptText() != null
                            && field.getPromptText().startsWith("Поиск по лекциям")).findFirst().orElseThrow();
            query.setText("производная");
        });
        awaitTrue(() -> window.get().getScene().getRoot().lookupAll(".list-cell").stream()
                .filter(javafx.scene.control.ListCell.class::isInstance).map(javafx.scene.control.ListCell.class::cast)
                .anyMatch(cell -> cell.getItem() instanceof LectureLibrary.Hit hit
                        && hit.kind() == LectureLibrary.Kind.NOTE && hit.excerpt().contains("производной")),
                "snippet follows the Russian word form deep inside the note");
        fx(() -> {
            var cell = window.get().getScene().getRoot().lookupAll(".list-cell").stream()
                    .filter(javafx.scene.control.ListCell.class::isInstance).map(javafx.scene.control.ListCell.class::cast)
                    .filter(c -> c.getItem() instanceof LectureLibrary.Hit hit && hit.kind() == LectureLibrary.Kind.NOTE)
                    .findFirst().orElseThrow();
            cell.getListView().getSelectionModel().select(cell.getIndex());
            cell.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 2,
                    false, false, false, false, false, false, false, false, false, true, null));
        });
        awaitTrue(() -> {
            var preview = (MarkdownView) window.get().getScene().lookup(".md-view");
            return preview.lookup(".search-highlight") != null && preview.getVvalue() > 0.5;
        }, "double click scrolls to and highlights the match");
        awaitTrue(() -> window.get().getScene().lookup(".search-highlight") == null, "highlight expires");
        fx(() -> window.get().fireEvent(new javafx.stage.WindowEvent(window.get(),
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        awaitTrue(() -> !window.get().isShowing(), "task window closed");
    }

    private static void seed(LectureLibrary library, Path work) throws Exception {
        library.saveSubject(null, "Математический анализ", "Курс лекций первого семестра");
        library.saveSubject(null, "История", "");
        library.createLecture("Математический анализ", "Производная", "Тема", "");
        library.createLecture("Математический анализ", "Предел", "Тема", "");
        library.createLecture("История", "Реформы", "Тема", "");
        // Проверка не зависит от поставки: важны сами сохранённые пути и флаги движка.
        var bundled = LocalProcessing.bundledWhisper(true);
        var cli = bundled.available() ? bundled.executable()
                : Files.writeString(work.resolve("whisper-cli.exe"), "placeholder");
        var model = bundled.available() ? bundled.model()
                : Files.writeString(work.resolve("check-model.bin"), "placeholder");
        library.saveWhisperSettings(cli, model, true, "ru");
        library.saveDiarizationModels(Files.createFile(work.resolve("segments-test.onnx")),
                Files.createFile(work.resolve("voices-test.onnx")));
        library.saveNoteSettings("Собери конспект свободным текстом.", 25);
        library.saveProfile(null, "Внешний API", "https://example.com/v1", "большая модель", true,
                16_000, 1_000, 300, null, false);
    }

    private static void subjectsChecks(LectureLibrary library) throws Exception {
        fx(() -> {
            subjectsPanel = new SubjectsPanel(new Stage(), library, subjectId -> { }, lectureId -> { }, () -> { });
            var stage = new Stage();
            var scene = new Scene(subjectsPanel, 1100, 640);
            scene.getStylesheets().add(stylesheet());
            stage.setScene(scene);
            stage.show();
            subjectsPanel.initialize();
        });
        var subjects = awaitSubjects(2, "both subjects are listed");
        require(subjects.getItems().stream().anyMatch(item -> item.name().equals("Математический анализ")
                        && item.lectures() == 2 && item.notes() == 0),
                "the subject card counts its lectures: " + subjects.getItems());
        require(button("Создать предмет") != null && subjectAction("Перенести все лекции") != null
                && subjectAction("Удалить") != null && button("В библиотеку") != null
                && subjectAction("Перенести выбранную лекцию") != null, "subject actions are present");
        require(!subjectAction("Изменить").isDisable(), "the selected subject can be edited");

        fx(() -> field("#subject-search").setText("истори"));
        var filtered = awaitSubjects(1, "an inaccurate word form finds one subject");
        require(filtered.getItems().getFirst().name().equals("История"), "search result: " + filtered.getItems());

        fx(() -> {
            field("#subject-search").clear();
            var order = (ComboBox<?>) node("#subject-order");
            order.getSelectionModel().select(1);
            order.getOnAction().handle(new ActionEvent());
        });
        var sorted = awaitSubjects(2, "the list returns to two subjects after the sort change");
        require(sorted.getItems().getFirst().name().equals("Математический анализ"),
                "the subject with more lectures comes first: " + sorted.getItems());

        fx(() -> {
            int index = 0;
            for (var item : subjects.getItems()) {
                if (item.name().equals("Математический анализ")) break;
                index++;
            }
            subjects.getSelectionModel().select(index);
        });
        var lectures = awaitLectures(2, "lectures of the selected subject");
        require(lectures.getItems().stream().allMatch(item -> item.subject().equals("Математический анализ")),
                "the lecture list belongs to the subject: " + lectures.getItems());
        fx(() -> lectures.getSelectionModel().select(0));
        awaitTrue(() -> !subjectAction("Перенести выбранную лекцию").isDisable()
                && !button("Открыть лекцию").isDisabled(), "a selected lecture can be moved or opened");
        require(labels(subjectsPanel).contains("Курс лекций первого семестра"), "the subject description is shown");
        require(labels(subjectsPanel).contains("2 лекц."), "the details line counts the lectures");
        fx(() -> {
            ((Stage) subjectsPanel.getScene().getWindow()).close();
            subjectsPanel.close();
        });
    }

    private static void settingsChecks(LectureLibrary library) throws Exception {
        fx(() -> {
            settingsPanel = new SettingsPanel(new Stage(), library, Path.of("C:/library-check"), () -> { }, () -> { });
            var stage = new Stage();
            var scene = new Scene(settingsPanel, 1100, 900);
            scene.getStylesheets().add(stylesheet());
            stage.setScene(scene);
            stage.show();
            settingsPanel.initialize();
        });
        require(settingsPanel.lookupAll(".settings-card").size() == 6, "six settings cards");
        var captions = new StringBuilder();
        for (var node : settingsPanel.lookupAll(".settings-title")) captions.append(((Label) node).getText()).append(' ');
        require(captions.toString().contains("Модели и профили генерации")
                && captions.toString().contains("Распознавание речи")
                && captions.toString().contains("Конспект")
                && captions.toString().contains("Библиотека"), "settings cards: " + captions);

        var profiles = awaitProfiles(2, "both model profiles are listed");
        require(profiles.getItems().stream().anyMatch(item -> item.name().equals("Внешний API") && item.cloud()
                        && item.answerTokens() == 1_000 && item.timeoutSeconds() == 300),
                "the external profile keeps its budgets: " + profiles.getItems());
        require(profiles.getItems().stream().filter(LectureLibrary.Profile::isDefault).count() == 1,
                "exactly one default profile");
        require(button("Проверить соединение") != null && button("Сделать основным") != null
                && button("Создать профиль") != null && button("Удалить") != null, "profile actions are present");

        awaitTrue(() -> !field("#whisper-executable").getText().isBlank()
                && field("#whisper-model").getText().endsWith(".bin"), "Whisper paths come from the saved settings");
        awaitTrue(() -> field("#diarization-segmentation").getText().endsWith("segments-test.onnx")
                && field("#diarization-embedding").getText().endsWith("voices-test.onnx"),
                "both diarization paths come from the saved settings");
        awaitTrue(() -> ((TextArea) node("#note-prompt")).getText().contains("Собери конспект свободным текстом"),
                "the saved system prompt is shown");
        awaitTrue(() -> "25".equals(field("#note-limit").getText()), "the saved time limit is shown");
        awaitTrue(() -> ((CheckBox) node("#whisper-cpu")).isSelected(), "the CPU flag is restored");
        awaitTrue(() -> {
            var text = labels(settingsPanel);
            return text.contains("3 лекций") && text.contains("JDK ") && text.contains("whisper-cli.exe");
        }, "library counters and build information");
        fx(() -> {
            ((Stage) settingsPanel.getScene().getWindow()).close();
            settingsPanel.close();
        });
    }

    private static Node node(String id) {
        Node found = subjectsPanel == null ? null : subjectsPanel.lookup(id);
        if (found == null && settingsPanel != null) found = settingsPanel.lookup(id);
        if (found == null) throw new AssertionError("widget not found: " + id);
        return found;
    }

    private static TextField field(String id) {
        return (TextField) node(id);
    }

    private static String labels(javafx.scene.Parent root) {
        var text = new StringBuilder();
        for (var node : root.lookupAll(".label")) text.append(((Label) node).getText()).append('\n');
        return text.toString();
    }

    private static ListView<LectureLibrary.Subject> awaitSubjects(int expected, String name) throws Exception {
        var found = new AtomicReference<ListView<LectureLibrary.Subject>>();
        awaitTrue(() -> {
            var box = (ListView<LectureLibrary.Subject>) subjectsPanel.lookup("#subject-list");
            if (box == null || box.getItems().size() != expected) return false;
            found.set(box);
            return true;
        }, name);
        return found.get();
    }

    private static ListView<LectureLibrary.Lecture> awaitLectures(int expected, String name) throws Exception {
        var found = new AtomicReference<ListView<LectureLibrary.Lecture>>();
        awaitTrue(() -> {
            var box = (ListView<LectureLibrary.Lecture>) subjectsPanel.lookup("#subject-lectures");
            if (box == null || box.getItems().size() != expected) return false;
            found.set(box);
            return true;
        }, name);
        return found.get();
    }

    private static ListView<LectureLibrary.Profile> awaitProfiles(int expected, String name) throws Exception {
        var found = new AtomicReference<ListView<LectureLibrary.Profile>>();
        awaitTrue(() -> {
            var box = (ListView<LectureLibrary.Profile>) settingsPanel.lookup("#profile-list");
            if (box == null || box.getItems().size() != expected) return false;
            found.set(box);
            return true;
        }, name);
        return found.get();
    }

    private static void awaitTrue(BooleanSupplier condition, String name) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var done = new CompletableFuture<Boolean>();
            Platform.runLater(() -> {
                try {
                    done.complete(condition.getAsBoolean());
                } catch (Throwable error) {
                    done.complete(false);
                }
            });
            if (done.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(100);
        }
        throw new AssertionError(name);
    }

    private static javafx.scene.control.MenuItem subjectAction(String title) {
        return subjectsPanel.lookupAll(".menu-button").stream().map(javafx.scene.control.MenuButton.class::cast)
                .flatMap(menu -> menu.getItems().stream()).filter(item -> item.getText().equals(title))
                .findFirst().orElseThrow();
    }

    private static Button button(String label) {
        var root = settingsPanel != null ? settingsPanel : subjectsPanel;
        for (var node : root.lookupAll(".button")) {
            var button = (Button) node;
            if (label.equals(button.getText())) return button;
        }
        return null;
    }

    private static String stylesheet() {
        var url = MarkdownView.class.getResource("/app.css");
        require(url != null, "app.css is on the classpath");
        return url.toExternalForm();
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
        future.get(30, TimeUnit.SECONDS);
    }
}
