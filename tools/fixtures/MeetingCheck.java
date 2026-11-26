package org.example;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.example.SaluteJazzPage.State;

public class MeetingCheck {
    private static final URI INVITATION = MeetingBrowser.invitation("https://salutejazz.ru/test-meeting");
    private static final String ROOM = """
            <button aria-label="Выйти из встречи">×</button>
            <button>Участники</button>
            """;
    private static final String GUEST_ROOM = """
            <nav><button>Войти</button></nav>
            <header><div>Видеовстреча</div><div>01:34</div></header>
            """;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
        checkScreens();
        checkBrowser(work);
        System.out.println("Meeting screen detection, incoming WebRTC sound, media permissions and profile cleanup verified.");
    }

    private static void checkScreens() {
        var options = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        try (var playwright = Playwright.create(options);
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"))) {
            var page = browser.newPage();
            page.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                    .setContentType("text/html; charset=utf-8").setBody("<main></main>")));
            page.navigate(INVITATION.toString());
            page.setContent("<button>Подключиться</button>");
            expect(State.PREJOIN, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<p role=status>Пожалуйста, ожидайте. Организатор скоро вас впустит</p>" + ROOM);
            expect(State.WAITING_ADMISSION, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<input type=password>" + ROOM);
            expect(State.AUTHENTICATING, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<button>Участники</button>");
            expect(State.WAITING_USER, SaluteJazzPage.observe(page, INVITATION));
            page.setContent(ROOM);
            expect(State.IN_CALL, SaluteJazzPage.observe(page, INVITATION));
            page.setContent(ROOM + "<p>Встреча завершена</p>");
            expect(State.IN_CALL, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<p role=status>Восстанавливаем соединение…</p>" + ROOM);
            expect(State.RECONNECTING, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<div role=dialog><h2>Организатор завершил встречу</h2></div>" + ROOM);
            expect(State.ENDED, SaluteJazzPage.observe(page, INVITATION));
            page.setContent("<p role=alert>Организатор исключил вас из зала ожидания</p>");
            expect(State.DENIED, SaluteJazzPage.observe(page, INVITATION));
            page.navigate("https://other.salutejazz.ru/test-meeting");
            page.setContent(ROOM);
            expect(State.AUTHENTICATING, SaluteJazzPage.observe(page, INVITATION));

            var guestInvitation = MeetingBrowser.invitation("https://salutejazz.ru/calls/test");
            page.navigate(guestInvitation.toString());
            page.setContent(GUEST_ROOM);
            expect(State.IN_CALL, SaluteJazzPage.observe(page, guestInvitation));
            var oldInvitation = MeetingBrowser.invitation("https://jazz.sber.ru/calls/test");
            expect(State.IN_CALL, SaluteJazzPage.observe(page, oldInvitation));
            page.setContent(GUEST_ROOM + "<button>Подключиться</button>");
            expect(State.PREJOIN, SaluteJazzPage.observe(page, guestInvitation));
            page.setContent(GUEST_ROOM + "<input type=email>");
            expect(State.AUTHENTICATING, SaluteJazzPage.observe(page, guestInvitation));
            page.setContent("<nav><button>Войти</button></nav><header>Семинар</header>");
            expect(State.WAITING_USER, SaluteJazzPage.observe(page, guestInvitation));
            page.navigate("https://salutejazz.ru/");
            page.setContent(GUEST_ROOM);
            expect(State.WAITING_USER, SaluteJazzPage.observe(page, guestInvitation));
            page.navigate("https://other.salutejazz.ru/calls/test");
            page.setContent(GUEST_ROOM);
            expect(State.AUTHENTICATING, SaluteJazzPage.observe(page, guestInvitation));
        }
    }

    private static void checkBrowser(Path work) throws Exception {
        var received = new CompletableFuture<String>();
        var playing = new CompletableFuture<Void>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/permission", exchange -> {
            try (exchange) {
                received.complete(exchange.getRequestURI().getQuery());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/playing", exchange -> {
            try (exchange) {
                playing.complete(null);
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = """
                        <!doctype html><button>Play tone</button><audio autoplay></audio><script type="module">
                        async function localDescription(peer, description) {
                            await peer.setLocalDescription(description);
                            if (peer.iceGatheringState !== "complete") {
                                await new Promise(resolve => {
                                    peer.addEventListener("icegatheringstatechange", () => {
                                        if (peer.iceGatheringState === "complete") resolve();
                                    });
                                });
                            }
                            return peer.localDescription;
                        }
                        document.querySelector("button").addEventListener("click", async () => {
                            const audio = new AudioContext();
                            const tone = audio.createOscillator();
                            const source = audio.createMediaStreamDestination();
                            const gain = audio.createGain();
                            gain.gain.value = 0.1;
                            tone.connect(gain).connect(source);
                            await audio.resume();
                            const sender = new RTCPeerConnection();
                            const receiver = new RTCPeerConnection();
                            const output = document.querySelector("audio");
                            receiver.ontrack = event => { output.srcObject = event.streams[0]; };
                            sender.addTrack(source.stream.getAudioTracks()[0], source.stream);
                            await receiver.setRemoteDescription(await localDescription(sender, await sender.createOffer()));
                            await sender.setRemoteDescription(await localDescription(receiver, await receiver.createAnswer()));
                            tone.start();
                            tone.stop(audio.currentTime + 4);
                            await output.play();
                            await fetch("/playing");
                        });
                        const mic = await navigator.permissions.query({name:"microphone"});
                        const camera = await navigator.permissions.query({name:"camera"});
                        await fetch("/permission?mic=" + mic.state + "&camera=" + camera.state);
                        </script>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            URI localPage = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            try (var meeting = MeetingBrowser.open(work, localPage)) {
                expect("mic=denied&camera=denied", received.get(10, TimeUnit.SECONDS));
                expect(State.AUTHENTICATING, meeting.observe());
                var recording = Files.createDirectory(work.resolve("recording"));
                var helper = Capture.start(meeting.process(), recording);
                try {
                    var started = CompletableFuture.supplyAsync(() -> helper.inputReader().lines().findFirst().orElse(""));
                    expect("{\"event\":\"started\",\"pid\":" + meeting.process().pid() + "}",
                            started.get(10, TimeUnit.SECONDS));
                    meeting.controlledPage().getByRole(AriaRole.BUTTON).click();
                    playing.get(10, TimeUnit.SECONDS);
                    Thread.sleep(1_500);
                    helper.getOutputStream().close();
                    if (!helper.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Audio helper did not stop.");
                    expect(0, helper.exitValue());
                    var chunks = new Recording(recording).chunks();
                    if (chunks.isEmpty() || Recording.isDigitalSilence(chunks.getFirst().path())) {
                        throw new AssertionError("WASAPI did not record the tone from the isolated Chrome.");
                    }
                    if (!meeting.process().isAlive()) throw new AssertionError("Stopping capture closed Chrome.");
                } finally {
                    if (helper.isAlive()) helper.destroyForcibly();
                }
            }
            try (var profiles = Files.list(work.resolve("browser"))) {
                if (profiles.findAny().isPresent()) throw new AssertionError("Temporary browser profile was retained.");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void expect(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
