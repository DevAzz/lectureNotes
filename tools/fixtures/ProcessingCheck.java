package org.example;

import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.AudioSystem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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
        try (var library = new LectureLibrary(work)) {
            String lectureId = library.createLecture("Математика", "Скорость изменения", "", "");
            var recording = library.startRecording(lectureId, Instant.now(), "Контрольная запись");
            Files.copy(wav, recording.directory().resolve("00000-000000000000.wav"));
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

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    String prompt = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!exchange.getRequestMethod().equals("POST") || !prompt.contains("Производная")
                            || !prompt.contains("\"model\":\"iq3_s\"")) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    String payload = """
                            {"choices":[{"finish_reason":"stop","message":{
                            "reasoning_content":"draft", "content":"Производная показывает скорость изменения. [[S1]]"}}]}
                            """;
                    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            try {
                URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
                String generated = LocalProcessing.generate("Скорость изменения", segments, api, "iq3_s", "");
                library.saveNote(lectureId, transcriptId, generated, true);
                check(generated.contains("[00:01:01](#t=61500)"), "Timestamp rendered from segment");
                check(library.documents(lectureId).note().markdown().equals(generated), "Current note");
                check(library.noteVersions(lectureId).size() == 2, "Manual edition preserved");
                if (liveModel != null) {
                    String live = LocalProcessing.generate("Скорость изменения", segments, URI.create(liveModel), "iq3_s", "");
                    check(live.contains("](#t="), "Local model generation with links");
                    System.out.println("Live iq3_s checked.");
                }
            } finally {
                server.stop(0);
            }
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
            System.out.println("WAV conversion, SRT offsets, local generation and editions verified.");
        }
    }

    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
