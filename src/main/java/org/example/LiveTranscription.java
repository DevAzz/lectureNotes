package org.example;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

final class LiveTranscription {
    @FunctionalInterface
    interface SegmentSink {
        void accept(List<LocalProcessing.Segment> segments) throws IOException;
    }

    private static final long WINDOW_FRAMES = 8L * Recording.SAMPLE_RATE;
    private static final long MIN_TAIL_FRAMES = Recording.SAMPLE_RATE;

    private record Window(Recording.Chunk chunk, long start, long frames) {
        long end() { return start + frames; }
    }

    static void follow(Recording recording, LocalProcessing.Whisper selected,
                       SegmentSink publish)
            throws IOException, InterruptedException {
        var fallback = LocalProcessing.cpuFallback(selected);
        var engine = fallback != null && selected.cpu() ? fallback : selected;
        if (!engine.available()) throw new IOException("Движок или модель Whisper недоступны.");

        Path work = Files.createTempDirectory(recording.directory(), "asr-live-").toAbsolutePath();
        boolean nativeWav = LocalProcessing.supportsNativeWav(engine);
        Path sample = work.resolve("sample.wav");
        Path pcm = work.resolve("pcm.wav");
        Path output = work.resolve("preview");
        Path srt = work.resolve("preview.srt");
        try {
            long nextFrame = 0;
            while (!Thread.currentThread().isInterrupted()) {
                Window window = nextWindow(recording, nextFrame);
                if (window == null) {
                    Thread.sleep(300);
                    continue;
                }
                try {
                    snapshot(window, sample);
                } catch (NoSuchFileException error) {
                    // A full .part file may have been finalized after the scan.
                    continue;
                }
                nextFrame = window.end();
                if (Recording.isDigitalSilence(sample)) {
                    publish.accept(List.of());
                    continue;
                }
                Files.deleteIfExists(srt);
                if (!nativeWav) {
                    try {
                        LocalProcessing.convertToWhisperPcm(sample, pcm);
                    } catch (javax.sound.sampled.UnsupportedAudioFileException error) {
                        throw new IOException("Whisper не смог открыть фрагмент записи.", error);
                    }
                }
                engine = LocalProcessing.runWhisper(engine, fallback, nativeWav ? sample : pcm, output,
                        Duration.ofSeconds(25));
                var segments = Files.exists(srt)
                        ? LocalProcessing.parseSrt(Files.readString(srt),
                            millis(window.start()), millis(window.end()))
                        : List.<LocalProcessing.Segment>of();
                publish.accept(LocalProcessing.containsSpeech(segments) ? segments : List.of());
            }
        } finally {
            Files.deleteIfExists(srt);
            Files.deleteIfExists(pcm);
            Files.deleteIfExists(sample);
            Files.deleteIfExists(work);
        }
    }

    private static Window nextWindow(Recording recording, long nextFrame) throws IOException {
        for (var chunk : recording.liveChunks()) {
            long start = Math.max(chunk.startFrame(), nextFrame);
            long available = chunk.startFrame() + chunk.frames() - start;
            if (available >= WINDOW_FRAMES) return new Window(chunk, start, WINDOW_FRAMES);
            if (available <= 0) continue;
            if (chunk.partialFile()) return null;
            if (available >= MIN_TAIL_FRAMES) return new Window(chunk, start, available);
        }
        return null;
    }

    private static void snapshot(Window window, Path target) throws IOException {
        try (var source = FileChannel.open(window.chunk().path(), StandardOpenOption.READ);
             var output = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            var header = ByteBuffer.allocate(Recording.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (source.read(header) == -1) throw new EOFException("Неполный заголовок WAV.");
            }
            int dataBytes = Math.toIntExact(window.frames() * Recording.FRAME_BYTES);
            header.putInt(4, Recording.HEADER_BYTES - 8 + dataBytes);
            header.putInt(40, dataBytes);
            header.flip();
            while (header.hasRemaining()) output.write(header);

            long position = Recording.HEADER_BYTES
                    + (window.start() - window.chunk().startFrame()) * Recording.FRAME_BYTES;
            long bytesLeft = dataBytes;
            while (bytesLeft > 0) {
                long moved = source.transferTo(position, bytesLeft, output);
                if (moved == 0) throw new EOFException("Не удалось прочитать фрагмент WAV.");
                position += moved;
                bytesLeft -= moved;
            }
        }
    }

    private static long millis(long frames) {
        return frames * 1000 / Recording.SAMPLE_RATE;
    }
}
