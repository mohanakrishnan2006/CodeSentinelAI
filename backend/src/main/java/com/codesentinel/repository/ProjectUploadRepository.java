package com.codesentinel.repository;

import com.codesentinel.model.ProjectUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectUploadRepository extends JpaRepository<ProjectUpload, UUID> {

    @Query("""
            select upload from ProjectUpload upload
            where upload.project.id = :projectId and upload.project.owner.id = :ownerId
            order by upload.createdAt desc
            """)
    List<ProjectUpload> findAllForOwnedProject(
            @Param("projectId") UUID projectId,
            @Param("ownerId") UUID ownerId);

    @Query("""
            select upload from ProjectUpload upload
            where upload.id = :uploadId and upload.project.id = :projectId
              and upload.project.owner.id = :ownerId
            """)
    Optional<ProjectUpload> findOneForOwnedProject(
            @Param("uploadId") UUID uploadId,
            @Param("projectId") UUID projectId,
            @Param("ownerId") UUID ownerId);
}
