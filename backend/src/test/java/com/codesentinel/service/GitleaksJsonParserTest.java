package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitleaksJsonParserTest {

    private static final String DUMMY_SECRET = "dummy_test_credential_ABC123";
    private final GitleaksJsonParser parser = new GitleaksJsonParser(new ObjectMapper());

    @Test
    @DisplayName("Normalizes multiple findings without retaining secrets or matches")
    void normalizesFindingsWithoutSecretMaterial() {
        ScanExecutionResult result = parser.parse("""
                [
                  {
                    "RuleID": "generic-api-key",
                    "File": "src/config.py",
                    "StartLine": 12,
                    "EndLine": 12,
                    "StartColumn": 5,
                    "EndColumn": 25,
                    "Secret": "%s",
                    "Match": "API_KEY=%s"
                  },
                  {
                    "RuleID": "generic-password",
                    "File": "config/application.yml",
                    "StartLine": 3,
                    "Secret": "%s",
                    "Match": "password=%s"
                  }
                ]
                """.formatted(DUMMY_SECRET, DUMMY_SECRET, DUMMY_SECRET, DUMMY_SECRET));

        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.findings()).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(ScanFindingSeverity.HIGH);
            assertThat(finding.scanner()).isEqualTo("GITLEAKS");
            assertThat(finding.message()).isEqualTo("Potential exposed secret detected.");
            assertThat(finding.metadata()).isEmpty();
            assertThat(finding.toString()).doesNotContain(DUMMY_SECRET, "API_KEY=");
        });
        assertThat(result.findings().get(0).filePath()).isEqualTo("src/config.py");
        assertThat(result.findings().get(0).startLine()).isEqualTo(12);
        assertThat(result.findings().get(1).startLine()).isEqualTo(3);
    }

    @Test
    @DisplayName("Treats an empty valid report as a successful no-finding result")
    void parsesNoFindings() {
        assertThat(parser.parse("[]").findings()).isEmpty();
    }

    @Test
    @DisplayName("Normalizes findings reported under the trusted scanner mount")
    void normalizesTrustedScannerMountPath() {
        ScanExecutionResult result = parser.parse(
                "[" + finding("/src/src/config.env", 1, "generic-api-key") + "]");

        assertThat(result.findings()).singleElement()
                .extracting(NormalizedScanFinding::filePath)
                .isEqualTo("src/config.env");
    }

    @Test
    @DisplayName("Rejects malformed, trailing, non-array, or over-limit output")
    void rejectsMalformedOutput() {
        assertThrows(ScanExecutionException.class, () -> parser.parse("{"));
        assertThrows(ScanExecutionException.class, () -> parser.parse("{}"));
        assertThrows(ScanExecutionException.class, () -> parser.parse("[][]"));
        String report = "[" + (finding() + ",").repeat(10_000) + finding() + "]";
        assertThrows(ScanExecutionException.class, () -> parser.parse(report));
    }

    @Test
    @DisplayName("Rejects missing, invalid, oversized, and unsafe finding fields")
    void rejectsUnsafeFindingFields() {
        assertThrows(ScanExecutionException.class, () -> parser.parse("[{\"Secret\":\"" + DUMMY_SECRET + "\"}]"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("[" + finding("..\\u002foutside.txt", 1, "generic-api-key") + "]"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("[" + finding("/etc/passwd", 1, "generic-api-key") + "]"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("[" + finding("/src/../outside.txt", 1, "generic-api-key") + "]"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("[" + finding("src/file.txt", 0, "generic-api-key") + "]"));
        assertThrows(ScanExecutionException.class,
                () -> parser.parse("[" + finding("src/file.txt", 1, "x".repeat(257)) + "]"));
    }

    private String finding() {
        return finding("src/test.txt", 1, "generic-api-key");
    }

    private String finding(String file, int line, String rule) {
        return """
                {"RuleID":"%s","File":"%s","StartLine":%d,"Secret":"%s","Match":"value=%s"}
                """.formatted(rule, file, line, DUMMY_SECRET, DUMMY_SECRET).trim();
    }
}
