package com.codesentinel.service;

import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.ScannerExecutionStatus;
import com.codesentinel.model.ScannerFailureCode;
import com.codesentinel.model.ScannerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeScanExecutorTest {

    private static final ScanWorkItem WORK_ITEM =
            new ScanWorkItem(UUID.randomUUID(), UUID.randomUUID(), Path.of("source"));

    @Test
    @DisplayName("Runs both scanners, aggregates findings, and returns a successful batch")
    void aggregatesSuccessfulAdapters() {
        CompositeScanExecutor executor = new CompositeScanExecutor(List.of(
                adapter(ScannerType.GITLEAKS, ScanExecutionResult.empty(), null),
                adapter(ScannerType.SEMGREP, result(ScanFindingSeverity.MEDIUM), null)));

        ScanBatchResult result = executor.execute(WORK_ITEM);

        assertThat(result.status()).isEqualTo(ScanStatus.SUCCEEDED);
        assertThat(result.totalFindings()).isEqualTo(1);
        assertThat(result.outcomes()).extracting(ScannerExecutionOutcome::scanner)
                .containsExactly(ScannerType.SEMGREP, ScannerType.GITLEAKS);
        assertThat(result.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.status()).isEqualTo(ScannerExecutionStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("Keeps successful scanner findings when another scanner fails")
    void returnsPartialForOneScannerFailure() {
        CompositeScanExecutor executor = new CompositeScanExecutor(List.of(
                adapter(ScannerType.SEMGREP, result(ScanFindingSeverity.HIGH), null),
                adapter(ScannerType.GITLEAKS, null,
                        new ScanExecutionException("secret error", ScannerFailureCode.EXECUTION_ERROR))));

        ScanBatchResult result = executor.execute(WORK_ITEM);

        assertThat(result.status()).isEqualTo(ScanStatus.PARTIAL);
        assertThat(result.totalFindings()).isEqualTo(1);
        assertThat(result.outcomes()).filteredOn(outcome ->
                outcome.status() == ScannerExecutionStatus.FAILED)
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.failureCode()).isEqualTo(ScannerFailureCode.EXECUTION_ERROR);
                    assertThat(outcome.findingCount()).isZero();
                    assertThat(outcome.toString()).doesNotContain("secret error");
                });
    }

    @Test
    @DisplayName("Returns failed when every scanner fails and rejects an empty scanner configuration")
    void computesAllFailedAndNoAdapterStatuses() {
        CompositeScanExecutor failed = new CompositeScanExecutor(List.of(
                adapter(ScannerType.SEMGREP, null, new IllegalStateException("private details")),
                adapter(ScannerType.GITLEAKS, null, new IllegalStateException("private details"))));
        assertThat(failed.execute(WORK_ITEM).status()).isEqualTo(ScanStatus.FAILED);
        assertThrows(IllegalStateException.class, () -> new CompositeScanExecutor(List.of()).execute(WORK_ITEM));
    }

    private static ScannerAdapter adapter(
            ScannerType scannerType,
            ScanExecutionResult result,
            RuntimeException failure) {
        return new ScannerAdapter() {
            @Override
            public ScannerType scannerType() {
                return scannerType;
            }

            @Override
            public ScanExecutionResult execute(ScanWorkItem workItem) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    private static ScanExecutionResult result(ScanFindingSeverity severity) {
        return new ScanExecutionResult(List.of(new NormalizedScanFinding(
                "safe-rule", severity, "safe generic message", "src/file.txt",
                1, 1, 1, 2, "TEST", java.util.Map.of())));
    }
}
