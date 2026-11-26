package org.example;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// All Playwright operations for a meeting run on the same worker thread.
final class MeetingBrowser implements AutoCloseable {
    private final Playwright playwright;
    private final BrowserContext context;
    private final Page page;
    private final Path profile;
    private final URI origin;
    private final ProcessHandle process;

    private MeetingBrowser(Playwright playwright, BrowserContext context, Page page, Path profile,
                           URI origin, ProcessHandle process) {
        this.playwright = playwright;
        this.context = context;
        this.page = page;
        this.profile = profile;
        this.origin = origin;
        this.process = process;
    }

    static URI invitation(String link) {
        URI uri = URI.create(link.strip());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.equals("salutejazz.ru") || host.endsWith(".salutejazz.ru")
                     || host.equals("jazz.sber.ru"))) {
            throw new IllegalArgumentException("Введите HTTPS-ссылку на встречу в SaluteJazz.");
        }
        return uri;
    }

    static MeetingBrowser open(Path data, URI invitation) throws IOException, InterruptedException {
        Path executable = chrome();
        Path profile = Files.createTempDirectory(Files.createDirectories(data.resolve("browser")), "chrome-");
        Playwright playwright = null;
        try {
            Path preferences = Files.createDirectories(profile.resolve("Default")).resolve("Preferences");
            Files.writeString(preferences, """
                    {"profile":{"default_content_setting_values":{"media_stream_mic":2,"media_stream_camera":2}}}
                    """, StandardCharsets.UTF_8);
            playwright = startDriver();
            var options = new BrowserType.LaunchPersistentContextOptions()
                    .setExecutablePath(executable).setHeadless(false).setChromiumSandbox(true)
                    .setLocale("ru-RU").setViewportSize(null).setAcceptDownloads(false).setTimeout(15_000)
                    .setIgnoreDefaultArgs(List.of("--mute-audio", "--unsafely-disable-devtools-self-xss-warnings"))
                    .setArgs(List.of("--incognito", "--disable-background-mode", "--enable-automation",
                            "--auto-deny-permission-prompts"));
            Instant launching = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            BrowserContext context = playwright.chromium().launchPersistentContext(profile, options);
            ProcessHandle process = captureTarget(context, executable, profile, launching);
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
            page.setDefaultTimeout(1_000);
            var session = new MeetingBrowser(playwright, context, page, profile, invitation, process);
            // Navigation travels over Playwright's pipe. The invitation never enters Chrome's command line.
            try {
                page.navigate(invitation.toASCIIString(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.COMMIT).setTimeout(15_000));
            } catch (PlaywrightException error) {
                if (!process.isAlive() || page.isClosed()) throw new IOException("Chrome закрылся при открытии встречи.");
            }
            return session;
        } catch (IOException | RuntimeException error) {
            try {
                if (playwright != null) playwright.close();
            } catch (PlaywrightException ignored) {
                // Playwright error messages can contain the invitation.
            }
            try { deleteProfile(profile); }
            catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
            if (error instanceof IOException failure) throw failure;
            throw new IOException("Не удалось запустить отдельный Chrome через Playwright.");
        }
    }

    ProcessHandle process() {
        return process;
    }

    Page controlledPage() {
        return page;
    }

    static Playwright startDriver() {
        return Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1", "DEBUG", "", "PWDEBUG", "0")));
    }

    SaluteJazzPage.State observe() {
        if (!process.isAlive()) return SaluteJazzPage.State.CLOSED;
        try {
            // The Java API dispatches browser events while a Playwright call is in progress.
            page.waitForTimeout(1);
            if (page.isClosed()) return SaluteJazzPage.State.CLOSED;
            return SaluteJazzPage.observe(page, origin);
        } catch (PlaywrightException error) {
            return page.isClosed() || !process.isAlive()
                    ? SaluteJazzPage.State.CLOSED : SaluteJazzPage.State.AUTHENTICATING;
        }
    }

    private static ProcessHandle captureTarget(BrowserContext context, Path executable, Path profile, Instant launching)
            throws IOException {
        var cdp = context.browser().newBrowserCDPSession();
        try {
            boolean verifiedProfile = false;
            for (var argument : cdp.send("Browser.getBrowserCommandLine").getAsJsonArray("arguments")) {
                String value = argument.getAsString();
                if (value.equals("--user-data-dir=" + profile)) verifiedProfile = true;
                if (value.startsWith("--type=")) throw new IOException("Chrome сообщил PID дочернего процесса.");
            }
            var roots = new ArrayList<Long>();
            for (var item : cdp.send("SystemInfo.getProcessInfo").getAsJsonArray("processInfo")) {
                var info = item.getAsJsonObject();
                if ("browser".equals(info.get("type").getAsString())) roots.add(info.get("id").getAsLong());
            }
            if (verifiedProfile && roots.size() == 1) {
                ProcessHandle process = ProcessHandle.of(roots.getFirst()).orElse(null);
                if (process != null && process.isAlive()) {
                    ProcessHandle.Info info = process.info();
                    if (info.command().filter(path -> executable.toString().equalsIgnoreCase(path)).isPresent()
                            && info.startInstant().filter(created -> !created.isBefore(launching)).isPresent()) {
                        return process;
                    }
                }
            }
            throw new IOException("Не удалось подтвердить PID отдельного Chrome для записи.");
        } finally {
            cdp.detach();
        }
    }

    private static Path chrome() throws IOException {
        for (String name : new String[] {"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String root = System.getenv(name);
            if (root == null) continue;
            Path candidate = Path.of(root, "Google", "Chrome", "Application", "chrome.exe");
            if (Files.isRegularFile(candidate)) return candidate.toRealPath();
        }
        throw new IOException("Google Chrome не найден.");
    }

    @Override public void close() throws IOException, InterruptedException {
        try {
            try { context.close(); }
            finally { playwright.close(); }
        } catch (PlaywrightException ignored) {
            // The user may have closed the window. Verify the process below.
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.onExit().get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                    throw new IOException("Chrome продолжает работать.");
                }
            }
            deleteProfile(profile);
        }
    }

    private static void deleteProfile(Path profile) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try (var files = Files.walk(profile)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                return;
            } catch (IOException error) {
                if (attempt == 4) throw error;
                Thread.sleep(200);
            }
        }
    }
}
