package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Вкладка «Предметы»: поиск, создание, правка, перенос лекций и удаление предметов.
 * Все обращения к SQLite выполняются вне потока JavaFX.
 */
final class SubjectsPanel extends VBox {
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private enum Order {
        NAME("По названию"), POPULAR("Сначала больше лекций");

        private final String label;

        Order(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private final Window owner;
    private final LectureLibrary library;
    private final Consumer<Long> openSubjectInLibrary;
    private final Consumer<String> openLectureInLibrary;
    private final Runnable libraryChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final TextField search = new TextField();
    private final ComboBox<Order> order = new ComboBox<>();
    private final ListView<LectureLibrary.Subject> subjects = new ListView<>();
    private final ListView<LectureLibrary.Lecture> lectures = new ListView<>();
    private final Label details = new Label();
    private final Label status = new Label();
    private final Button create = new Button("Создать предмет");
    private final Button edit = new Button("Изменить");
    private final Button remove = new Button("Удалить");
    private final Button moveAll = new Button("Перенести все лекции");
    private final Button moveOne = new Button("Перенести выбранную лекцию");
    private final Button openSubject = new Button("Показать лекции в библиотеке");
    private final Button openLecture = new Button("Открыть лекцию");

    private List<LectureLibrary.Subject> all = List.of();
    private LectureLibrary.Subject shown;
    private boolean applying;
    private boolean writing;
    private boolean closed;

    SubjectsPanel(Window owner, LectureLibrary library, Consumer<Long> openSubjectInLibrary,
                  Consumer<String> openLectureInLibrary, Runnable libraryChanged) {
        super(10);
        this.owner = owner;
        this.library = library;
        this.openSubjectInLibrary = openSubjectInLibrary;
        this.openLectureInLibrary = openLectureInLibrary;
        this.libraryChanged = libraryChanged;
        setPadding(new Insets(14));

        search.setId("subject-search");
        search.setPromptText("Поиск по названию и описанию предмета");
        search.setAccessibleText("Поиск по названию и описанию предмета");
        HBox.setHgrow(search, Priority.ALWAYS);
        order.setId("subject-order");
        order.getItems().setAll(Order.values());
        order.getSelectionModel().selectFirst();
        order.setTooltip(new Tooltip("Порядок списка предметов"));

        subjects.setId("subject-list");
        subjects.setPlaceholder(new Label("Предметов пока нет"));
        subjects.setCellFactory(source -> new ListCell<>() {
            { setWrapText(true); }

            @Override protected void updateItem(LectureLibrary.Subject item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : card(item));
            }
        });
        subjects.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> {
            if (!applying) showSubject(value);
            updateButtons();
        });
        search.textProperty().addListener((obs, prior, value) -> applyFilter());
        order.setOnAction(event -> applyFilter());

        lectures.setId("subject-lectures");
        lectures.setPlaceholder(new Label("В этом предмете ещё нет лекций"));
        lectures.setCellFactory(source -> new ListCell<>() {
            { setWrapText(true); }

            @Override protected void updateItem(LectureLibrary.Lecture item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.title() + "\n" + LIST_DATE.format(item.created()) + " · " + state(item));
            }
        });
        lectures.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> updateButtons());

        details.setWrapText(true);
        details.setText("Выберите предмет");
        status.setWrapText(true);

        create.setOnAction(event -> editSubject(null));
        edit.setOnAction(event -> editSubject(shown));
        remove.setOnAction(event -> removeSubject());
        moveAll.setOnAction(event -> moveAllLectures());
        moveOne.setOnAction(event -> moveOneLecture());
        openSubject.setOnAction(event -> {
            if (shown != null) openSubjectInLibrary.accept(shown.id());
        });
        openLecture.setOnAction(event -> {
            var item = lectures.getSelectionModel().getSelectedItem();
            if (item != null) openLectureInLibrary.accept(item.id());
        });

        var listPane = new VBox(6, new Label("Предметы"), subjects);
        VBox.setVgrow(subjects, Priority.ALWAYS);
        var lecturePane = new VBox(6, new Label("Лекции предмета"), lectures);
        VBox.setVgrow(lectures, Priority.ALWAYS);
        var right = new VBox(8, details, lecturePane, new HBox(8, moveOne, openLecture));
        var split = new SplitPane(listPane, right);
        split.setDividerPositions(0.36);
        VBox.setVgrow(split, Priority.ALWAYS);

        getChildren().addAll(new HBox(8, search, order, create), split,
                new HBox(8, edit, remove, moveAll, openSubject, status));
        updateButtons();
    }

    void initialize() {
        refresh();
    }

    /** Перечитывает предметы и их счётчики после изменений в библиотеке. */
    void refresh() {
        if (closed) return;
        worker.execute(() -> {
            try {
                var current = library.subjects();
                Platform.runLater(() -> {
                    if (!closed) applySubjects(current);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed) say("Не удалось загрузить предметы.");
                });
            }
        });
    }

    void close() {
        closed = true;
        worker.shutdown();
    }

    private static String card(LectureLibrary.Subject item) {
        return item.name() + "\n" + counts(item);
    }

    private static String counts(LectureLibrary.Subject item) {
        return item.lectures() + " лекц. · " + item.transcripts() + " с расшифровкой · " + item.notes() + " с конспектом";
    }

    private static String state(LectureLibrary.Lecture item) {
        return switch (item.state()) {
            case "COMPLETE" -> "запись готова";
            case "PARTIAL" -> "частичная запись";
            case "EMPTY" -> "без аудио";
            case "DRAFT" -> "карточка без записи";
            default -> "запись или восстановление";
        } + (item.hasNote() ? " · есть конспект" : "");
    }

    private void applySubjects(List<LectureLibrary.Subject> current) {
        if (closed) return;
        all = current;
        long selected = shown == null ? 0 : shown.id();
        shown = current.stream().filter(item -> item.id() == selected).findFirst().orElse(null);
        applyFilter();
        updateButtons();
    }

    /** Поиск и сортировка работают уже на загруженном списке — предметов у пользователя десятки. */
    private void applyFilter() {
        if (closed) return;
        String key = RussianSearch.normalize(search.getText());
        var visible = new ArrayList<LectureLibrary.Subject>();
        for (var item : all) {
            if (key.isEmpty() || RussianSearch.normalize(item.name()).contains(key)
                    || RussianSearch.normalize(item.description()).contains(key)) visible.add(item);
        }
        if (order.getValue() == Order.POPULAR) {
            visible.sort(Comparator.comparingInt(LectureLibrary.Subject::lectures).reversed()
                    .thenComparing(item -> RussianSearch.normalize(item.name())));
        }
        var prior = subjects.getSelectionModel().getSelectedItem();
        applying = true;
        subjects.getItems().setAll(visible);
        var focus = shown != null ? visible.stream().filter(item -> item.id() == shown.id()).findFirst().orElse(null)
                : prior != null && visible.contains(prior) ? prior
                : visible.isEmpty() ? null : visible.getFirst();
        if (focus != null) subjects.getSelectionModel().select(focus);
        else subjects.getSelectionModel().clearSelection();
        applying = false;
        if (focus != null) showSubject(focus);
        else {
            shown = null;
            details.setText(search.getText().isBlank() ? "Выберите предмет" : "Ничего не найдено");
            lectures.getItems().clear();
        }
        say(visible.size() == all.size() ? "" : "Предметов найдено: " + visible.size());
        updateButtons();
    }

    private void showSubject(LectureLibrary.Subject item) {
        shown = item;
        lectures.getItems().clear();
        if (item == null) {
            details.setText("Выберите предмет");
            return;
        }
        var text = new StringBuilder(item.name());
        if (!item.description().isBlank()) text.append('\n').append(item.description());
        text.append('\n').append(counts(item));
        details.setText(text.toString());
        long id = item.id();
        worker.execute(() -> {
            try {
                var saved = library.subjectLectures(id);
                Platform.runLater(() -> {
                    if (closed || shown == null || shown.id() != id) return;
                    lectures.getItems().setAll(saved);
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed && shown != null && shown.id() == id) say("Не удалось загрузить лекции предмета.");
                });
            }
        });
    }

    private void editSubject(LectureLibrary.Subject item) {
        var name = new TextField(item == null ? "" : item.name());
        var description = new TextField(item == null ? "" : item.description());
        name.setPromptText("Например: Математический анализ");
        var form = DesktopDialogs.fields();
        DesktopDialogs.field(form, 0, "Название", name);
        DesktopDialogs.field(form, 1, "Описание", description);
        if (!DesktopDialogs.form(owner, item == null ? "Новый предмет" : "Изменить предмет", form)) return;
        String title = name.getText();
        String details = description.getText();
        mutate(() -> library.saveSubject(item == null ? null : item.id(), title, details), this::refresh);
    }

    /** Удаление возможно только для предмета без лекций, поэтому сразу предлагаем перенос. */
    private void removeSubject() {
        var item = shown;
        if (item == null) return;
        if (item.lectures() == 0) {
            if (!DesktopDialogs.confirm(owner, "Удаление предмета",
                    "Удалить предмет «" + item.name() + "»? Лекций в нём нет.")) return;
            mutate(() -> library.deleteSubject(item.id()), this::refresh);
            return;
        }
        var target = otherSubject("В какой предмет перенести " + item.lectures() + " лекций?");
        if (target == null) return;
        if (!DesktopDialogs.confirm(owner, "Перенос и удаление", "Перенести лекции предмета «" + item.name()
                + "» в «" + target.name() + "» и удалить пустой предмет?")) return;
        long to = target.id();
        mutate(() -> {
            library.moveSubjectLectures(item.id(), to);
            library.deleteSubject(item.id());
        }, this::refresh);
    }

    private void moveAllLectures() {
        var item = shown;
        if (item == null || item.lectures() == 0) return;
        var target = otherSubject("В какой предмет перенести все лекции?");
        if (target == null) return;
        if (!DesktopDialogs.confirm(owner, "Перенос лекций", "Перенести " + item.lectures() + " лекций из «"
                + item.name() + "» в «" + target.name() + "»?")) return;
        long to = target.id();
        mutate(() -> library.moveSubjectLectures(item.id(), to), this::refresh);
    }

    private void moveOneLecture() {
        var lecture = lectures.getSelectionModel().getSelectedItem();
        if (lecture == null || lecture.capturing()) return;
        var target = chooseSubject("В какой предмет перенести «" + lecture.title() + "»?");
        if (target == null || target.id() == lecture.subjectId()) return;
        long to = target.id();
        String title = lecture.title();
        mutate(() -> library.moveLecture(lecture.id(), to), () -> {
            showSubject(shown);
            libraryChanged.run();
            say("Лекция «" + title + "» перенесена.");
        });
    }

    /** Диалог выбора другого предмета; возвращает null, если выбирать не из чего или пользователь отменил. */
    private LectureLibrary.Subject otherSubject(String question) {
        var others = all.stream().filter(item -> shown == null || item.id() != shown.id()).toList();
        if (others.isEmpty()) {
            DesktopDialogs.notice(owner, "Нужен хотя бы один другой предмет — создайте его.");
            return null;
        }
        return chooseFrom(others, question);
    }

    private LectureLibrary.Subject chooseSubject(String question) {
        if (all.isEmpty()) return null;
        return chooseFrom(all, question);
    }

    private LectureLibrary.Subject chooseFrom(List<LectureLibrary.Subject> choices, String question) {
        var box = new ComboBox<LectureLibrary.Subject>();
        box.getItems().setAll(choices);
        box.getSelectionModel().selectFirst();
        var form = DesktopDialogs.fields();
        DesktopDialogs.field(form, 0, question, box);
        if (!DesktopDialogs.form(owner, "Перенос лекций", form)) return null;
        return box.getValue();
    }

    @FunctionalInterface
    private interface Mutation { void call() throws Exception; }

    private void mutate(Mutation operation, Runnable completed) {
        writing = true;
        updateButtons();
        worker.execute(() -> {
            try {
                operation.call();
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    completed.run();
                    libraryChanged.run();
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    updateButtons();
                    say(error instanceof IOException && error.getMessage() != null ? error.getMessage()
                            : "Не удалось изменить предметы.");
                });
            }
        });
    }

    private void updateButtons() {
        boolean ready = !writing && !closed;
        var lecture = lectures.getSelectionModel().getSelectedItem();
        create.setDisable(!ready);
        edit.setDisable(!ready || shown == null);
        remove.setDisable(!ready || shown == null);
        moveAll.setDisable(!ready || shown == null || shown.lectures() == 0);
        openSubject.setDisable(!ready || shown == null);
        moveOne.setDisable(!ready || lecture == null || lecture.capturing());
        openLecture.setDisable(!ready || lecture == null);
    }

    private void say(String message) {
        status.setText(message);
        status.setVisible(!message.isBlank());
        status.setManaged(!message.isBlank());
    }
}
