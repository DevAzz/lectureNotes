package org.example;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class LibraryPanel extends VBox {
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private record Choice<T>(String label, T value) {
        @Override public String toString() { return label; }
    }

    private final Window owner;
    private final LectureLibrary library;
    private final Consumer<LectureLibrary.Lecture> openMeeting;
    private final Predicate<String> reserved;
    private final Consumer<String> onLectureDeleted;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong queryVersion = new AtomicLong();
    private final PauseTransition queryDelay = new PauseTransition(Duration.millis(180));
    private final TextField query = new TextField();
    private final ComboBox<Choice<Long>> subject = new ComboBox<>();
    private final DatePicker since = new DatePicker();
    private final DatePicker until = new DatePicker();
    private final ComboBox<Choice<Boolean>> note = new ComboBox<>();
    private final ComboBox<Choice<String>> state = new ComboBox<>();
    private final ComboBox<Choice<LectureLibrary.Sort>> order = new ComboBox<>();
    private final ListView<LectureLibrary.Hit> results = new ListView<>();
    private final Label resultStatus = new Label();
    private final Label summary = new Label("Выберите лекцию");
    private final TextArea gaps = new TextArea();
    private final ListView<LectureLibrary.LiveSegment> liveDraft = new ListView<>();
    private final Button editDraftLine = new Button("Изменить реплику");
    private final Button removeAudio = new Button("Удалить WAV");
    private final Button connect = new Button("Открыть для встречи");
    private final Button edit = new Button("Изменить карточку");
    private final Button delete = new Button("Удалить лекцию");
    private final Button subjectsButton = new Button("Предметы");
    private final Runnable openSubjects;
    private final RecordingPlayer player;
    private final LectureDocuments documents;
    private final TabPane tabs = new TabPane();
    private final Tab recordingTab;
    private final Tab transcriptTab;
    private final Tab noteTab;
    private List<LectureLibrary.Subject> subjects = List.of();
    private LectureLibrary.Lecture shown;
    private long playbackVersion;
    private long liveDraftVersion;
    private boolean applying;
    private boolean writing;
    private boolean closed;

    LibraryPanel(Window owner, LectureLibrary library, Consumer<LectureLibrary.Lecture> openMeeting,
                 Predicate<String> reserved, Consumer<String> onLectureDeleted, Runnable chooseLibraryFolder,
                 Runnable openSubjects) {
        super(10);
        this.owner = owner;
        this.library = library;
        this.openMeeting = openMeeting;
        this.reserved = reserved;
        this.onLectureDeleted = onLectureDeleted;
        this.openSubjects = openSubjects;
        player = new RecordingPlayer(this::notice);
        documents = new LectureDocuments(owner, player, library, () -> refresh(false, null));
        documents.busyProperty().addListener((obs, prior, value) -> updateButtons());
        liveDraft.setPlaceholder(new Label("Живых реплик пока нет"));
        liveDraft.setCellFactory(source -> new ListCell<>() {
            {
                setWrapText(true);
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && !isEmpty()) playDraftLine(getItem());
                });
            }

            @Override protected void updateItem(LectureLibrary.LiveSegment segment, boolean empty) {
                super.updateItem(segment, empty);
                setText(empty || segment == null ? null : segment.toString());
            }
        });
        liveDraft.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) playDraftLine(liveDraft.getSelectionModel().getSelectedItem());
        });
        liveDraft.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> updateButtons());
        editDraftLine.setOnAction(event -> editDraftSegment());
        removeAudio.setOnAction(event -> removeOriginalAudio());
        var details = new VBox(8, new Label("Черновик живой расшифровки"), liveDraft,
                new HBox(8, editDraftLine, removeAudio), gaps);
        VBox.setVgrow(liveDraft, Priority.ALWAYS);
        var recordingPane = new BorderPane(details, summary, null, null, null);
        recordingPane.setPadding(new Insets(10));
        BorderPane.setMargin(details, new Insets(8, 0, 8, 0));
        gaps.setEditable(false);
        gaps.setWrapText(true);
        gaps.setFocusTraversable(false);
        gaps.setPrefRowCount(2);
        gaps.visibleProperty().bind(gaps.textProperty().isNotEmpty());
        gaps.managedProperty().bind(gaps.visibleProperty());
        recordingTab = new Tab("Запись", recordingPane);
        transcriptTab = new Tab("Расшифровка", documents.transcriptPanel());
        noteTab = new Tab("Конспект", documents.notePanel());
        tabs.getTabs().addAll(recordingTab, transcriptTab, noteTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var lecturePane = new BorderPane(tabs);
        player.setPadding(new Insets(10));
        lecturePane.setBottom(player);
        var split = new SplitPane(results, lecturePane);
        split.setDividerPositions(0.31);
        VBox.setVgrow(split, Priority.ALWAYS);

        setPadding(new Insets(14));
        query.setPromptText("Поиск по лекциям, репликам и конспектам");
        query.setAccessibleText("Поиск по лекциям, репликам и конспектам");
        HBox.setHgrow(query, Priority.ALWAYS);
        configureFilters();
        since.setPromptText("С даты");
        until.setPromptText("По дату");
        since.setAccessibleText("С даты");
        until.setAccessibleText("По дату");
        since.setPrefWidth(128);
        until.setPrefWidth(128);
        since.setEditable(false);
        until.setEditable(false);
        var reset = new Button("Сбросить фильтры");
        reset.setOnAction(event -> resetFilters());
        reset.setTooltip(new Tooltip("Очистить поиск, предмет, период, конспект и состояние"));
        reset.disableProperty().bind(Bindings.createBooleanBinding(this::filtersEmpty,
                query.textProperty(), subject.valueProperty(), since.valueProperty(), until.valueProperty(),
                note.valueProperty(), state.valueProperty()));
        var libraryFolder = new Button("Папка библиотеки");
        libraryFolder.setOnAction(event -> chooseLibraryFolder.run());
        var searchRow = new HBox(8, query, order, subjectsButton, libraryFolder);
        var filters = new HBox(8, subject, since, until, note, state, reset);
        getChildren().addAll(searchRow, filters, split, new HBox(8, connect, edit, delete, resultStatus));

        results.setPlaceholder(new Label("Лекции не найдены"));
        results.setCellFactory(source -> new ListCell<>() {
            @Override protected void updateItem(LectureLibrary.Hit hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null ? null : resultLabel(hit));
                setWrapText(true);
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((obs, prior, hit) -> {
            if (applying || hit == null) return;
            if (shown != null && !hit.lecture().id().equals(shown.id()) && !documents.canLeaveSelection()) {
                applying = true;
                results.getSelectionModel().select(prior);
                applying = false;
                return;
            }
            show(hit.lecture(), hit.kind(), hit.ordinal());
        });
        connect.setOnAction(event -> openMeeting.accept(shown));
        edit.setOnAction(event -> editLecture());
        delete.setOnAction(event -> deleteLecture());
        subjectsButton.setOnAction(event -> openSubjects.run());
        queryDelay.setOnFinished(event -> refresh(false, null));
        query.textProperty().addListener((obs, prior, value) -> {
            queryVersion.incrementAndGet();
            queryDelay.playFromStart();
        });
        updateButtons();
    }

    private void configureFilters() {
        subject.getItems().add(new Choice<>("Все предметы", null));
        note.getItems().setAll(List.of(new Choice<>("Любой конспект", null), new Choice<>("Есть конспект", true),
                new Choice<>("Без конспекта", false)));
        state.getItems().setAll(List.of(new Choice<>("Все состояния", null), new Choice<>("Черновик", "DRAFT"),
                new Choice<>("Идёт запись / восстановление", "ACTIVE"), new Choice<>("Запись готова", "COMPLETE"),
                new Choice<>("Частичная запись", "PARTIAL"), new Choice<>("Без аудио", "EMPTY"),
                new Choice<>("Есть расшифровка", "TRANSCRIBED")));
        order.getItems().setAll(List.of(new Choice<>("По релевантности", LectureLibrary.Sort.RELEVANCE),
                new Choice<>("По дате", LectureLibrary.Sort.DATE), new Choice<>("По названию", LectureLibrary.Sort.TITLE)));
        subject.getSelectionModel().selectFirst();
        note.getSelectionModel().selectFirst();
        state.getSelectionModel().selectFirst();
        order.getSelectionModel().selectFirst();
        for (var input : List.of(subject, note, state, order, since, until)) {
            input.setOnAction(event -> { if (!applying) refresh(false, null); });
        }
    }

    private void resetFilters() {
        applying = true;
        query.clear();
        subject.getSelectionModel().selectFirst();
        note.getSelectionModel().selectFirst();
        state.getSelectionModel().selectFirst();
        since.setValue(null);
        until.setValue(null);
        applying = false;
        refresh(false, null);
    }

    /** Кнопка сброса нужна только когда задан поиск или хотя бы один фильтр. */
    private boolean filtersEmpty() {
        return query.getText().isBlank() && value(subject) == null && since.getValue() == null
                && until.getValue() == null && value(note) == null && value(state) == null;
    }

    private static String resultLabel(LectureLibrary.Hit hit) {
        var lecture = hit.lecture();
        String location = switch (hit.kind()) {
            case CARD -> hit.excerpt();
            case SEGMENT -> RecordingPlayer.timestamp(hit.startMillis()) + " · " + hit.excerpt();
            case NOTE -> "Конспект · " + hit.excerpt();
        };
        String when = LIST_DATE.format(lecture.created());
        return location.isBlank() ? lecture.label() + "\n" + when
                : lecture.label() + "\n" + when + " · " + location;
    }

    LocalProcessing.Whisper whisper() {
        return documents.whisper();
    }

    void initialize() {
        worker.execute(() -> {
            try {
                boolean recovered = library.recover();
                boolean cleaned = library.cleanFiles();
                Platform.runLater(() -> {
                    if (closed) return;
                    refresh(true, null);
                    if (!recovered) notice("Часть незавершённых записей будет восстановлена при следующем запуске.");
                    if (!cleaned) notice("Часть файлов из очереди удаления будет очищена при следующем запуске.");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed) {
                        refresh(true, null);
                        notice("Не удалось восстановить состояние библиотеки.");
                    }
                });
            }
        });
    }

    void refresh(boolean refreshSubjects, String focusId) {
        if (closed) return;
        queryDelay.stop();
        long request = queryVersion.incrementAndGet();
        var zone = ZoneId.systemDefault();
        var start = since.getValue();
        var end = until.getValue();
        if (start != null && end != null && end.isBefore(start)) {
            resultStatus.setText("Проверьте период");
            return;
        }
        var filter = new LectureLibrary.Filters(value(subject), start == null ? null : start.atStartOfDay(zone).toInstant(),
                end == null ? null : end.plusDays(1).atStartOfDay(zone).toInstant(),
                value(note), value(state), value(order));
        String search = query.getText();
        String currentId = shown == null ? null : shown.id();
        worker.execute(() -> {
            try {
                var updatedSubjects = refreshSubjects ? library.subjects() : null;
                if (updatedSubjects != null) Platform.runLater(() -> applySubjects(updatedSubjects));
                if (request != queryVersion.get()) return;
                var hits = library.find(search, filter);
                var current = currentId == null ? null : library.lecture(currentId);
                Platform.runLater(() -> {
                    if (!closed && request == queryVersion.get()) applyResults(hits, current, focusId);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed && request == queryVersion.get()) resultStatus.setText("Не удалось загрузить библиотеку");
                });
            }
        });
    }

    private static <T> T value(ComboBox<Choice<T>> box) {
        return box.getValue() == null ? null : box.getValue().value();
    }

    /** Переключает библиотеку на лекции выбранного предмета. */
    void showSubject(long subjectId) {
        var choice = subject.getItems().stream()
                .filter(item -> item.value() != null && item.value() == subjectId)
                .findFirst().orElse(null);
        if (choice == null) {
            refresh(true, null);
            return;
        }
        subject.getSelectionModel().select(choice);
        refresh(false, null);
    }

    /** Настройки во вкладке «Настройки» меняют движок и профили этой панели. */
    void reloadSettings() {
        documents.reloadSettings();
    }

    private void applySubjects(List<LectureLibrary.Subject> current) {
        if (closed) return;
        subjects = current;
        Long selected = value(subject);
        applying = true;
        subject.getItems().setAll(List.of(new Choice<>("Все предметы", null)));
        for (var item : subjects) subject.getItems().add(new Choice<>(item.name(), item.id()));
        var choice = subject.getItems().stream().filter(item -> Objects.equals(item.value(), selected)).findFirst()
                .orElse(subject.getItems().getFirst());
        subject.getSelectionModel().select(choice);
        applying = false;
        if (!Objects.equals(selected, choice.value())) refresh(false, null);
    }

    private void applyResults(List<LectureLibrary.Hit> hits, LectureLibrary.Lecture current, String focusId) {
        var prior = results.getSelectionModel().getSelectedItem();
        if (current != null && shown != null && current.id().equals(shown.id())) {
            renderLecture(current);
        }
        boolean canFocus = focusId != null && (shown == null || shown.id().equals(focusId));
        var selection = hits.stream().filter(hit -> canFocus ? focusId.equals(hit.lecture().id())
                : prior != null && sameLocation(prior, hit)).findFirst().orElse(null);
        applying = true;
        results.getItems().setAll(hits);
        if (selection != null) results.getSelectionModel().select(selection);
        else results.getSelectionModel().clearSelection();
        applying = false;
        if (canFocus && selection != null) {
            show(selection.lecture(), selection.kind(), selection.ordinal());
            results.scrollTo(selection);
        }
        resultStatus.setText(query.getText().isBlank() ? "" : "Результатов: " + hits.size()
                + (hits.size() == 50 ? " (первые 50)" : ""));
    }

    private static boolean sameLocation(LectureLibrary.Hit a, LectureLibrary.Hit b) {
        return a.kind() == b.kind() && a.ordinal() == b.ordinal() && a.lecture().id().equals(b.lecture().id());
    }

    private void show(LectureLibrary.Lecture item, LectureLibrary.Kind kind, int ordinal) {
        renderLecture(item);
        documents.select(item, ordinal);
        tabs.getSelectionModel().select(switch (kind) {
            case CARD -> recordingTab;
            case SEGMENT -> transcriptTab;
            case NOTE -> noteTab;
        });
    }

    private void renderLecture(LectureLibrary.Lecture item) {
        var previous = shown;
        shown = item;
        documents.select(item, 0);
        updateButtons();
        summary.setText(item == null ? "Выберите лекцию" : item.title() + " · " + (item.audioRemoved() ? "WAV удалены" : switch (item.state()) {
            case "COMPLETE" -> "Запись готова";
            case "PARTIAL" -> "Частичная запись";
            case "EMPTY" -> "Аудио не получено";
            case "DRAFT" -> "Карточка без записи";
            default -> "Запись или восстановление";
        }));
        if (item != null && previous != null && item.id().equals(previous.id()) && item.state().equals(previous.state())
                && item.audioRemoved() == previous.audioRemoved()) return;
        long request = ++playbackVersion;
        player.setChunks(List.of());
        gaps.clear();
        if (previous == null || item == null || !previous.id().equals(item.id())) liveDraft.getItems().clear();
        if (item != null) liveDraftChanged(item.id());
        else liveDraftVersion++;
        if (item == null || item.state().equals("DRAFT") || item.audioRemoved()) return;
        worker.execute(() -> {
            try {
                var path = library.recordingsPath(item.id());
                if (!java.nio.file.Files.isDirectory(path)) return;
                var chunks = new Recording(path).chunks();
                var missing = new StringBuilder();
                long end = 0;
                for (var chunk : chunks) {
                    if (chunk.startMillis() - end >= 100) {
                        missing.append(RecordingPlayer.timestamp(end)).append(" — ")
                                .append(RecordingPlayer.timestamp(chunk.startMillis())).append('\n');
                    }
                    end = chunk.endMillis();
                }
                Platform.runLater(() -> {
                    if (closed || request != playbackVersion) return;
                    player.setChunks(chunks);
                    gaps.setText(missing.isEmpty() ? "" : "Пропуски в записи\n" + missing);
                });
            } catch (IOException error) {
                Platform.runLater(() -> { if (!closed && request == playbackVersion) notice("Не удалось открыть WAV."); });
            }
        });
    }

    void liveDraftChanged(String id) {
        if (closed || shown == null || !shown.id().equals(id)) return;
        long request = ++liveDraftVersion;
        worker.execute(() -> {
            try {
                var segments = library.liveSegments(id);
                Platform.runLater(() -> {
                    if (closed || request != liveDraftVersion) return;
                    var prior = liveDraft.getSelectionModel().getSelectedItem();
                    liveDraft.getItems().setAll(segments);
                    if (prior != null) {
                        for (var segment : segments) {
                            if (segment.id() == prior.id()) {
                                liveDraft.getSelectionModel().select(segment);
                                break;
                            }
                        }
                    }
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed && request == liveDraftVersion) notice("Не удалось открыть черновик расшифровки.");
                });
            }
        });
    }

    private void playDraftLine(LectureLibrary.LiveSegment line) {
        if (line != null && shown != null && shown.hasAudio()) player.seek(line.segment().start(), true);
    }

    private void editDraftSegment() {
        var line = liveDraft.getSelectionModel().getSelectedItem();
        if (line == null || shown == null || shown.capturing()) return;
        String edited = DesktopDialogs.editSegment(owner, line.segment());
        if (edited == null || edited.equals(line.segment().text())) return;
        String id = shown.id();
        mutate(() -> library.editLiveSegment(id, line.id(), edited), () -> liveDraftChanged(id));
    }

    private void removeOriginalAudio() {
        var item = shown;
        if (item == null || !item.hasAudio()) return;
        if (!DesktopDialogs.confirm(owner, "Удаление WAV", "Удалить WAV лекции «" + item.title() + "»?\n"
                + "Карточка, черновик, расшифровка и конспект останутся в библиотеке.")) return;
        player.close();
        var removed = new AtomicBoolean();
        mutate(() -> removed.set(library.removeOriginalAudio(item.id())), () -> {
            refresh(false, item.id());
            if (!removed.get()) notice("Удаление WAV завершится при следующем запуске после освобождения файлов.");
        });
    }

    private void editLecture() {
        var item = shown;
        var name = new TextField(item.title());
        var topic = new TextField(item.topic());
        var focus = new TextField(item.focus());
        var section = new ComboBox<LectureLibrary.Subject>();
        section.getItems().setAll(subjects);
        subjects.stream().filter(value -> value.id() == item.subjectId()).findFirst().ifPresent(section::setValue);
        var form = DesktopDialogs.fields();
        DesktopDialogs.field(form, 0, "Предмет", section);
        DesktopDialogs.field(form, 1, "Название", name);
        DesktopDialogs.field(form, 2, "Тема", topic);
        DesktopDialogs.field(form, 3, "Фокус конспекта", focus);
        if (!DesktopDialogs.form(owner, "Изменить карточку", form) || section.getValue() == null) return;
        String subjectName = section.getValue().name();
        String title = name.getText();
        String topicValue = topic.getText();
        String focusValue = focus.getText();
        mutate(() -> library.editLecture(item.id(), subjectName, title, topicValue, focusValue),
                () -> refresh(true, null));
    }

    private void deleteLecture() {
        var item = shown;
        String data = switch (item.state()) {
            case "DRAFT" -> "карточка";
            case "EMPTY" -> "карточка и файлы сессии";
            default -> "карточка, аудио, версии расшифровки и файлы обработки";
        };
        if (item.hasNote()) data += ", конспект и его редакции";
        if (!DesktopDialogs.confirm(owner, "Удаление лекции", "Будут удалены данные лекции «" + item.title() + "»\n" + data)) return;
        player.close();
        mutate(() -> library.deleteLecture(item.id()), () -> {
            onLectureDeleted.accept(item.id());
            if (shown != null && shown.id().equals(item.id())) renderLecture(null);
            refresh(false, null);
            worker.execute(() -> {
                try {
                    if (library.cleanFiles()) return;
                } catch (Exception ignored) { }
                Platform.runLater(() -> notice("Оставшиеся файлы будут очищены при следующем запуске."));
            });
        });
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
                    updateButtons();
                    completed.run();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    updateButtons();
                    notice(error instanceof IOException ? error.getMessage() : "Не удалось изменить библиотеку.");
                });
            }
        });
    }

    void updateButtons() {
        boolean canChange = shown != null && !shown.capturing() && !writing && !reserved.test(shown.id());
        connect.setDisable(!canChange || !shown.canRecord());
        edit.setDisable(!canChange);
        delete.setDisable(!canChange || documents.busyProperty().get());
        editDraftLine.setDisable(!canChange || liveDraft.getSelectionModel().isEmpty());
        removeAudio.setDisable(!canChange || !shown.hasAudio() || documents.busyProperty().get());
        subjectsButton.setDisable(writing);
    }

    private void notice(String message) {
        if (!closed) DesktopDialogs.notice(owner, message);
    }

    boolean canClose() { return documents.canLeaveSelection(); }

    void close() {
        closed = true;
        queryDelay.stop();
        queryVersion.incrementAndGet();
        documents.close();
        player.close();
        worker.shutdown();
    }

    void awaitClose() throws InterruptedException {
        worker.awaitTermination(12, TimeUnit.SECONDS);
        documents.awaitClose();
    }
}
