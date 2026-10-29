package com.codesentinel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_runs", indexes = {
        @Index(name = "idx_scan_runs_upload_requested", columnList = "upload_id, requested_at"),
        @Index(name = "idx_scan_runs_status_requested", columnList = "status, requested_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "projectUpload")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ScanRun {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upload_id", nullable = false)
    private ProjectUpload projectUpload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ScanStatus status = ScanStatus.QUEUED;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_findings", nullable = false)
    @Builder.Default
    private int totalFindings = 0;

    @Column(name = "critical_count", nullable = false)
    @Builder.Default
    private int criticalCount = 0;

    @Column(name = "high_count", nullable = false)
    @Builder.Default
    private int highCount = 0;

    @Column(name = "medium_count", nullable = false)
    @Builder.Default
    private int mediumCount = 0;

    @Column(name = "low_count", nullable = false)
    @Builder.Default
    private int lowCount = 0;

    @Column(name = "info_count", nullable = false)
    @Builder.Default
    private int infoCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = ScanStatus.QUEUED;
        }
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
