package com.codesentinel.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "codesentinel.scan-worker")
public class ScanWorkerProperties {

    private boolean enabled;

    @Positive
    private long pollIntervalMs = 1000;

    @Positive
    private long leaseTimeoutMs = 300000;
}
