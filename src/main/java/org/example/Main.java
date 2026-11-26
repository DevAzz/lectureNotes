package org.example;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(System.getProperty("lecturenotes.data",
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
                        "LectureNotes").toString()));
        if (args.length == 1 && args[0].equals("--check")) {
            System.out.println("JDK " + Runtime.version().feature() + ", audio helper: " + Capture.helper());
            LectureLibrary.checkDriver();
            var javafx = Platform.class.getModule().getDescriptor();
            System.out.println("SQLite FTS5 and RussianAnalyzer started. JavaFX: "
                    + (javafx == null ? "classpath" : javafx.toNameAndVersion()));
            return;
        }
        Path directory = data.toAbsolutePath().normalize();
        LectureLibrary library;
        try {
            library = new LectureLibrary(directory);
        } catch (Exception error) {
            String message = error instanceof java.io.IOException ? error.getMessage() : "Не удалось открыть библиотеку.";
            Platform.startup(() -> Platform.runLater(() -> showError(message)));
            return;
        }
        try {
            Platform.startup(() -> Platform.runLater(() -> {
                try {
                    new DesktopWindow(directory, library).show();
                } catch (RuntimeException error) {
                    try { library.close(); } catch (Exception failure) { error.addSuppressed(failure); }
                    showError("Не удалось открыть окно приложения.");
                }
            }));
        } catch (RuntimeException | Error error) {
            try { library.close(); } catch (Exception failure) { error.addSuppressed(failure); }
            throw error;
        }
    }

    private static void showError(String message) {
        var alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle("Конспекты лекций");
        alert.setHeaderText(null);
        alert.showAndWait();
        Platform.exit();
    }
}
