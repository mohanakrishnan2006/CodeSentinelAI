package com.codesentinel.service;

import com.codesentinel.config.AiServiceProperties;
import com.codesentinel.dto.FindingAnalysisResponse;
import com.codesentinel.dto.ai.AiAnalysisRequest;
import com.codesentinel.dto.ai.AiAnalysisResponse;
import com.codesentinel.exception.FindingAnalysisConflictException;
import com.codesentinel.exception.FindingAnalysisNotFoundException;
import com.codesentinel.exception.FindingNotFoundException;
import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingAnalysis;
import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import com.codesentinel.model.ScanFindingAnalysisStatus;
import com.codesentinel.repository.ScanFindingAnalysisRepository;
import com.codesentinel.repository.ScanFindingRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanFindingAnalysisPersistenceService {

    private final ScanFindingAnalysisRepository analysisRepository;
    private final ScanFindingRepository findingRepository;
    private final UserRepository userRepository;
    private final AiServiceProperties aiServiceProperties;

    @Transactional
    public Reservation reserve(UUID findingId, String authenticatedEmail) {
        UUID ownerId = findOwnerId(authenticatedEmail);
        ScanFinding finding = findingRepository.findOneForOwnerForUpdate(findingId, ownerId)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
        Instant now = Instant.now();
        ScanFindingAnalysis analysis = analysisRepository.findByFindingId(findingId).orElse(null);

        if (analysis == null) {
            analysis = newAnalysis(finding, now);
            analysisRepository.saveAndFlush(analysis);
            return Reservation.claimed(
                    analysis.getId(), analysis.getClaimToken(), AiAnalysisRequest.fromFinding(finding));
        }

        if (analysis.getStatus() == ScanFindingAnalysisStatus.COMPLETED) {
            return Reservation.existing(FindingAnalysisResponse.fromEntity(analysis));
        }

        if (analysis.getStatus() == ScanFindingAnalysisStatus.PENDING) {
            Instant staleBefore = now.minus(aiServiceProperties.getPendingStaleTimeout());
            if (analysis.getUpdatedAt().isAfter(staleBefore)) {
                throw new FindingAnalysisConflictException();
            }
            if (analysis.getAttemptCount() >= aiServiceProperties.getMaxAttempts()) {
                analysis.setStatus(ScanFindingAnalysisStatus.FAILED);
                analysis.setFailureCode(ScanFindingAnalysisFailureCode.ATTEMPTS_EXHAUSTED);
                analysis.setCompletedAt(now);
                analysisRepository.saveAndFlush(analysis);
                return Reservation.existing(FindingAnalysisResponse.fromEntity(analysis));
            }
        } else if (analysis.getAttemptCount() >= aiServiceProperties.getMaxAttempts()) {
            throw new FindingAnalysisConflictException();
        }

        analysis.setStatus(ScanFindingAnalysisStatus.PENDING);
        analysis.setFailureCode(null);
        analysis.setCompletedAt(null);
        analysis.setAttemptCount(analysis.getAttemptCount() + 1);
        analysis.setClaimToken(UUID.randomUUID());
        analysis.setUpdatedAt(now);
        analysisRepository.saveAndFlush(analysis);
        return Reservation.claimed(
                analysis.getId(), analysis.getClaimToken(), AiAnalysisRequest.fromFinding(finding));
    }

    @Transactional
    public FindingAnalysisResponse complete(
            UUID analysisId,
            UUID claimToken,
            AiAnalysisResponse result) {
        ScanFindingAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        if (ownsActiveClaim(analysis, claimToken)) {
            analysis.setStatus(ScanFindingAnalysisStatus.COMPLETED);
            analysis.setExplanation(result.explanation());
            analysis.setWhyItMatters(result.whyItMatters());
            analysis.setRecommendedRemediation(result.recommendedRemediation());
            analysis.setFixLanguage(result.illustrativeFix() == null
                    ? null : result.illustrativeFix().language());
            analysis.setFixCode(result.illustrativeFix() == null
                    ? null : result.illustrativeFix().code());
            analysis.setModelIdentifier(aiServiceProperties.getModelIdentifier());
            analysis.setFailureCode(null);
            analysis.setCompletedAt(Instant.now());
        }
        return FindingAnalysisResponse.fromEntity(analysis);
    }

    @Transactional
    public void fail(UUID analysisId, UUID claimToken, ScanFindingAnalysisFailureCode failureCode) {
        ScanFindingAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        if (ownsActiveClaim(analysis, claimToken)) {
            analysis.setStatus(ScanFindingAnalysisStatus.FAILED);
            analysis.setFailureCode(failureCode);
            analysis.setCompletedAt(Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public FindingAnalysisResponse get(UUID findingId, String authenticatedEmail) {
        UUID ownerId = findOwnerId(authenticatedEmail);
        findingRepository.findOneForOwner(findingId, ownerId)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
        return analysisRepository.findByFindingId(findingId)
                .map(FindingAnalysisResponse::fromEntity)
                .orElseThrow(() -> new FindingAnalysisNotFoundException(findingId));
    }

    private ScanFindingAnalysis newAnalysis(ScanFinding finding, Instant now) {
        return ScanFindingAnalysis.builder()
                .finding(finding)
                .status(ScanFindingAnalysisStatus.PENDING)
                .attemptCount(1)
                .claimToken(UUID.randomUUID())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UUID findOwnerId(String authenticatedEmail) {
        return userRepository.findByEmail(authenticatedEmail.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"))
                .getId();
    }

    private boolean ownsActiveClaim(ScanFindingAnalysis analysis, UUID claimToken) {
        return analysis.getStatus() == ScanFindingAnalysisStatus.PENDING
                && analysis.getClaimToken().equals(claimToken);
    }

    public record Reservation(
            UUID analysisId,
            UUID claimToken,
            AiAnalysisRequest request,
            FindingAnalysisResponse existingResponse) {

        static Reservation claimed(UUID id, UUID token, AiAnalysisRequest request) {
            return new Reservation(id, token, request, null);
        }

        static Reservation existing(FindingAnalysisResponse response) {
            return new Reservation(null, null, null, response);
        }

        public boolean isClaimed() {
            return analysisId != null;
        }
    }
}
