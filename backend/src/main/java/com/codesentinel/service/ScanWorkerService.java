package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.service.ScanRunCoordinator.ClaimedScan;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class ScanWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ScanWorkerService.class);

    private final ScanRunCoordinator coordinator;
    private final UploadStorageService storageService;
    private final ScanExecutor scanExecutor;
    private final ScanWorkerProperties properties;
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "codesentinel-scan-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public boolean processNextScan() {
        Optional<ClaimedScan> claimed = coordinator.claimNext(properties.getLeaseTimeoutMs());
        if (claimed.isEmpty()) {
            return false;
        }

        ClaimedScan scan = claimed.get();
        AtomicBoolean leaseLost = new AtomicBoolean();
        AtomicBoolean heartbeatActive = new AtomicBoolean(true);
        Thread workerThread = Thread.currentThread();
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeatActive.get()) {
                return;
            }
            try {
                if (!coordinator.renew(scan.scanId(), scan.claimToken(), properties.getLeaseTimeoutMs())) {
                    if (heartbeatActive.compareAndSet(true, false)) {
                        leaseLost.set(true);
                        workerThread.interrupt();
                    }
                }
            } catch (RuntimeException ex) {
                if (heartbeatActive.compareAndSet(true, false)) {
                    log.error("Unable to renew lease for scan {} ({})",
                            scan.scanId(), ex.getClass().getSimpleName());
                    leaseLost.set(true);
                    workerThread.interrupt();
                }
            }
        }, properties.getLeaseHeartbeatIntervalMs(),
                properties.getLeaseHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        try {
            Path sourceDirectory = storageService.resolveUploadDirectory(
                    scan.uploadId(), scan.storageReference());
            if (leaseLost.get()) {
                throw new ScanExecutionException("Scan lease was lost before execution");
            }
            ScanExecutionResult result =
                    scanExecutor.execute(new ScanWorkItem(scan.scanId(), scan.uploadId(), sourceDirectory));
            heartbeatActive.set(false);
            heartbeat.cancel(false);
            if (leaseLost.get()) {
                throw new ScanExecutionException("Scan lease was lost during execution");
            }
            if (!coordinator.complete(scan.scanId(), scan.claimToken(), result)) {
                log.warn("Scan {} was no longer running when processing completed", scan.scanId());
            }
        } catch (Exception ex) {
            heartbeatActive.set(false);
            heartbeat.cancel(false);
            // Clear the heartbeat cancellation interrupt before persisting failure on the poller thread.
            Thread.interrupted();
            log.error("Scan {} failed in worker ({})",
                    scan.scanId(), ex.getClass().getSimpleName());
            try {
                if (!coordinator.fail(scan.scanId(), scan.claimToken())) {
                    log.warn("Scan {} could not be marked failed", scan.scanId());
                }
            } catch (RuntimeException persistenceFailure) {
                log.error("Unable to persist failure for scan {} ({})",
                        scan.scanId(), persistenceFailure.getClass().getSimpleName());
            }
        } finally {
            heartbeatActive.set(false);
            heartbeat.cancel(false);
        }
        return true;
    }

    @PreDestroy
    void stopHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }
}
