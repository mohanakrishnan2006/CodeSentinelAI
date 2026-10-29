package com.codesentinel.service;

import com.codesentinel.config.UploadProperties;
import com.codesentinel.exception.InvalidZipException;
import com.codesentinel.exception.UnsafeArchiveEntryException;
import com.codesentinel.exception.UploadLimitExceededException;
import com.codesentinel.exception.UploadStorageException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadStorageServiceTest {

    @TempDir
    Path temporaryDirectory;

    private UploadProperties properties;
    private UploadStorageService storageService;

    @BeforeEach
    void setUp() {
        properties = new UploadProperties();
        properties.setStorageDirectory(temporaryDirectory.resolve("uploads"));
        storageService = new UploadStorageService(properties);
    }

    @Test
    @DisplayName("Extracts regular files into a generated directory and stores no executable files")
    void extractsFilesUnderGeneratedReference() throws Exception {
        UUID uploadId = UUID.randomUUID();
        MockMultipartFile multipartFile = zipFile("project.zip",
                new String[][]{{"src/Main.java", "class Main {}"}, {"README.md", "example"}});

        UploadStorageService.StoredUpload stored = storageService.store(multipartFile, uploadId);

        assertThat(stored.fileCount()).isEqualTo(2);
        assertThat(stored.totalSizeBytes()).isEqualTo(20);
        assertThat(stored.storageReference()).isEqualTo(uploadId.toString());
        assertThat(Files.readString(temporaryDirectory.resolve("uploads")
                .resolve(uploadId.toString()).resolve("src/Main.java"))).isEqualTo("class Main {}");
        assertThat(Files.exists(temporaryDirectory.resolve("uploads")
                .resolve(".work-" + uploadId))).isFalse();
    }

    @Test
    @DisplayName("Resolves only an existing upload directory under the configured root")
    void resolvesValidUploadDirectory() throws Exception {
        UUID uploadId = UUID.randomUUID();
        Path uploadDirectory = temporaryDirectory.resolve("uploads").resolve(uploadId.toString());
        Files.createDirectories(uploadDirectory);

        assertThat(storageService.resolveUploadDirectory(uploadId, uploadId.toString()))
                .isEqualTo(uploadDirectory.toRealPath());
    }

    @Test
    @DisplayName("Rejects storage references that could escape the configured root")
    void rejectsStoragePathTraversal() {
        UUID uploadId = UUID.randomUUID();

        assertThrows(UploadStorageException.class,
                () -> storageService.resolveUploadDirectory(uploadId, "..\\outside"));
    }

    @Test
    @DisplayName("Rejects missing upload directories")
    void rejectsMissingUploadDirectory() {
        UUID uploadId = UUID.randomUUID();

        assertThrows(UploadStorageException.class,
                () -> storageService.resolveUploadDirectory(uploadId, uploadId.toString()));
    }

    @Test
    @DisplayName("Rejects invalid archive bytes")
    void rejectsInvalidArchive() {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "invalid.zip", "application/zip", "not a zip".getBytes());

        assertThrows(InvalidZipException.class,
                () -> storageService.store(multipartFile, UUID.randomUUID()));
    }

    @Test
    @DisplayName("Rejects path traversal archive entries without writing outside the upload directory")
    void rejectsPathTraversal() throws Exception {
        UUID uploadId = UUID.randomUUID();

        assertThrows(UnsafeArchiveEntryException.class,
                () -> storageService.store(zipFile("traversal.zip",
                        new String[][]{{"../outside.txt", "unsafe"}}), uploadId));

        assertThat(Files.exists(temporaryDirectory.resolve("outside.txt"))).isFalse();
        assertThat(Files.exists(temporaryDirectory.resolve("uploads").resolve(uploadId.toString()))).isFalse();
        try (var remaining = Files.list(temporaryDirectory.resolve("uploads"))) {
            assertThat(remaining.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("Rejects archives with too many files")
    void rejectsTooManyFiles() throws Exception {
        properties.setMaxFiles(1);
        UUID uploadId = UUID.randomUUID();

        assertThrows(UploadLimitExceededException.class,
                () -> storageService.store(zipFile("many.zip",
                        new String[][]{{"one.txt", "1"}, {"two.txt", "2"}}), uploadId));
    }

    @Test
    @DisplayName("Rejects archives exceeding the extracted-size limit")
    void rejectsExtractedSizeLimit() throws Exception {
        properties.setMaxTotalSizeBytes(5);

        assertThrows(UploadLimitExceededException.class,
                () -> storageService.store(zipFile("large-entry.zip",
                        new String[][]{{"large.txt", "123456"}}), UUID.randomUUID()));
    }

    @Test
    @DisplayName("Rejects an individual extracted file over its configured limit")
    void rejectsIndividualFileSizeLimit() throws Exception {
        properties.setMaxFileSizeBytes(5);

        assertThrows(UploadLimitExceededException.class,
                () -> storageService.store(zipFile("large-file.zip",
                        new String[][]{{"large.txt", "123456"}}), UUID.randomUUID()));
    }

    @Test
    @DisplayName("Rejects uploads exceeding the compressed archive limit")
    void rejectsOversizedArchive() throws Exception {
        properties.setMaxArchiveSizeBytes(8);

        assertThrows(UploadLimitExceededException.class,
                () -> storageService.store(zipFile("oversized.zip",
                        new String[][]{{"file.txt", "content"}}), UUID.randomUUID()));
    }

    @Test
    @DisplayName("Rejects Unix symbolic links")
    void rejectsSymbolicLink() throws Exception {
        byte[] archive = symbolicLinkArchive();
        MockMultipartFile multipartFile =
                new MockMultipartFile("file", "links.zip", "application/zip", archive);

        assertThrows(UnsafeArchiveEntryException.class,
                () -> storageService.store(multipartFile, UUID.randomUUID()));
    }

    @Test
    @DisplayName("Reports storage failures without retaining temporary upload content")
    void reportsStorageFailure() throws Exception {
        Path storagePath = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(storagePath, "file");
        properties.setStorageDirectory(storagePath);

        assertThrows(UploadStorageException.class,
                () -> storageService.store(zipFile("valid.zip",
                        new String[][]{{"file.txt", "content"}}), UUID.randomUUID()));
        assertThat(Files.readString(storagePath)).isEqualTo("file");
    }

    @Test
    @DisplayName("Rejects duplicate paths ignoring case")
    void rejectsDuplicatePathWithDifferentCase() throws Exception {
        byte[] archive = duplicateNamesArchive();
        MockMultipartFile multipartFile =
                new MockMultipartFile("file", "duplicates.zip", "application/zip", archive);

        assertThrows(UnsafeArchiveEntryException.class,
                () -> storageService.store(multipartFile, UUID.randomUUID()));
    }

    @Test
    @DisplayName("Rejects executable file types")
    void rejectsExecutableFileTypes() throws Exception {
        assertThrows(UnsafeArchiveEntryException.class,
                () -> storageService.store(zipFile("executable.zip",
                        new String[][]{{"program.exe", "not executed"}}), UUID.randomUUID()));
    }

    private MockMultipartFile zipFile(String filename, String[][] files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes());
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", filename, "application/zip", bytes.toByteArray());
    }

    private byte[] symbolicLinkArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("link");
            entry.setUnixMode(0120777);
            zip.putArchiveEntry(entry);
            zip.write("target".getBytes());
            zip.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] duplicateNamesArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            for (String name : new String[]{"src/Main.java", "SRC/main.java"}) {
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                zip.putArchiveEntry(entry);
                zip.write("same".getBytes());
                zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
