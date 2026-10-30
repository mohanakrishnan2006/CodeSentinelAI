package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemgrepJsonParserTest {

    private final SemgrepJsonParser parser = new SemgrepJsonParser(new ObjectMapper());

    @Test
    @DisplayName("Parses multiple findings without retaining source snippets")
    void parsesMultipleFindings() {
        ScanExecutionResult result = parser.parse("""
                {
                  "results": [
                    {
                      "check_id": "rules.sql-injection",
                      "path": "src/Database.java",
                      "start": {"line": 10, "col": 2},
                      "end": {"line": 10, "col": 20},
                      "extra": {
                        "severity": "ERROR",
                        "message": "Review SQL construction",
                        "metadata": {"cwe": ["CWE-89"], "category": "security"},
                        "lines": "source must not be persisted"
                      }
                    },
                    {
                      "check_id": "rules.dynamic-eval",
                      "path": "src/app.py",
                      "start": {"line": 2, "col": 1},
                      "end": {"line": 2, "col": 14},
                      "extra": {"severity": "WARNING", "message": "Dynamic evaluation"}
                    }
                  ]
                }
                """);

        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.findings()).extracting(NormalizedScanFinding::severity)
                .containsExactly(ScanFindingSeverity.HIGH, ScanFindingSeverity.MEDIUM);
        assertThat(result.findings().get(0).scanner()).isEqualTo("SEMGREP");
        assertThat(result.findings().get(0).metadata())
                .containsEntry("cwe", "CWE-89")
                .containsEntry("category", "security");
        assertThat(result.findings().get(0).message()).isEqualTo("Review SQL construction");
        assertThat(result.findings().get(0).metadata()).doesNotContainKey("lines");
    }

    @Test
    @DisplayName("Treats a valid empty result list as successful zero findings")
    void parsesZeroFindings() {
        ScanExecutionResult result = parser.parse("{\"results\":[]}");

        assertThat(result.findings()).isEmpty();
        assertThat(result.totalFindings()).isZero();
    }

    @Test
    @DisplayName("Maps Semgrep severities to the normalized severity model")
    void mapsSeverities() {
        assertThat(parseSeverity("CRITICAL")).isEqualTo(ScanFindingSeverity.CRITICAL);
        assertThat(parseSeverity("ERROR")).isEqualTo(ScanFindingSeverity.HIGH);
        assertThat(parseSeverity("WARNING")).isEqualTo(ScanFindingSeverity.MEDIUM);
        assertThat(parseSeverity("INFO")).isEqualTo(ScanFindingSeverity.INFO);
    }

    @Test
    @DisplayName("Rejects malformed JSON and a missing results array")
    void rejectsMalformedResults() {
        assertThrows(ScanExecutionException.class, () -> parser.parse("{"));
        assertThrows(ScanExecutionException.class, () -> parser.parse("{\"errors\":[]}"));
        assertThrows(ScanExecutionException.class, () -> parser.parse("{\"results\":[]}{}"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("{\"results\":[],\"errors\":[{\"message\":\"parse error\"}]}"));
    }

    @Test
    @DisplayName("Rejects missing, oversized, invalid-severity, and unsafe finding fields")
    void rejectsInvalidFindingFields() {
        assertThrows(ScanExecutionException.class, () -> parser.parse("""
                {"results":[{"path":"src/a.py"}]}
                """));
        assertThrows(ScanExecutionException.class, () -> parser.parse(finding("UNKNOWN", "src/a.py", "short")));
        assertThrows(ScanExecutionException.class, () -> parser.parse(
                finding("ERROR", "src/a.py", "x".repeat(4_001))));
        assertThrows(ScanExecutionException.class, () -> parser.parse(finding("ERROR", "../outside.py", "short")));
    }

    @Test
    @DisplayName("Rejects more findings than the configured parser safety ceiling")
    void rejectsTooManyFindings() {
        String finding = finding("INFO", "src/a.py", "message")
                .replace("{\"results\":[", "")
                .replace("]}", "");
        String json = "{\"results\":[" + (finding + ",").repeat(10_000) + finding + "]}";

        assertThrows(ScanExecutionException.class, () -> parser.parse(json));
    }

    private ScanFindingSeverity parseSeverity(String severity) {
        return parser.parse(finding(severity, "src/a.py", "message")).findings().get(0).severity();
    }

    private String finding(String severity, String path, String message) {
        return """
                {"results":[{"check_id":"rule.id","path":"%s",
                  "start":{"line":1,"col":1},"end":{"line":1,"col":2},
                  "extra":{"severity":"%s","message":"%s"}}]}
                """.formatted(path, severity, message);
    }
}
