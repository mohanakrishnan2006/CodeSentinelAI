package com.codesentinel.service;

import com.codesentinel.model.ScannerExecutionStatus;
import com.codesentinel.model.ScannerFailureCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "codesentinel.scan-worker.executor", havingValue = "composite")
public class CompositeScanExecutor implements ScanExecutor {

    private final List<ScannerAdapter> adapters;

    @Override
    public ScanBatchResult execute(ScanWorkItem workItem) {
        if (adapters.isEmpty()) {
            throw new IllegalStateException("No scanner adapters are configured");
        }
        List<NormalizedScanFinding> findings = new ArrayList<>();
        List<ScannerExecutionOutcome> outcomes = new ArrayList<>();
        adapters.stream()
                .sorted(Comparator.comparing(ScannerAdapter::scannerType))
                .forEach(adapter -> executeAdapter(adapter, workItem, findings, outcomes));
        return new ScanBatchResult(findings, outcomes);
    }

    private void executeAdapter(
            ScannerAdapter adapter,
            ScanWorkItem workItem,
            List<NormalizedScanFinding> findings,
            List<ScannerExecutionOutcome> outcomes) {
        Instant startedAt = Instant.now();
        try {
            ScanExecutionResult result = adapter.execute(workItem);
            findings.addAll(result.findings());
            outcomes.add(new ScannerExecutionOutcome(
                    adapter.scannerType(),
                    ScannerExecutionStatus.SUCCEEDED,
                    result.totalFindings(),
                    null,
                    startedAt,
                    Instant.now()));
        } catch (Exception ex) {
            ScannerFailureCode failureCode = ex instanceof ScanExecutionException scanException
                    ? scanException.failureCode()
                    : ScannerFailureCode.EXECUTION_ERROR;
            outcomes.add(new ScannerExecutionOutcome(
                    adapter.scannerType(),
                    ScannerExecutionStatus.FAILED,
                    0,
                    failureCode,
                    startedAt,
                    Instant.now()));
        }
    }
}
