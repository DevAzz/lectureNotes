package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

enum AppTheme {
    DARK("Тёмная"), LIGHT("Светлая");

    private static final String BASE_CSS = AppTheme.class.getResource("/app.css").toExternalForm();
    private static final String LIGHT_CSS = AppTheme.class.getResource("/app-light.css").toExternalForm();
    private static final String FILE_NAME = "theme.txt";

    private final String title;

    AppTheme(String title) {
        this.title = title;
    }

    static AppTheme load(Path directory) {
        try {
            return LIGHT.name().equals(Files.readString(directory.resolve(FILE_NAME)).strip()) ? LIGHT : DARK;
        } catch (IOException error) {
            return DARK;
        }
    }

    void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        LocalProcessing.writeAtomic(directory.resolve(FILE_NAME), name());
    }

    static AppTheme from(List<String> stylesheets) {
        return stylesheets.contains(LIGHT_CSS) ? LIGHT : DARK;
    }

    void applyTo(List<String> stylesheets) {
        if (!stylesheets.contains(BASE_CSS)) stylesheets.add(BASE_CSS);
        if (this == DARK) stylesheets.remove(LIGHT_CSS);
        else if (!stylesheets.contains(LIGHT_CSS)) stylesheets.add(LIGHT_CSS);
    }

    @Override public String toString() {
        return title;
    }
}
