package com.codesentinel.service;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "codesentinel.scan-worker.executor", havingValue = "placeholder")
public class PlaceholderScanExecutor implements ScanExecutor {

    @Override
    public ScanBatchResult execute(ScanWorkItem workItem) {
        return ScanBatchResult.empty();
    }
}
