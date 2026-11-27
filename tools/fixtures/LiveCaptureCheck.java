package org.example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

final class LiveCaptureCheck {
    private static final String OLD_CARD = "b7b03ec4-fa43-44f1-875b-c8065353f676";

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        checkInvitation(Files.createDirectory(work.resolve("library")));
        checkSilence(Files.createDirectory(work.resolve("silence")), args.length > 2 ? Path.of(args[2]) : null);
        checkLive(work, Path.of(args[1]));
    }

    private static void checkSilence(Path directory, Path source) throws Exception {
        Path wav = directory.resolve("00000-000000000000.wav");
        if (source != null) {
            Files.copy(source, wav);
        } else {
            int frames = Recording.SAMPLE_RATE * 8;
            byte[] data = new byte[frames * Recording.FRAME_BYTES];
            for (int offset = 0; offset < data.length; offset += 8) data[offset] = 1;
            var format = new AudioFormat(Recording.SAMPLE_RATE, 16, 2, true, false);
            try (var audio = new AudioInputStream(new ByteArrayInputStream(data), format, frames)) {
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav.toFile());
            }
        }
        // Invalid executable ensures that both paths skip Whisper entirely for silent PCM.
        var engine = new LocalProcessing.Whisper(Files.createFile(directory.resolve("unused.exe")),
                Files.createFile(directory.resolve("unused.bin")), false);
        var recording = new Recording(directory);
        try {
            LocalProcessing.transcribe(recording, engine);
            throw new AssertionError("Silent WAV was published as speech");
        } catch (IOException error) {
            check("В записи нет звукового сигнала.".equals(error.getMessage()), "Silent WAV is reported explicitly");
        }
        Files.move(wav, wav.resolveSibling(wav.getFileName() + ".part"));
        var update = new CompletableFuture<List<LocalProcessing.Segment>>();
        Thread preview = Thread.startVirtualThread(() -> {
            try {
                LiveTranscription.follow(recording, engine, update::complete);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                update.completeExceptionally(error);
            }
        });
        try {
            check(update.get(5, TimeUnit.SECONDS).isEmpty(), "Dither produces no live text");
        } finally {
            preview.interrupt();
            preview.join(5_000);
        }
        System.out.println("Silent PCM skips Whisper in both live and saved recordings.");
    }

    private static void checkInvitation(Path directory) throws Exception {
        Path database = directory.resolve("library.sqlite");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = db.createStatement()) {
            for (String sql : LibrarySchema.V1) statement.execute(sql);
            statement.execute("INSERT INTO subject(id,name,name_key) VALUES(1,'История','история')");
            statement.execute("""
                    INSERT INTO lecture(id,subject_id,title,title_key,created_at,state)
                    VALUES('%s',1,'Лекция','лекция',1,'DRAFT')
                    """.formatted(OLD_CARD));
            statement.execute("PRAGMA user_version=1");
        }
        String link = "https://meet.example.org/calls/test?psw=synthetic-invitation-password";
        String id;
        try (var library = new LectureLibrary(directory)) {
            check(library.lecture(OLD_CARD).title().equals("Лекция"), "Migration preserved existing lecture");
            check(library.encryptedInvitation(OLD_CARD) == null, "Existing lecture has no saved invitation");
            var secret = InvitationSecret.protect(MeetingBrowser.invitation(link));
            id = library.createLecture("История", "Тест", "", "", secret);
            library.startRecording(id, Instant.now(), "Контрольная запись");
            library.appendLiveSegments(id, List.of(new LocalProcessing.Segment(250, 950, "Живая реплика.")));
            library.finishRecording(id, "PARTIAL");
            library.saveNoteSystemPrompt("Напиши конспект свободным текстом.");
        }
        try (var library = new LectureLibrary(directory)) {
            library.editLecture(id, "История", "Изменённое название", "", "");
            check(link.equals(InvitationSecret.unprotect(library.encryptedInvitation(id))),
                    "Saved link survives restart and unrelated card edits");
            check(library.liveSegments(id).getFirst().segment().equals(
                    new LocalProcessing.Segment(250, 950, "Живая реплика.")), "Live transcript survives restart");
            check(library.noteSystemPrompt().equals("Напиши конспект свободным текстом."),
                    "Custom system prompt survives restart");
            check(!new String(Files.readAllBytes(database), StandardCharsets.ISO_8859_1).contains(link),
                    "SQLite does not contain plaintext link");
            library.editMeetingCard(id, "История", "Изменённое название", "", "", null);
            check(library.encryptedInvitation(id) == null, "Clearing the field removes the saved link");
        }
        System.out.println("V1 migration, protected invitations, live drafts and prompt persistence verified.");
    }

    private static void checkLive(Path work, Path wav) throws Exception {
        Path script = work.resolve("play.ps1");
        Files.writeString(script, """
                param([string]$Wave)
                $ErrorActionPreference = 'Stop'
                $player = New-Object Media.SoundPlayer $Wave
                $player.Load()
                [Console]::WriteLine('ready')
                [Console]::ReadLine() | Out-Null
                $player.PlayLooping()
                [Console]::ReadLine() | Out-Null
                """, StandardCharsets.US_ASCII);
        Path directory = Files.createDirectory(work.resolve("recording"));
        Recording recording = new Recording(directory);
        var engine = LocalProcessing.bundledWhisper(false);
        var previews = new LinkedBlockingQueue<List<LocalProcessing.Segment>>();
        var failure = new CompletableFuture<Void>();
        var player = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-File",
                script.toString(), wav.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Process capture = null;
        Thread preview = null;
        Thread events = null;
        try {
            var ready = CompletableFuture.supplyAsync(() -> player.inputReader().lines().findFirst().orElse(""));
            check("ready".equals(ready.get(10, TimeUnit.SECONDS)), "Synthetic speech player started");
            capture = Capture.start(player.toHandle(), directory);
            Process active = capture;
            var started = new CompletableFuture<Void>();
            events = Thread.startVirtualThread(() -> {
                try (var output = active.inputReader(StandardCharsets.UTF_8)) {
                    output.lines().filter(line -> line.startsWith("{\"event\":\"started\""))
                            .forEach(line -> started.complete(null));
                } catch (Exception error) {
                    started.completeExceptionally(error);
                }
            });
            started.get(10, TimeUnit.SECONDS);
            preview = Thread.startVirtualThread(() -> {
                try {
                    LiveTranscription.follow(recording, engine, segments -> {
                        if (!segments.isEmpty()) previews.add(segments);
                    });
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Exception error) {
                    failure.completeExceptionally(error);
                }
            });
            long began = System.nanoTime();
            long deadline = began + TimeUnit.SECONDS.toNanos(30);
            player.getOutputStream().write('\n');
            player.getOutputStream().flush();
            var first = waitForPreview(previews, failure, deadline);
            var second = waitForPreview(previews, failure, deadline);
            check(first.getFirst().start() < 8_100 && second.getFirst().start() >= 8_000, "Live timestamps advance");
            check(active.isAlive() && recording.chunks().isEmpty(), "Both previews arrived while WAV was active");
            System.out.println("Live previews arrived before recording ended, in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) + " ms, "
                    + engine.model().getFileName());
            preview.interrupt();
            preview.join(5_000);
            check(!preview.isAlive(), "Live Whisper stops promptly");
            capture.getOutputStream().close();
            check(capture.waitFor(10, TimeUnit.SECONDS) && capture.exitValue() == 0, "Capture finished cleanly");
            check(!recording.chunks().isEmpty(), "Original WAV was finalized");
            try (var files = Files.list(directory)) {
                check(files.noneMatch(path -> path.getFileName().toString().startsWith("asr-live-")),
                        "Temporary preview files were cleaned");
            }
        } finally {
            if (preview != null) preview.interrupt();
            if (capture != null && capture.isAlive()) capture.destroyForcibly();
            if (events != null) events.interrupt();
            if (player.isAlive()) player.destroyForcibly();
        }
    }

    private static List<LocalProcessing.Segment> waitForPreview(
            LinkedBlockingQueue<List<LocalProcessing.Segment>> previews, CompletableFuture<Void> failure, long deadline)
            throws Exception {
        while (System.nanoTime() < deadline) {
            if (failure.isDone()) failure.get();
            var result = previews.poll(300, TimeUnit.MILLISECONDS);
            if (result != null) return result;
        }
        throw new AssertionError("No live transcript within 30 seconds.");
    }

    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
