package com.codesentinel.service;

import com.codesentinel.dto.ai.AiAnalysisRequest;
import com.codesentinel.dto.ai.AiAnalysisResponse;

public interface AiAnalysisClient {

    AiAnalysisResponse analyze(AiAnalysisRequest request);
}
