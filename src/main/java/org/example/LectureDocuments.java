package org.example;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class LectureDocuments {
    private static final Pattern NOTE_TIME = Pattern.compile("\\(#t=(\\d+)\\)");
    private static final DateTimeFormatter REVISION_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final Window owner;
    private final RecordingPlayer player;
    private final LectureLibrary library;
    private final Runnable onPublished;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ListView<LocalProcessing.Segment> transcript = new ListView<>();
    private final TextArea reading = new TextArea();
    private final Label readingStatus = new Label();
    private final Button format = new Button("Разбить на абзацы");
    private final Button exportReading = new Button("Экспорт Markdown");
    private String readingPrompt = LocalProcessing.READING_PROMPT;
    private final TextArea note = new TextArea();
    private final MarkdownEditor noteEditor;
    private final Label status = new Label();
    private final Label noteStatus = new Label();
    private final Button recognize = new Button("Распознать");
    private final Button modelSettings = new Button("Настройки моделей");
    private final Button playLine = new Button("Прослушать реплику");
    private final Button editLine = new Button("Изменить реплику");
    private final SplitMenuButton diarize = new SplitMenuButton();
    private final Button renameSpeaker = new Button("Переименовать спикера");
    private final Button exportTranscript = new Button("Экспорт Markdown");
    private final Button generate = new Button("Сформировать конспект");
    private final Button save = new Button("Сохранить редакцию");
    private final Button exportNote = new Button("Экспорт Markdown");
    private final Button playReference = new Button("Прослушать по ссылке");
    private final Button revisions = new Button("Редакции");
    private final Button delete = new Button("Удалить конспект");
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private LectureLibrary.Lecture selected;
    private Long transcriptId;
    private LectureLibrary.Note currentNote;
    private String savedText = "";
    private int requestedOrdinal;
    private long selection;
    private boolean loading;
    private RussianSearch.Match pendingMatch;
    private boolean closed;
    private Runnable openSettings = () -> { };
    private List<LectureLibrary.Profile> profiles = List.of();
    private LocalProcessing.Whisper whisper;
    private String systemPrompt = LocalProcessing.DEFAULT_SYSTEM_PROMPT;
    private int noteTimeLimitMinutes = LectureLibrary.DEFAULT_NOTE_TIME_LIMIT_MINUTES;

    private final DocumentTaskBar taskBar;

    LectureDocuments(Window owner, RecordingPlayer player, LectureLibrary library, Runnable onPublished,
                     DocumentTaskBar taskBar) {
        this.taskBar = taskBar;
        this.owner = owner;
        this.player = player;
        this.library = library;
        this.onPublished = onPublished;
        try {
            whisper = library.whisperSettings();
            readingPrompt = library.readingPrompt();
            systemPrompt = library.noteSystemPrompt();
            noteTimeLimitMinutes = library.noteTimeLimitMinutes();
            profiles = library.profiles();
        } catch (Exception error) {
            var bundled = LocalProcessing.bundledWhisper(false);
            if (!Files.isRegularFile(bundled.executable())) bundled = LocalProcessing.bundledWhisper(true);
            whisper = bundled;
        }
        noteEditor = new MarkdownEditor(note, player::currentTimeMillis);
        noteEditor.setOnSeek(time -> {
            if (selected != null && selected.hasAudio()) player.seek(time, true);
        });
        noteEditor.setSaveAction(() -> {
            if (!save.isDisabled()) saveNote();
        });
    }

    /** Кнопка «Настройки моделей» ведёт на общую вкладку настроек. */
    void setOpenSettings(Runnable action) {
        openSettings = action;
    }

    /** Перечитывает настройки библиотеки после изменений во вкладке «Настройки». */
    void reloadSettings() {
        worker.execute(() -> {
            try {
                var engine = library.whisperSettings();
                var savedProfiles = library.profiles();
                String prompt = library.noteSystemPrompt();
                int minutes = library.noteTimeLimitMinutes();
                Platform.runLater(() -> {
                    if (closed) return;
                    whisper = engine;
                    profiles = savedProfiles;
                    systemPrompt = prompt;
                    noteTimeLimitMinutes = minutes;
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed) DesktopDialogs.notice(owner, "Не удалось перечитать настройки приложения.");
                });
            }
        });
    }

    LocalProcessing.Whisper whisper() {
        return whisper;
    }

    VBox transcriptPanel() {
        transcript.setPlaceholder(new Label("Расшифровки пока нет"));
        transcript.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> updateButtons());
        transcript.setOnMouseClicked(event -> { if (event.getClickCount() == 2) playSegment(); });
        recognize.setOnAction(event -> startRecognition());
        modelSettings.setOnAction(event -> openSettings.run());
        playLine.setOnAction(event -> playSegment());
        editLine.setOnAction(event -> editSegment());
        diarize.setText("Разметить спикеров");
        diarize.setOnAction(event -> startDiarization(false));
        var singleSpeaker = new MenuItem("Один спикер на всю запись");
        singleSpeaker.setOnAction(event -> startDiarization(true));
        diarize.getItems().setAll(singleSpeaker);
        renameSpeaker.setOnAction(event -> renameSpeaker());
        exportTranscript.setOnAction(event -> {
            var markdown = new StringBuilder("# ").append(selected.title()).append("\n\n");
            for (var segment : transcript.getItems()) markdown.append("- ").append(segment).append('\n');
            export("transcript.md", markdown.toString());
        });
        var panel = new VBox(8, transcript, status, new HBox(8, recognize, modelSettings, exportTranscript),
                new HBox(8, playLine, editLine), new HBox(8, diarize, renameSpeaker));
        panel.setPadding(new Insets(6, 10, 10, 10));
        VBox.setVgrow(transcript, Priority.ALWAYS);
        showStatus("");
        updateButtons();
        return panel;
    }

    VBox readingPanel() {
        reading.setEditable(false);
        reading.setWrapText(true);
        reading.setPromptText("Здесь появится полный текст записи, разбитый на абзацы");
        format.setOnAction(event -> startGeneration(true));
        exportReading.setOnAction(event -> export("recording.md", reading.getText()));
        var panel = new VBox(8, reading, readingStatus, new HBox(8, format, exportReading));
        panel.setPadding(new Insets(6, 10, 10, 10));
        VBox.setVgrow(reading, Priority.ALWAYS);
        return panel;
    }

    VBox notePanel() {
        note.setWrapText(true);
        note.setPromptText("Напишите конспект в Markdown или сформируйте его по расшифровке");
        note.textProperty().addListener((obs, prior, text) -> updateButtons());
        generate.setOnAction(event -> startGeneration(false));
        save.setOnAction(event -> saveNote());
        exportNote.setOnAction(event -> export("note.md", note.getText()));
        playReference.setOnAction(event -> playReference());
        revisions.setOnAction(event -> showRevisions());
        delete.setOnAction(event -> deleteNote());
        var panel = new VBox(8, noteEditor, noteStatus, new HBox(8, generate, save, revisions, delete),
                new HBox(8, playReference, exportNote));
        panel.setPadding(new Insets(6, 10, 10, 10));
        VBox.setVgrow(noteEditor, Priority.ALWAYS);
        showStatus("");
        updateButtons();
        return panel;
    }

    void select(LectureLibrary.Lecture item, int ordinal) {
        if (item != null && selected != null && selected.id().equals(item.id())) {
            selected = item;
            if (ordinal > 0) requestedOrdinal = ordinal;
            selectRequestedSegment();
            updateButtons();
            return;
        }
        pendingMatch = null;
        requestedOrdinal = ordinal;
        selected = item;
        long request = ++selection;
        loading = item != null;
        transcriptId = null;
        transcript.getItems().clear();
        reading.clear();
        setNote(null);
        showStatus(item == null ? "" : "Открываем документы");
        updateButtons();
        if (item == null) return;
        worker.execute(() -> {
            try {
                var saved = library.documents(item.id());
                String prompt = library.noteSystemPrompt();
                int minutes = library.noteTimeLimitMinutes();
                Platform.runLater(() -> {
                    if (closed || request != selection) return;
                    transcriptId = saved.transcriptId();
                    systemPrompt = prompt;
                    noteTimeLimitMinutes = minutes;
                    transcript.getItems().setAll(saved.segments());
                    selectRequestedSegment();
                    setNote(saved.note());
                    reading.setText(saved.readingText() == null ? "" : saved.readingText());
                    loading = false;
                    showStatus("");
                    if (pendingMatch != null) revealMatch(pendingMatch);
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed && request == selection) showStatus("Не удалось открыть документы записи.");
                });
            }
        });
    }

    void revealMatch(RussianSearch.Match match) {
        pendingMatch = loading ? match : null;
        if (!loading && !noteEditor.revealMatch(match)) showStatus("Фрагмент изменён. Обновите поиск.");
    }

    private void selectRequestedSegment() {
        if (requestedOrdinal < 1 || requestedOrdinal > transcript.getItems().size()) return;
        int index = requestedOrdinal - 1;
        transcript.getSelectionModel().select(index);
        transcript.scrollTo(index);
        requestedOrdinal = 0;
    }

    private void setNote(LectureLibrary.Note value) {
        currentNote = value;
        note.setText(value == null ? "" : value.markdown());
        savedText = note.getText();
        note.positionCaret(0);
    }

    private boolean dirty() {
        return !note.getText().equals(savedText);
    }

    private void playSegment() {
        var segment = transcript.getSelectionModel().getSelectedItem();
        if (segment != null && selected != null && selected.hasAudio()) player.seek(segment.start(), true);
    }

    private void editSegment() {
        int index = transcript.getSelectionModel().getSelectedIndex();
        var segment = transcript.getSelectionModel().getSelectedItem();
        if (segment == null || transcriptId == null) return;
        String text = DesktopDialogs.editSegment(owner, segment);
        if (text == null || text.equals(segment.text())) return;
        String id = selected.id();
        long expectedVersion = transcriptId;
        run("Сохраняем исправленную реплику", () -> {
            long version = library.editTranscriptSegment(id, expectedVersion, index, text);
            return () -> {
                transcriptId = version;
                reading.clear();
                transcript.getItems().set(index,
                        new LocalProcessing.Segment(segment.start(), segment.end(), text,
                                segment.speaker(), segment.speakerName()));
                transcript.getSelectionModel().select(index);
            };
        }, true);
    }

    private void playReference() {
        String text = note.getText();
        int caret = note.getCaretPosition();
        int start = text.lastIndexOf('\n', caret - 1) + 1;
        int end = text.indexOf('\n', caret);
        var matcher = NOTE_TIME.matcher(text.substring(start, end < 0 ? text.length() : end));
        try {
            if (matcher.find()) player.seek(Long.parseLong(matcher.group(1)), true);
            else DesktopDialogs.notice(owner, "Поставьте курсор на строку со ссылкой на таймкод.");
        } catch (NumberFormatException error) {
            DesktopDialogs.notice(owner, "Не удалось прочитать таймкод.");
        }
    }

    private void startRecognition() {
        if (!whisper.available()) {
            DesktopDialogs.notice(owner, "Файлы Whisper не найдены. Укажите их во вкладке «Настройки».");
            return;
        }
        String id = selected.id();
        LocalProcessing.Whisper engine = whisper;
        run(DocumentTaskBar.Operation.RECOGNITION, () -> {
            var audio = new Recording(library.recordingsPath(id));
            var result = LocalProcessing.transcribe(audio, engine);
            long version = library.saveTranscript(id, result);
            return () -> {
                transcriptId = version;
                reading.clear();
                transcript.getItems().setAll(result);
                selectRequestedSegment();
            };
        }, true);
    }

    private void startDiarization(boolean singleSpeaker) {
        String id = selected.id();
        long expectedVersion = transcriptId;
        var source = List.copyOf(transcript.getItems());
        if (source.stream().anyMatch(segment -> segment.speaker() != null)
                && !DesktopDialogs.confirm(owner, "Разметка спикеров", "Заменить текущие метки спикеров?")) return;
        int index = transcript.getSelectionModel().getSelectedIndex();
        run(DocumentTaskBar.Operation.SPEAKERS, () -> {
            var labeled = singleSpeaker
                    ? source.stream().map(s -> new LocalProcessing.Segment(s.start(), s.end(), s.text(), "Спикер 1", null)).toList()
                    : SpeakerDiarization.label(new Recording(library.recordingsPath(id)), source, library.diarizationModels());
            long version = library.replaceTranscript(id, expectedVersion, labeled);
            return () -> showLabeledTranscript(version, labeled, index);
        }, true);
    }

    private void renameSpeaker() {
        int index = transcript.getSelectionModel().getSelectedIndex();
        var line = transcript.getSelectionModel().getSelectedItem();
        String speaker = line.speaker();
        var name = new TextField(line.speakerName() == null ? "" : line.speakerName());
        name.setPromptText(speaker);
        name.setPrefColumnCount(30);
        if (!DesktopDialogs.form(owner, "Имя спикера для всех его реплик", name)) return;
        String entered = name.getText().strip();
        String label = entered.isEmpty() ? null : entered;
        if (Objects.equals(label, line.speakerName())) return;
        String id = selected.id();
        long expectedVersion = transcriptId;
        var renamed = transcript.getItems().stream()
                .map(segment -> speaker.equals(segment.speaker())
                        ? new LocalProcessing.Segment(segment.start(), segment.end(), segment.text(), speaker, label)
                        : segment)
                .toList();
        run("Сохраняем имя спикера", () -> {
            long version = library.replaceTranscript(id, expectedVersion, renamed);
            return () -> showLabeledTranscript(version, renamed, index);
        }, true);
    }

    private void showLabeledTranscript(long version, List<LocalProcessing.Segment> segments, int index) {
        transcriptId = version;
        reading.clear();
        transcript.getItems().setAll(segments);
        if (index >= 0) {
            transcript.getSelectionModel().select(index);
            transcript.scrollTo(index);
        }
    }

    private void startGeneration(boolean forReading) {
        if (!forReading && dirty()) {
            DesktopDialogs.notice(owner, "Сохраните текущую редакцию перед генерацией.");
            return;
        }
        if (profiles.isEmpty()) {
            DesktopDialogs.notice(owner, "Создайте профиль модели во вкладке «Настройки».");
            return;
        }
        var profile = new ComboBox<LectureLibrary.Profile>();
        profile.getItems().setAll(profiles);
        profiles.stream().filter(LectureLibrary.Profile::isDefault).findFirst()
                .or(() -> profiles.stream().findFirst())
                .ifPresent(profile::setValue);
        var focus = new TextField(selected.focus());
        var timeLimit = new TextField(Integer.toString(noteTimeLimitMinutes));
        var prompt = new TextArea(forReading ? readingPrompt : systemPrompt);
        prompt.setWrapText(true);
        prompt.setPrefColumnCount(56);
        prompt.setPrefRowCount(8);
        var settings = DesktopDialogs.fields();
        DesktopDialogs.field(settings, 0, "Профиль модели", profile);
        if (!forReading) DesktopDialogs.field(settings, 1, "Фокус", focus);
        DesktopDialogs.field(settings, 2, "Лимит времени, мин", timeLimit);
        DesktopDialogs.field(settings, 3, "Системный промпт", prompt);
        var hint = new Label(forReading ? "Модель выбирает места для абзацев. Все слова сохраняются." : "Основной профиль и промпт задаются во вкладке «Настройки».");
        hint.getStyleClass().add("note-hint");
        settings.add(hint, 1, 4);
        if (!DesktopDialogs.form(owner, forReading ? "Разбить текст на абзацы" : "Сформировать конспект", settings)) return;
        var chosen = profile.getValue();
        if (chosen == null) {
            DesktopDialogs.notice(owner, "Выберите профиль модели.");
            return;
        }
        if (chosen.cloud() && !DesktopDialogs.confirm(owner, "Отправка текста во внешний сервис",
                "Профиль «" + chosen.name() + "» обращается к " + chosen.baseUrl() + ".\n"
                        + "Модель: " + chosen.modelId() + ".\n"
                        + (forReading ? "Передаём текст расшифровки (" : "Передаём тему, фокус и текст расшифровки (") + transcript.getItems().size()
                        + " реплик). Звук не передаётся.")) return;
        String requestedFocus = focus.getText().strip();
        String requestedPrompt = prompt.getText();
        if (requestedPrompt.isBlank()) {
            DesktopDialogs.notice(owner, "Введите системный промпт.");
            return;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(timeLimit.getText().strip());
            if (minutes <= 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            DesktopDialogs.notice(owner, "Укажите положительное целое число минут.");
            return;
        }
        var item = selected;
        var source = List.copyOf(transcript.getItems());
        Long sourceId = transcriptId;
        boolean needsKey = chosen.hasSecret();
        long profileId = chosen.id();
        if (forReading) {
            run(DocumentTaskBar.Operation.READING, () -> {
                String secret = needsKey ? InvitationSecret.unprotectText(library.profileSecret(profileId)) : "";
                String text = LocalProcessing.formatReading(source, chosen.llm(secret), requestedPrompt,
                        java.time.Duration.ofMinutes(minutes));
                library.saveReadingText(item.id(), sourceId, text);
                library.saveSetting("reading_prompt", requestedPrompt);
                return () -> {
                    readingPrompt = requestedPrompt;
                    reading.setText(text);
                    reading.positionCaret(0);
                };
            }, false);
            return;
        }
        systemPrompt = requestedPrompt;
        noteTimeLimitMinutes = minutes;
        run(DocumentTaskBar.Operation.NOTE, () -> {
            String secret = needsKey ? InvitationSecret.unprotectText(library.profileSecret(profileId)) : "";
            library.saveNoteSettings(requestedPrompt, minutes);
            String markdown = LocalProcessing.generate(item.topic().isBlank() ? item.title() : item.topic(),
                    source, chosen.llm(secret), requestedFocus, requestedPrompt, java.time.Duration.ofMinutes(minutes));
            library.saveNote(item.id(), sourceId, markdown, true);
            return () -> setNote(new LectureLibrary.Note(sourceId, markdown));
        }, true);
    }

    private void saveNote() {
        String id = selected.id();
        String markdown = note.getText();
        Long sourceId = currentNote == null ? transcriptId : currentNote.transcriptId();
        run("Сохраняем редакцию", () -> {
            library.saveNote(id, sourceId, markdown, false);
            return () -> {
                currentNote = new LectureLibrary.Note(sourceId, markdown);
                savedText = markdown;
            };
        }, true);
    }

    private void showRevisions() {
        if (!canLeaveSelection()) return;
        String id = selected.id();
        run("Открываем редакции", () -> {
            var versions = library.noteVersions(id);
            return () -> chooseRevision(id, versions);
        }, false);
    }

    private void chooseRevision(String id, List<LectureLibrary.NoteRevision> versions) {
        if (versions.isEmpty()) return;
        var choices = new ComboBox<String>();
        for (var version : versions) {
            String source = switch (version.source()) {
                case "MANUAL" -> "ручная";
                case "GENERATED" -> "модель";
                default -> "импорт";
            };
            choices.getItems().add(REVISION_DATE.format(version.created()) + " · " + source + " · №" + version.id());
        }
        choices.getSelectionModel().selectFirst();
        if (!DesktopDialogs.form(owner, "Восстановить редакцию", choices)) return;
        long version = versions.get(choices.getSelectionModel().getSelectedIndex()).id();
        run("Восстанавливаем редакцию", () -> {
            var restored = library.restoreNote(id, version);
            return () -> setNote(restored);
        }, true);
    }

    private void deleteNote() {
        if (!DesktopDialogs.confirm(owner, "Удаление конспекта", "Удалить конспект и все его редакции?")) return;
        String id = selected.id();
        run("Удаляем конспект", () -> {
            library.deleteNote(id);
            return () -> setNote(null);
        }, true);
    }

    private void export(String name, String body) {
        var dialog = new FileChooser();
        dialog.setTitle("Экспорт Markdown");
        dialog.setInitialFileName(name);
        dialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        var file = dialog.showSaveDialog(owner);
        if (file == null) return;
        Path destination = file.toPath();
        if (Files.exists(destination) && !DesktopDialogs.confirm(owner, "Экспорт", "Заменить существующий файл?")) return;
        run("Экспортируем Markdown", () -> {
            LocalProcessing.writeAtomic(destination, body);
            return () -> { };
        }, false);
    }

    @FunctionalInterface
    private interface Background { Runnable call() throws Exception; }

    private void run(String message, Background task, boolean publish) {
        run(message, task, publish, null);
    }

    private void run(DocumentTaskBar.Operation operation, Background task, boolean publish) {
        taskBar.start(selected, operation);
        run(operation.running, task, publish, operation);
    }

    private void run(String message, Background task, boolean publish, DocumentTaskBar.Operation operation) {
        long request = selection;
        busy.set(true);
        showStatus(message);
        updateButtons();
        worker.execute(() -> {
            try {
                Runnable completed = task.call();
                Platform.runLater(() -> {
                    if (closed) return;
                    busy.set(false);
                    if (operation != null) taskBar.finish(null);
                    if (request == selection) {
                        showStatus("Готово");
                        completed.run();
                    }
                    updateButtons();
                    if (publish) onPublished.run();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy.set(false);
                    updateButtons();
                    String detail = error instanceof IOException && error.getMessage() != null
                            && error.getMessage().codePoints().anyMatch(cp -> cp >= 0x410 && cp <= 0x44f)
                            ? error.getMessage() : "Не удалось выполнить операцию.";
                    if (operation != null) taskBar.finish(detail);
                    if (request != selection) return;
                    showStatus("Операция завершилась ошибкой");
                    if (operation == null) DesktopDialogs.notice(owner, detail);
                });
            }
        });
    }

    private void updateButtons() {
        boolean available = selected != null && !busy.get() && !loading;
        note.setEditable(available);
        noteEditor.setDirty(dirty());
        noteEditor.setTimecodeAvailable(available && selected != null && selected.hasAudio());
        recognize.setDisable(!available || !selected.hasAudio());
        modelSettings.setDisable(false);
        playLine.setDisable(!available || !selected.hasAudio() || transcript.getSelectionModel().isEmpty());
        editLine.setDisable(!available || transcriptId == null || transcript.getSelectionModel().isEmpty());
        diarize.setDisable(!available || !selected.hasAudio() || transcriptId == null || transcript.getItems().isEmpty());
        var line = transcript.getSelectionModel().getSelectedItem();
        renameSpeaker.setDisable(!available || transcriptId == null || line == null
                || !SpeakerDiarization.canName(line.speaker()));
        exportTranscript.setDisable(!available || transcript.getItems().isEmpty());
        format.setDisable(!available || transcript.getItems().isEmpty());
        exportReading.setDisable(!available || reading.getText().isBlank());
        generate.setDisable(!available || transcript.getItems().isEmpty());
        save.setDisable(!available || !dirty());
        exportNote.setDisable(!available || note.getText().isBlank());
        playReference.setDisable(!available || !selected.hasAudio());
        revisions.setDisable(!available || currentNote == null);
        delete.setDisable(!available || currentNote == null);
    }

    /** Пустая строка состояния не занимает место, поэтому панель документов начинается сразу с её содержимого. */
    private void showStatus(String value) {
        status.setText(value);
        noteStatus.setText(value);
        boolean visible = !value.isBlank();
        readingStatus.setText(value);
        readingStatus.setVisible(visible);
        readingStatus.setManaged(visible);
        status.setVisible(visible);
        status.setManaged(visible);
        noteStatus.setVisible(visible);
        noteStatus.setManaged(visible);
    }

    boolean canLeaveSelection() {
        return !dirty() || DesktopDialogs.confirm(owner, "Несохранённые правки",
                "В конспекте есть несохранённые правки. Продолжить?");
    }

    ReadOnlyBooleanProperty busyProperty() { return busy.getReadOnlyProperty(); }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    boolean awaitClose() throws InterruptedException {
        return worker.awaitTermination(12, TimeUnit.SECONDS);
    }
}
