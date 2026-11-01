package com.codesentinel.dto;

import com.codesentinel.dto.ai.AiIllustrativeFix;
import com.codesentinel.model.ScanFindingAnalysis;
import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import com.codesentinel.model.ScanFindingAnalysisStatus;

import java.time.Instant;
import java.util.UUID;

public record FindingAnalysisResponse(
        UUID findingId,
        ScanFindingAnalysisStatus status,
        String explanation,
        String whyItMatters,
        String recommendedRemediation,
        AiIllustrativeFix illustrativeFix,
        String modelIdentifier,
        int attemptCount,
        ScanFindingAnalysisFailureCode failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public static FindingAnalysisResponse fromEntity(ScanFindingAnalysis analysis) {
        AiIllustrativeFix fix = analysis.getFixLanguage() == null
                ? null
                : new AiIllustrativeFix(analysis.getFixLanguage(), analysis.getFixCode());
        return new FindingAnalysisResponse(
                analysis.getFinding().getId(),
                analysis.getStatus(),
                analysis.getExplanation(),
                analysis.getWhyItMatters(),
                analysis.getRecommendedRemediation(),
                fix,
                analysis.getModelIdentifier(),
                analysis.getAttemptCount(),
                analysis.getFailureCode(),
                analysis.getCreatedAt(),
                analysis.getUpdatedAt(),
                analysis.getCompletedAt());
    }
}
