package com.codesentinel.service;

import com.codesentinel.dto.ProjectUploadResponse;
import com.codesentinel.exception.InvalidZipException;
import com.codesentinel.exception.MissingUploadFileException;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.exception.UploadNotFoundException;
import com.codesentinel.exception.UploadStorageException;
import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectUploadService {

    private static final Logger log = LoggerFactory.getLogger(ProjectUploadService.class);

    private final ProjectRepository projectRepository;
    private final ProjectUploadRepository uploadRepository;
    private final UserRepository userRepository;
    private final UploadStorageService storageService;

    @Transactional
    public ProjectUploadResponse upload(UUID projectId, MultipartFile file, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        Project project = findOwnedProject(projectId, owner.getId());
        String originalFilename = validateOriginalFilename(file);
        UUID uploadId = UUID.randomUUID();

        UploadStorageService.StoredUpload stored = storageService.store(file, uploadId);
        ProjectUpload upload = ProjectUpload.builder()
                .id(uploadId)
                .project(project)
                .originalFilename(originalFilename)
                .status(UploadStatus.COMPLETED)
                .fileCount(stored.fileCount())
                .extractedSizeBytes(stored.totalSizeBytes())
                .storageReference(stored.storageReference())
                .build();

        try {
            ProjectUpload savedUpload = uploadRepository.saveAndFlush(upload);
            registerRollbackCleanup(uploadId);
            return ProjectUploadResponse.fromEntity(savedUpload);
        } catch (RuntimeException ex) {
            try {
                storageService.deleteUploadDirectory(uploadId);
            } catch (UploadStorageException cleanupException) {
                ex.addSuppressed(cleanupException);
                log.error("Failed to clean up storage for upload {}", uploadId, cleanupException);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectUploadResponse> list(UUID projectId, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        findOwnedProject(projectId, owner.getId());
        return uploadRepository.findAllForOwnedProject(projectId, owner.getId())
                .stream()
                .map(ProjectUploadResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectUploadResponse get(UUID projectId, UUID uploadId, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        findOwnedProject(projectId, owner.getId());
        ProjectUpload upload = uploadRepository.findOneForOwnedProject(uploadId, projectId, owner.getId())
                .orElseThrow(() -> new UploadNotFoundException(uploadId));
        return ProjectUploadResponse.fromEntity(upload);
    }

    private User findUser(String authenticatedEmail) {
        return userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"));
    }

    private Project findOwnedProject(UUID projectId, UUID ownerId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private String validateOriginalFilename(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MissingUploadFileException();
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidZipException("A ZIP filename is required");
        }
        String safeFilename = originalFilename.replace('\\', '/');
        safeFilename = safeFilename.substring(safeFilename.lastIndexOf('/') + 1).trim();
        if (safeFilename.isEmpty() || safeFilename.length() > 255
                || safeFilename.chars().anyMatch(Character::isISOControl)
                || !safeFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new InvalidZipException("The uploaded file must have a valid .zip filename");
        }
        return safeFilename;
    }

    private void registerRollbackCleanup(UUID uploadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    storageService.deleteUploadDirectory(uploadId);
                } catch (UploadStorageException ex) {
                    log.error("Failed to clean up rolled-back upload {}", uploadId, ex);
                }
            }
        });
    }
}
