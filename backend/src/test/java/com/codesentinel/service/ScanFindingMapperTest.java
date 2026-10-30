package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScannerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScanFindingMapperTest {

    private final ScanFindingMapper mapper = new ScanFindingMapper();

    @Test
    @DisplayName("Deduplicates by stable safe fields and persists only a generic description")
    void deduplicatesAndDropsRawMessages() {
        String secretBearingMessage = "dummy-only-not-real-secret";
        NormalizedScanFinding finding = new NormalizedScanFinding(
                "CVE-2025-1234", ScanFindingSeverity.HIGH, secretBearingMessage,
                "pom.xml", null, null, null, null, "TRIVY", Map.of());

        var persisted = mapper.toEntities(ScanRun.builder().build(), List.of(
                finding,
                new NormalizedScanFinding("CVE-2025-1234", ScanFindingSeverity.HIGH,
                        "another raw message", "pom.xml", null, null, null, null,
                        "TRIVY", Map.of())));

        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getScanner()).isEqualTo(ScannerType.TRIVY);
        assertThat(persisted.get(0).getDescription()).isEqualTo("Dependency vulnerability detected.");
        assertThat(persisted.get(0).getDescription()).doesNotContain(secretBearingMessage);
        assertThat(persisted.get(0).getFingerprint()).hasSize(64);
    }

    @Test
    @DisplayName("Rejects unsafe paths and incomplete coordinates before persistence")
    void rejectsUnsafeNormalizedFinding() {
        assertThrows(IllegalArgumentException.class, () -> mapper.toEntities(ScanRun.builder().build(), List.of(
                finding("../outside.txt", 1, 1, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> mapper.toEntities(ScanRun.builder().build(), List.of(
                finding("src/file.txt", 2, 1, 1, 1))));
    }

    private NormalizedScanFinding finding(
            String path, Integer startLine, Integer startColumn, Integer endLine, Integer endColumn) {
        return new NormalizedScanFinding("rule", ScanFindingSeverity.MEDIUM, "safe",
                path, startLine, startColumn, endLine, endColumn, "SEMGREP", Map.of());
    }
}
