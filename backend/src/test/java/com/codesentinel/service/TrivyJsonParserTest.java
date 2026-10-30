package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrivyJsonParserTest {

    private final TrivyJsonParser parser = new TrivyJsonParser(new ObjectMapper());

    @Test
    @DisplayName("Parses multiple vulnerability results with safe normalized fields")
    void parsesMultipleFindingsAndDropsRawScannerFields() {
        ScanExecutionResult result = parser.parse("""
                {
                  "SchemaVersion": 2,
                  "Results": [{
                    "Target": "/src/pom.xml",
                    "Vulnerabilities": [
                      {"VulnerabilityID":"CVE-2025-1234","Severity":"CRITICAL",
                       "Title":"dummy-only-not-real-secret"},
                      {"VulnerabilityID":"GHSA-abcd-efgh-ijkl","Severity":"HIGH",
                       "InstalledVersion":"1.0.0"}
                    ]
                  }]
                }
                """);

        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings()).extracting(NormalizedScanFinding::filePath)
                .containsOnly("pom.xml");
        assertThat(result.findings()).extracting(NormalizedScanFinding::severity)
                .containsExactly(ScanFindingSeverity.CRITICAL, ScanFindingSeverity.HIGH);
        assertThat(result.findings()).allSatisfy(finding -> {
            assertThat(finding.scanner()).isEqualTo("TRIVY");
            assertThat(finding.startLine()).isNull();
            assertThat(finding.message()).isEqualTo("Dependency vulnerability detected.");
            assertThat(finding.toString()).doesNotContain("dummy-only-not-real-secret", "1.0.0");
        });
    }

    @Test
    @DisplayName("Accepts valid reports with zero findings")
    void acceptsZeroFindings() {
        assertThat(parser.parse("""
                {"SchemaVersion":2,"Results":[{"Target":"pom.xml","Vulnerabilities":null}]}
                """).findings()).isEmpty();
        assertThat(parser.parse("""
                {"SchemaVersion":2,"Results":[]}
                """).findings()).isEmpty();
    }

    @Test
    @DisplayName("Maps supported Trivy severities and safely maps unknown severities to INFO")
    void mapsSeverityValues() {
        ScanExecutionResult result = parser.parse("""
                {"SchemaVersion":2,"Results":[{"Target":"requirements.txt","Vulnerabilities":[
                  {"VulnerabilityID":"CVE-1","Severity":"MEDIUM"},
                  {"VulnerabilityID":"CVE-2","Severity":"LOW"},
                  {"VulnerabilityID":"CVE-3","Severity":"UNKNOWN"},
                  {"VulnerabilityID":"CVE-4","Severity":"NEGLIGIBLE"}
                ]}]}
                """);

        assertThat(result.findings()).extracting(NormalizedScanFinding::severity)
                .containsExactly(ScanFindingSeverity.MEDIUM, ScanFindingSeverity.LOW,
                        ScanFindingSeverity.INFO, ScanFindingSeverity.INFO);
    }

    @Test
    @DisplayName("Rejects malformed JSON and malformed or oversized finding fields")
    void rejectsInvalidOutputAndFields() {
        assertInvalid("{");
        assertInvalid("{\"Results\":[]} trailing");
        assertInvalid("{\"Results\":{}}");
        assertInvalid("{\"Results\":[]}");
        assertInvalid("{\"SchemaVersion\":2,\"Results\":[],\"Errors\":[{}]}");
        assertInvalid("{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"pom.xml\",\"Vulnerabilities\":["
                + "{\"Severity\":\"HIGH\"}]}]}");
        assertInvalid("{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"pom.xml\",\"Vulnerabilities\":["
                + "{\"VulnerabilityID\":\"CVE-1\",\"Severity\":\"BLOCKER\"}]}]}");
        assertInvalid("{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"pom.xml\",\"Vulnerabilities\":["
                + "{\"VulnerabilityID\":\"" + "x".repeat(257) + "\",\"Severity\":\"HIGH\"}]}]}");
        assertInvalid("{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"" + "a".repeat(1025)
                + "\",\"Vulnerabilities\":[{\"VulnerabilityID\":\"CVE-1\",\"Severity\":\"HIGH\"}]}]}");
    }

    @Test
    @DisplayName("Strips only the scanner source root and rejects absolute or traversing paths")
    void normalizesPathsSafely() {
        assertThat(parser.parse("""
                {"SchemaVersion":2,"Results":[{"Target":"/src/src/main.java","Vulnerabilities":[
                  {"VulnerabilityID":"CVE-1","Severity":"HIGH"}]}]}
                """).findings().get(0).filePath()).isEqualTo("src/main.java");
        assertInvalid(reportForPath("/etc/passwd"));
        assertInvalid(reportForPath("/src/../outside.txt"));
        assertInvalid(reportForPath("src\\\\outside.txt"));
        assertInvalid(reportForPath("C:/outside.txt"));
        assertInvalid(reportForPath("src//file.txt"));
    }

    private void assertInvalid(String json) {
        assertThrows(ScanExecutionException.class, () -> parser.parse(json));
    }

    private String reportForPath(String path) {
        return "{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"" + path + "\",\"Vulnerabilities\":["
                + "{\"VulnerabilityID\":\"CVE-1\",\"Severity\":\"HIGH\"}]}]}";
    }
}
