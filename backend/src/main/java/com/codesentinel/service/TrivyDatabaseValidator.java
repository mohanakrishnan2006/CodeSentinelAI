package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.model.ScannerFailureCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class TrivyDatabaseValidator {

    private static final long FUTURE_CLOCK_SKEW_SECONDS = 600;
    private static final long MAX_METADATA_BYTES = 64 * 1024;

    private final ScanWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public Path requireFreshDatabase() {
        try {
            Path root = Path.of(properties.getTrivyDatabaseDirectory()).toAbsolutePath().normalize();
            Path databaseDirectory = root.resolve("db");
            Path database = databaseDirectory.resolve("trivy.db");
            Path metadataFile = databaseDirectory.resolve("metadata.json");
            if (!isSafeDirectory(root) || !isSafeDirectory(databaseDirectory)
                    || !isSafeRegularFile(database) || !isSafeRegularFile(metadataFile)
                    || Files.size(metadataFile) > MAX_METADATA_BYTES) {
                throw unavailable();
            }
            JsonNode metadata = objectMapper.readTree(metadataFile.toFile());
            Instant updatedAt = parseInstant(metadata, "UpdatedAt");
            Instant nextUpdate = parseInstant(metadata, "NextUpdate");
            Instant now = Instant.now();
            if (updatedAt.isAfter(now.plusSeconds(FUTURE_CLOCK_SKEW_SECONDS))
                    || !nextUpdate.isAfter(now)
                    || !nextUpdate.isAfter(updatedAt)) {
                throw unavailable();
            }
            return root;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ScanExecutionException scanExecutionException) {
                throw scanExecutionException;
            }
            throw unavailable();
        }
    }

    private boolean isSafeDirectory(Path path) {
        return !Files.isSymbolicLink(path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isSafeRegularFile(Path path) {
        return !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private Instant parseInstant(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isTextual()) {
            throw unavailable();
        }
        try {
            return Instant.parse(value.asText());
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private ScanExecutionException unavailable() {
        return new ScanExecutionException(
                "Trivy vulnerability database is missing, invalid, or stale",
                ScannerFailureCode.DATABASE_UNAVAILABLE);
    }
}
