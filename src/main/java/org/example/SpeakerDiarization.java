package org.example;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

final class SpeakerDiarization {
    private static final String EXECUTABLE = "sherpa-onnx-offline-speaker-diarization.exe";
    private static final int SAMPLE_RATE = (int) LocalProcessing.WHISPER_PCM.getSampleRate();
    private static final int FRAME_BYTES = LocalProcessing.WHISPER_PCM.getFrameSize();
    private static final long MAX_PCM_BYTES = Integer.MAX_VALUE - Recording.HEADER_BYTES;
    private static final Pattern TURN = Pattern.compile("(\\d+(?:\\.\\d+)?) -- (\\d+(?:\\.\\d+)?) speaker_(\\d+)");
    static final String UNKNOWN = "Спикер не определён";
    static final String MULTIPLE = "Несколько спикеров";

    record Turn(long start, long end, int speaker) {}

    record Models(Path segmentation, Path embedding) {
        static Models bundled() {
            Path directory = bundledDirectory();
            return new Models(directory.resolve("segmentation.onnx"), directory.resolve("embedding.onnx"));
        }
    }

    static Path bundledDirectory() {
        try {
            Path code = Path.of(SpeakerDiarization.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return (Files.isDirectory(code) ? code : code.getParent()).resolve("diarization");
        } catch (URISyntaxException error) {
            return Path.of("diarization").toAbsolutePath();
        }
    }

    static List<LocalProcessing.Segment> label(Recording recording, List<LocalProcessing.Segment> segments, Models models)
            throws IOException, InterruptedException {
        Path engine = bundledDirectory();
        Path executable = engine.resolve(EXECUTABLE);
        Path segmentation = models.segmentation();
        Path embedding = models.embedding();
        if (!Files.isRegularFile(executable)) {
            throw new IOException("В этой сборке нет движка разметки спикеров.");
        }
        for (Path model : List.of(segmentation, embedding)) {
            if (!Files.isRegularFile(model)) throw new IOException("Не найден файл модели диаризации: " + model);
        }
        var chunks = recording.chunks();
        if (chunks.isEmpty()) throw new IOException("Сохранённые WAV для разметки спикеров отсутствуют.");
        Path work = Files.createTempDirectory("lecturenotes-diarization-");
        Path wav = work.resolve("audio.wav");
        Path output = work.resolve("speakers.txt");
        try {
            writeAudio(wav, chunks);
            int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
            var command = List.of(executable.toString(), "--segmentation.pyannote-model=" + segmentation,
                    "--embedding.model=" + embedding, "--clustering.cluster-threshold=0.6",
                    "--segmentation.num-threads=" + threads, "--embedding.num-threads=" + threads,
                    wav.getFileName().toString());
            Process process = new ProcessBuilder(command).directory(work.toFile()).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (process.waitFor() != 0) throw new IOException("Не удалось обработать WAV для разметки спикеров.");
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
            var result = assign(segments, parseTurns(Files.readAllLines(output, StandardCharsets.ISO_8859_1)));
            if (result.stream().allMatch(segment -> UNKNOWN.equals(segment.speaker()))) {
                throw new IOException("Не удалось сопоставить голоса с репликами расшифровки.");
            }
            return result;
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(wav);
            Files.deleteIfExists(work);
        }
    }

    private static void writeAudio(Path wav, List<Recording.Chunk> chunks) throws IOException, InterruptedException {
        byte[] silence = new byte[64 * 1024];
        byte[] samples = new byte[silence.length];
        try (var file = new RandomAccessFile(wav.toFile(), "rw")) {
            file.seek(Recording.HEADER_BYTES);
            long written = 0;
            for (var chunk : chunks) {
                long start = Math.round(chunk.startFrame() * (double) SAMPLE_RATE / Recording.SAMPLE_RATE) * FRAME_BYTES;
                checkSize(start);
                while (written < start) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    int count = (int) Math.min(silence.length, start - written);
                    file.write(silence, 0, count);
                    written += count;
                }
                try (var original = AudioSystem.getAudioInputStream(chunk.path().toFile());
                     var pcm = AudioSystem.getAudioInputStream(LocalProcessing.WHISPER_PCM, original)) {
                    try {
                        pcm.skipNBytes(written - start);
                    } catch (EOFException ignored) {
                        continue; // An earlier chunk already covers this part of the timeline.
                    }
                    for (int count; (count = pcm.read(samples)) > 0;) {
                        if (Thread.interrupted()) throw new InterruptedException();
                        written += count;
                        checkSize(written);
                        file.write(samples, 0, count);
                    }
                } catch (UnsupportedAudioFileException | IllegalArgumentException error) {
                    throw new IOException("Один из фрагментов записи не удалось прочитать для разметки спикеров.", error);
                }
            }
            if (written == 0) throw new IOException("Запись не содержит аудио для разметки спикеров.");
            file.seek(0);
            byte[] header = ByteBuffer.allocate(Recording.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) written + 36)
                    .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_BYTES)
                    .putShort((short) FRAME_BYTES).putShort((short) 16)
                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) written).array();
            file.write(header);
        }
    }

    private static void checkSize(long bytes) throws IOException {
        if (bytes > MAX_PCM_BYTES) throw new IOException("Запись слишком длинная для разметки спикеров.");
    }

    static List<Turn> parseTurns(List<String> lines) {
        var turns = new ArrayList<Turn>();
        for (String line : lines) {
            var match = TURN.matcher(line.strip());
            if (!match.matches()) continue;
            long start = Math.round(Double.parseDouble(match.group(1)) * 1000);
            long end = Math.round(Double.parseDouble(match.group(2)) * 1000);
            if (end > start) turns.add(new Turn(start, end, Integer.parseInt(match.group(3))));
        }
        turns.sort(Comparator.comparingLong(Turn::start));
        return turns;
    }

    static List<LocalProcessing.Segment> assign(List<LocalProcessing.Segment> segments, List<Turn> turns) {
        var result = new ArrayList<LocalProcessing.Segment>(segments.size());
        for (var segment : segments) {
            var overlapBySpeaker = new HashMap<Integer, Long>();
            for (var turn : turns) {
                if (turn.start() >= segment.end()) break;
                long overlap = Math.min(segment.end(), turn.end()) - Math.max(segment.start(), turn.start());
                if (overlap > 0) overlapBySpeaker.merge(turn.speaker(), overlap, Long::sum);
            }
            int speakerId = -1;
            long maximum = 0;
            long second = 0;
            for (var entry : overlapBySpeaker.entrySet()) {
                long overlap = entry.getValue();
                if (overlap > maximum) {
                    speakerId = entry.getKey();
                    second = maximum;
                    maximum = overlap;
                } else {
                    second = Math.max(second, overlap);
                }
            }
            String speaker = UNKNOWN;
            long minimum = Math.min(250, Math.max(1, (segment.end() - segment.start()) / 2));
            if (maximum >= minimum) {
                speaker = second >= minimum && second * 2 >= maximum ? MULTIPLE : "Спикер " + (speakerId + 1);
            }
            result.add(new LocalProcessing.Segment(segment.start(), segment.end(), segment.text(), speaker, null));
        }
        return List.copyOf(result);
    }

    static boolean canName(String speaker) {
        return speaker != null && !UNKNOWN.equals(speaker) && !MULTIPLE.equals(speaker);
    }

    private SpeakerDiarization() {}
}
