package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "codesentinel.scan-worker.enabled", havingValue = "true")
public class ScanWorkerPoller {

    private static final Logger log = LoggerFactory.getLogger(ScanWorkerPoller.class);

    private final ScanWorkerService workerService;
    private final ScanRunCoordinator coordinator;
    private final ScanWorkerProperties properties;

    @Scheduled(fixedDelayString = "${codesentinel.scan-worker.poll-interval-ms:1000}")
    public void poll() {
        try {
            coordinator.failExpiredScans(Instant.now());
            workerService.processNextScan();
        } catch (RuntimeException ex) {
            log.error("Scan worker poll failed ({})", ex.getClass().getSimpleName());
        }
    }
}
