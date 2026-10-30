package com.codesentinel.dto;

import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScannerType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class ScanFindingResponse {

    UUID id;
    UUID scanId;
    ScannerType scanner;
    String ruleId;
    ScanFindingSeverity severity;
    String filePath;
    Integer startLine;
    Integer startColumn;
    Integer endLine;
    Integer endColumn;
    String description;
    Instant createdAt;

    public static ScanFindingResponse fromEntity(ScanFinding finding) {
        return ScanFindingResponse.builder()
                .id(finding.getId())
                .scanId(finding.getScanRun().getId())
                .scanner(finding.getScanner())
                .ruleId(finding.getRuleId())
                .severity(finding.getSeverity())
                .filePath(finding.getFilePath())
                .startLine(finding.getStartLine())
                .startColumn(finding.getStartColumn())
                .endLine(finding.getEndLine())
                .endColumn(finding.getEndColumn())
                .description(finding.getDescription())
                .createdAt(finding.getCreatedAt())
                .build();
    }
}
