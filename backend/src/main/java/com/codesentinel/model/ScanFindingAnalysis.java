package com.codesentinel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "scan_finding_analyses",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_scan_finding_analyses_finding",
                columnNames = "finding_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "finding")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ScanFindingAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id", nullable = false, unique = true)
    private ScanFinding finding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScanFindingAnalysisStatus status;

    @Column(name = "explanation", length = 3000)
    private String explanation;

    @Column(name = "why_it_matters", length = 2000)
    private String whyItMatters;

    @Column(name = "recommended_remediation", length = 3000)
    private String recommendedRemediation;

    @Column(name = "fix_language", length = 64)
    private String fixLanguage;

    @Column(name = "fix_code", length = 4096)
    private String fixCode;

    @Column(name = "model_identifier", length = 128)
    private String modelIdentifier;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private ScanFindingAnalysisFailureCode failureCode;

    @Column(name = "claim_token", nullable = false, unique = true)
    private UUID claimToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
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
