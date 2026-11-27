package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Вкладка «Настройки»: профили моделей, движок Whisper, системный промпт и лимит конспекта,
 * папка библиотеки и сведения о сборке. Настройки хранятся в SQLite библиотеки.
 */
final class SettingsPanel extends VBox {
    private static final List<String> WHISPER_LANGUAGES = List.of("ru", "en", "auto");

    private final Window owner;
    private final LectureLibrary library;
    private final Path data;
    private final Runnable chooseLibraryFolder;
    private final Runnable settingsSaved;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final ListView<LectureLibrary.Profile> profiles = new ListView<>();
    private final Label profileStatus = status();
    private final Button addProfile = new Button("Создать профиль");
    private final Button editProfile = new Button("Изменить");
    private final Button removeProfile = new Button("Удалить");
    private final Button primaryProfile = new Button("Сделать основным");
    private final Button testProfile = new Button("Проверить соединение");

    private final TextField whisperExecutable = new TextField();
    private final TextField whisperModel = new TextField();
    private final CheckBox whisperCpu = new CheckBox("Только CPU");
    private final ComboBox<String> whisperLanguage = new ComboBox<>();
    private final Label whisperStatus = status();
    private final Button pickExecutable = new Button("Файл whisper-cli.exe…");
    private final Button pickModel = new Button("Файл модели…");
    private final Button saveWhisper = new Button("Сохранить движок");
    private final Button resetWhisper = new Button("Встроенные файлы");

    private final TextArea notePrompt = new TextArea();
    private final TextField noteLimit = new TextField();
    private final Label noteStatus = status();
    private final Button saveNoteSettings = new Button("Сохранить промпт и лимит");
    private final Button restorePrompt = new Button("Промпт по умолчанию");

    private final Label libraryStatus = status();
    private final Label aboutStatus = status();

    private boolean writing;
    private boolean closed;

    SettingsPanel(Window owner, LectureLibrary library, Path data, Runnable chooseLibraryFolder,
                  Runnable settingsSaved) {
        super(12);
        this.owner = owner;
        this.library = library;
        this.data = data;
        this.chooseLibraryFolder = chooseLibraryFolder;
        this.settingsSaved = settingsSaved;
        setPadding(new Insets(14));

        profiles.setId("profile-list");
        profiles.setPlaceholder(new Label("Профилей моделей пока нет"));
        profiles.setMinHeight(150);
        profiles.setCellFactory(source -> new ListCell<>() {
            { setWrapText(true); }

            @Override protected void updateItem(LectureLibrary.Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : profileCard(item));
            }
        });
        profiles.getSelectionModel().selectedItemProperty().addListener((obs, prior, value) -> updateButtons());
        addProfile.setOnAction(event -> editProfile(null));
        editProfile.setOnAction(event -> editProfile(profiles.getSelectionModel().getSelectedItem()));
        removeProfile.setOnAction(event -> removeProfile());
        primaryProfile.setOnAction(event -> {
            var item = profiles.getSelectionModel().getSelectedItem();
            if (item != null) mutate(() -> library.setDefaultProfile(item.id()), this::load);
        });
        testProfile.setOnAction(event -> testConnection());

        whisperExecutable.setId("whisper-executable");
        whisperExecutable.setPromptText("…/whisper/whisper-cli.exe");
        whisperModel.setId("whisper-model");
        whisperModel.setPromptText("…/whisper/ggml-large-v3-turbo-q5_0.bin");
        whisperExecutable.setAccessibleText("Путь к whisper-cli.exe");
        whisperModel.setAccessibleText("Путь к файлу модели Whisper");
        whisperCpu.setId("whisper-cpu");
        whisperLanguage.setId("whisper-language");
        whisperLanguage.getItems().setAll(WHISPER_LANGUAGES);
        whisperLanguage.setEditable(true);
        whisperLanguage.setTooltip(new Tooltip("Код языка распознавания или auto"));
        pickExecutable.setOnAction(event -> chooseFile(whisperExecutable, "whisper-cli.exe", "*.exe"));
        pickModel.setOnAction(event -> chooseFile(whisperModel, "Файл модели Whisper", "*.bin", "*.gguf", "*.*"));
        saveWhisper.setOnAction(event -> saveWhisper());
        resetWhisper.setOnAction(event -> useBundledWhisper());

