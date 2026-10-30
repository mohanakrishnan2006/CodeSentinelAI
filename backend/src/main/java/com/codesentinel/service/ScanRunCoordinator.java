package com.codesentinel.service;

import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanOutcome;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.ScannerExecutionStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ScanFindingRepository;
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
    private final ScanFindingRepository scanFindingRepository;
    private final ScanOutcomeRepository scanOutcomeRepository;
    private final ProjectRepository projectRepository;
    private final ScanFindingMapper scanFindingMapper;

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
        ScanRun scanRun = scanRunRepository.getReferenceById(scanId);
        var findings = scanFindingMapper.toEntities(scanRun, result.findings());
        Instant completedAt = Instant.now();
        int updated = scanRunRepository.completeRunningScan(
                scanId,
                ScanStatus.RUNNING,
                result.status(),
                completedAt,
                claimToken,
                findings.size(),
                count(findings, ScanFindingSeverity.CRITICAL),
                count(findings, ScanFindingSeverity.HIGH),
                count(findings, ScanFindingSeverity.MEDIUM),
                count(findings, ScanFindingSeverity.LOW),
                count(findings, ScanFindingSeverity.INFO));
        if (updated != 1) {
            return false;
        }
        ScanRun completedScanRun = scanRunRepository.getReferenceById(scanId);
        findings.forEach(finding -> finding.setScanRun(completedScanRun));
        scanFindingRepository.saveAll(findings);
        scanOutcomeRepository.saveAll(result.outcomes().stream()
                .map(outcome -> ScanOutcome.builder()
                        .scanRun(completedScanRun)
                        .scanner(outcome.scanner())
                        .status(outcome.status())
                        .findingCount(outcome.status() == ScannerExecutionStatus.SUCCEEDED
                                ? count(findings, outcome.scanner()) : 0)
                        .failureCode(outcome.failureCode())
                        .startedAt(outcome.startedAt())
                        .completedAt(outcome.completedAt())
                        .build())
                .toList());
        return true;
    }

    private int count(java.util.List<ScanFinding> findings, ScanFindingSeverity severity) {
        return Math.toIntExact(findings.stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count());
    }

    private int count(java.util.List<ScanFinding> findings, com.codesentinel.model.ScannerType scanner) {
        return Math.toIntExact(findings.stream()
                .filter(finding -> finding.getScanner() == scanner)
                .count());
    }

    @Transactional
    public boolean fail(UUID scanId, UUID claimToken) {
        return scanRunRepository.failRunningScan(
                scanId, ScanStatus.RUNNING, ScanStatus.FAILED, Instant.now(), claimToken) == 1;
    }

    public record ClaimedScan(UUID scanId, UUID uploadId, String storageReference, UUID claimToken) {
    }
}
