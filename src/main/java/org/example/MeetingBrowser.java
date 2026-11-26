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
import java.util.concurrent.TimeUnit;

record MeetingBrowser(Process process, Path profile, HttpServer redirect) implements AutoCloseable {
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

    static MeetingBrowser open(Path data, URI invitation) throws IOException {
        Path profile = Files.createTempDirectory(Files.createDirectories(data.resolve("browser")), "chrome-");
        HttpServer redirect = null;
        try {
            Path preferences = Files.createDirectories(profile.resolve("Default")).resolve("Preferences");
            Files.writeString(preferences, """
                    {"profile":{"default_content_setting_values":{"media_stream_mic":2,"media_stream_camera":2}}}
                    """, StandardCharsets.UTF_8);
            // Keep the secret invitation out of command-line arguments and application files.
            redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
            String token = "/" + UUID.randomUUID();
            HttpServer server = redirect;
            redirect.createContext(token, exchange -> {
                try (exchange) {
                    if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals(token)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Location", invitation.toASCIIString());
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                    exchange.sendResponseHeaders(302, -1);
                } finally {
                    Thread.startVirtualThread(() -> server.stop(0));
                }
            });
            redirect.start();
            Process chrome = new ProcessBuilder(chrome().toString(), "--user-data-dir=" + profile,
                    "--incognito", "--no-first-run", "--no-default-browser-check", "--disable-background-mode",
                    "--auto-deny-permission-prompts", "--new-window",
                    "http://127.0.0.1:" + redirect.getAddress().getPort() + token)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return new MeetingBrowser(chrome, profile, redirect);
        } catch (IOException | RuntimeException error) {
            if (redirect != null) redirect.stop(0);
            try { deleteProfile(profile); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
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
        redirect.stop(0);
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
