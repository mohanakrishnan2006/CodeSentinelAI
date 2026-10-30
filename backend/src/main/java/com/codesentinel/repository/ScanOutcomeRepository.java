package com.codesentinel.repository;

import com.codesentinel.model.ScanOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScanOutcomeRepository extends JpaRepository<ScanOutcome, UUID> {

    List<ScanOutcome> findAllByScanRun_IdOrderByScannerAsc(UUID scanId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ScanOutcome outcome
            where outcome.scanRun.projectUpload.id in :uploadIds
            """)
    int deleteAllForUploads(@Param("uploadIds") List<UUID> uploadIds);
}
