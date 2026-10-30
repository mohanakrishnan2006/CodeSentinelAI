package com.codesentinel.service;

import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.ScanOutcome;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ScanOutcomeRepository;
import com.codesentinel.repository.ScanRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanRunCoordinator {

    private static final int MAX_CLAIM_ATTEMPTS = 10;

    private final ScanRunRepository scanRunRepository;
    private final ScanOutcomeRepository scanOutcomeRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public Optional<ClaimedScan> claimNext(long leaseTimeoutMs) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<ScanRun> next = scanRunRepository
                    .findFirstByStatusOrderByRequestedAtAsc(ScanStatus.QUEUED);
            if (next.isEmpty()) {
                return Optional.empty();
            }

            ScanRun candidate = next.get();
            ProjectUpload upload = candidate.getProjectUpload();
            UUID projectId = upload.getProject().getId();
            if (projectRepository.findByIdForUpdate(projectId).isEmpty()) {
                return Optional.empty();
            }

            Instant now = Instant.now();
            UUID claimToken = UUID.randomUUID();
            int claimed = scanRunRepository.claimQueuedScan(
                    candidate.getId(),
                    ScanStatus.QUEUED,
                    ScanStatus.RUNNING,
                    now,
                    now.plusMillis(leaseTimeoutMs),
                    claimToken);
            if (claimed == 0) {
                continue;
            }

            ScanRun scanRun = scanRunRepository.findById(candidate.getId()).orElseThrow();
            ProjectUpload claimedUpload = scanRun.getProjectUpload();
            if (claimedUpload.getStatus() != UploadStatus.COMPLETED) {
                scanRunRepository.failRunningScan(
                        scanRun.getId(), ScanStatus.RUNNING, ScanStatus.FAILED, Instant.now(), claimToken);
                continue;
            }
            return Optional.of(new ClaimedScan(
                    scanRun.getId(), claimedUpload.getId(), claimedUpload.getStorageReference(), claimToken));
        }
        return Optional.empty();
    }

    @Transactional
    public int failExpiredScans(Instant now) {
        return scanRunRepository.failExpiredRunningScans(
                ScanStatus.RUNNING, ScanStatus.FAILED, now, now);
    }

    @Transactional
    public boolean renew(UUID scanId, UUID claimToken, long leaseTimeoutMs) {
        Instant now = Instant.now();
        return scanRunRepository.renewRunningScan(
                scanId, ScanStatus.RUNNING, now, now.plusMillis(leaseTimeoutMs), claimToken) == 1;
    }

    @Transactional
    public boolean complete(UUID scanId, UUID claimToken, ScanBatchResult result) {
        Instant completedAt = Instant.now();
        int updated = scanRunRepository.completeRunningScan(
                scanId,
                ScanStatus.RUNNING,
                result.status(),
                completedAt,
                claimToken,
                result.totalFindings(),
                Math.toIntExact(result.count(com.codesentinel.model.ScanFindingSeverity.CRITICAL)),
                Math.toIntExact(result.count(com.codesentinel.model.ScanFindingSeverity.HIGH)),
                Math.toIntExact(result.count(com.codesentinel.model.ScanFindingSeverity.MEDIUM)),
                Math.toIntExact(result.count(com.codesentinel.model.ScanFindingSeverity.LOW)),
                Math.toIntExact(result.count(com.codesentinel.model.ScanFindingSeverity.INFO)));
        if (updated != 1) {
            return false;
        }
        ScanRun scanRun = scanRunRepository.getReferenceById(scanId);
        scanOutcomeRepository.saveAll(result.outcomes().stream()
                .map(outcome -> ScanOutcome.builder()
                        .scanRun(scanRun)
                        .scanner(outcome.scanner())
                        .status(outcome.status())
                        .findingCount(outcome.findingCount())
                        .failureCode(outcome.failureCode())
                        .startedAt(outcome.startedAt())
                        .completedAt(outcome.completedAt())
                        .build())
                .toList());
        return true;
    }

    @Transactional
    public boolean fail(UUID scanId, UUID claimToken) {
        return scanRunRepository.failRunningScan(
                scanId, ScanStatus.RUNNING, ScanStatus.FAILED, Instant.now(), claimToken) == 1;
    }

    public record ClaimedScan(UUID scanId, UUID uploadId, String storageReference, UUID claimToken) {
    }
}
