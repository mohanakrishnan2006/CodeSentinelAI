package com.codesentinel.repository;

import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanRunRepository extends JpaRepository<ScanRun, UUID> {

    Optional<ScanRun> findFirstByStatusOrderByRequestedAtAsc(ScanStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ScanRun scan
            set scan.status = :running,
                scan.startedAt = :now,
                scan.leaseExpiresAt = :leaseExpiresAt,
                scan.updatedAt = :now
            where scan.id = :scanId and scan.status = :queued
            """)
    int claimQueuedScan(
            @Param("scanId") UUID scanId,
            @Param("queued") ScanStatus queued,
            @Param("running") ScanStatus running,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ScanRun scan
            set scan.status = :status,
                scan.completedAt = :completedAt,
                scan.leaseExpiresAt = null,
                scan.updatedAt = :completedAt
            where scan.id = :scanId and scan.status = :running
            """)
    int completeRunningScan(
            @Param("scanId") UUID scanId,
            @Param("running") ScanStatus running,
            @Param("status") ScanStatus status,
            @Param("completedAt") Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ScanRun scan
            set scan.status = :failed,
                scan.completedAt = :completedAt,
                scan.leaseExpiresAt = null,
                scan.updatedAt = :completedAt
            where scan.id = :scanId and scan.status = :running
            """)
    int failRunningScan(
            @Param("scanId") UUID scanId,
            @Param("running") ScanStatus running,
            @Param("failed") ScanStatus failed,
            @Param("completedAt") Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ScanRun scan
            set scan.status = :failed,
                scan.completedAt = :completedAt,
                scan.leaseExpiresAt = null,
                scan.updatedAt = :completedAt
            where scan.status = :running and scan.leaseExpiresAt < :now
            """)
    int failExpiredRunningScans(
            @Param("running") ScanStatus running,
            @Param("failed") ScanStatus failed,
            @Param("now") Instant now,
            @Param("completedAt") Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ScanRun scan
            set scan.status = :cancelled,
                scan.completedAt = :completedAt,
                scan.leaseExpiresAt = null,
                scan.updatedAt = :completedAt
            where scan.projectUpload.project.id = :projectId and scan.status = :queued
            """)
    int cancelQueuedForProject(
            @Param("projectId") UUID projectId,
            @Param("queued") ScanStatus queued,
            @Param("cancelled") ScanStatus cancelled,
            @Param("completedAt") Instant completedAt);

    long countByProjectUpload_Project_IdAndStatus(UUID projectId, ScanStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ScanRun scan
            where scan.projectUpload.id in :uploadIds
            """)
    int deleteAllForUploads(@Param("uploadIds") List<UUID> uploadIds);

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
}
