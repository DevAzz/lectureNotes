package org.example;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class InvitationSecret {
    private static final int MAX_PLAINTEXT = 8_192;
    private static final int MAX_BLOB = 16_384;

    static byte[] protect(URI link) throws IOException, InterruptedException {
        if (link == null) return null;
        byte[] data = link.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PLAINTEXT) throw new IOException("Ссылка на встречу слишком длинная.");
        return transform("protect-invitation", data);
    }

    static String unprotect(byte[] data) throws IOException, InterruptedException {
        if (data == null) return "";
        if (data.length > MAX_BLOB) throw new IOException("Не удалось открыть сохранённую ссылку.");
        return new String(transform("unprotect-invitation", data), StandardCharsets.UTF_8);
    }

    /** Секрет модели хранится так же, как приглашение, — только в виде DPAPI-блоба. */
    static byte[] protectText(String secret) throws IOException, InterruptedException {
        if (secret == null || secret.isBlank()) return null;
        byte[] data = secret.strip().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PLAINTEXT) throw new IOException("Слишком длинное секретное значение.");
        return transform("protect-invitation", data);
    }

    static String unprotectText(byte[] data) throws IOException, InterruptedException {
        if (data == null) return "";
        if (data.length > MAX_BLOB) throw new IOException("Не удалось открыть сохранённый секрет.");
        return new String(transform("unprotect-invitation", data), StandardCharsets.UTF_8);
    }

    private static byte[] transform(String operation, byte[] input) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(Capture.helper().toString(), operation)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var reply = new CompletableFuture<byte[]>();
        Thread.startVirtualThread(() -> {
            try (var output = process.getInputStream()) {
                reply.complete(output.readNBytes(MAX_BLOB + 1));
            } catch (IOException error) {
                reply.completeExceptionally(error);
            }
        });
        try {
            try (var pipe = process.getOutputStream()) { pipe.write(input); }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Windows не обработала защищённую ссылку.");
            }
            byte[] data = reply.get(5, TimeUnit.SECONDS);
            if (data.length == 0 || data.length > MAX_BLOB) throw new IOException("Неверный ответ DPAPI.");
            return data;
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Windows не обработала защищённую ссылку.", error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
