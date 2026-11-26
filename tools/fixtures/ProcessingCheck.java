package org.example;

import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.AudioSystem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Run via tools/check-processing.ps1 to exercise the pipeline without downloading ASR weights. */
public final class ProcessingCheck {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path wav = Path.of(args[1]);
        try (var library = new Recording.Library(work)) {
            var recording = library.create("Математика", "Скорость изменения");
            Files.copy(wav, recording.directory().resolve("00000-000000000000.wav"));
            recording.storeState("COMPLETE");
            check(library.load(false).getFirst().chunks().size() == 1, "Published WAV in library");
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
                    """, 60_000, 64_000);
            check(segments.size() == 2 && segments.getFirst().start() == 61_500
                    && segments.getLast().end() == 64_000, "SRT timeline with chunk offset");
            LocalProcessing.saveNote(recording, "Моя первая редакция");

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
                String generated = LocalProcessing.generate(recording, segments, api, "iq3_s", "");
                check(generated.contains("[00:01:01](#t=61500)"), "Timestamp rendered from segment");
                check(Files.readString(recording.directory().resolve("note.md")).equals(generated), "Current note");
                try (var notes = Files.list(recording.directory())) {
                    long versions = notes.filter(path -> path.getFileName().toString().startsWith("note-")).count();
                    check(versions == 2, "Manual edition preserved");
                }
                if (args.length == 3) {
                    String live = LocalProcessing.generate(recording, segments, URI.create(args[2]), "iq3_s", "");
                    check(live.contains("](#t="), "Local model generation with links");
                    System.out.println("Live iq3_s checked.");
                }
            } finally {
                server.stop(0);
            }
            System.out.println("WAV conversion, SRT offsets, local generation and editions verified.");
        }
    }

    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
