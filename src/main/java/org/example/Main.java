package org.example;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check")) {
            System.out.println("JDK " + Runtime.version().feature() + ", audio helper: " + Capture.helper());
            var whisper = LocalProcessing.bundledWhisper(false);
            if (!java.nio.file.Files.isRegularFile(whisper.executable())
                    || !java.nio.file.Files.isRegularFile(LocalProcessing.bundledWhisper(true).executable())) {
                throw new java.io.IOException("Bundled Whisper CPU/CUDA CLI is missing.");
            }
            System.out.println("Whisper CPU/CUDA CLI available.");
            LectureLibrary.checkDriver();
            var javafx = Platform.class.getModule().getDescriptor();
            System.out.println("SQLite FTS5 and RussianAnalyzer started. JavaFX: "
                    + (javafx == null ? "classpath" : javafx.toNameAndVersion()));
            try (var playwright = MeetingBrowser.startDriver()) {
                System.out.println("Playwright driver started: " + playwright.chromium().name());
            }
            return;
        }
        var location = LibraryLocation.forCurrentUser();
        Path directory;
        LectureLibrary library;
        try {
            directory = location.dataDirectory(System.getProperty("lecturenotes.data"));
            library = new LectureLibrary(directory);
        } catch (Exception error) {
            String message = error instanceof java.io.IOException ? error.getMessage() : "Не удалось открыть библиотеку.";
            Platform.startup(() -> Platform.runLater(() -> showError(message, location)));
            return;
        }
        try {
            Platform.startup(() -> Platform.runLater(() -> {
                try {
                    new DesktopWindow(directory, library, location).show();
                } catch (RuntimeException error) {
                    try { library.close(); } catch (Exception failure) { error.addSuppressed(failure); }
                    showError("Не удалось открыть окно приложения.", location);
                }
            }));
        } catch (RuntimeException | Error error) {
            try { library.close(); } catch (Exception failure) { error.addSuppressed(failure); }
            throw error;
        }
    }

    private static void showError(String message, LibraryLocation location) {
        var alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle("Конспекты лекций");
        alert.setHeaderText(null);
        AppTheme.load(location.defaultDirectory()).applyTo(alert.getDialogPane().getStylesheets());
        alert.showAndWait();
        Platform.exit();
    }
}
