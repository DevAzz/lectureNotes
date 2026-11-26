package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

record Recording(Path directory) {
    static final int SAMPLE_RATE = 44100;
    private static final Pattern CHUNK_NAME = Pattern.compile("\\d{5}-(\\d{12,})\\.wav");
    private static final Pattern INCOMPLETE_CHUNK = Pattern.compile("\\d{5}-\\d{12,}\\.wav(?:\\.part)?");

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

    void clearEmptyChunks() throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (INCOMPLETE_CHUNK.matcher(file.getFileName().toString()).matches() && Files.size(file) < 48) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    static Properties readProperties(Path directory) throws IOException {
        var properties = new Properties();
        try (var input = Files.newBufferedReader(directory.resolve("recording.properties"), StandardCharsets.UTF_8)) {
            properties.load(input);
        }
        return properties;
    }

}
