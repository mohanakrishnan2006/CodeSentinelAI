package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.ScannerExecutionStatus;

import java.util.List;

public record ScanBatchResult(
        List<NormalizedScanFinding> findings,
        List<ScannerExecutionOutcome> outcomes) {

    public ScanBatchResult {
        findings = List.copyOf(findings);
        outcomes = List.copyOf(outcomes);
    }

    public int totalFindings() {
        return findings.size();
    }

    public long count(ScanFindingSeverity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    public ScanStatus status() {
        long succeeded = outcomes.stream()
                .filter(outcome -> outcome.status() == ScannerExecutionStatus.SUCCEEDED)
                .count();
        if (outcomes.isEmpty() || succeeded == outcomes.size()) {
            return ScanStatus.SUCCEEDED;
        }
        return succeeded == 0 ? ScanStatus.FAILED : ScanStatus.PARTIAL;
    }

    public static ScanBatchResult empty() {
        return new ScanBatchResult(List.of(), List.of());
    }
}
