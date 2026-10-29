package com.codesentinel.repository;

import com.codesentinel.model.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from Project project where project.id = :projectId")
    Optional<Project> findByIdForUpdate(@Param("projectId") UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select project from Project project
            where project.id = :projectId and project.owner.id = :ownerId
            """)
    Optional<Project> findByIdAndOwnerIdForUpdate(
            @Param("projectId") UUID projectId,
            @Param("ownerId") UUID ownerId);
}
