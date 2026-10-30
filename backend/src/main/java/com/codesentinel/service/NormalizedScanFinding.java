package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;

import java.util.Map;

public record NormalizedScanFinding(
        String ruleId,
        ScanFindingSeverity severity,
        String message,
        String filePath,
        Integer startLine,
        Integer startColumn,
        Integer endLine,
        Integer endColumn,
        String scanner,
        Map<String, String> metadata) {

    public NormalizedScanFinding {
        metadata = Map.copyOf(metadata);
    }
}
