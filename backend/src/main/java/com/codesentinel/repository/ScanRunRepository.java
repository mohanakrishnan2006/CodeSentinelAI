package com.codesentinel.repository;

import com.codesentinel.model.ScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanRunRepository extends JpaRepository<ScanRun, UUID> {

    @Query("""
            select scan from ScanRun scan
            where scan.projectUpload.id = :uploadId
              and scan.projectUpload.project.id = :projectId
              and scan.projectUpload.project.owner.id = :ownerId
            order by scan.requestedAt desc
            """)
    List<ScanRun> findAllForOwnedUpload(
            @Param("uploadId") UUID uploadId,
            @Param("projectId") UUID projectId,
            @Param("ownerId") UUID ownerId);

    @Query("""
            select scan from ScanRun scan
            where scan.id = :scanId
              and scan.projectUpload.project.owner.id = :ownerId
            """)
    Optional<ScanRun> findOneForOwner(
            @Param("scanId") UUID scanId,
            @Param("ownerId") UUID ownerId);

    @Modifying
    @Query("delete from ScanRun scan where scan.projectUpload.id in :uploadIds")
    int deleteAllForUploads(@Param("uploadIds") List<UUID> uploadIds);
}
