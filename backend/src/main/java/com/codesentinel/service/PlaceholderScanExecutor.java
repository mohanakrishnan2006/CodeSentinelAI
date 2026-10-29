package com.codesentinel.service;

import org.springframework.stereotype.Component;

@Component
public class PlaceholderScanExecutor implements ScanExecutor {

    @Override
    public void execute(ScanWorkItem workItem) {
        // Scanner integrations will replace this no-op in later milestones.
    }
}
