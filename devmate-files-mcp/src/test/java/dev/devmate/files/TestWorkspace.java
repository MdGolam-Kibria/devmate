package dev.devmate.files;

import dev.devmate.files.config.FilesProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.util.unit.DataSize;

/**
 * A small fake project on disk, plus decoys that the sandbox must hide: a secret, a build output directory, a
 * binary, an oversized file and a symlink that escapes the root.
 *
 * <pre>
 * root/
 *   README.md
 *   .env                                   (denied)
 *   src/main/java/com/acme/PaymentProducer.java   KafkaTemplate
 *   src/main/java/com/acme/PaymentService.java    KafkaTemplate
 *   src/main/java/com/acme/KafkaConfig.java       KafkaTemplate
 *   src/main/resources/application.yml
 *   target/Generated.java                  (excluded dir, contains KafkaTemplate)
 *   logo.png                               (binary)
 *   big.txt                                (> maxFileSize)
 *   escape.txt -> outside/secret.txt       (symlink out of the root)
 * outside/secret.txt
 * </pre>
 */
public record TestWorkspace(Path root, Path outside) {

    public static final DataSize MAX_FILE_SIZE = DataSize.ofKilobytes(4);
    public static final DataSize MAX_RANGE_FILE_SIZE = DataSize.ofKilobytes(64);

    public static TestWorkspace create(Path base) {
        try {
            Path root = Files.createDirectories(base.resolve("root"));
            Path outside = Files.createDirectories(base.resolve("outside"));
            write(root.resolve("README.md"), "# Acme payments\n");
            write(root.resolve(".env"), "DB_PASSWORD=KafkaTemplate-secret\n");
            Path pkg = root.resolve("src/main/java/com/acme");
            write(pkg.resolve("PaymentProducer.java"), """
                    package com.acme;

                    class PaymentProducer {
                        private final KafkaTemplate<String, String> template;
                    }
                    """);
            write(pkg.resolve("PaymentService.java"), "package com.acme;\n// uses kafkatemplate indirectly\n");
            write(pkg.resolve("KafkaConfig.java"), "package com.acme;\n@Bean KafkaTemplate<?, ?> kafkaTemplate() {}\n");
            write(root.resolve("src/main/resources/application.yml"), "spring.kafka.bootstrap-servers: x\n");
            write(root.resolve("target/Generated.java"), "KafkaTemplate generated\n");
            Files.write(root.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 'K'});
            write(root.resolve("big.txt"), "KafkaTemplate\n" + "x".repeat((int) MAX_FILE_SIZE.toBytes()));
            write(outside.resolve("secret.txt"), "KafkaTemplate outside the sandbox\n");
            Files.createSymbolicLink(root.resolve("escape.txt"), outside.resolve("secret.txt"));
            return new TestWorkspace(root.toRealPath(), outside.toRealPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FilesProperties properties() {
        return new FilesProperties(List.of(root), MAX_FILE_SIZE, 1000, 200, 20_000, 240,
                MAX_RANGE_FILE_SIZE, 5, 8, 2000,
                List.of(".git", "target"), List.of(".env", ".env.*", "*.pem", "*.key"), false);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
