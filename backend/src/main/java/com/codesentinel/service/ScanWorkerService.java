package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.service.ScanRunCoordinator.ClaimedScan;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScanWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ScanWorkerService.class);

    private final ScanRunCoordinator coordinator;
    private final UploadStorageService storageService;
    private final ScanExecutor scanExecutor;
    private final ScanWorkerProperties properties;

    public boolean processNextScan() {
        Optional<ClaimedScan> claimed = coordinator.claimNext(properties.getLeaseTimeoutMs());
        if (claimed.isEmpty()) {
            return false;
        }

        ClaimedScan scan = claimed.get();
        try {
            Path sourceDirectory = storageService.resolveUploadDirectory(
                    scan.uploadId(), scan.storageReference());
            scanExecutor.execute(new ScanWorkItem(scan.scanId(), scan.uploadId(), sourceDirectory));
            if (!coordinator.complete(scan.scanId())) {
                log.warn("Scan {} was no longer running when processing completed", scan.scanId());
            }
        } catch (Exception ex) {
            log.error("Scan {} failed in worker ({})",
                    scan.scanId(), ex.getClass().getSimpleName());
            try {
                if (!coordinator.fail(scan.scanId())) {
                    log.warn("Scan {} could not be marked failed", scan.scanId());
                }
            } catch (RuntimeException persistenceFailure) {
                log.error("Unable to persist failure for scan {} ({})",
                        scan.scanId(), persistenceFailure.getClass().getSimpleName());
            }
        }
        return true;
    }
}
