package com.codesentinel.dto;

import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class ScanRunResponse {

    UUID id;
    UUID uploadId;
    UUID projectId;
    ScanStatus status;
    Instant requestedAt;
    Instant startedAt;
    Instant completedAt;
    int totalFindings;
    int criticalCount;
    int highCount;
    int mediumCount;
    int lowCount;
    int infoCount;
    List<ScannerOutcomeResponse> scannerOutcomes;
    Instant createdAt;
    Instant updatedAt;

    public static ScanRunResponse fromEntity(ScanRun scanRun, List<ScannerOutcomeResponse> scannerOutcomes) {
        return ScanRunResponse.builder()
                .id(scanRun.getId())
                .uploadId(scanRun.getProjectUpload().getId())
                .projectId(scanRun.getProjectUpload().getProject().getId())
                .status(scanRun.getStatus())
                .requestedAt(scanRun.getRequestedAt())
                .startedAt(scanRun.getStartedAt())
                .completedAt(scanRun.getCompletedAt())
                .totalFindings(scanRun.getTotalFindings())
                .criticalCount(scanRun.getCriticalCount())
                .highCount(scanRun.getHighCount())
                .mediumCount(scanRun.getMediumCount())
                .lowCount(scanRun.getLowCount())
                .infoCount(scanRun.getInfoCount())
                .scannerOutcomes(List.copyOf(scannerOutcomes))
                .createdAt(scanRun.getCreatedAt())
                .updatedAt(scanRun.getUpdatedAt())
                .build();
    }
}
