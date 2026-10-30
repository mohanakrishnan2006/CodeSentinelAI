package com.codesentinel.dto;

import com.codesentinel.model.ScanOutcome;
import com.codesentinel.model.ScannerExecutionStatus;
import com.codesentinel.model.ScannerFailureCode;
import com.codesentinel.model.ScannerType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ScannerOutcomeResponse {

    ScannerType scanner;
    ScannerExecutionStatus status;
    int findingCount;
    ScannerFailureCode failureCode;
    Instant startedAt;
    Instant completedAt;

    public static ScannerOutcomeResponse fromEntity(ScanOutcome outcome) {
        return ScannerOutcomeResponse.builder()
                .scanner(outcome.getScanner())
                .status(outcome.getStatus())
                .findingCount(outcome.getFindingCount())
                .failureCode(outcome.getFailureCode())
                .startedAt(outcome.getStartedAt())
                .completedAt(outcome.getCompletedAt())
                .build();
    }
}
