package org.example;

import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.AudioSystem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Run via tools/check-processing.ps1 to exercise lecture processing. */
public final class ProcessingCheck {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path wav = Path.of(args[1]);
        String liveModel = null;
        Path speechWav = null;
        for (int index = 2; index < args.length; index += 2) {
            switch (args[index]) {
                case "--live-model" -> liveModel = args[index + 1];
                case "--speech-wav" -> speechWav = Path.of(args[index + 1]);
                default -> throw new IllegalArgumentException(args[index]);
            }
        }
        String lectureId;
        String customPrompt = "Собери конспект свободным текстом.\nМожно без ссылок.";
        try (var library = new LectureLibrary(work)) {
            lectureId = library.createLecture("Математика", "Скорость изменения", "", "");
            var recording = library.startRecording(lectureId, Instant.now(), "Контрольная запись");
            Path originalWav = Files.copy(wav, recording.directory().resolve("00000-000000000000.wav"));
            library.appendLiveSegments(lectureId, List.of(new LocalProcessing.Segment(1_500, 3_000, "Черновая реплика.")));
            library.finishRecording(lectureId, "COMPLETE");
            check(library.lecture(lectureId).hasAudio() && recording.chunks().size() == 1, "Published WAV in library");
            Path mono = work.resolve("mono.wav");
            LocalProcessing.convertToWhisperPcm(wav, mono);
            try (var pcm = AudioSystem.getAudioInputStream(mono.toFile())) {
                var format = pcm.getFormat();
                check(format.getSampleRate() == 16000 && format.getChannels() == 1
                        && format.getSampleSizeInBits() == 16 && pcm.getFrameLength() > 0, "ASR input WAV");
            }

            List<LocalProcessing.Segment> segments = LocalProcessing.parseSrt("""
                    1
                    00:00:01,500 --> 00:00:03,000
                    Производная показывает скорость изменения функции.

                    2
                    00:00:03,000 --> 00:00:05,000
                    Пример — скорость движения.
                    """.replace("\n", "\r\n"), 60_000, 64_000);
            check(segments.size() == 2 && segments.getFirst().start() == 61_500
                    && segments.getLast().end() == 64_000, "SRT timeline with chunk offset");
            check(!LocalProcessing.containsSpeech(List.of(new LocalProcessing.Segment(0, 1000,
                    "[Музыка] (шум) ♪"))), "Sound-only output is not recognized speech");
            check(LocalProcessing.containsSpeech(List.of(new LocalProcessing.Segment(0, 1000,
                    "[Музыка] Производная функции."))), "Speech alongside a sound marker is recognized");
            long transcriptId = library.saveTranscript(lectureId, segments);
            library.saveNote(lectureId, transcriptId, "Моя первая редакция", false);
            var preview = library.liveSegments(lectureId).getFirst();
            library.editLiveSegment(lectureId, preview.id(), "Исправленная черновая реплика.");
            check(library.liveSegments(lectureId).getFirst().segment().equals(
                    new LocalProcessing.Segment(1_500, 3_000, "Исправленная черновая реплика.")),
                    "Live edit preserves its time interval");
            long edited = library.editTranscriptSegment(lectureId, transcriptId, 0, "Производная описывает скорость изменения.");
            var revised = library.documents(lectureId);
            check(edited != transcriptId && revised.transcriptId().equals(edited)
                    && revised.segments().getFirst().start() == 61_500
                    && revised.segments().getFirst().text().contains("описывает"), "Transcript edit creates a timed revision");
            var turns = SpeakerDiarization.parseTurns(List.of(
                    "62.850 -- 64.000 speaker_01", "diagnostic text", "61.400 -- 62.850 speaker_00"));
            var labeled = SpeakerDiarization.assign(revised.segments(), turns);
            check("Спикер 1".equals(labeled.getFirst().speaker()) && "Спикер 2".equals(labeled.getLast().speaker()),
                    "Speaker labels follow the greatest timeline overlap");
            var ambiguous = SpeakerDiarization.assign(List.of(
                    new LocalProcessing.Segment(61_500, 64_000, "Смена говорящего."),
                    new LocalProcessing.Segment(65_000, 66_000, "Пауза.")), turns);
            check(SpeakerDiarization.MULTIPLE.equals(ambiguous.getFirst().speaker())
                    && SpeakerDiarization.UNKNOWN.equals(ambiguous.getLast().speaker()), "Uncertain speaker labels");
            var last = labeled.getLast();
            labeled = List.of(labeled.getFirst(), new LocalProcessing.Segment(
                    last.start(), last.end(), last.text(), last.speaker(), "Преподаватель"));
            long labeledVersion = library.replaceTranscript(lectureId, edited, labeled);
            library.editTranscriptSegment(lectureId, labeledVersion, 1, "Пример движения.");
            var savedSpeaker = library.documents(lectureId).segments().getLast();
            check("Спикер 2".equals(savedSpeaker.speaker()) && "Преподаватель".equals(savedSpeaker.speakerName()),
                    "Editing text preserves the original speaker label and assigned name");
            check(library.noteTimeLimitMinutes() == 60, "New library defaults to a one-hour time budget");
            library.saveNoteSettings(customPrompt, 17);
            check(customPrompt.equals(library.noteSystemPrompt()), "Custom system prompt");

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
            var attempts = new AtomicInteger();
            var requestsWithoutTokenCap = new AtomicInteger();
            var slowRequests = new AtomicInteger();
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    String prompt = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!exchange.getRequestMethod().equals("POST")) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    String payload;
                    if (prompt.contains("\"model\":\"plain-note\"")) {
                        if (!prompt.contains(Json.quote(customPrompt))) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        if (!prompt.contains("\"max_tokens\"")) requestsWithoutTokenCap.incrementAndGet();
                        if (attempts.incrementAndGet() == 1) {
                            payload = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Обрезанный текст\"}}]}";
                        } else {
                            payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Свободный конспект.\"}}]}";
                        }
                    } else if (prompt.contains("\"model\":\"slow-note\"")) {
                        slowRequests.incrementAndGet();
                        try { Thread.sleep(650); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Черновик.\"}}]}";
                    } else payload = """
                            {"choices":[{"finish_reason":"stop","message":{
                            "reasoning_content":"draft", "content":"Производная показывает скорость изменения. [[S1]] [[S999]]"}}]}
                            """;
                    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            try {
                URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
                String generated = LocalProcessing.generate("Скорость изменения", segments, api, "iq3_s", "",
                        LocalProcessing.DEFAULT_SYSTEM_PROMPT, Duration.ofSeconds(10));
                library.saveNote(lectureId, transcriptId, generated, true);
                check(generated.contains("[00:01:01](#t=61500)"), "Timestamp rendered from segment");
                check(generated.contains("[[S999]]"), "Unknown reference left as text");
                check(library.documents(lectureId).note().markdown().equals(generated), "Current note");
                check(library.noteVersions(lectureId).size() == 2, "Manual edition preserved");
                String free = LocalProcessing.generate("Скорость изменения", segments, api, "plain-note", "", customPrompt,
                        Duration.ofSeconds(10));
                check(free.equals("Свободный конспект.") && attempts.get() == 4 && requestsWithoutTokenCap.get() == 4,
                        "Provider truncation splits the part, and free-form text is accepted without a token cap");
                try {
                    var longSegments = List.of(new LocalProcessing.Segment(0, 1000, "А".repeat(7_900)),
                            new LocalProcessing.Segment(1000, 2000, "Б".repeat(7_900)));
                    LocalProcessing.generate("Тест времени", longSegments, api, "slow-note", "", customPrompt,
                            Duration.ofMillis(1_100));
                    throw new AssertionError("Generation ignored the shared time limit.");
                } catch (java.io.IOException error) {
                    check("Истекло время формирования конспекта.".equals(error.getMessage()) && slowRequests.get() == 2,
                            "One deadline covers both parts");
                }
                check(library.documents(lectureId).note().markdown().equals(generated), "Timeout leaves the saved note available");
                if (liveModel != null) {
                    String live = LocalProcessing.generate("Скорость изменения", segments, URI.create(liveModel), "iq3_s",
                            "", LocalProcessing.DEFAULT_SYSTEM_PROMPT, Duration.ofMinutes(5));
                    check(!live.isBlank(), "Local model generation");
                    System.out.println("Live iq3_s checked.");
                }
            } finally {
                server.stop(0);
            }
            Path engineOutput = Files.writeString(originalWav.resolveSibling(originalWav.getFileName() + ".srt"), "Данные ASR");
            check(library.removeOriginalAudio(lectureId) && Files.notExists(originalWav) && Files.isRegularFile(engineOutput),
                    "Only original WAV is deleted");
            check(!library.lecture(lectureId).hasAudio() && library.lecture(lectureId).audioRemoved(),
                    "Library displays the removed audio state");
            check(library.documents(lectureId).note() != null, "Audio removal preserves the note");
            check("Спикер 1".equals(library.documents(lectureId).segments().getFirst().speaker()),
                    "Audio removal preserves speaker labels");
            if (speechWav != null) {
                String speechId = library.createLecture("Математика", "Речь для Whisper", "", "");
                var speech = library.startRecording(speechId, Instant.now(), "Контрольная запись");
                Files.copy(speechWav, speech.directory().resolve("00000-000000000000.wav"));
                library.finishRecording(speechId, "COMPLETE");
                for (boolean cpu : List.of(true, false)) {
                    var recognized = LocalProcessing.transcribe(speech, LocalProcessing.bundledWhisper(cpu));
                    check(recognized.stream().anyMatch(segment -> segment.text().matches(".*[А-Яа-яЁё].*")),
                            "Bundled Whisper recognized Russian speech");
                    long version = library.saveTranscript(speechId, recognized);
                    check(library.documents(speechId).transcriptId().equals(version), "Published Whisper transcript");
                    System.out.println("Bundled Whisper and transcript publication verified, CPU forced: " + cpu);
                }
            }
        }
        try (var reopened = new LectureLibrary(work)) {
            check(reopened.noteSystemPrompt().equals(customPrompt), "Custom system prompt survives restart");
            check(reopened.noteTimeLimitMinutes() == 17, "Time budget survives restart");
            check(reopened.liveSegments(lectureId).getFirst().segment().text().equals("Исправленная черновая реплика."),
                    "Edited live draft survives restart");
            check(reopened.documents(lectureId).segments().getFirst().text().contains("описывает"),
                    "Edited transcript survives restart");
        }
        var location = new LibraryLocation(work.resolve("location-settings"));
        check(location.dataDirectory(null).equals(work.resolve("location-settings")), "Default library folder");
        Path selectedFolder = Files.createDirectory(work.resolve("Другая библиотека"));
        location.save(selectedFolder);
        check(new LibraryLocation(work.resolve("location-settings")).dataDirectory(null).equals(selectedFolder),
                "Chosen library folder survives restart");
        check(location.dataDirectory(work.toString()).equals(work), "Explicit data folder remains supported");
        try (var chosen = new LectureLibrary(location.dataDirectory(null))) {
            check(Files.isRegularFile(selectedFolder.resolve("library.sqlite"))
                    && Files.isDirectory(selectedFolder.resolve("recordings")), "Chosen folder holds the library and recordings");
        }
        System.out.println("Playback data, deadline, prompt, edits, WAV-only deletion and library location verified.");
    }

    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
