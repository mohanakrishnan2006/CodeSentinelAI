package com.codesentinel.service;

import com.codesentinel.model.ScannerExecutionStatus;
import com.codesentinel.model.ScannerFailureCode;
import com.codesentinel.model.ScannerType;

import java.time.Instant;

public record ScannerExecutionOutcome(
        ScannerType scanner,
        ScannerExecutionStatus status,
        int findingCount,
        ScannerFailureCode failureCode,
        Instant startedAt,
        Instant completedAt) {

    public ScannerExecutionOutcome {
        if (findingCount < 0) {
            throw new IllegalArgumentException("Finding count cannot be negative");
        }
        if (status == ScannerExecutionStatus.SUCCEEDED && failureCode != null) {
            throw new IllegalArgumentException("Successful scanner outcome cannot have a failure code");
        }
        if (status == ScannerExecutionStatus.FAILED && failureCode == null) {
            throw new IllegalArgumentException("Failed scanner outcome requires a failure code");
        }
    }
}
