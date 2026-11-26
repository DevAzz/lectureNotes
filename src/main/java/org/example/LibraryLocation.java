package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

record LibraryLocation(Path defaultDirectory) {
    static LibraryLocation forCurrentUser() {
        String parent = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home"));
        return new LibraryLocation(Path.of(parent, "LectureNotes").toAbsolutePath().normalize());
    }

    Path dataDirectory(String override) throws IOException {
        try {
            if (override != null && !override.isBlank()) return absolute(Path.of(override));
            Path setting = settingFile();
            if (Files.notExists(setting)) return defaultDirectory;
            String saved = Files.readString(setting).strip();
            if (saved.isEmpty()) return defaultDirectory;
            Path selected = absolute(Path.of(saved));
            if (!Files.isDirectory(selected)) {
                throw new IOException("Сохранённая папка библиотеки недоступна. Проверьте путь " + selected);
            }
            return selected;
        } catch (InvalidPathException error) {
            throw new IOException("Неверный путь к папке библиотеки.", error);
        }
    }

    void save(Path directory) throws IOException {
        Path selected = absolute(directory);
        if (!Files.isDirectory(selected) || !Files.isWritable(selected)) {
            throw new IOException("Выберите доступную для записи папку.");
        }
        Files.createDirectories(defaultDirectory);
        LocalProcessing.writeAtomic(settingFile(), selected.toString());
    }

    private Path settingFile() {
        return defaultDirectory.resolve("library-location.txt");
    }

    private static Path absolute(Path directory) {
        return directory.toAbsolutePath().normalize();
    }
}
