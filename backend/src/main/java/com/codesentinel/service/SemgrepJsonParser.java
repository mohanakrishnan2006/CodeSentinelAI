package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SemgrepJsonParser {

    private static final int MAX_FINDINGS = 10_000;
    private static final int MAX_RULE_ID_LENGTH = 256;
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_METADATA_FIELDS = 16;
    private static final int MAX_METADATA_VALUE_LENGTH = 512;
    private static final int MAX_COORDINATE = 10_000_000;
    private static final Set<String> SAFE_METADATA_KEYS =
            Set.of("category", "cwe", "owasp", "technology", "references", "confidence");

    private final ObjectMapper objectMapper;

    public ScanExecutionResult parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ScanExecutionException("Semgrep returned an empty result");
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            JsonNode results = root == null ? null : root.get("results");
            if (results == null || !results.isArray() || results.size() > MAX_FINDINGS) {
                throw new ScanExecutionException("Semgrep returned an invalid result list");
            }
            JsonNode errors = root.get("errors");
            if (errors != null && (!errors.isArray() || !errors.isEmpty())) {
                throw new ScanExecutionException("Semgrep reported an incomplete scan result");
            }

            return new ScanExecutionResult(
                    java.util.stream.StreamSupport.stream(results.spliterator(), false)
                            .map(this::parseFinding)
                            .toList());
        } catch (IOException ex) {
            throw new ScanExecutionException("Semgrep returned malformed JSON");
        }
    }

    private NormalizedScanFinding parseFinding(JsonNode result) {
        String ruleId = requiredText(result, "check_id", MAX_RULE_ID_LENGTH);
        String filePath = validateRelativePath(requiredText(result, "path", MAX_PATH_LENGTH));
        JsonNode extra = requiredObject(result, "extra");
        String message = requiredText(extra, "message", MAX_MESSAGE_LENGTH);
        ScanFindingSeverity severity = parseSeverity(requiredText(extra, "severity", 20));
        JsonNode start = requiredObject(result, "start");
        JsonNode end = requiredObject(result, "end");
        int startLine = requiredCoordinate(start, "line");
        int startColumn = requiredCoordinate(start, "col");
        int endLine = requiredCoordinate(end, "line");
        int endColumn = requiredCoordinate(end, "col");
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            throw new ScanExecutionException("Semgrep returned invalid finding locations");
        }

        JsonNode metadata = extra.get("metadata");
        return new NormalizedScanFinding(
                ruleId,
                severity,
                message,
                filePath,
                startLine,
                startColumn,
                endLine,
                endColumn,
                "SEMGREP",
                parseMetadata(metadata));
    }

    private Map<String, String> parseMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) {
            return Map.of();
        }
        if (!metadata.isObject()) {
            throw new ScanExecutionException("Semgrep returned invalid metadata");
        }

        Map<String, String> safeValues = new LinkedHashMap<>();
        metadata.fields().forEachRemaining(entry -> {
            if (!SAFE_METADATA_KEYS.contains(entry.getKey()) || safeValues.size() >= MAX_METADATA_FIELDS) {
                return;
            }
            String value = metadataValue(entry.getValue());
            if (value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new ScanExecutionException("Semgrep returned oversized metadata");
            }
            safeValues.put(entry.getKey(), value);
        });
        return safeValues;
    }

    private String metadataValue(JsonNode value) {
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        if (value.isArray()) {
            StringBuilder combined = new StringBuilder();
            for (JsonNode item : value) {
                if (!item.isTextual() && !item.isNumber() && !item.isBoolean()) {
                    throw new ScanExecutionException("Semgrep returned unsupported metadata");
                }
                if (!combined.isEmpty()) {
                    combined.append(", ");
                }
                combined.append(item.asText());
                if (combined.length() > MAX_METADATA_VALUE_LENGTH) {
                    throw new ScanExecutionException("Semgrep returned oversized metadata");
                }
            }
            return combined.toString();
        }
        throw new ScanExecutionException("Semgrep returned unsupported metadata");
    }

    private ScanFindingSeverity parseSeverity(String value) {
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> ScanFindingSeverity.CRITICAL;
            case "ERROR" -> ScanFindingSeverity.HIGH;
            case "WARNING" -> ScanFindingSeverity.MEDIUM;
            case "INFO" -> ScanFindingSeverity.INFO;
            default -> throw new ScanExecutionException("Semgrep returned an unknown severity");
        };
    }

    private String validateRelativePath(String value) {
        if (value.length() > MAX_PATH_LENGTH || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.contains(":")) {
            throw new ScanExecutionException("Semgrep returned an invalid source path");
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute() || path.normalize().startsWith("..")
                    || java.util.Arrays.stream(value.split("/", -1))
                    .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
                throw new ScanExecutionException("Semgrep returned an invalid source path");
            }
        } catch (InvalidPathException ex) {
            throw new ScanExecutionException("Semgrep returned an invalid source path");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field, int maximumLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength) {
            throw new ScanExecutionException("Semgrep returned an invalid finding");
        }
        return value.asText();
    }

    private JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new ScanExecutionException("Semgrep returned an invalid finding");
        }
        return value;
    }

    private int requiredCoordinate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new ScanExecutionException("Semgrep returned an invalid finding location");
        }
        int coordinate = value.intValue();
        if (coordinate < 1 || coordinate > MAX_COORDINATE) {
            throw new ScanExecutionException("Semgrep returned an invalid finding location");
        }
        return coordinate;
    }
}
