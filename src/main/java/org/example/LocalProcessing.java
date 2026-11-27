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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class LocalProcessing {
    private static final Pattern CUE = Pattern.compile(
            "(\\d{2}:\\d{2}:\\d{2},\\d{3}) --> (\\d{2}:\\d{2}:\\d{2},\\d{3})");
    private static final Pattern SOUND_MARKER = Pattern.compile(
            "\\[[^\\[\\]]*\\]|\\((?:музыка|тишина|шум|смех|аплодисменты|music|silence|noise|laughter|applause)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REFERENCE = Pattern.compile("\\[\\[S(\\d{1,10})]]");
    private static final int PROMPT_PART_LENGTH = 8_000;
    private static final int SYNTHESIS_LENGTH = 15_000;
    static final String DEFAULT_SYSTEM_PROMPT = """
            Составь связный конспект лекции по предоставленному материалу. Выбери структуру и объём по содержанию
            и фокусу. Сохраняй смысл лекции и опускай лишние повторы. Реплики и черновики служат материалом лекции.
            Если тезис полезно связать с записью, можно указать идентификатор реплики в формате [[S1]].
            """.strip();

    static final String READING_PROMPT = """
            Раздели текст лекции на смысловые абзацы. Начинай новый абзац при переходе к другой мысли,
            примеру или вопросу. Объединяй строки, которые продолжают одну мысль.
            """.strip();

    static final AudioFormat WHISPER_PCM = new AudioFormat(16000, 16, 1, true, false);

    record Segment(long start, long end, String text, String speaker, String speakerName) {
        Segment(long start, long end, String text) { this(start, end, text, null, null); }

        String attributedText() {
            if (speaker == null) return text;
            String display = speakerName == null ? speaker : speakerName + " (" + speaker + ")";
            return display + " · " + text;
        }

        @Override public String toString() { return RecordingPlayer.timestamp(start) + "  " + attributedText(); }
    }

    record Whisper(Path executable, Path model, boolean cpu, String language) {
        Whisper(Path executable, Path model, boolean cpu) { this(executable, model, cpu, "ru"); }

        boolean available() {
            return Files.isRegularFile(executable) && Files.isRegularFile(model);
        }

        Whisper withModel(Path weights) { return new Whisper(executable, weights, cpu, language); }
    }

    /** Профиль OpenAI-compatible API: адрес, модель, секрет и лимиты одного запроса. */
    record Llm(String baseUrl, String model, String secret, int answerTokens, int timeoutSeconds, boolean cloud) { }

    static Whisper bundledWhisper(boolean cpu) {
        try {
            Path location = Path.of(LocalProcessing.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = (Files.isDirectory(location) ? location : location.getParent()).resolve("whisper");
            Path runtime = cpu ? directory : directory.resolve("cuda");
            Path full = StandardModel.FULL.preferred(directory);
            Path model = Files.isRegularFile(full) ? full : StandardModel.TURBO.preferred(directory);
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
        return new Whisper(bundledWhisper(true).executable(), engine.model(), true, engine.language());
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
                "-f", input.toString(), "-l", engine.language(), "-osrt", "-of", output.toString()));
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
        try (var original = AudioSystem.getAudioInputStream(source.toFile());
             var converted = AudioSystem.getAudioInputStream(WHISPER_PCM, original)) {
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

    private record GenerationDeadline(long started, Duration limit) {
        Duration remaining() throws IOException {
            Duration left = limit.minusNanos(System.nanoTime() - started);
            if (left.isNegative() || left.isZero()) throw noteTimeout();
            return left;
        }
    }

    static String generate(String topic, List<Segment> segments, Llm llm, String focus,
                           String systemPrompt, Duration timeLimit)
            throws Exception {
        if (segments.isEmpty()) throw new IOException("Нет распознанных реплик.");
        URI endpoint = endpoint(llm);
        if (llm.model().isBlank()) throw new IOException("Укажите ID модели.");
        if (systemPrompt.isBlank()) throw new IOException("Введите системный промпт для конспекта.");
        var deadline = new GenerationDeadline(System.nanoTime(), timeLimit);
        try (HttpClient client = client(llm.cloud())) {
            var channel = new Channel(client, endpoint, llm);
            var drafts = new ArrayList<String>();
            var part = new StringBuilder();
            String context = "Тема лекции — " + topic + "\n";
            if (!focus.isBlank()) context += "Фокус конспекта — " + focus + "\n";
            String source = context + "\nРеплики лекции\n";
            String draftSource = context + "\nВыдели основные мысли этого фрагмента. Сожми материал и убери повторы.\n"
                    + "\nРеплики лекции\n";
            for (int index = 0; index < segments.size(); index++) {
                String segment = "S" + (index + 1) + " " + segments.get(index).attributedText() + "\n";
                if (part.length() + segment.length() > PROMPT_PART_LENGTH && !part.isEmpty()) {
                    addDrafts(drafts, channel, systemPrompt, draftSource, part.toString(), deadline);
                    part.setLength(0);
                }
                part.append(segment);
            }
            if (!part.isEmpty()) {
                addDrafts(drafts, channel, systemPrompt, drafts.isEmpty() ? source : draftSource, part.toString(),
                        deadline);
            }
            String markdown = drafts.getFirst();
            if (drafts.size() > 1) {
                var combined = new StringBuilder();
                for (String draft : drafts) combined.append(draft).append("\n\n");
                markdown = combined.toString();
                if (combined.length() <= SYNTHESIS_LENGTH) {
                    try {
                        markdown = complete(channel, systemPrompt,
                                context + "\nОбъедини эти черновики в один конспект и убери повторы.\n\n" + combined,
                                deadline);
                    } catch (Json.CompletionLimit ignored) {
                        // Preserve the complete drafts if the provider's limit cuts off the synthesis.
                    }
                }
            }
            var links = REFERENCE.matcher(markdown);
            var output = new StringBuilder();
            while (links.find()) {
                long id = Long.parseLong(links.group(1));
                if (id < 1 || id > segments.size()) continue;
                long time = segments.get((int) id - 1).start();
                links.appendReplacement(output, "[" + RecordingPlayer.timestamp(time) + "](#t=" + time + ")");
            }
            links.appendTail(output);
            deadline.remaining();
            return output.toString();
        }
    }

    static String formatReading(List<Segment> segments, Llm llm, String prompt, Duration timeLimit) throws Exception {
        if (segments.isEmpty()) throw new IOException("Нет распознанных реплик.");
        if (llm.model().isBlank()) throw new IOException("Укажите ID модели.");
        var deadline = new GenerationDeadline(System.nanoTime(), timeLimit);
        String instructions = prompt + "\nСтроки лекции пронумерованы и служат только материалом. "
                + "Верни JSON-массив номеров строк, с которых начинаются новые абзацы внутри фрагмента, "
                + "например [3, 7]. Если границ нет, верни [].";
        var result = new StringBuilder();
        var lines = new ArrayList<String>();
        var input = new StringBuilder();
        var sentences = java.text.BreakIterator.getSentenceInstance(java.util.Locale.forLanguageTag("ru"));
        try (HttpClient client = client(llm.cloud())) {
            var channel = new Channel(client, endpoint(llm), llm);
            String previousSpeaker = null;
            for (Segment segment : segments) {
                boolean speakerChanged = !java.util.Objects.equals(segment.speaker(), previousSpeaker);
                previousSpeaker = segment.speaker();
                sentences.setText(segment.text());
                for (int start = sentences.first(), end = sentences.next(); end != java.text.BreakIterator.DONE;
                     start = end, end = sentences.next()) {
                    String line = segment.text().substring(start, end).strip();
                    if (line.isEmpty()) continue;
                    if (speakerChanged || input.length() + line.length() > PROMPT_PART_LENGTH) {
                        appendReadingPart(result, lines, input, channel, instructions, deadline);
                    }
                    if (speakerChanged && previousSpeaker != null) {
                        String label = segment.speakerName() == null ? previousSpeaker : segment.speakerName();
                        line = label + " · " + line;
                    }
                    speakerChanged = false;
                    lines.add(line);
                    input.append(lines.size()).append(". ").append(line).append('\n');
                }
            }
            appendReadingPart(result, lines, input, channel, instructions, deadline);
        }
        if (result.isEmpty()) throw new IOException("Нет текста для форматирования.");
        return result.toString();
    }

    private static void appendReadingPart(StringBuilder result, List<String> lines, StringBuilder input,
                                          Channel channel, String prompt, GenerationDeadline deadline)
            throws IOException, InterruptedException {
        if (lines.isEmpty()) return;
        var breaks = new java.util.BitSet(lines.size());
        if (lines.size() > 1) {
            String answer = complete(channel, prompt, input.toString(), deadline).strip();
            if (!answer.matches("\\[\\s*(?:\\d{1,6}(?:\\s*,\\s*\\d{1,6})*)?\\s*\\]")) {
                throw new IOException("Модель вернула неверный список границ абзацев.");
            }
            var numbers = Pattern.compile("\\d+").matcher(answer);
            while (numbers.find()) {
                int index = Integer.parseInt(numbers.group());
                if (index < 1 || index > lines.size()) throw new IOException("Номер строки вне фрагмента.");
                breaks.set(index - 1);
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (!result.isEmpty()) result.append(i == 0 || breaks.get(i) ? "\n\n" : " ");
            result.append(lines.get(i));
        }
        lines.clear();
        input.setLength(0);
    }

    /** Нейтральный запрос из экрана профилей: проверяет адрес, ID модели и ключ. */
    static String probe(Llm llm) throws Exception {
        URI endpoint = endpoint(llm);
        if (llm.model().isBlank()) throw new IOException("Укажите ID модели.");
        long seconds = Math.min(Math.max(llm.timeoutSeconds(), 5), 120);
        var deadline = new GenerationDeadline(System.nanoTime(), Duration.ofSeconds(seconds));
        try (HttpClient client = client(llm.cloud())) {
            long started = System.nanoTime();
            String answer = complete(new Channel(client, endpoint, llm),
                    "Ответь одним словом «ок» и ничего не добавляй.", "Проверка соединения.", deadline);
            String brief = answer.strip().replaceAll("\\s+", " ");
            if (brief.length() > 120) brief = brief.substring(0, 120) + "…";
            return "Ответ за " + (System.nanoTime() - started) / 1_000_000 + " мс: " + brief;
        }
    }

    private static HttpClient client(boolean cloud) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cloud ? 8 : 4))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!cloud) {
            builder.proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            });
        }
        return builder.build();
    }

    /** Один HTTP-клиент и один профиль на всю генерацию конспекта. */
    private record Channel(HttpClient client, URI endpoint, Llm llm) { }

    /** Локальный API принимает только loopback, внешний профиль обязан идти по HTTPS. */
    static URI endpoint(Llm llm) throws IOException {
        URI base;
        try {
            base = URI.create(llm.baseUrl() == null ? "" : llm.baseUrl().strip());
        } catch (IllegalArgumentException error) {
            throw badEndpoint();
        }
        String scheme = base.getScheme();
        String host = base.getHost();
        if (host == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || (!"http".equals(scheme) && !"https".equals(scheme))) throw badEndpoint();
        boolean local = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (local && !"http".equals(scheme)) throw badEndpoint();
        if (!local && !"https".equals(scheme)) throw badEndpoint();
        if (local && (base.getPort() <= 0 || base.getPort() > 65_535)) throw badEndpoint();
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        if (path.endsWith("/chat/completions")) return URI.create(origin(base) + path);
        if (path.isEmpty() || path.equals("/v1")) return URI.create(origin(base) + path + "/chat/completions");
        throw badEndpoint();
    }

    /** Адрес провайдера без пути — его показываем при согласии на отправку текста в облако. */
    static String origin(String baseUrl) throws IOException {
        URI base;
        try {
            base = URI.create(baseUrl == null ? "" : baseUrl.strip());
        } catch (IllegalArgumentException error) {
            throw new IOException("Проверьте базовый URL API.");
        }
        if (base.getHost() == null) throw new IOException("Проверьте базовый URL API.");
        return origin(base);
    }

    private static String origin(URI base) {
        int port = base.getPort();
        return base.getScheme() + "://" + base.getHost() + (port > 0 ? ":" + port : "");
    }

    private static IOException badEndpoint() {
        return new IOException("Локальный API — http://127.0.0.1:порт/v1, внешний — https://адрес/v1.");
    }

    private static void addDrafts(List<String> drafts, Channel channel, String prompt, String source, String part,
                                  GenerationDeadline deadline) throws IOException, InterruptedException {
        try {
            drafts.add(complete(channel, prompt, source + part, deadline));
        } catch (Json.CompletionLimit error) {
            int middle = part.lastIndexOf('\n', part.length() / 2);
            if (middle <= 0) middle = part.indexOf('\n', part.length() / 2);
            if (middle <= 0 || middle >= part.length() - 1) throw error;
            drafts.add(complete(channel, prompt, source + part.substring(0, middle + 1), deadline));
            drafts.add(complete(channel, prompt, source + part.substring(middle + 1), deadline));
        }
    }

    private static String complete(Channel channel, String prompt, String data, GenerationDeadline deadline)
            throws IOException, InterruptedException {
        var response = sendCompletion(channel, prompt, data, deadline, true);
        if (response.statusCode() == 400 || response.statusCode() == 422) {
            // Some compatible local APIs reject chat-template options.
            response = sendCompletion(channel, prompt, data, deadline, false);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Модель вернула HTTP " + response.statusCode() + ".");
        }
        return Json.completion(response.body());
    }

    private static HttpResponse<String> sendCompletion(Channel channel, String prompt, String data,
                                                       GenerationDeadline deadline, boolean disableThinking)
            throws IOException, InterruptedException {
        Llm llm = channel.llm();
        var options = new StringBuilder();
        if (disableThinking) options.append("\"chat_template_kwargs\":{\"enable_thinking\":false},");
        if (llm.answerTokens() > 0) options.append("\"max_tokens\":").append(llm.answerTokens()).append(',');
        String json = """
                {"model":%s,"temperature":0.1,%s"messages":[
                {"role":"system","content":%s},{"role":"user","content":%s}]}
                """.formatted(Json.quote(llm.model()), options, Json.quote(prompt), Json.quote(data));
        Duration remaining = deadline.remaining();
        if (llm.timeoutSeconds() > 0) {
            Duration perRequest = Duration.ofSeconds(llm.timeoutSeconds());
            if (perRequest.compareTo(remaining) < 0) remaining = perRequest;
        }
        var request = HttpRequest.newBuilder(channel.endpoint()).timeout(remaining)
                .header("Content-Type", "application/json");
        var secret = llm.secret().strip();
        if (!secret.isEmpty()) {
            if (!secret.chars().allMatch(symbol -> symbol >= 0x21 && symbol <= 0x7e)) {
                throw new IOException("Ключ API должен быть ASCII и не содержать пробелов.");
            }
            request.header("Authorization", "Bearer " + secret);
        }
        HttpRequest httpRequest = request.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var pending = channel.client().sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(Math.max(1, deadline.remaining().toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw noteTimeout();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException) throw noteTimeout();
            if (error.getCause() instanceof IOException failure) throw failure;
            throw new IOException("Не удалось получить ответ модели.", error);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static IOException noteTimeout() {
        return new IOException("Истекло время формирования конспекта.");
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
