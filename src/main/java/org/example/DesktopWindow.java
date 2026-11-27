package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class DesktopWindow {
    private static final Pattern LEVEL = Pattern.compile("\\{\"event\":\"level\",\"peak\":(\\d+),\"frames\":(\\d+)\\}");

    private record Draft(String subject, String title, String topic, String focus, URI invitation) { }

    private final Stage stage = new Stage();
    private final Path data;
    private final LectureLibrary library;
    private final LibraryLocation location;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final ComboBox<String> subject = new ComboBox<>();
    private final TextField title = new TextField();
    private final TextField topic = new TextField();
    private final TextField focus = new TextField();
    private final TextField invitation = new TextField();
    private final Button open = new Button("Подключиться по ссылке");
    private final Button systemRecord = new Button("Записать звук компьютера");
    private final Button save = new Button("Сохранить карточку");
    private final Button reset = new Button("Новая карточка");
    private final Button start = new Button("Начать запись");
    private final Button stop = new Button("Остановить запись");
    private final Button leave = new Button("Завершить встречу");
    private final Label status = new Label("Готово к подключению");
    private final ProgressBar level = new ProgressBar(0);
    private final Label liveStatus = new Label("Ожидаем начала записи");
    private final TextArea liveText = new TextArea();
    private final TabPane sections = new TabPane();
    private final Tab meetingTab;
    private final Tab libraryTab;
    private final Tab subjectsTab;
    private final Tab settingsTab;
    private final LibraryPanel libraryPanel;
    private final DocumentTaskBar taskBar = new DocumentTaskBar(this::openTaskResult);
    private final SubjectsPanel subjectsPanel;
    private final SettingsPanel settingsPanel;
    private MeetingBrowser browser;
    private Process capture;
    private Thread liveWorker;
    private boolean signalReported;
    private String draftId;
    private String activeId;
    private boolean busy;
    private boolean stopped;
    private boolean finishing;
    private boolean finishRequested;
    private boolean closing;
    private boolean closeStarted;

    DesktopWindow(Path data, LectureLibrary library, LibraryLocation location) {
        this.data = data;
        this.library = library;
        this.location = location;
        libraryPanel = new LibraryPanel(stage, library, this::loadDraft, this::reserved, this::onLectureDeleted,
                this::showSubjectsTab, taskBar);
        subjectsPanel = new SubjectsPanel(stage, library, this::showSubjectInLibrary, this::openLectureInLibrary,
                () -> libraryPanel.refresh(true, null));
        settingsPanel = new SettingsPanel(stage, library, data, location.defaultDirectory(), this::chooseLibraryFolder,
                () -> libraryPanel.reloadSettings());
        meetingTab = new Tab("Лекция", meetingPanel());
        libraryTab = new Tab("Библиотека", libraryPanel);
        subjectsTab = new Tab("Предметы", subjectsPanel);
        settingsTab = new Tab("Настройки", settingsPanel);
        sections.getTabs().addAll(meetingTab, libraryTab, subjectsTab, settingsTab);
        sections.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sections.getSelectionModel().selectedItemProperty().addListener((obs, prior, current) -> {
            if (current == subjectsTab) subjectsPanel.refresh();
            if (current == settingsTab) settingsPanel.load();
            if (current == meetingTab) reloadSubjects();
        });
        stage.setTitle("Конспекты лекций");
        var root = new VBox(sections, taskBar);
        VBox.setVgrow(sections, Priority.ALWAYS);
        var scene = new Scene(root, 1080, 640);
        settingsPanel.theme().applyTo(scene.getStylesheets());
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(event -> {
            event.consume();
            closeWindow();
        });
        buttons();
    }

    void show() {
        stage.show();
        libraryPanel.initialize();
        subjectsPanel.initialize();
        settingsPanel.initialize();
        reloadSubjects();
    }

    /** Список предметов общий для всех вкладок: форма лекции предлагает уже созданные названия. */
    private void reloadSubjects() {
        if (closing) return;
        workers.execute(() -> {
            try {
                var names = library.subjects().stream().map(LectureLibrary.Subject::name).toList();
                Platform.runLater(() -> {
                    if (closed()) return;
                    String chosen = subjectValue();
                    subject.getItems().setAll(names);
                    subject.setValue(chosen.isBlank() ? null : chosen);
                });
            } catch (Exception ignored) {
                // Форма лекции остаётся с прежним списком, если библиотека недоступна.
            }
        });
    }

    private boolean closed() {
        return closeStarted;
    }

    private void showSubjectsTab() {
        sections.getSelectionModel().select(subjectsTab);
    }

    private void showSubjectInLibrary(long subjectId) {
        sections.getSelectionModel().select(libraryTab);
        libraryPanel.showSubject(subjectId);
    }

    private void openLectureInLibrary(LectureLibrary.Lecture item) {
        if (libraryPanel.openSubjectLecture(item)) sections.getSelectionModel().select(libraryTab);
    }

    private void openTaskResult(LectureLibrary.Lecture item, DocumentTaskBar.Operation operation) {
        sections.getSelectionModel().select(libraryTab);
        libraryPanel.openTaskResult(item, operation);
    }

    private void chooseLibraryFolder() {
        if (closing) return;
        var chooser = new DirectoryChooser();
        chooser.setTitle("Папка библиотеки");
        chooser.setInitialDirectory(data.toFile());
        var selected = chooser.showDialog(stage);
        if (selected == null) return;
        var directory = selected.toPath();
        workers.execute(() -> {
            try {
                location.save(directory);
                Platform.runLater(() -> notice("После перезапуска откроется библиотека в папке\n" + directory
                        + "\n\nТекущая библиотека хранится в папке\n" + data));
            } catch (IOException error) {
                Platform.runLater(() -> notice("Не удалось сохранить папку библиотеки."));
            }
        });
    }

    private VBox meetingPanel() {
        var form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        subject.setEditable(true);
        subject.setPromptText("Название предмета");
        subject.setAccessibleText("Предмет");
        DesktopDialogs.field(form, 0, "Предмет", subject);
        DesktopDialogs.field(form, 1, "Название", title);
        DesktopDialogs.field(form, 2, "Тема", topic);
        DesktopDialogs.field(form, 3, "Фокус конспекта", focus);
        DesktopDialogs.field(form, 4, "Ссылка на лекцию", invitation);
        form.setMaxWidth(690);
        invitation.setPromptText("https://…");
        open.setOnAction(event -> beginRecording(false));
        systemRecord.setOnAction(event -> beginRecording(true));
        save.setOnAction(event -> saveCard());
        reset.setOnAction(event -> clearDraft());
        start.setOnAction(event -> startCapture());
        stop.setOnAction(event -> {
            if (browser == null) finishRequested = true;
            stopped = true;
            recordStop();
        });
        leave.setOnAction(event -> {
            finishRequested = browser.process().isAlive();
            leaveMeeting();
        });
        level.setPrefWidth(270);
        var meter = new HBox(10, new Label("Уровень звука записи"), level);
        var cardActions = new HBox(8, systemRecord, open, save, reset);
        var captureActions = new HBox(8, start, stop, leave);
        for (var control : java.util.List.of(start, stop, leave)) {
            control.visibleProperty().bind(control.disableProperty().not());
            control.managedProperty().bind(control.visibleProperty());
        }
        start.getStyleClass().add("primary-action");
        stop.getStyleClass().add("primary-action");
        captureActions.visibleProperty().bind(start.visibleProperty().or(stop.visibleProperty()).or(leave.visibleProperty()));
        captureActions.managedProperty().bind(captureActions.visibleProperty());
        liveText.setEditable(false);
        liveText.setWrapText(true);
        liveText.setPrefRowCount(5);
        liveText.setMinHeight(65);
        var live = new VBox(6, new Label("Расшифровка во время записи"), liveStatus, liveText);
        VBox.setVgrow(liveText, Priority.ALWAYS);
        VBox.setVgrow(live, Priority.ALWAYS);
        var panel = new VBox(16, form, cardActions, captureActions, status, meter, live);
        panel.setPadding(new Insets(24));
        return panel;
    }

    private boolean reserved(String id) {
        return id.equals(activeId) || (busy && id.equals(draftId));
    }

    private void onLectureDeleted(String id) {
        taskBar.forget(id);
        if (id.equals(draftId)) clearDraft();
    }

    private void loadDraft(LectureLibrary.Lecture item) {
        if (busy || activeId != null || closing) {
            notice("Сначала завершите текущую встречу.");
            return;
        }
        draftId = item.id();
        subject.setValue(item.subject());
        title.setText(item.title());
        topic.setText(item.topic());
        focus.setText(item.focus());
        invitation.clear();
        status.setText("Открываем карточку");
        sections.getSelectionModel().select(meetingTab);
        busy = true;
        buttons();
        workers.execute(() -> {
            try {
                String link = InvitationSecret.unprotect(library.encryptedInvitation(item.id()));
                Platform.runLater(() -> {
                    invitation.setText(link);
                    finishFormAction("Карточка открыта", null);
                    if (!closing) invitation.requestFocus();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    clearDraft();
                    finishFormAction("Не удалось открыть карточку", "Не удалось прочитать сохранённую ссылку.");
                });
            }
        });
    }

    private void clearDraft() {
        draftId = null;
        subject.setValue(null);
        title.clear();
        topic.clear();
        focus.clear();
        invitation.clear();
        status.setText("Готово к подключению");
    }

    /** Поле предмета можно заполнить вручную или выбрать готовый предмет из списка. */
    private String subjectValue() {
        String chosen = subject.getValue();
        String typed = subject.getEditor().getText();
        if (typed != null && !typed.isBlank()) return typed.strip();
        return chosen == null ? "" : chosen.strip();
    }

    private Draft readDraft() {
        if (subjectValue().isBlank() || title.getText().isBlank()) {
            notice("Введите предмет и название лекции.");
            return null;
        }
        try {
            URI link = invitation.getText().isBlank() ? null : MeetingBrowser.invitation(invitation.getText());
            return new Draft(subjectValue(), title.getText(), topic.getText(), focus.getText(), link);
        } catch (IllegalArgumentException error) {
            notice("Введите ссылку на страницу (http:// или https://).");
            return null;
        }
    }

    private String persistDraft(String id, Draft draft) throws Exception {
        byte[] link = InvitationSecret.protect(draft.invitation());
        if (id == null) return library.createLecture(draft.subject(), draft.title(), draft.topic(), draft.focus(), link);
        library.editMeetingCard(id, draft.subject(), draft.title(), draft.topic(), draft.focus(), link);
        return id;
    }

    private void saveCard() {
        Draft draft = readDraft();
        if (draft == null) return;
        String current = draftId;
        busy = true;
        buttons();
        workers.execute(() -> {
            try {
                String id = persistDraft(current, draft);
                Platform.runLater(() -> {
                    draftId = id;
                    libraryPanel.refresh(true, id);
                    finishFormAction("Карточка сохранена", null);
                });
            } catch (Exception error) {
                Platform.runLater(() -> finishFormAction("Не удалось сохранить карточку",
                        error instanceof IOException ? error.getMessage() : null));
            }
        });
    }

    private void beginRecording(boolean systemAudio) {
        Draft draft = readDraft();
        if (draft == null) return;
        URI link = draft.invitation();
        if (!systemAudio && link == null) {
            notice("Введите ссылку на страницу (http:// или https://).");
            return;
        }
        String current = draftId;
        busy = true;
        stopped = false;
        finishing = false;
        finishRequested = false;
        status.setText(systemAudio ? "Готовим запись звука компьютера" : "Открываем отдельное окно Chrome");
        buttons();
        workers.execute(() -> {
            String saved = current;
            try {
                Capture.helper();
                String id = persistDraft(current, draft);
                saved = id;
                MeetingBrowser launched = systemAudio ? null : MeetingBrowser.open(data, link);
                Platform.runLater(() -> {
                    draftId = id;
                    activeId = id;
                    browser = launched;
                    busy = false;
                    libraryPanel.refresh(true, id);
                    if (closing) leaveMeeting();
                    else if (systemAudio) startCapture();
                    else {
                        status.setText("Когда будете готовы, нажмите «Начать запись»");
                        launched.process().onExit().thenRun(() -> Platform.runLater(() -> {
                            if (browser == launched && !busy && !finishing) endIfMeetingClosed();
                        }));
                    }
                    buttons();
                });
            } catch (Exception error) {
                String id = saved;
                Platform.runLater(() -> {
                    draftId = id;
                    if (id != null) libraryPanel.refresh(true, id);
                    finishFormAction(systemAudio ? "Не удалось начать запись" : "Не удалось открыть встречу",
                            error instanceof IOException ? error.getMessage() : null);
                });
            }
        });
    }

    private void finishFormAction(String message, String detail) {
        busy = false;
        status.setText(message);
        buttons();
        if (closing) completeClose();
        else if (detail != null) notice(detail);
    }

    private boolean endIfMeetingClosed() {
        if (!closing && (browser == null || browser.process().isAlive())) return false;
        if (!closing && !stopped) status.setText("Окно браузера закрылось");
        leaveMeeting();
        return true;
    }

    private void startCapture() {
        if (browser != null && !browser.process().isAlive()) {
            leaveMeeting();
            return;
        }
        busy = true;
        var session = browser;
        String id = activeId;
        Instant requestedAt = Instant.now();
        String recordingAction = session == null ? systemRecord.getText() : start.getText();
        status.setText("Запускаем захват звука");
        buttons();
        workers.execute(() -> {
            Path output = null;
            try {
                output = library.startRecording(id, requestedAt, recordingAction).directory();
                Process process = session == null ? Capture.startSystem(output) : Capture.start(session.process(), output);
                Path recordingDirectory = output;
                Platform.runLater(() -> {
                    capture = process;
                    signalReported = false;
                    busy = false;
                    buttons();
                    libraryPanel.refresh(false, null);
                    if (!endIfMeetingClosed()) startLiveTranscription(process, recordingDirectory);
                });
                Thread.startVirtualThread(() -> monitorCapture(id, process));
            } catch (Exception failure) {
                if (output != null) try { library.finishRecording(id, "EMPTY"); } catch (Exception ignored) { }
                Platform.runLater(() -> {
                    busy = false;
                    if (session == null) activeId = null;
                    status.setText("Захват не запустился");
                    libraryPanel.refresh(false, null);
                    if (!endIfMeetingClosed()) notice(failure instanceof IOException ? failure.getMessage()
                            : "Не удалось запустить модуль записи.");
                    buttons();
                });
            }
        });
    }

    private void startLiveTranscription(Process process, Path directory) {
        var whisper = libraryPanel.whisper();
        String lectureId = activeId;
        liveText.clear();
        liveStatus.setText("Готовим первый фрагмент");
        liveWorker = Thread.startVirtualThread(() -> {
            try {
                LiveTranscription.follow(new Recording(directory), whisper, segments -> {
                    try { library.appendLiveSegments(lectureId, segments); }
                    catch (java.sql.SQLException error) {
                        throw new IOException("Не удалось сохранить живые реплики.", error);
                    }
                    Platform.runLater(() -> {
                        if (!segments.isEmpty()) libraryPanel.liveDraftChanged(lectureId);
                        if (capture != process || liveWorker == null) return;
                        if (segments.isEmpty()) {
                            if (liveText.getText().isEmpty()) liveStatus.setText("Ожидаем речь других участников");
                            return;
                        }
                        liveStatus.setText("Черновик пополняется по мере записи");
                        var text = new StringBuilder();
                        for (var segment : segments) text.append(segment).append('\n');
                        liveText.appendText(text.toString());
                    });
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                Platform.runLater(() -> {
                    if (capture != process || liveWorker == null) return;
                    liveWorker = null;
                    liveStatus.setText("Живая расшифровка прервалась. Запись продолжается.");
                });
            }
        });
    }

    private void stopLiveTranscription() {
        if (liveWorker == null) return;
        liveWorker.interrupt();
        liveWorker = null;
        liveStatus.setText("Полная расшифровка доступна через «Распознать» в библиотеке");
    }

    private void monitorCapture(String id, Process process) {
        int exit = 1;
        long lastSignalAt = System.nanoTime();
        try (var events = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = events.readLine()) != null) {
                if (line.startsWith("{\"event\":\"started\"")) {
                    Platform.runLater(() -> { if (capture == process && !busy) status.setText("Идёт запись"); });
                }
                var match = LEVEL.matcher(line);
                if (match.matches()) {
                    int peak = Integer.parseInt(match.group(1));
                    long frame = Long.parseLong(match.group(2));
                    long millis = frame * 1000 / Recording.SAMPLE_RATE;
                    boolean signal = peak > Recording.SILENCE_PEAK;
                    long now = System.nanoTime();
                    if (signal) lastSignalAt = now;
                    boolean waitingForSound = now - lastSignalAt >= TimeUnit.SECONDS.toNanos(3);
                    Platform.runLater(() -> {
                        if (capture != process || busy) return;
                        level.setProgress(peak / 32768.0);
                        if (signal) signalReported = true;
                        String connection = waitingForSound ? " · нет входящего звука" : "";
                        status.setText("Идёт запись · " + RecordingPlayer.timestamp(millis) + connection);
                    });
                }
            }
            exit = process.waitFor();
        } catch (IOException | InterruptedException error) {
            process.destroy();
        }
        int result = exit;
        Platform.runLater(() -> captureEnded(id, process, result));
    }

    private void recordStop() {
        busy = true;
        status.setText("Сохраняем запись");
        stopLiveTranscription();
        Process process = capture;
        String id = activeId;
        Instant requestedAt = Instant.now();
        // Closing stdin also stops the helper if the JVM exits unexpectedly.
        try { process.getOutputStream().close(); }
        catch (IOException error) { process.destroy(); }
        if (stopped) workers.execute(() -> {
            try { library.markStopping(id, requestedAt); }
            catch (Exception error) { Platform.runLater(() -> notice("Не удалось сохранить отметку об остановке.")); }
        });
        Thread.startVirtualThread(() -> {
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        buttons();
    }

    private void captureEnded(String id, Process process, int exit) {
        if (capture != process) return;
        stopLiveTranscription();
        capture = null;
        level.setProgress(0);
        busy = true;
        boolean partial = (browser != null && stopped) || !finishRequested || exit != 0;
        boolean noSignalReported = !signalReported;
        stopped = true;
        buttons();
        workers.execute(() -> {
            String stored = null;
            boolean silent = false;
            try {
                Path path = library.recordingsPath(id);
                if (exit != 0) Capture.recover(path);
                var chunks = new Recording(path).chunks();
                String state = chunks.isEmpty() ? "EMPTY" : partial ? "PARTIAL" : "COMPLETE";
                library.finishRecording(id, state);
                stored = state;
                if (noSignalReported && !chunks.isEmpty()) {
                    try {
                        boolean hasSignal = false;
                        for (var chunk : chunks) {
                            if (!Recording.isDigitalSilence(chunk.path())) {
                                hasSignal = true;
                                break;
                            }
                        }
                        silent = !hasSignal;
                    } catch (IOException ignored) {
                        // Audio is saved. A failed signal check should not change its state.
                    }
                }
            } catch (Exception error) { Platform.runLater(() -> notice("Не удалось завершить сохранение записи.")); }
            String state = stored;
            boolean noSignal = silent;
            Platform.runLater(() -> {
                busy = false;
                status.setText(state == null ? "Запись требует восстановления" : exit != 0 ? "Захват прервался" :
                        state.equals("EMPTY") ? "Аудио не получено" : noSignal ?
                                "Запись сохранена · входящего звука не было" : "Запись сохранена в библиотеке");
                if (finishing || browser == null) leaveMeeting();
                else endIfMeetingClosed();
                libraryPanel.refresh(false, null);
                buttons();
            });
        });
    }

    private void leaveMeeting() {
        finishing = true;
        if (capture != null) {
            recordStop();
            return;
        }
        MeetingBrowser session = browser;
        browser = null;
        busy = true;
        buttons();
        workers.execute(() -> {
            try { if (session != null) session.close(); }
            catch (Exception error) {
                Platform.runLater(() -> notice("Окно Chrome или временный профиль не удалось полностью закрыть."));
            } finally {
                Platform.runLater(() -> {
                    busy = false;
                    activeId = null;
                    finishing = false;
                    if (stopped) {
                        draftId = null;
                        invitation.clear();
                    }
                    if (!stopped && finishRequested) {
                        status.setText("Встреча завершена");
                    }
                    if (closing) completeClose();
                    buttons();
                });
            }
        });
    }

    private void buttons() {
        boolean idle = !busy && !closing;
        boolean editing = idle && activeId == null;
        systemRecord.setDisable(!editing);
        open.setDisable(!editing);
        save.setDisable(!editing);
        reset.setDisable(!editing);
        start.setDisable(!idle || browser == null || stopped || capture != null);
        stop.setDisable(!idle || capture == null);
        leave.setDisable(!idle || browser == null);
        subject.setDisable(!editing);
        title.setDisable(!editing);
        topic.setDisable(!editing);
        focus.setDisable(!editing);
        invitation.setDisable(!editing);
        libraryPanel.updateButtons();
    }

    private void closeWindow() {
        if (closing || !libraryPanel.canClose()) return;
        closing = true;
        buttons();
        if (busy) return;
        if (activeId != null) leaveMeeting();
        else completeClose();
    }

    private void completeClose() {
        if (closeStarted) return;
        closeStarted = true;
        libraryPanel.close();
        subjectsPanel.close();
        settingsPanel.close();
        workers.execute(() -> {
            try { libraryPanel.awaitClose(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { try { library.close(); } catch (Exception ignored) { } }
            Platform.runLater(stage::hide);
        });
        workers.shutdown();
    }

    private void notice(String message) {
        if (!closing) DesktopDialogs.notice(stage, message);
    }
}
