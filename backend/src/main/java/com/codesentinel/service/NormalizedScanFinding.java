package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;

import java.util.Map;

public record NormalizedScanFinding(
        String ruleId,
        ScanFindingSeverity severity,
        String message,
        String filePath,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String scanner,
        Map<String, String> metadata) {

    public NormalizedScanFinding {
        metadata = Map.copyOf(metadata);
    }
}
