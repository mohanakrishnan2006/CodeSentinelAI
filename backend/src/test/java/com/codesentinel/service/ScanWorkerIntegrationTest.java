package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanOutcomeRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanWorkerIntegrationTest {

    private static final String OWNER_EMAIL = "scan.worker.owner@example.com";
    private static final Path STORAGE_DIRECTORY = createTemporaryStorageDirectory();

    @Autowired
    private ScanRunCoordinator coordinator;
    @Autowired
    private ScanWorkerService workerService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectUploadRepository uploadRepository;
    @Autowired
    private ScanOutcomeRepository scanOutcomeRepository;
    @Autowired
    private ScanRunRepository scanRunRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScanWorkerProperties scanWorkerProperties;
    @SpyBean
    private ScanExecutor scanExecutor;

    private ProjectUpload upload;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("codesentinel.upload.storage-directory", () -> STORAGE_DIRECTORY.toString());
    }

    @BeforeEach
    void setUp() throws IOException {
        reset(scanExecutor);
        scanOutcomeRepository.deleteAll();
        scanRunRepository.deleteAll();
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        clearStorageDirectory();

        User owner = userRepository.save(User.builder().fullName("Worker Owner")
                .email(OWNER_EMAIL).passwordHash("unused").role(Role.USER).build());
        Project project = projectRepository.save(Project.builder().name("Worker project")
                .owner(owner).build());
        UUID uploadId = UUID.randomUUID();
        upload = uploadRepository.save(ProjectUpload.builder()
                .id(uploadId)
                .project(project)
                .originalFilename("source.zip")
                .status(UploadStatus.COMPLETED)
                .fileCount(1)
                .extractedSizeBytes(6)
                .storageReference(uploadId.toString())
                .build());
    }

    @AfterAll
    static void cleanTestStorage() throws IOException {
        deleteTree(STORAGE_DIRECTORY);
    }

    @Test
    @DisplayName("Claims queued work and records RUNNING status and a lease")
    void claimsQueuedScan() {
        ScanRun scan = createQueuedScan();

        var claim = coordinator.claimNext(60_000).orElseThrow();

        ScanRun saved = scanRunRepository.findById(scan.getId()).orElseThrow();
        assertThat(claim.scanId()).isEqualTo(scan.getId());
        assertThat(claim.uploadId()).isEqualTo(upload.getId());
        assertThat(saved.getStatus()).isEqualTo(ScanStatus.RUNNING);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getLeaseExpiresAt()).isAfter(saved.getStartedAt());
    }

    @Test
    @DisplayName("Concurrent workers cannot claim the same queued scan twice")
    void claimsScanOnlyOnceAcrossWorkers() throws Exception {
        ScanRun scan = createQueuedScan();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ScanRunCoordinator.ClaimedScan>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not reach the start barrier");
                    }
                    return coordinator.claimNext(60_000).orElse(null);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ScanRunCoordinator.ClaimedScan> claims = new ArrayList<>();
            for (Future<ScanRunCoordinator.ClaimedScan> result : results) {
                ScanRunCoordinator.ClaimedScan claim = result.get(10, TimeUnit.SECONDS);
                if (claim != null) {
                    claims.add(claim);
                }
            }
            assertThat(claims).hasSize(1);
            assertThat(claims.get(0).scanId()).isEqualTo(scan.getId());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("Placeholder executor completes a scan successfully without running source code")
    void executesPlaceholderAndCompletesScan() throws Exception {
        ScanRun scan = createQueuedScan();
        Files.createDirectories(STORAGE_DIRECTORY.resolve(upload.getStorageReference()));

        assertThat(workerService.processNextScan()).isTrue();
        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.SUCCEEDED);
        org.mockito.ArgumentCaptor<ScanWorkItem> workItem =
                org.mockito.ArgumentCaptor.forClass(ScanWorkItem.class);
        verify(scanExecutor).execute(workItem.capture());
        assertThat(workItem.getValue().uploadId()).isEqualTo(upload.getId());
        assertThat(workItem.getValue().sourceDirectory())
                .isEqualTo(STORAGE_DIRECTORY.resolve(upload.getStorageReference()).toRealPath());
    }

    @Test
    @DisplayName("Renews the scan lease while the executor is active")
    void renewsLeaseDuringExecution() throws Exception {
        ScanRun scan = createQueuedScan();
        Files.createDirectories(STORAGE_DIRECTORY.resolve(upload.getStorageReference()));
        doAnswer(invocation -> {
            Thread.sleep(100);
            ScanRun running = scanRunRepository.findById(scan.getId()).orElseThrow();
            assertThat(running.getLeaseExpiresAt())
                    .isAfter(running.getStartedAt().plusMillis(scanWorkerProperties.getLeaseTimeoutMs()));
            return ScanBatchResult.empty();
        }).when(scanExecutor).execute(any(ScanWorkItem.class));

        assertThat(workerService.processNextScan()).isTrue();
        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Persists summary counts from validated in-memory findings")
    void persistsValidatedSummaryCounts() throws Exception {
        ScanRun scan = createQueuedScan();
        Files.createDirectories(STORAGE_DIRECTORY.resolve(upload.getStorageReference()));
        ScanExecutionResult scannerResult = new ScanExecutionResult(List.of(
                new NormalizedScanFinding("rule.high", ScanFindingSeverity.HIGH, "High finding",
                        "src/Main.java", 1, 1, 1, 2, "SEMGREP", Map.of()),
                new NormalizedScanFinding("rule.medium", ScanFindingSeverity.MEDIUM, "Medium finding",
                        "src/App.py", 2, 1, 2, 2, "SEMGREP", Map.of())));
        ScanBatchResult result = new ScanBatchResult(scannerResult.findings(), List.of(
                new ScannerExecutionOutcome(com.codesentinel.model.ScannerType.SEMGREP,
                        com.codesentinel.model.ScannerExecutionStatus.SUCCEEDED, 2, null,
                        Instant.now(), Instant.now())));
        doReturn(result).when(scanExecutor).execute(any(ScanWorkItem.class));

        assertThat(workerService.processNextScan()).isTrue();
        ScanRun completed = scanRunRepository.findById(scan.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ScanStatus.SUCCEEDED);
        assertThat(completed.getTotalFindings()).isEqualTo(2);
        assertThat(completed.getHighCount()).isEqualTo(1);
        assertThat(completed.getMediumCount()).isEqualTo(1);
        assertThat(scanOutcomeRepository.findAllByScanRun_IdOrderByScannerAsc(scan.getId()))
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.getScanner()).isEqualTo(com.codesentinel.model.ScannerType.SEMGREP);
                    assertThat(outcome.getFindingCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("Persists safe per-scanner outcomes and reports partial success")
    void persistsPartialScannerOutcomes() throws Exception {
        ScanRun scan = createQueuedScan();
        Files.createDirectories(STORAGE_DIRECTORY.resolve(upload.getStorageReference()));
        ScanExecutionResult scannerResult = new ScanExecutionResult(List.of(
                new NormalizedScanFinding("rule.high", ScanFindingSeverity.HIGH, "Safe message",
                        "src/Main.java", 1, 1, 1, 2, "SEMGREP", Map.of())));
        ScanBatchResult result = new ScanBatchResult(scannerResult.findings(), List.of(
                new ScannerExecutionOutcome(com.codesentinel.model.ScannerType.SEMGREP,
                        com.codesentinel.model.ScannerExecutionStatus.SUCCEEDED, 1, null,
                        Instant.now(), Instant.now()),
                new ScannerExecutionOutcome(com.codesentinel.model.ScannerType.GITLEAKS,
                        com.codesentinel.model.ScannerExecutionStatus.FAILED, 0,
                        com.codesentinel.model.ScannerFailureCode.TIMEOUT, Instant.now(), Instant.now())));
        doReturn(result).when(scanExecutor).execute(any(ScanWorkItem.class));

        assertThat(workerService.processNextScan()).isTrue();

        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.PARTIAL);
        assertThat(scanOutcomeRepository.findAllByScanRun_IdOrderByScannerAsc(scan.getId()))
                .hasSize(2)
                .anySatisfy(outcome -> {
                    assertThat(outcome.getScanner()).isEqualTo(com.codesentinel.model.ScannerType.GITLEAKS);
                    assertThat(outcome.getStatus())
                            .isEqualTo(com.codesentinel.model.ScannerExecutionStatus.FAILED);
                    assertThat(outcome.getFailureCode())
                            .isEqualTo(com.codesentinel.model.ScannerFailureCode.TIMEOUT);
                    assertThat(outcome.getFindingCount()).isZero();
                });
    }

    @Test
    @DisplayName("Missing upload storage fails the scan safely")
    void failsWhenUploadDirectoryIsMissing() {
        ScanRun scan = createQueuedScan();

        assertThat(workerService.processNextScan()).isTrue();
        ScanRun saved = scanRunRepository.findById(scan.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Storage-reference traversal fails without resolving outside the configured root")
    void rejectsStoragePathTraversal() {
        upload.setStorageReference("..\\outside");
        uploadRepository.saveAndFlush(upload);
        ScanRun scan = createQueuedScan();

        assertThat(workerService.processNextScan()).isTrue();
        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.FAILED);
        assertThat(Files.exists(STORAGE_DIRECTORY.getParent().resolve("outside"))).isFalse();
    }

    @Test
    @DisplayName("Executor exceptions mark the scan failed")
    void marksScanFailedWhenExecutorThrows() throws Exception {
        ScanRun scan = createQueuedScan();
        Files.createDirectories(STORAGE_DIRECTORY.resolve(upload.getStorageReference()));
        doThrow(new IllegalStateException("placeholder failure"))
                .when(scanExecutor).execute(any(ScanWorkItem.class));

        assertThat(workerService.processNextScan()).isTrue();
        ScanRun saved = scanRunRepository.findById(scan.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Expired worker leases recover abandoned scans to FAILED")
    void recoversExpiredScanLease() {
        ScanRun scan = createQueuedScan();
        coordinator.claimNext(1).orElseThrow();

        assertThat(coordinator.failExpiredScans(Instant.now().plusSeconds(2))).isEqualTo(1);
        ScanRun saved = scanRunRepository.findById(scan.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getLeaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("An expired claim cannot overwrite the result of a later claim")
    void rejectsStaleClaimCompletion() {
        ScanRun scan = createQueuedScan();
        ScanRunCoordinator.ClaimedScan oldClaim = coordinator.claimNext(1).orElseThrow();
        assertThat(coordinator.failExpiredScans(Instant.now().plusSeconds(2))).isEqualTo(1);

        ScanRun recovered = scanRunRepository.findById(scan.getId()).orElseThrow();
        recovered.setStatus(ScanStatus.QUEUED);
        recovered.setCompletedAt(null);
        scanRunRepository.saveAndFlush(recovered);
        ScanRunCoordinator.ClaimedScan newClaim = coordinator.claimNext(60_000).orElseThrow();

        assertThat(oldClaim.claimToken()).isNotEqualTo(newClaim.claimToken());
        assertThat(coordinator.renew(scan.getId(), newClaim.claimToken(), 60_000)).isTrue();
        assertThat(coordinator.renew(scan.getId(), oldClaim.claimToken(), 60_000)).isFalse();
        assertThat(coordinator.complete(scan.getId(), oldClaim.claimToken(), ScanBatchResult.empty()))
                .isFalse();
        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.RUNNING);
    }

    private ScanRun createQueuedScan() {
        return scanRunRepository.saveAndFlush(ScanRun.builder()
                .projectUpload(upload)
                .status(ScanStatus.QUEUED)
                .build());
    }

    private static Path createTemporaryStorageDirectory() {
        try {
            return Files.createTempDirectory("codesentinel-worker-tests-");
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void clearStorageDirectory() throws IOException {
        try (var children = Files.list(STORAGE_DIRECTORY)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
