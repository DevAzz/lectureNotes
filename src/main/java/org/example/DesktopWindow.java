package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.SaluteJazzPage.State;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class DesktopWindow {
    private static final Pattern LEVEL = Pattern.compile("\\{\"event\":\"level\",\"peak\":(\\d+),\"frames\":(\\d+)\\}");

    private record Draft(String subject, String title, String topic, String focus) { }

    private final Stage stage = new Stage();
    private final Path data;
    private final LectureLibrary library;
    private final ScheduledExecutorService workers = Executors.newSingleThreadScheduledExecutor();
    private final TextField subject = new TextField();
    private final TextField title = new TextField();
    private final TextField topic = new TextField();
    private final TextField focus = new TextField();
    private final TextField invitation = new TextField();
    private final CheckBox permission = new CheckBox("Разрешение на запись получено");
    private final Button open = new Button("Подключиться");
    private final Button save = new Button("Сохранить карточку");
    private final Button reset = new Button("Новая карточка");
    private final Button start = new Button("Я на встрече · начать запись");
    private final Button stop = new Button("Остановить запись");
    private final Button leave = new Button("Завершить встречу");
    private final Label status = new Label("Готово к подключению");
    private final ProgressBar level = new ProgressBar(0);
    private final TabPane sections = new TabPane();
    private final Tab meetingTab;
    private final LibraryPanel libraryPanel;
    private MeetingBrowser browser;
    private ScheduledFuture<?> browserWatch;
    private State browserState = State.WAITING_USER;
    private Process capture;
    private String draftId;
    private String activeId;
    private boolean busy;
    private boolean stopped;
    private boolean captureStartFailed;
    private boolean finishing;
    private boolean finishRequested;
    private boolean closing;
    private boolean closeStarted;

    DesktopWindow(Path data, LectureLibrary library) {
        this.data = data;
        this.library = library;
        libraryPanel = new LibraryPanel(stage, library, this::loadDraft, this::reserved, this::onLectureDeleted);
        meetingTab = new Tab("Лекция", meetingPanel());
        sections.getTabs().addAll(meetingTab, new Tab("Библиотека", libraryPanel));
        sections.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        stage.setTitle("Конспекты лекций");
        stage.setScene(new Scene(sections, 1080, 640));
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
    }

    private VBox meetingPanel() {
        var form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        DesktopDialogs.field(form, 0, "Предмет", subject);
        DesktopDialogs.field(form, 1, "Название", title);
        DesktopDialogs.field(form, 2, "Тема", topic);
        DesktopDialogs.field(form, 3, "Фокус конспекта", focus);
        DesktopDialogs.field(form, 4, "Ссылка SaluteJazz", invitation);
        form.add(permission, 1, 5);
        form.setMaxWidth(690);
        invitation.setPromptText("https://");
        open.setOnAction(event -> openMeeting());
        save.setOnAction(event -> saveCard());
        reset.setOnAction(event -> clearDraft());
        start.setOnAction(event -> startCapture(true));
        permission.selectedProperty().addListener((value, old, approved) -> {
            if (!tryStartAutomatically()) buttons();
        });
        stop.setOnAction(event -> {
            stopped = true;
            recordStop();
        });
        leave.setOnAction(event -> {
            finishRequested = browser.process().isAlive();
            leaveMeeting();
        });
        level.setPrefWidth(270);
        var meter = new HBox(10, new Label("Уровень звука"), level);
        var cardActions = new HBox(8, open, save, reset);
        var captureActions = new HBox(8, start, stop, leave);
        var panel = new VBox(16, form, cardActions, captureActions, status, meter);
        panel.setPadding(new Insets(24));
        return panel;
    }

    private boolean reserved(String id) {
        return id.equals(activeId) || (busy && id.equals(draftId));
    }

    private void onLectureDeleted(String id) {
        if (id.equals(draftId)) clearDraft();
    }

    private void loadDraft(LectureLibrary.Lecture item) {
        if (busy || browser != null || closing) {
            notice("Сначала завершите текущую встречу.");
            return;
        }
        draftId = item.id();
        subject.setText(item.subject());
        title.setText(item.title());
        topic.setText(item.topic());
        focus.setText(item.focus());
        invitation.clear();
        permission.setSelected(false);
        status.setText("Карточка открыта");
        sections.getSelectionModel().select(meetingTab);
        invitation.requestFocus();
    }

    private void clearDraft() {
        draftId = null;
        subject.clear();
        title.clear();
        topic.clear();
        focus.clear();
        invitation.clear();
        permission.setSelected(false);
        status.setText("Готово к подключению");
    }

    private Draft readDraft() {
        if (subject.getText().isBlank() || title.getText().isBlank()) {
            notice("Введите предмет и название лекции.");
            return null;
        }
        return new Draft(subject.getText(), title.getText(), topic.getText(), focus.getText());
    }

    private String persistDraft(String id, Draft draft) throws Exception {
        if (id == null) return library.createLecture(draft.subject(), draft.title(), draft.topic(), draft.focus());
        library.editLecture(id, draft.subject(), draft.title(), draft.topic(), draft.focus());
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
                Platform.runLater(() -> finishFormAction("Не удалось сохранить карточку", null));
            }
        });
    }

    private void openMeeting() {
        Draft draft = readDraft();
        if (draft == null) return;
        final URI link;
        try { link = MeetingBrowser.invitation(invitation.getText()); }
        catch (IllegalArgumentException error) {
            notice("Введите HTTPS-ссылку на встречу в SaluteJazz.");
            return;
        }
        String current = draftId;
        busy = true;
        stopped = false;
        captureStartFailed = false;
        finishing = false;
        finishRequested = false;
        status.setText("Открываем отдельное окно Chrome");
        buttons();
        workers.execute(() -> {
            String saved = current;
            try {
                Capture.helper();
                String id = persistDraft(current, draft);
                saved = id;
                MeetingBrowser launched = MeetingBrowser.open(data, link);
                Platform.runLater(() -> {
                    draftId = id;
                    activeId = id;
                    browser = launched;
                    browserState = State.AUTHENTICATING;
                    busy = false;
                    invitation.clear();
                    status.setText("Войдите в браузере и дождитесь допуска к встрече");
                    libraryPanel.refresh(true, id);
                    if (closing) leaveMeeting();
                    else watchBrowser(launched);
                    buttons();
                });
            } catch (Exception error) {
                String id = saved;
                Platform.runLater(() -> {
                    draftId = id;
                    if (id != null) libraryPanel.refresh(true, id);
                    finishFormAction("Не удалось открыть встречу", error instanceof IOException ? error.getMessage() : null);
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

    private void watchBrowser(MeetingBrowser session) {
        browserWatch = workers.scheduleWithFixedDelay(new Runnable() {
            private State previous;

            @Override public void run() {
                State state = session.observe();
                boolean confirmed = state != State.IN_CALL || previous == State.IN_CALL;
                previous = state;
                if (confirmed) Platform.runLater(() -> {
                    if (browserState != state) onBrowserState(session, state);
                });
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void onBrowserState(MeetingBrowser session, State state) {
        if (browser != session || finishing) return;
        browserState = state;
        if (busy) return;
        if (endIfMeetingClosed()) return;
        if (capture == null && !stopped) showBrowserState();
        if (!tryStartAutomatically()) buttons();
    }

    private void showBrowserState() {
        status.setText(switch (browserState) {
            case WAITING_USER -> "Продолжите в Chrome. Если вы уже на встрече, начните запись вручную";
            case AUTHENTICATING -> "Продолжите в Chrome. Если появится проверка входа, пройдите её вручную";
            case PREJOIN -> "Подключитесь к встрече в Chrome";
            case WAITING_ADMISSION -> "Ожидаем допуска организатора";
            case IN_CALL -> "Допуск подтверждён. Подтвердите разрешение на запись";
            case RECONNECTING -> "SaluteJazz восстанавливает соединение";
            case DENIED -> "Организатор отклонил подключение";
            case ENDED -> "Встреча завершена в SaluteJazz";
            case CLOSED -> "Окно встречи закрылось";
        });
    }

    private boolean endIfMeetingClosed() {
        if (!closing && browserState != State.ENDED && !browser.process().isAlive()) browserState = State.CLOSED;
        if (!closing && browserState != State.ENDED && browserState != State.CLOSED) return false;
        if (!stopped && capture == null && !closing) showBrowserState();
        if (browserState == State.ENDED) finishRequested = true;
        leaveMeeting();
        return true;
    }

    private boolean tryStartAutomatically() {
        if (busy || closing || finishing || browser == null || stopped || captureStartFailed || capture != null
                || browserState != State.IN_CALL || !permission.isSelected()) return false;
        startCapture(false);
        return true;
    }

    private void startCapture(boolean manual) {
        if (!permission.isSelected()) {
            notice("Подтвердите, что получили разрешение на запись.");
            return;
        }
        if (!browser.process().isAlive()) {
            leaveMeeting();
            return;
        }
        busy = true;
        var session = browser;
        String id = activeId;
        status.setText("Запускаем захват звука");
        buttons();
        workers.execute(() -> {
            Path output = null;
            try {
                State current = session.observe();
                if ((manual && !current.canStartManually()) || (!manual && current != State.IN_CALL)) {
                    Platform.runLater(() -> {
                        busy = false;
                        onBrowserState(session, current);
                        if (closing && browser == session && !finishing) leaveMeeting();
                    });
                    return;
                }
                output = library.startRecording(id).directory();
                Process process = Capture.start(session.process(), output);
                Platform.runLater(() -> {
                    capture = process;
                    busy = false;
                    buttons();
                    libraryPanel.refresh(false, null);
                    endIfMeetingClosed();
                });
                Thread.startVirtualThread(() -> monitorCapture(id, process));
            } catch (Exception failure) {
                if (output != null) try { library.finishRecording(id, "EMPTY"); } catch (Exception ignored) { }
                Platform.runLater(() -> {
                    busy = false;
                    captureStartFailed = true;
                    status.setText("Захват не запустился");
                    libraryPanel.refresh(false, null);
                    if (!endIfMeetingClosed()) notice("Не удалось запустить модуль записи.");
                    buttons();
                });
            }
        });
    }

    private void monitorCapture(String id, Process process) {
        int exit = 1;
        try (var events = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = events.readLine()) != null) {
                if (line.startsWith("{\"event\":\"started\"")) {
                    Platform.runLater(() -> { if (capture == process && !busy) status.setText("Идёт запись"); });
                }
                var match = LEVEL.matcher(line);
                if (match.matches()) {
                    int peak = Integer.parseInt(match.group(1));
                    long millis = Long.parseLong(match.group(2)) * 1000 / Recording.SAMPLE_RATE;
                    Platform.runLater(() -> {
                        if (capture != process || busy) return;
                        level.setProgress(peak / 32768.0);
                        String connection = browserState == State.RECONNECTING ? " · восстанавливаем соединение" : "";
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
        capture = null;
        level.setProgress(0);
        busy = true;
        boolean partial = stopped || !finishRequested || exit != 0;
        stopped = true;
        buttons();
        workers.execute(() -> {
            String stored = null;
            try {
                Path path = library.recordingsPath(id);
                if (exit != 0) Capture.recover(path);
                String state = new Recording(path).chunks().isEmpty() ? "EMPTY" : partial ? "PARTIAL" : "COMPLETE";
                library.finishRecording(id, state);
                stored = state;
            } catch (Exception error) { Platform.runLater(() -> notice("Не удалось завершить сохранение записи.")); }
            String state = stored;
            Platform.runLater(() -> {
                busy = false;
                status.setText(state == null ? "Запись требует восстановления" : exit != 0 ? "Захват прервался" :
                        state.equals("EMPTY") ? "Аудио не получено" : "Запись сохранена в библиотеке");
                if (finishing) leaveMeeting();
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
        if (browserWatch != null) {
            browserWatch.cancel(false);
            browserWatch = null;
        }
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
                    permission.setSelected(false);
                    if (stopped) draftId = null;
                    if (!stopped && browserState != State.CLOSED && browserState != State.ENDED) {
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
        boolean editing = idle && browser == null;
        open.setDisable(!editing);
        save.setDisable(!editing);
        reset.setDisable(!editing);
        start.setDisable(!idle || browser == null || stopped || capture != null || !browserState.canStartManually());
        stop.setDisable(!idle || capture == null);
        leave.setDisable(!idle || browser == null);
        subject.setDisable(!editing);
        title.setDisable(!editing);
        topic.setDisable(!editing);
        focus.setDisable(!editing);
        invitation.setDisable(!editing);
        permission.setDisable(!idle || capture != null);
        libraryPanel.updateButtons();
    }

    private void closeWindow() {
        if (closing || !libraryPanel.canClose()) return;
        closing = true;
        buttons();
        if (busy) return;
        if (browser != null) leaveMeeting();
        else completeClose();
    }

    private void completeClose() {
        if (closeStarted) return;
        closeStarted = true;
        libraryPanel.close();
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
