package com.codesentinel.repository;

import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScannerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanFindingRepository extends JpaRepository<ScanFinding, UUID> {

    @Query("""
            select finding from ScanFinding finding
            where finding.scanRun.id = :scanId
              and finding.scanRun.projectUpload.project.owner.id = :ownerId
              and (:severity is null or finding.severity = :severity)
              and (:scanner is null or finding.scanner = :scanner)
              and (:filePath is null or finding.filePath = :filePath)
            """)
    Page<ScanFinding> findAllForOwner(
            @Param("scanId") UUID scanId,
            @Param("ownerId") UUID ownerId,
            @Param("severity") ScanFindingSeverity severity,
            @Param("scanner") ScannerType scanner,
            @Param("filePath") String filePath,
            Pageable pageable);

    @Query("""
            select finding from ScanFinding finding
            where finding.id = :findingId
              and finding.scanRun.projectUpload.project.owner.id = :ownerId
            """)
    Optional<ScanFinding> findOneForOwner(
            @Param("findingId") UUID findingId,
            @Param("ownerId") UUID ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ScanFinding finding
            where finding.scanRun.projectUpload.id in :uploadIds
            """)
    int deleteAllForUploads(@Param("uploadIds") List<UUID> uploadIds);
}
