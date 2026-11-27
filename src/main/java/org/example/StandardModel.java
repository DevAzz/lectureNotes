package org.example;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;

enum StandardModel {
    TURBO("Whisper Large v3 Turbo Q5_0", "ggml-large-v3-turbo-q5_0.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
    FULL("Whisper Large v3 FP16", "ggml-large-v3.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/fb5ed9369c26eaf08392504d8446da431e0fffb9/ggml-large-v3.bin",
            "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
    SEGMENTATION("pyannote Segmentation 3.0", "segmentation.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"),
    EMBEDDING("3D-Speaker ERes2Net", "embedding.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            "f1ee61be04000b50392a63442e5dd9feaea7e81dddd494d3647cf1c3d88a1498");

    final String title;
    final String filename;
    private final URI source;
    private final String checksum;

    StandardModel(String title, String filename, String source, String checksum) {
        this.title = title;
        this.filename = filename;
        this.source = URI.create(source);
        this.checksum = checksum;
    }

    Path path() {
        return LibraryLocation.forCurrentUser().defaultDirectory().resolve("models").resolve(filename);
    }

    Path preferred(Path legacyDirectory) {
        Path legacy = legacyDirectory.resolve(filename);
        return !Files.isRegularFile(path()) && Files.isRegularFile(legacy) ? legacy : path();
    }

    Path download(LongConsumer progress) throws IOException, InterruptedException {
        Path target = path();
        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), filename, ".part");
        try {
            var connection = source.toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
            try (var input = new DigestInputStream(connection.getInputStream(), digest);
                 var output = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[128 * 1024];
                long bytes = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    output.write(buffer, 0, count);
                    bytes += count;
                    progress.accept(bytes);
                }
            }
            if (!checksum.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("Контрольная сумма SHA-256 не совпала.");
            }
            if (this == SEGMENTATION) unpackSegmentation(partial, target);
            else Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static void unpackSegmentation(Path archive, Path target) throws IOException, InterruptedException {
        String folder = "sherpa-onnx-pyannote-segmentation-3-0";
        Path work = Files.createTempDirectory(target.getParent(), "pyannote-");
        Path model = work.resolve("model.onnx");
        Path license = work.resolve("LICENSE");
        try {
            var process = new ProcessBuilder("tar.exe", "-xf", archive.toString(), "-C", work.toString(),
                    "--strip-components", "1", folder + "/model.onnx", folder + "/LICENSE").start();
            try {
                if (process.waitFor() != 0) throw new IOException("Не удалось распаковать модель pyannote.");
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
            Files.move(license, target.resolveSibling("LICENSE-pyannote.txt"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(model, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(license);
            Files.deleteIfExists(work);
        }
    }
}
