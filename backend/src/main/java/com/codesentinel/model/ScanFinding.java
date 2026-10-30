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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_findings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_scan_findings_scan_fingerprint",
                columnNames = {"scan_id", "fingerprint"}),
        indexes = {
                @Index(name = "idx_scan_findings_scan", columnList = "scan_id"),
                @Index(name = "idx_scan_findings_scanner_severity", columnList = "scanner,severity")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ScanFinding {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private ScanRun scanRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "scanner", nullable = false, length = 20)
    private ScannerType scanner;

    @Column(name = "rule_id", nullable = false, length = 256)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ScanFindingSeverity severity;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "start_column")
    private Integer startColumn;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "end_column")
    private Integer endColumn;

    @Column(name = "description", nullable = false, length = 128)
    private String description;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
