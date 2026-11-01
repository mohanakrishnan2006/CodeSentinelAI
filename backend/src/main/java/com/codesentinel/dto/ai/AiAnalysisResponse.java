package com.codesentinel.dto.ai;

public record AiAnalysisResponse(
        String explanation,
        String whyItMatters,
        String recommendedRemediation,
        AiIllustrativeFix illustrativeFix) {
}