        notePrompt.setId("note-prompt");
        notePrompt.setWrapText(true);
        notePrompt.setPrefRowCount(8);
        notePrompt.setAccessibleText("Системный промпт для конспекта");
        noteLimit.setId("note-limit");
        noteLimit.setPrefColumnCount(6);
        restorePrompt.setOnAction(event -> notePrompt.setText(LocalProcessing.DEFAULT_SYSTEM_PROMPT));
        saveNoteSettings.setOnAction(event -> saveNoteSettings());

        var chooseFolder = new Button("Выбрать папку библиотеки");
        chooseFolder.setOnAction(event -> chooseLibraryFolder.run());

        var whisperForm = DesktopDialogs.fields();
        DesktopDialogs.field(whisperForm, 0, "whisper-cli.exe", whisperExecutable);
        DesktopDialogs.field(whisperForm, 1, "Файл модели", whisperModel);
        DesktopDialogs.field(whisperForm, 2, "Язык распознавания", whisperLanguage);
        whisperForm.add(pickExecutable, 2, 0);
        whisperForm.add(pickModel, 2, 1);
        whisperForm.add(whisperCpu, 2, 2);
        whisperForm.add(new HBox(8, saveWhisper, resetWhisper), 1, 3);

        var body = new VBox(12,
                card("Модели и профили генерации",
                        profiles,
                        new HBox(8, addProfile, editProfile, removeProfile, primaryProfile, testProfile),
                        profileStatus),
                card("Распознавание речи (Whisper)", whisperForm, whisperStatus),
                card("Конспект", notePrompt,
                        new HBox(8, new Label("Лимит времени, мин"), noteLimit, saveNoteSettings, restorePrompt),
                        noteStatus),
                card("Библиотека", libraryStatus, chooseFolder),
                card("О сборке", aboutStatus));

        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPannable(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
        updateButtons();
    }

    void initialize() {
        load();
    }

    void close() {
        closed = true;
        worker.shutdown();
    }

