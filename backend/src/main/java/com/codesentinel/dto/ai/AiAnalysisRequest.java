package com.codesentinel.dto.ai;

import com.codesentinel.model.ScanFinding;

import java.util.Map;

public record AiAnalysisRequest(
        String ruleId,
        String severity,
        String message,
        String filePath,
        Integer startLine,
        Integer startColumn,
        Integer endLine,
        Integer endColumn,
        String scanner,
        Map<String, String> metadata) {

    public static AiAnalysisRequest fromFinding(ScanFinding finding) {
        return new AiAnalysisRequest(
                finding.getRuleId(),
                finding.getSeverity().name(),
                finding.getDescription(),
                finding.getFilePath(),
                finding.getStartLine(),
                finding.getStartColumn(),
                finding.getEndLine(),
                finding.getEndColumn(),
                finding.getScanner().name(),
                Map.of());
    }
}
