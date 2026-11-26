package org.example;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

record Recording(Path directory) {
    static final int SAMPLE_RATE = 44100;
    static final int HEADER_BYTES = 44;
    static final int FRAME_BYTES = 4;
    // Process loopback may return PCM dither at one or two quantization steps during silence.
    static final int SILENCE_PEAK = 2;
    private static final Pattern CHUNK_NAME = Pattern.compile("\\d{5}-(\\d{12,})\\.wav(\\.part)?");

    record Chunk(Path path, long startFrame, long frames) {
        long startMillis() { return startFrame * 1000 / SAMPLE_RATE; }
        long endMillis() { return (startFrame + frames) * 1000 / SAMPLE_RATE; }
        boolean partialFile() { return path.getFileName().toString().endsWith(".part"); }
    }

    List<Chunk> chunks() throws IOException {
        return chunks(false);
    }

    List<Chunk> liveChunks() throws IOException {
        return chunks(true);
    }

    private List<Chunk> chunks(boolean includePartial) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.toList()) {
                var match = CHUNK_NAME.matcher(path.getFileName().toString());
                if (!match.matches() || (!includePartial && match.group(2) != null)) continue;
                try {
                    long frames = (Files.size(path) - HEADER_BYTES) / FRAME_BYTES;
                    if (frames > 0) chunks.add(new Chunk(path, Long.parseLong(match.group(1)), frames));
                } catch (NoSuchFileException error) {
                    // The capture helper may have renamed an active file while we were listing it.
                    if (!includePartial) throw error;
                }
            }
        }
        chunks.sort(Comparator.comparingLong(Chunk::startFrame));
        return chunks;
    }

    void clearEmptyChunks() throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (CHUNK_NAME.matcher(file.getFileName().toString()).matches()
                        && Files.size(file) < HEADER_BYTES + FRAME_BYTES) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    static boolean isDigitalSilence(Path path) throws IOException {
        try (var input = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = input.getFormat();
            if (!format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian()) return false;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                for (int offset = 0; offset + 1 < count; offset += 2) {
                    int sample = (short) ((buffer[offset] & 255) | (buffer[offset + 1] << 8));
                    if (Math.abs(sample) > SILENCE_PEAK) return false;
                }
            }
            return true;
        } catch (UnsupportedAudioFileException error) {
            return false;
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
