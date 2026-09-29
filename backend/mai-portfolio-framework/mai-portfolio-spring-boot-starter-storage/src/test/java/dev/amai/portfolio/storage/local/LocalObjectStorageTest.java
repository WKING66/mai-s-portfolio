package dev.amai.portfolio.storage.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {
    @TempDir
    Path directory;

    @Test
    void storesAndReadsObjectInsideConfiguredDirectory() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(directory);
        byte[] content = "portfolio-image".getBytes(StandardCharsets.UTF_8);

        var stored = storage.store(new ObjectStorageWriteRequest(
            "images/2026/example.txt", new ByteArrayInputStream(content), content.length, "text/plain"));

        assertThat(stored.objectKey()).isEqualTo("images/2026/example.txt");
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(storage.exists(stored.objectKey())).isTrue();
        try (var object = storage.open(stored.objectKey())) {
            assertThat(object.byteSize()).isEqualTo(content.length);
            assertThat(object.inputStream().readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void refusesToOverwriteExistingObject() {
        LocalObjectStorage storage = new LocalObjectStorage(directory);
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        storage.store(new ObjectStorageWriteRequest(
            "immutable/object.bin", new ByteArrayInputStream(first), first.length, "application/octet-stream"));

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "immutable/object.bin", new ByteArrayInputStream(second), second.length,
            "application/octet-stream")))
            .isInstanceOf(ObjectAlreadyExistsException.class);
        try (var stored = storage.open("immutable/object.bin")) {
            assertThat(stored.inputStream().readAllBytes()).isEqualTo(first);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void rejectsTraversalAndAbsoluteKeys() {
        LocalObjectStorage storage = new LocalObjectStorage(directory);
        byte[] content = {1};

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "../outside.bin", new ByteArrayInputStream(content), content.length,
            "application/octet-stream")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.exists("C:/outside.bin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysWithLeadingOrTrailingWhitespaceInsteadOfChangingTheirTarget() {
        LocalObjectStorage storage = new LocalObjectStorage(directory);
        byte[] content = {1};

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            " images/object.bin", new ByteArrayInputStream(content), content.length,
            "application/octet-stream")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("images/object.bin "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removesTemporaryFileWhenDeclaredLengthDoesNotMatch() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(directory);
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "broken/object.bin", new ByteArrayInputStream(content), content.length + 1,
            "application/octet-stream")))
            .isInstanceOf(ObjectStorageException.class);
        assertThat(Files.exists(directory.resolve("broken/object.bin"))).isFalse();
        try (var files = Files.list(directory.resolve("broken"))) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
