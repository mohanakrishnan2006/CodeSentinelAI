package com.codesentinel.service;

import com.codesentinel.dto.FindingAnalysisResponse;
import com.codesentinel.dto.ai.AiAnalysisResponse;
import com.codesentinel.exception.AiIntegrationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanFindingAnalysisService {

    private final ScanFindingAnalysisPersistenceService persistenceService;
    private final AiAnalysisClient aiAnalysisClient;

    public FindingAnalysisResponse analyze(UUID findingId, String authenticatedEmail) {
        ScanFindingAnalysisPersistenceService.Reservation reservation =
                persistenceService.reserve(findingId, authenticatedEmail);
        if (!reservation.isClaimed()) {
            return reservation.existingResponse();
        }

        try {
            AiAnalysisResponse result = aiAnalysisClient.analyze(reservation.request());
            return persistenceService.complete(
                    reservation.analysisId(), reservation.claimToken(), result);
        } catch (AiIntegrationException ex) {
            persistenceService.fail(
                    reservation.analysisId(), reservation.claimToken(), ex.getFailureCode());
            throw ex;
        }
    }

    public FindingAnalysisResponse get(UUID findingId, String authenticatedEmail) {
        return persistenceService.get(findingId, authenticatedEmail);
    }
}