    /** Перечитывает сохранённые настройки после изменений в библиотеке. */
    void load() {
        if (closed) return;
        worker.execute(() -> {
            try {
                var savedProfiles = library.profiles();
                var whisper = library.whisperSettings();
                String language = library.whisperLanguage();
                String prompt = library.noteSystemPrompt();
                int minutes = library.noteTimeLimitMinutes();
                var stats = library.stats();
                Platform.runLater(() -> {
                    if (closed) return;
                    var prior = profiles.getSelectionModel().getSelectedItem();
                    profiles.getItems().setAll(savedProfiles);
                    if (prior != null) {
                        profiles.getItems().stream().filter(item -> item.id() == prior.id())
                                .findFirst().ifPresent(profiles.getSelectionModel()::select);
                    } else if (!savedProfiles.isEmpty()) profiles.getSelectionModel().selectFirst();
                    if (!writing) {
                        whisperExecutable.setText(whisper.executable().toString());
                        whisperModel.setText(whisper.model().toString());
                        whisperCpu.setSelected(whisper.cpu());
                        whisperLanguage.setValue(language);
                        notePrompt.setText(prompt);
                        noteLimit.setText(Integer.toString(minutes));
                    }
                    say(whisperStatus, whisper.available()
                            ? "Файлы движка на месте: " + whisper.model().getFileName()
                            : "Файлы движка или модели не найдены — распознавание недоступно.");
                    libraryStatus.setText("Папка библиотеки: " + data + "\n"
                            + stats.subjects() + " предметов · " + stats.lectures() + " лекций · "
                            + stats.transcripts() + " с расшифровкой · " + stats.notes() + " с конспектом"
                            + (stats.pendingCleanup() > 0
                                    ? "\nВ очереди очистки файлов: " + stats.pendingCleanup() : ""));
                    aboutStatus.setText("JDK " + Runtime.version().feature() + " · JavaFX " + javafxVersion()
                            + " · библиотека SQLite, схема v6"
                            + "\nWhisper: " + whisper.executable().getFileName() + " · "
                            + whisper.model().getFileName() + (whisper.cpu() ? " · CPU" : " · CUDA")
                            + "\nПомощник записи: " + helperPath());
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed) say(profileStatus, "Не удалось загрузить настройки.");
                });
            }
        });
    }

    private static String javafxVersion() {
        var module = Platform.class.getModule().getDescriptor();
        return module == null ? "из classpath" : module.rawVersion().orElse("неизвестной версии");
    }

    private static String helperPath() {
        try {
            return Capture.helper().toString();
        } catch (IOException error) {
            return "не найден";
        }
    }

    private static String profileCard(LectureLibrary.Profile item) {
        return item.name() + (item.isDefault() ? " · основной" : item.cloud() ? " · внешний" : " · локальный")
                + "\n" + item.baseUrl() + " · " + item.modelId()
                + "\nконтекст " + item.contextTokens() + " токенов · ответ "
                + (item.answerTokens() > 0 ? "до " + item.answerTokens() : "без лимита")
                + " · тайм-аут " + item.timeoutSeconds() + " с · "
                + (item.hasSecret() ? "ключ сохранён" : "без ключа");
    }

    private void editProfile(LectureLibrary.Profile item) {
        var name = new TextField(item == null ? "" : item.name());
        var kind = new ComboBox<String>();
        kind.getItems().setAll("Локальный (http://127.0.0.1)", "Внешний (https://…)");
        kind.getSelectionModel().select(item != null && item.cloud() ? 1 : 0);
        var url = new TextField(item == null ? "http://127.0.0.1:47831/v1" : item.baseUrl());
        var model = new TextField(item == null ? "" : item.modelId());
        var context = new TextField(item == null ? "8192" : Integer.toString(item.contextTokens()));
        var answer = new TextField(item == null ? "0" : Integer.toString(item.answerTokens()));
        var timeout = new TextField(item == null ? "900" : Integer.toString(item.timeoutSeconds()));
        var secret = new PasswordField();
        boolean hasSecret = item != null && item.hasSecret();
        secret.setPromptText(hasSecret ? "Ключ сохранён — пустое поле оставляет его" : "Ключ API, если он нужен");
        var clearSecret = new CheckBox("Удалить сохранённый ключ");
        clearSecret.setDisable(!hasSecret);
        var asDefault = new CheckBox("Использовать для генерации по умолчанию");
        asDefault.setSelected(item == null || item.isDefault());

        var form = DesktopDialogs.fields();
        DesktopDialogs.field(form, 0, "Название профиля", name);
        DesktopDialogs.field(form, 1, "Тип профиля", kind);
        DesktopDialogs.field(form, 2, "Базовый URL", url);
        DesktopDialogs.field(form, 3, "ID модели", model);
        DesktopDialogs.field(form, 4, "Ключ API", secret);
        form.add(clearSecret, 1, 5);
        DesktopDialogs.field(form, 6, "Бюджет контекста, токенов", context);
        DesktopDialogs.field(form, 7, "Лимит ответа, токенов (0 — без лимита)", answer);
        DesktopDialogs.field(form, 8, "Тайм-аут запроса, секунд", timeout);
        form.add(asDefault, 1, 9);
        if (!DesktopDialogs.form(owner, item == null ? "Новый профиль модели" : "Изменить профиль модели", form)) return;

        Long id = item == null ? null : item.id();
        boolean cloud = kind.getSelectionModel().getSelectedIndex() == 1;
        String title = name.getText();
        String base = url.getText();
        String modelId = model.getText();
        String typed = secret.getText().strip();
        if (!typed.isEmpty() && !typed.chars().allMatch(symbol -> symbol >= 0x21 && symbol <= 0x7e)) {
            say(profileStatus, "Ключ API должен быть ASCII и не содержать пробелов.");
            return;
        }
        boolean erase = clearSecret.isSelected();
        boolean makeDefault = asDefault.isSelected();
        int contextTokens = number(context.getText(), 8192);
        int answerTokens = number(answer.getText(), 0);
        int timeoutSeconds = number(timeout.getText(), 900);
        mutate(() -> {
            byte[] saved;
            if (!typed.isBlank()) saved = InvitationSecret.protectText(typed);
            else if (erase) saved = null;
            else saved = id == null ? null : library.profileSecret(id);
            library.saveProfile(id, title, base, modelId, cloud, contextTokens, answerTokens, timeoutSeconds,
                    saved, makeDefault);
        }, this::load);
    }

    private void removeProfile() {
        var item = profiles.getSelectionModel().getSelectedItem();
        if (item == null) return;
        if (!DesktopDialogs.confirm(owner, "Удаление профиля", "Удалить профиль «" + item.name() + "»"
                + (item.isDefault() ? ". Он основной: следующий профиль станет основным." : "?"))) return;
        mutate(() -> library.deleteProfile(item.id()), this::load);
    }

    private void testConnection() {
        var item = profiles.getSelectionModel().getSelectedItem();
        if (item == null) return;
        writing = true;
        updateButtons();
        say(profileStatus, "Проверяем соединение с «" + item.name() + "»…");
        worker.execute(() -> {
            try {
                String secret = item.hasSecret() ? InvitationSecret.unprotectText(library.profileSecret(item.id())) : "";
                String reply = LocalProcessing.probe(item.llm(secret));
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    say(profileStatus, "Профиль «" + item.name() + "» отвечает. " + reply);
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    say(profileStatus, error instanceof IOException && error.getMessage() != null
                            ? "Проверка не прошла: " + error.getMessage()
                            : "Проверка не прошла: сервис недоступен или адрес неверный.");
                    updateButtons();
                });
            }
        });
    }

    private void chooseFile(TextField target, String title, String... patterns) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(title, List.of(patterns)));
        var start = path(target.getText());
        if (start != null && Files.isDirectory(start.getParent())) chooser.setInitialDirectory(start.getParent().toFile());
        var selected = chooser.showOpenDialog(owner);
        if (selected != null) target.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    private void saveWhisper() {
        Path executable = path(whisperExecutable.getText());
        Path model = path(whisperModel.getText());
        String language = whisperLanguage.getValue() == null ? "" : whisperLanguage.getValue().strip();
        boolean cpu = whisperCpu.isSelected();
        mutate(() -> library.saveWhisperSettings(executable, model, cpu, language), () -> {
            load();
            settingsSaved.run();
        });
    }

    private void useBundledWhisper() {
        var bundled = LocalProcessing.bundledWhisper(false);
        if (!bundled.available()) bundled = LocalProcessing.bundledWhisper(true);
        whisperExecutable.setText(bundled.executable().toString());
        whisperModel.setText(bundled.model().toString());
        whisperCpu.setSelected(bundled.cpu());
        say(whisperStatus, "Встроенные файлы подставлены — нажмите «Сохранить движок».");
    }

    private void saveNoteSettings() {
        String prompt = notePrompt.getText();
        int minutes = number(noteLimit.getText(), LectureLibrary.DEFAULT_NOTE_TIME_LIMIT_MINUTES);
        mutate(() -> library.saveNoteSettings(prompt, minutes), () -> {
            say(noteStatus, "Промпт и лимит сохранены.");
            settingsSaved.run();
        });
    }

    private static Path path(String value) {
        try {
            return Path.of(value.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            return null;
        }
    }

    private static int number(String value, int fallback) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException error) {
            return fallback;
        }
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
                    updateButtons();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (closed) return;
                    writing = false;
                    updateButtons();
                    say(profileStatus, error instanceof IOException && error.getMessage() != null
                            ? error.getMessage() : "Не удалось сохранить настройки.");
                });
            }
        });
    }

    private void updateButtons() {
        boolean ready = !writing && !closed;
        boolean selected = profiles.getSelectionModel().getSelectedItem() != null;
        addProfile.setDisable(!ready);
        editProfile.setDisable(!ready || !selected);
        removeProfile.setDisable(!ready || !selected);
        primaryProfile.setDisable(!ready || !selected);
        testProfile.setDisable(!ready || !selected);
        saveWhisper.setDisable(!ready);
        resetWhisper.setDisable(!ready);
        saveNoteSettings.setDisable(!ready);
        restorePrompt.setDisable(!ready);
    }

    private static Label status() {
        var label = new Label();
        label.getStyleClass().add("note-status");
        label.setWrapText(true);
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    private static void say(Label label, String message) {
        label.setText(message);
        label.setVisible(!message.isBlank());
        label.setManaged(!message.isBlank());
    }

    /** Карточка настроек с заголовком. */
    private static VBox card(String title, Node... content) {
        var caption = new Label(title);
        caption.getStyleClass().add("settings-title");
        var box = new VBox(8, caption);
        box.getStyleClass().add("settings-card");
        box.getChildren().addAll(content);
        return box;
    }
}
