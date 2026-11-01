package com.codesentinel.repository;

import com.codesentinel.model.ScanFindingAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanFindingAnalysisRepository extends JpaRepository<ScanFindingAnalysis, UUID> {

    Optional<ScanFindingAnalysis> findByFindingId(UUID findingId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ScanFindingAnalysis analysis
            where analysis.finding.scanRun.projectUpload.id in :uploadIds
            """)
    int deleteAllForUploads(@Param("uploadIds") List<UUID> uploadIds);
}
