package org.example;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

record Recording(Path directory, String subject, String title, Instant created, String state) {
    static final int SAMPLE_RATE = 44100;
    private static final Pattern CHUNK_NAME = Pattern.compile("\\d{5}-(\\d{12,})\\.wav");

    record Chunk(Path path, long startFrame, long frames) {
        long startMillis() { return startFrame * 1000 / SAMPLE_RATE; }
        long endMillis() { return (startFrame + frames) * 1000 / SAMPLE_RATE; }
    }

    List<Chunk> chunks() throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.toList()) {
                var match = CHUNK_NAME.matcher(path.getFileName().toString());
                if (match.matches() && Files.size(path) > 44) {
                    chunks.add(new Chunk(path, Long.parseLong(match.group(1)), (Files.size(path) - 44) / 4));
                }
            }
        }
        chunks.sort(Comparator.comparingLong(Chunk::startFrame));
        return chunks;
    }

    String label() {
        return subject.isBlank() ? title : subject + " · " + title;
    }

    void storeState(String value) throws IOException {
        var properties = Files.exists(directory.resolve("recording.properties")) ? readProperties(directory) : new Properties();
        properties.setProperty("title", title);
        properties.setProperty("subject", subject);
        properties.setProperty("created", created.toString());
        properties.setProperty("state", value);
        if (properties.getProperty("permissionAt") == null) {
            properties.setProperty("permissionAt", created.toString());
            properties.setProperty("permissionBasis", "Подтверждение пользователя");
        }
        Path temporary = Files.createTempFile(directory, "recording-", ".tmp");
        try {
            try (var output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(output, null);
            }
            Files.move(temporary, directory.resolve("recording.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties readProperties(Path directory) throws IOException {
        var properties = new Properties();
        try (var input = Files.newBufferedReader(directory.resolve("recording.properties"), StandardCharsets.UTF_8)) {
            properties.load(input);
        }
        return properties;
    }

    static final class Library implements AutoCloseable {
        private final Path root;
        private final FileChannel lockChannel;
        private final FileLock lock;

        Library(Path data) throws IOException {
            root = Files.createDirectories(data.resolve("recordings"));
            lockChannel = FileChannel.open(data.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                lockChannel.close();
                throw new IOException("Библиотека уже открыта в другом экземпляре.");
            }
        }

        Recording create(String subject, String title) throws IOException {
            var recording = new Recording(Files.createDirectory(root.resolve(UUID.randomUUID().toString())),
                    subject, title, Instant.now(), "RECORDING");
            recording.storeState(recording.state());
            return recording;
        }

        List<Recording> load(boolean recover) throws IOException, InterruptedException {
            var result = new ArrayList<Recording>();
            try (var entries = Files.list(root)) {
                for (Path path : entries.toList()) {
                    if (!Files.isRegularFile(path.resolve("recording.properties"))) continue;
                    var properties = readProperties(path);
                    var recording = new Recording(path, properties.getProperty("subject", ""),
                            properties.getProperty("title"), Instant.parse(properties.getProperty("created")),
                            properties.getProperty("state"));
                    if (recover && (recording.state().equals("RECORDING") || recording.state().equals("STOPPING"))) {
                        Capture.recover(path);
                        String state = recording.chunks().isEmpty() ? "EMPTY" : "PARTIAL";
                        recording.storeState(state);
                        recording = new Recording(path, recording.subject(), recording.title(), recording.created(), state);
                    }
                    result.add(recording);
                }
            }
            result.sort(Comparator.comparing(Recording::created).reversed());
            return result;
        }

        @Override public void close() throws IOException {
            lock.release();
            lockChannel.close();
        }
    }
}
