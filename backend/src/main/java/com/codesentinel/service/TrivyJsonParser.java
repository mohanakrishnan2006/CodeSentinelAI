package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class TrivyJsonParser {

    private static final int MAX_RESULTS = 10_000;
    private static final int MAX_FINDINGS = 10_000;
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_PATH_LENGTH = 1_024;

    private final ObjectMapper objectMapper;

    public ScanExecutionResult parse(String json) {
        if (json == null || json.isBlank()) {
            throw invalidResult();
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            JsonNode schemaVersion = root == null ? null : root.get("SchemaVersion");
            JsonNode results = root == null ? null : root.get("Results");
            JsonNode errors = root == null ? null : root.get("Errors");
            if (root == null || !root.isObject()
                    || schemaVersion == null || !schemaVersion.canConvertToInt()
                    || schemaVersion.intValue() != 2
                    || results == null || !results.isArray() || results.size() > MAX_RESULTS
                    || (errors != null && (!errors.isArray() || !errors.isEmpty()))) {
                throw invalidResult();
            }

            List<NormalizedScanFinding> findings = new ArrayList<>();
            for (JsonNode result : results) {
                if (!result.isObject()) {
                    throw invalidResult();
                }
                JsonNode vulnerabilities = result.get("Vulnerabilities");
                if (vulnerabilities == null || vulnerabilities.isNull()) {
                    continue;
                }
                if (!vulnerabilities.isArray()) {
                    throw invalidResult();
                }
                String path = normalizeTarget(requiredText(result, "Target", MAX_PATH_LENGTH));
                for (JsonNode vulnerability : vulnerabilities) {
                    if (findings.size() >= MAX_FINDINGS) {
                        throw invalidResult();
                    }
                    findings.add(parseFinding(vulnerability, path));
                }
            }
            return new ScanExecutionResult(findings);
        } catch (IOException ex) {
            throw invalidResult();
        }
    }

    private NormalizedScanFinding parseFinding(JsonNode vulnerability, String path) {
        String ruleId = requiredText(vulnerability, "VulnerabilityID", MAX_ID_LENGTH);
        ScanFindingSeverity severity = parseSeverity(requiredText(vulnerability, "Severity", 32));
        return new NormalizedScanFinding(
                ruleId,
                severity,
                "Dependency vulnerability detected.",
                path,
                null,
                null,
                null,
                null,
                "TRIVY",
                java.util.Map.of());
    }

    private String normalizeTarget(String target) {
        String path = target;
        if (path.startsWith("/src/")) {
            path = path.substring("/src/".length());
        } else if (path.startsWith("/")) {
            throw invalidResult();
        }
        if (path.isBlank() || path.length() > MAX_PATH_LENGTH
                || path.contains("\\") || path.contains(":")) {
            throw invalidResult();
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw invalidResult();
            }
        }
        return path;
    }

    private ScanFindingSeverity parseSeverity(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> ScanFindingSeverity.CRITICAL;
            case "HIGH" -> ScanFindingSeverity.HIGH;
            case "MEDIUM" -> ScanFindingSeverity.MEDIUM;
            case "LOW" -> ScanFindingSeverity.LOW;
            case "UNKNOWN", "NEGLIGIBLE" -> ScanFindingSeverity.INFO;
            default -> throw invalidResult();
        };
    }

    private String requiredText(JsonNode node, String field, int maximumLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength
                || value.asText().chars().anyMatch(Character::isISOControl)) {
            throw invalidResult();
        }
        return value.asText();
    }

    private ScanExecutionException invalidResult() {
        return new ScanExecutionException(
                "Trivy returned an invalid result", com.codesentinel.model.ScannerFailureCode.INVALID_OUTPUT);
    }
}
