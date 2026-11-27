package org.example;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;

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
 * Управление коллекциями и перенос записей между ними.
 * Все обращения к SQLite выполняются вне потока JavaFX.
 */
final class SubjectsPanel extends VBox {
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private enum Order {
        NAME("По названию"), POPULAR("По числу записей");

        private final String label;

        Order(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private final Window owner;
    private final LectureLibrary library;
    private final Consumer<Long> openSubjectInLibrary;
    private final Consumer<LectureLibrary.Lecture> openLectureInLibrary;
    private final Runnable libraryChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final TextField search = new TextField();
    private final ComboBox<Order> order = new ComboBox<>();
    private final ListView<LectureLibrary.Subject> subjects = new ListView<>();
    private final ListView<LectureLibrary.Lecture> lectures = new ListView<>();
    private final Label heading = new Label();
    private final Label statistics = new Label();
    private final Label details = new Label();
    private final Label status = new Label();
    private final Button create = new Button("Создать коллекцию");
    private final MenuItem edit = new MenuItem("Изменить");
    private final MenuItem remove = new MenuItem("Удалить");
    private final MenuItem moveAll = new MenuItem("Перенести все записи");
    private final MenuItem moveOne = new MenuItem("Перенести выбранную запись");
    private final Button openSubject = new Button("В библиотеку");
    private final Button openLecture = new Button("Открыть запись");

    private List<LectureLibrary.Subject> all = List.of();
    private LectureLibrary.Subject shown;
    private boolean applying;
    private boolean writing;
    private boolean closed;

    SubjectsPanel(Window owner, LectureLibrary library, Consumer<Long> openSubjectInLibrary,
                  Consumer<LectureLibrary.Lecture> openLectureInLibrary, Runnable libraryChanged) {
        super(10);
        this.owner = owner;
        this.library = library;
        this.openSubjectInLibrary = openSubjectInLibrary;
        this.openLectureInLibrary = openLectureInLibrary;
        this.libraryChanged = libraryChanged;
        setPadding(new Insets(14));

        search.setId("subject-search");
        search.setPromptText("Поиск по названию и описанию коллекции");
        search.setAccessibleText("Поиск по названию и описанию коллекции");
        HBox.setHgrow(search, Priority.ALWAYS);
        order.setId("subject-order");
        order.getItems().setAll(Order.values());
        order.getSelectionModel().selectFirst();
        order.setTooltip(new Tooltip("Сортировка коллекций"));

        subjects.setId("subject-list");
        subjects.setPlaceholder(new Label("Коллекций пока нет"));
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
        lectures.setPlaceholder(new Label("В этой коллекции ещё нет записей"));
        lectures.setCellFactory(source -> new ListCell<>() {
            {
                setWrapText(true);
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 2) openLecture.fire();
                });
            }

            @Override protected void updateItem(LectureLibrary.Lecture item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                var meta = new Label(LIST_DATE.format(item.created()) + " · " + state(item));
                meta.getStyleClass().add("subject-meta");
                var title = new Label(item.title());
                title.setWrapText(true);
                setGraphic(new VBox(3, title, meta));
            }
        });
        lectures.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> updateButtons());

        details.setWrapText(true);
        heading.setText("Выберите коллекцию");
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
            if (item != null) openLectureInLibrary.accept(item);
        });

        var actions = new MenuButton("Действия", null, edit, moveAll, remove);
        actions.disableProperty().bind(edit.disableProperty());
        var transfer = new MenuButton("Ещё", null, moveOne);
        transfer.disableProperty().bind(moveOne.disableProperty());
        lectures.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) { openLecture.fire(); event.consume(); }
        });
        heading.getStyleClass().add("subject-title");
        statistics.getStyleClass().add("subject-meta");
        heading.setWrapText(true);
        details.managedProperty().bind(details.textProperty().isNotEmpty());
        details.visibleProperty().bind(details.managedProperty());
        var listPane = new VBox(8, new Label("Коллекции"), search, order, subjects, create);
        var right = new VBox(8, heading, details, statistics, new HBox(8, openSubject, actions),
                new Label("Записи коллекции"), lectures, new HBox(8, openLecture, transfer));
        listPane.getStyleClass().add("subject-pane");
        right.getStyleClass().add("subject-pane");
        VBox.setVgrow(subjects, Priority.ALWAYS);
        VBox.setVgrow(lectures, Priority.ALWAYS);
        var split = new SplitPane(listPane, right);
        split.setDividerPositions(0.33);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(split, status);
        updateButtons();
    }

    void initialize() {
        refresh();
    }

    /** Перечитывает коллекции и их счётчики после изменений в библиотеке. */
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
                    if (!closed) say("Не удалось загрузить коллекции.");
                });
            }
        });
    }

    void close() {
        closed = true;
        worker.shutdown();
    }

    private static String card(LectureLibrary.Subject item) {
        return item.name() + " · записей: " + item.lectures();
    }

    private static String counts(LectureLibrary.Subject item) {
        return "Записей: " + item.lectures() + " · с расшифровкой: " + item.transcripts()
                + " · с конспектом: " + item.notes();
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

    /** Поиск и сортировка работают с уже загруженным списком коллекций. */
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
            showSubject(null);
            if (search.getText().isBlank()) subjects.setPlaceholder(new Label("Коллекций пока нет"));
            else {
                var clear = new Button("Сбросить поиск");
                clear.setOnAction(event -> search.clear());
                subjects.setPlaceholder(new VBox(8, new Label("Ничего не найдено"), clear));
            }
            lectures.getItems().clear();
        }
        say(visible.size() == all.size() ? "" : "Найдено коллекций: " + visible.size());
        updateButtons();
    }

    private void showSubject(LectureLibrary.Subject item) {
        shown = item;
        lectures.getItems().clear();
        if (item == null) {
            heading.setText("Выберите коллекцию");
            details.setText("");
            statistics.setText("");
            return;
        }
        heading.setText(item.name());
        statistics.setText(counts(item));
        details.setText(item.description());
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
                    if (!closed && shown != null && shown.id() == id) say("Не удалось загрузить записи коллекции.");
                });
            }
        });
    }

    private void editSubject(LectureLibrary.Subject item) {
        var name = new TextField(item == null ? "" : item.name());
        var description = new TextField(item == null ? "" : item.description());
        name.setPromptText("Например, рабочие встречи");
        var form = DesktopDialogs.fields();
        DesktopDialogs.field(form, 0, "Название", name);
        DesktopDialogs.field(form, 1, "Описание", description);
        if (!DesktopDialogs.form(owner, item == null ? "Новая коллекция" : "Изменить коллекцию", form)) return;
        String title = name.getText();
        String details = description.getText();
        mutate(() -> library.saveSubject(item == null ? null : item.id(), title, details), this::refresh);
    }

    /** Перед удалением непустой коллекции предлагаем перенести её записи. */
    private void removeSubject() {
        var item = shown;
        if (item == null) return;
        if (item.lectures() == 0) {
            if (!DesktopDialogs.confirm(owner, "Удаление коллекции",
                    "Удалить пустую коллекцию «" + item.name() + "»?")) return;
            mutate(() -> library.deleteSubject(item.id()), this::refresh);
            return;
        }
        var target = otherSubject("В какую коллекцию перенести записи?");
        if (target == null) return;
        if (!DesktopDialogs.confirm(owner, "Перенос и удаление", "Перенести все записи из «" + item.name()
                + "» в «" + target.name() + "» и удалить исходную коллекцию?")) return;
        long to = target.id();
        mutate(() -> {
            library.moveSubjectLectures(item.id(), to);
            library.deleteSubject(item.id());
        }, this::refresh);
    }

    private void moveAllLectures() {
        var item = shown;
        if (item == null || item.lectures() == 0) return;
        var target = otherSubject("В какую коллекцию перенести все записи?");
        if (target == null) return;
        if (!DesktopDialogs.confirm(owner, "Перенос записей", "Перенести все записи из «" + item.name()
                + "» в «" + target.name() + "»?")) return;
        long to = target.id();
        mutate(() -> library.moveSubjectLectures(item.id(), to), this::refresh);
    }

    private void moveOneLecture() {
        var lecture = lectures.getSelectionModel().getSelectedItem();
        if (lecture == null || lecture.capturing()) return;
        var target = chooseSubject("В какую коллекцию перенести «" + lecture.title() + "»?");
        if (target == null || target.id() == lecture.subjectId()) return;
        long to = target.id();
        String title = lecture.title();
        mutate(() -> library.moveLecture(lecture.id(), to), () -> {
            showSubject(shown);
            libraryChanged.run();
            say("Запись «" + title + "» перенесена.");
        });
    }

    /** Предлагает выбрать коллекцию для переноса. */
    private LectureLibrary.Subject otherSubject(String question) {
        var others = all.stream().filter(item -> shown == null || item.id() != shown.id()).toList();
        if (others.isEmpty()) {
            DesktopDialogs.notice(owner, "Сначала создайте ещё одну коллекцию.");
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
        if (!DesktopDialogs.form(owner, "Перенос записей", form)) return null;
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
                            : "Не удалось изменить коллекции.");
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
