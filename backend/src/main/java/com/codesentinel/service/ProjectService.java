package com.codesentinel.service;

import com.codesentinel.dto.ProjectRequest;
import com.codesentinel.dto.ProjectResponse;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.exception.ScanInProgressException;
import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanFindingRepository;
import com.codesentinel.repository.ScanOutcomeRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final ProjectUploadRepository uploadRepository;
    private final ScanFindingRepository scanFindingRepository;
    private final ScanOutcomeRepository scanOutcomeRepository;
    private final ScanRunRepository scanRunRepository;
    private final UserRepository userRepository;
    private final UploadStorageService uploadStorageService;

    @Transactional
    public ProjectResponse create(ProjectRequest request, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        Project project = Project.builder()
                .name(request.getName().trim())
                .description(normalizeDescription(request.getDescription()))
                .owner(owner)
                .build();
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        return projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId())
                .stream()
                .map(ProjectResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        return ProjectResponse.fromEntity(findOwnedProject(projectId, owner.getId()));
    }

    @Transactional
    public ProjectResponse update(UUID projectId, ProjectRequest request, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        Project project = findOwnedProject(projectId, owner.getId());
        project.setName(request.getName().trim());
        project.setDescription(normalizeDescription(request.getDescription()));
        return ProjectResponse.fromEntity(projectRepository.saveAndFlush(project));
    }

    @Transactional
    public void delete(UUID projectId, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        Project project = projectRepository.findByIdAndOwnerIdForUpdate(projectId, owner.getId())
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (scanRunRepository.countByProjectUpload_Project_IdAndStatus(projectId, ScanStatus.RUNNING) > 0) {
            throw new ScanInProgressException(projectId);
        }
        scanRunRepository.cancelQueuedForProject(
                projectId, ScanStatus.QUEUED, ScanStatus.CANCELLED, Instant.now());
        List<ProjectUpload> uploads = uploadRepository.findAllForOwnedProject(projectId, owner.getId());
        List<UUID> uploadIds = uploads.stream().map(ProjectUpload::getId).toList();
        if (!uploadIds.isEmpty()) {
            scanFindingRepository.deleteAllForUploads(uploadIds);
            scanOutcomeRepository.deleteAllForUploads(uploadIds);
            scanRunRepository.deleteAllForUploads(uploadIds);
        }
        uploadRepository.deleteAll(uploads);
        projectRepository.delete(project);
        cleanupUploadsAfterCommit(uploadIds);
    }

    private void cleanupUploadsAfterCommit(List<UUID> uploadIds) {
        if (uploadIds.isEmpty()) {
            return;
        }
        Runnable cleanup = () -> uploadIds.forEach(uploadId -> {
            try {
                uploadStorageService.deleteUploadDirectory(uploadId);
            } catch (RuntimeException ex) {
                log.error("Failed to remove stored files for upload {}", uploadId, ex);
            }
        });
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"));
    }

    private Project findOwnedProject(UUID projectId, UUID ownerId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
