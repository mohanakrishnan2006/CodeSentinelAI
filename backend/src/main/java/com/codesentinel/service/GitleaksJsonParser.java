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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

@Component
@RequiredArgsConstructor
public class GitleaksJsonParser {

    private static final int MAX_FINDINGS = 10_000;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_RULE_ID_LENGTH = 256;
    private static final int MAX_LINE = 10_000_000;
    private static final String SAFE_MESSAGE = "Potential exposed secret detected.";

    private final ObjectMapper objectMapper;

    public ScanExecutionResult parse(String json) {
        if (json == null || json.isBlank()) {
            throw invalidOutput();
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (root == null || !root.isArray() || root.size() > MAX_FINDINGS) {
                throw invalidOutput();
            }
            List<NormalizedScanFinding> findings =
                    StreamSupport.stream(root.spliterator(), false).map(this::parseFinding).toList();
            return new ScanExecutionResult(findings);
        } catch (IOException ex) {
            throw new ScanExecutionException(
                    "Gitleaks returned malformed JSON", com.codesentinel.model.ScannerFailureCode.INVALID_OUTPUT);
        }
    }

    private NormalizedScanFinding parseFinding(JsonNode result) {
        String ruleId = requiredText(result, "RuleID", MAX_RULE_ID_LENGTH);
        String filePath = validateRelativePath(requiredText(result, "File", MAX_PATH_LENGTH));
        int line = requiredPositiveInt(result, "StartLine");
        int startColumn = optionalCoordinate(result, "StartColumn", 1);
        int endLine = optionalCoordinate(result, "EndLine", line);
        int endColumn = optionalCoordinate(result, "EndColumn", startColumn);
        if (endLine < line || (endLine == line && endColumn < startColumn)) {
            throw invalidOutput();
        }

        return new NormalizedScanFinding(
                ruleId,
                ScanFindingSeverity.HIGH,
                SAFE_MESSAGE,
                filePath,
                line,
                startColumn,
                endLine,
                endColumn,
                "GITLEAKS",
                Map.of());
    }

    private String requiredText(JsonNode node, String field, int maximumLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength
                || value.asText().chars().anyMatch(Character::isISOControl)) {
            throw invalidOutput();
        }
        return value.asText();
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        int coordinate = optionalCoordinate(node, field, -1);
        if (coordinate < 1) {
            throw invalidOutput();
        }
        return coordinate;
    }

    private int optionalCoordinate(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw invalidOutput();
        }
        int coordinate = value.intValue();
        if (coordinate < 1 || coordinate > MAX_LINE) {
            throw invalidOutput();
        }
        return coordinate;
    }

    private String validateRelativePath(String value) {
        String relativePath = value.startsWith("/src/") ? value.substring("/src/".length()) : value;
        if (relativePath.length() > MAX_PATH_LENGTH || relativePath.startsWith("/") || relativePath.startsWith("\\")
                || relativePath.contains("\\") || relativePath.contains(":")) {
            throw invalidOutput();
        }
        try {
            Path path = Path.of(relativePath);
            if (path.isAbsolute() || path.normalize().startsWith("..")
                    || java.util.Arrays.stream(relativePath.split("/", -1))
                    .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
                throw invalidOutput();
            }
        } catch (InvalidPathException ex) {
            throw invalidOutput();
        }
        return relativePath;
    }

    private ScanExecutionException invalidOutput() {
        return new ScanExecutionException(
                "Gitleaks returned invalid output", com.codesentinel.model.ScannerFailureCode.INVALID_OUTPUT);
    }
}
