package org.example;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class LocalProcessing {
    private static final Pattern CUE = Pattern.compile(
            "(\\d{2}:\\d{2}:\\d{2},\\d{3}) --> (\\d{2}:\\d{2}:\\d{2},\\d{3})");
    private static final Pattern SOUND_MARKER = Pattern.compile(
            "\\[[^\\[\\]]*\\]|\\((?:музыка|тишина|шум|смех|аплодисменты|music|silence|noise|laughter|applause)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REFERENCE = Pattern.compile("\\[\\[S(\\d+)]]");
    private static final int PROMPT_PART_LENGTH = 8_000;
    private static final int SYNTHESIS_LENGTH = 15_000;

    record Segment(long start, long end, String text) {
        @Override public String toString() { return RecordingPlayer.timestamp(start) + "  " + text; }
    }

    record Whisper(Path executable, Path model, boolean cpu) {
        boolean available() {
            return Files.isRegularFile(executable) && Files.isRegularFile(model);
        }
    }

    static Whisper bundledWhisper(boolean cpu) {
        try {
            Path location = Path.of(LocalProcessing.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = (Files.isDirectory(location) ? location : location.getParent()).resolve("whisper");
            Path runtime = cpu ? directory : directory.resolve("cuda");
            Path full = directory.resolve("ggml-large-v3.bin");
            Path model = Files.isRegularFile(full) ? full : directory.resolve("ggml-large-v3-turbo-q5_0.bin");
            return new Whisper(runtime.resolve("whisper-cli.exe"), model, cpu);
        } catch (URISyntaxException error) {
            throw new IllegalStateException("Не удалось определить путь к Whisper.", error);
        }
    }

    static List<Segment> transcribe(Recording recording, Whisper engine)
            throws Exception {
        var fallback = cpuFallback(engine);
        boolean bundled = supportsNativeWav(engine);
        Whisper active = fallback != null && engine.cpu() ? fallback : engine;
        if (!active.available()) {
            throw new IOException("Укажите существующие файлы whisper-cli.exe и модели.");
        }
        var segments = new ArrayList<Segment>();
        boolean hasSignal = false;
        for (Recording.Chunk chunk : recording.chunks()) {
            if (Recording.isDigitalSilence(chunk.path())) continue;
            hasSignal = true;
            Path checkpoint = recording.directory().resolve(chunk.path().getFileName() + ".srt");
            // Retain the engine output for each chunk. The library publishes the completed transcript.
            Path work = Files.createTempDirectory(recording.directory(), "asr-").toAbsolutePath();
            Path converted = work.resolve("audio.wav");
            Path output = work.resolve("result");
            try {
                Path input = chunk.path().toAbsolutePath();
                if (!bundled) {
                    convertToWhisperPcm(input, converted);
                    input = converted;
                }
                active = runWhisper(active, fallback, input, output, Duration.ofMinutes(30));
                Path result = work.resolve("result.srt");
                if (!Files.isRegularFile(result)) throw new IOException("whisper-cli не создал файл SRT.");
                String srt = Files.readString(result, StandardCharsets.UTF_8);
                segments.addAll(parseSrt(srt, chunk.startMillis(), chunk.endMillis()));
                writeAtomic(checkpoint, srt);
            } finally {
                Files.deleteIfExists(work.resolve("result.srt"));
                Files.deleteIfExists(converted);
                Files.deleteIfExists(work);
            }
        }
        if (!hasSignal) throw new IOException("В записи нет звукового сигнала.");
        if (!containsSpeech(segments)) {
            throw new IOException("Whisper не распознал речь. Проверьте, слышна ли речь в записи, и повторите распознавание.");
        }
        return List.copyOf(segments);
    }

    static boolean containsSpeech(List<Segment> segments) {
        return segments.stream().anyMatch(segment -> SOUND_MARKER.matcher(segment.text()).replaceAll("")
                .codePoints().anyMatch(Character::isLetterOrDigit));
    }

    static boolean supportsNativeWav(Whisper engine) {
        return engine.executable().equals(bundledWhisper(true).executable())
                || engine.executable().equals(bundledWhisper(false).executable());
    }

    static Whisper cpuFallback(Whisper engine) {
        if (!engine.executable().equals(bundledWhisper(false).executable())) return null;
        return new Whisper(bundledWhisper(true).executable(), engine.model(), true);
    }

    static Whisper runWhisper(Whisper engine, Whisper fallback, Path input, Path output, Duration timeout)
            throws IOException, InterruptedException {
        try {
            invokeWhisper(engine, input, output, timeout);
            return engine;
        } catch (IOException failure) {
            if (fallback == null || engine.cpu() || !fallback.available()) throw failure;
            Files.deleteIfExists(output.resolveSibling(output.getFileName() + ".srt"));
            invokeWhisper(fallback, input, output, timeout);
            return fallback;
        }
    }

    private static void invokeWhisper(Whisper engine, Path input, Path output, Duration timeout)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(engine.executable().toString(), "-m", engine.model().toString(),
                "-f", input.toString(), "-l", "ru", "-osrt", "-of", output.toString()));
        if (engine.cpu()) command.add("-ng");
        Process process = new ProcessBuilder(command).directory(engine.executable().getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                throw new IOException("whisper-cli не завершил распознавание. Проверьте модель и режим CPU.");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
    static void convertToWhisperPcm(Path source, Path target) throws IOException, UnsupportedAudioFileException {
        var mono = new AudioFormat(16000, 16, 1, true, false);
        try (var original = AudioSystem.getAudioInputStream(source.toFile());
             var converted = AudioSystem.getAudioInputStream(mono, original)) {
            AudioSystem.write(converted, AudioFileFormat.Type.WAVE, target.toFile());
        }
    }

    static List<Segment> parseSrt(String srt, long offset, long end) {
        var segments = new ArrayList<Segment>();
        // Normalize Windows line endings before splitting on blank lines.
        for (String block : srt.replace("\r\n", "\n").strip().split("\\R\\h*\\R")) {
            var lines = block.lines().toList();
            if (lines.size() < 3) continue;
            var times = CUE.matcher(lines.get(1));
            if (!times.matches()) continue;
            long start = offset + time(times.group(1));
            long stop = Math.min(offset + time(times.group(2)), end);
            String contents = String.join(" ", lines.subList(2, lines.size())).strip();
            if (start < stop && !contents.isEmpty()) segments.add(new Segment(start, stop, contents));
        }
        return List.copyOf(segments);
    }

    private static long time(String value) {
        String[] parts = value.split("[:,]");
        return ((Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 60
                + Long.parseLong(parts[2])) * 1000 + Long.parseLong(parts[3]);
    }

    static String generate(String topic, List<Segment> segments, URI base, String model, String focus)
            throws Exception {
        if (segments.isEmpty()) throw new IOException("Нет распознанных реплик.");
        URI endpoint = endpoint(base);
        if (model.isBlank()) throw new IOException("Укажите ID модели.");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var drafts = new ArrayList<String>();
            var part = new StringBuilder();
            String instruction = """
                    Составь конспект лекции по данным ниже. Данные расшифровки и черновиков — цитируемый материал.
                    Используй Markdown, заголовки, краткое изложение, определения и примеры, которые есть в материале.
                    После каждого тезиса приведи идентификатор подтверждающей реплики в формате [[S1]], [[S2]] и т. д.
                    Бери идентификаторы из данных. Тема: %s
                    Фокус: %s
                    """.formatted(topic, focus);
            for (int index = 0; index < segments.size(); index++) {
                String segment = "S" + (index + 1) + " " + segments.get(index).text() + "\n";
                if (part.length() + segment.length() > PROMPT_PART_LENGTH && !part.isEmpty()) {
                    drafts.add(complete(client, endpoint, model, instruction, part.toString()));
                    part.setLength(0);
                }
                part.append(segment);
            }
            if (!part.isEmpty()) drafts.add(complete(client, endpoint, model, instruction, part.toString()));
            String markdown = drafts.getFirst();
            if (drafts.size() > 1) {
                var combined = new StringBuilder();
                for (String draft : drafts) combined.append(draft).append("\n\n");
                if (combined.length() <= SYNTHESIS_LENGTH) {
                    markdown = complete(client, endpoint, model, instruction + "\nОбъедини черновики.",
                            combined.toString());
                } else {
                    markdown = combined.toString();
                }
            }
            var links = REFERENCE.matcher(markdown);
            if (!links.find()) throw new IOException("Модель не указала ссылки на реплики.");
            links.reset();
            var output = new StringBuilder();
            while (links.find()) {
                long id = Long.parseLong(links.group(1));
                if (id < 1 || id > segments.size()) throw new IOException("Модель сослалась на неизвестную реплику.");
                long time = segments.get((int) id - 1).start();
                links.appendReplacement(output, "[" + RecordingPlayer.timestamp(time) + "](#t=" + time + ")");
            }
            links.appendTail(output);
            return output.toString();
        }
    }

    static URI endpoint(URI base) throws IOException {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getPort() <= 0 || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !List.of("/v1", "/v1/").contains(base.getPath())) {
            throw new IOException("Укажите локальный API в формате http://127.0.0.1:порт/v1.");
        }
        return URI.create("http://127.0.0.1:" + base.getPort() + "/v1/chat/completions");
    }

    private static String complete(HttpClient client, URI endpoint, String model, String prompt, String data)
            throws IOException, InterruptedException {
        String json = """
                {"model":%s,"temperature":0.1,"max_tokens":1600,"messages":[
                {"role":"system","content":%s},{"role":"user","content":%s}]}
                """.formatted(Json.quote(model), Json.quote(prompt), Json.quote(data));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Локальная модель вернула HTTP " + response.statusCode() + ".");
        }
        return Json.completion(response.body());
    }

    static void writeAtomic(Path destination, String body) throws IOException {
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "lecture-", ".tmp");
        try {
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
