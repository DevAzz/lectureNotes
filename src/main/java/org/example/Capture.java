package org.example;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

final class Capture {
    private static final long FILETIME_EPOCH_OFFSET_MILLIS = 11_644_473_600_000L;

    static Path helper() throws IOException {
        String override = System.getProperty("lecturenotes.audio");
        if (override != null && Files.isRegularFile(Path.of(override))) return Path.of(override).toAbsolutePath();
        try {
            Path location = Path.of(Capture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path bundled = location.resolveSibling("audio-capture.exe");
            if (Files.isRegularFile(bundled)) return bundled;
        } catch (URISyntaxException error) {
            throw new IOException("Не удалось определить путь к модулю аудио.", error);
        }
        Path built = Path.of("target", "native", "audio-capture.exe").toAbsolutePath();
        if (Files.isRegularFile(built)) return built;
        throw new IOException("Модуль audio-capture.exe не найден.");
    }

    static Process start(ProcessHandle browser, Path output) throws IOException {
        Instant created = browser.info().startInstant()
                .orElseThrow(() -> new IOException("Время запуска Chrome недоступно."));
        String fileTime = Long.toString(created.toEpochMilli() + FILETIME_EPOCH_OFFSET_MILLIS);
        return new ProcessBuilder(helper().toString(), "capture-ms", Long.toString(browser.pid()), fileTime,
                output.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static void recover(Path output) throws IOException, InterruptedException {
        Process repair = new ProcessBuilder(helper().toString(), "recover", output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!repair.waitFor(10, TimeUnit.SECONDS)) {
            repair.destroyForcibly();
            throw new IOException("Восстановление WAV превысило лимит времени.");
        }
        if (repair.exitValue() != 0) throw new IOException("Не удалось восстановить WAV.");
    }
}
