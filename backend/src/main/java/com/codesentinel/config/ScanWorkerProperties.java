package com.codesentinel.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.regex.Pattern;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "codesentinel.scan-worker")
public class ScanWorkerProperties {

    private static final Pattern PINNED_SEMGREP_IMAGE = Pattern.compile(
            "semgrep/semgrep:[0-9]+\\.[0-9]+\\.[0-9]+-nonroot@sha256:[a-f0-9]{64}");
    private static final Pattern PINNED_GITLEAKS_IMAGE = Pattern.compile(
            "ghcr\\.io/gitleaks/gitleaks:v[0-9]+\\.[0-9]+\\.[0-9]+@sha256:[a-f0-9]{64}");
    private static final Pattern PINNED_TRIVY_IMAGE = Pattern.compile(
            "aquasec/trivy:[0-9]+\\.[0-9]+\\.[0-9]+@sha256:[a-f0-9]{64}");

    private boolean enabled;

    @Positive
    private long pollIntervalMs = 1000;

    @Positive
    private long leaseTimeoutMs = 300000;

    @Positive
    private long leaseHeartbeatIntervalMs = 30000;

    @Positive
    private long executionTimeoutMs = 120000;

    @Positive
    private long dockerCommandTimeoutMs = 15000;

    @Positive
    private int maxOutputBytes = 10 * 1024 * 1024;

    @Positive
    private long maxSourceBytes = 262_144_000;

    @Positive
    private long memoryLimitBytes = 512L * 1024 * 1024;

    @Positive
    private int processLimit = 128;

    @Positive
    private long temporaryMemoryBytes = 64L * 1024 * 1024;

    @Positive
    private double cpuLimit = 1.0;

    private String semgrepImage =
            "semgrep/semgrep:1.182.1-nonroot@sha256:0ed16bbbb23e5bf7c442df9144704d74b54ad2d003094e09905130d642f22473";

    private String gitleaksImage =
            "ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f";

    private String trivyImage =
            "aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c";

    private String trivyDatabaseDirectory =
            Path.of(System.getProperty("user.home"), ".cache", "trivy").toString();

    private String temporaryDirectory =
            Path.of(System.getProperty("java.io.tmpdir"), "codesentinel-scan-worker").toString();

    @jakarta.validation.constraints.AssertTrue(message =
            "The worker lease must exceed scanner and cleanup deadlines by at least 30 seconds")
    public boolean isLeaseLongerThanExecutionAndCleanup() {
        return leaseTimeoutMs > executionTimeoutMs + (2 * dockerCommandTimeoutMs) + 30000;
    }

    @jakarta.validation.constraints.AssertTrue(message =
            "The worker lease heartbeat interval must be no more than one third of the lease timeout")
    public boolean isLeaseHeartbeatShorterThanLease() {
        return leaseHeartbeatIntervalMs <= leaseTimeoutMs / 3;
    }

    @jakarta.validation.constraints.AssertTrue(message =
            "Semgrep image must be pinned to an official non-root version and digest")
    public boolean isSemgrepImagePinned() {
        return semgrepImage != null && PINNED_SEMGREP_IMAGE.matcher(semgrepImage).matches();
    }

    @jakarta.validation.constraints.AssertTrue(message =
            "Gitleaks image must be pinned to an official version and digest")
    public boolean isGitleaksImagePinned() {
        return gitleaksImage != null && PINNED_GITLEAKS_IMAGE.matcher(gitleaksImage).matches();
    }

    @jakarta.validation.constraints.AssertTrue(message =
            "Trivy image must be pinned to an official version and digest")
    public boolean isTrivyImagePinned() {
        return trivyImage != null && PINNED_TRIVY_IMAGE.matcher(trivyImage).matches();
    }
}
