package org.example;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

record MeetingBrowser(Process process, Path profile) implements AutoCloseable {
    static URI invitation(String link) {
        URI uri = URI.create(link.strip());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equals("salutejazz.ru") || host.endsWith(".salutejazz.ru"))) {
            throw new IllegalArgumentException("Введите HTTPS-ссылку на встречу в SaluteJazz.");
        }
        return uri;
    }

    static MeetingBrowser open(Path data, URI invitation) throws IOException, InterruptedException {
        Path executable = chrome();
        Path profile = Files.createTempDirectory(Files.createDirectories(data.resolve("browser")), "chrome-");
        MeetingBrowser browser = null;
        HttpServer redirect = null;
        try {
            Path preferences = Files.createDirectories(profile.resolve("Default")).resolve("Preferences");
            Files.writeString(preferences, """
                    {"profile":{"default_content_setting_values":{"media_stream_mic":2,"media_stream_camera":2}}}
                    """, StandardCharsets.UTF_8);
            // Keep the secret invitation out of command-line arguments and application files.
            redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
            String token = "/" + UUID.randomUUID();
            var claimed = new AtomicBoolean();
            var handedOff = new CompletableFuture<Void>();
            redirect.createContext(token, exchange -> {
                try (exchange) {
                    if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getRawPath().equals(token)
                            || !claimed.compareAndSet(false, true)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Location", invitation.toASCIIString());
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                    exchange.sendResponseHeaders(302, -1);
                }
                handedOff.complete(null);
            });
            redirect.start();
            Process chrome = new ProcessBuilder(executable.toString(), "--user-data-dir=" + profile,
                    "--incognito", "--no-first-run", "--no-default-browser-check", "--disable-background-mode",
                    "--auto-deny-permission-prompts", "--new-window",
                    "http://127.0.0.1:" + redirect.getAddress().getPort() + token)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            browser = new MeetingBrowser(chrome, profile);
            awaitHandoff(chrome, handedOff);
            return browser;
        } catch (IOException | InterruptedException | RuntimeException error) {
            try {
                if (browser == null) deleteProfile(profile);
                else browser.close();
            } catch (IOException | InterruptedException cleanup) {
                if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                error.addSuppressed(cleanup);
            }
            throw error;
        } finally {
            if (redirect != null) redirect.stop(0);
        }
    }

    private static void awaitHandoff(Process chrome, CompletableFuture<Void> handedOff)
            throws IOException, InterruptedException {
        try {
            CompletableFuture.anyOf(handedOff, chrome.onExit()).get(15, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            if (chrome.isAlive()) throw new IOException("Chrome не открыл приглашение за 15 секунд.");
        } catch (ExecutionException error) {
            throw new IOException("Не удалось проверить запуск Chrome.", error);
        }
        if (!chrome.isAlive()) {
            throw new IOException("Chrome завершился при открытии встречи (код " + chrome.exitValue() + ").");
        }
    }

    private static Path chrome() throws IOException {
        for (String name : new String[] {"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String root = System.getenv(name);
            if (root == null) continue;
            Path candidate = Path.of(root, "Google", "Chrome", "Application", "chrome.exe");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IOException("Google Chrome не найден.");
    }

    @Override public void close() throws IOException, InterruptedException {
        if (process.isAlive()) {
            var children = process.descendants().toList();
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Chrome продолжает работать.");
            }
            for (ProcessHandle child : children) if (child.isAlive()) child.destroy();
        }
        for (int attempt = 0; ; attempt++) {
            try { deleteProfile(profile); return; }
            catch (IOException error) {
                if (attempt == 4) throw error;
                Thread.sleep(200);
            }
        }
    }

    private static void deleteProfile(Path profile) throws IOException {
        try (var files = Files.walk(profile)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
