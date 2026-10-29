package com.codesentinel.service;

import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.repository.ProjectRepository;
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
            int claimed = scanRunRepository.claimQueuedScan(
                    candidate.getId(),
                    ScanStatus.QUEUED,
                    ScanStatus.RUNNING,
                    now,
                    now.plusMillis(leaseTimeoutMs));
            if (claimed == 0) {
                continue;
            }

            ScanRun scanRun = scanRunRepository.findById(candidate.getId()).orElseThrow();
            ProjectUpload claimedUpload = scanRun.getProjectUpload();
            if (claimedUpload.getStatus() != UploadStatus.COMPLETED) {
                scanRunRepository.failRunningScan(
                        scanRun.getId(), ScanStatus.RUNNING, ScanStatus.FAILED, Instant.now());
                continue;
            }
            return Optional.of(new ClaimedScan(
                    scanRun.getId(), claimedUpload.getId(), claimedUpload.getStorageReference()));
        }
        return Optional.empty();
    }

    @Transactional
    public int failExpiredScans(Instant now) {
        return scanRunRepository.failExpiredRunningScans(
                ScanStatus.RUNNING, ScanStatus.FAILED, now, now);
    }

    @Transactional
    public boolean complete(UUID scanId) {
        return scanRunRepository.completeRunningScan(
                scanId, ScanStatus.RUNNING, ScanStatus.SUCCEEDED, Instant.now()) == 1;
    }

    @Transactional
    public boolean fail(UUID scanId) {
        return scanRunRepository.failRunningScan(
                scanId, ScanStatus.RUNNING, ScanStatus.FAILED, Instant.now()) == 1;
    }

    public record ClaimedScan(UUID scanId, UUID uploadId, String storageReference) {
    }
}
