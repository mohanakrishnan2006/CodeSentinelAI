package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;

import java.util.List;

public record ScanExecutionResult(List<NormalizedScanFinding> findings) {

    public ScanExecutionResult {
        findings = List.copyOf(findings);
    }

    public int totalFindings() {
        return findings.size();
    }

    public long count(ScanFindingSeverity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    public static ScanExecutionResult empty() {
        return new ScanExecutionResult(List.of());
    }
}
