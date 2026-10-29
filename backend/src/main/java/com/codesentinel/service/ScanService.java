package com.codesentinel.service;

import com.codesentinel.dto.ScanRunResponse;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.exception.ScanNotFoundException;
import com.codesentinel.exception.UploadNotFoundException;
import com.codesentinel.exception.UploadNotReadyException;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanService {

    private final ProjectRepository projectRepository;
    private final ProjectUploadRepository uploadRepository;
    private final ScanRunRepository scanRunRepository;
    private final UserRepository userRepository;

    @Transactional
    public ScanRunResponse create(UUID projectId, UUID uploadId, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        verifyOwnedProject(projectId, owner.getId());
        ProjectUpload upload = findOwnedUpload(projectId, uploadId, owner.getId());
        if (upload.getStatus() != UploadStatus.COMPLETED) {
            throw new UploadNotReadyException(uploadId);
        }

        ScanRun scanRun = ScanRun.builder()
                .projectUpload(upload)
                .status(ScanStatus.QUEUED)
                .build();
        return ScanRunResponse.fromEntity(scanRunRepository.saveAndFlush(scanRun));
    }

    @Transactional(readOnly = true)
    public List<ScanRunResponse> list(UUID projectId, UUID uploadId, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        verifyOwnedProject(projectId, owner.getId());
        findOwnedUpload(projectId, uploadId, owner.getId());
        return scanRunRepository.findAllForOwnedUpload(uploadId, projectId, owner.getId())
                .stream()
                .map(ScanRunResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScanRunResponse get(UUID scanId, String authenticatedEmail) {
        User owner = findUser(authenticatedEmail);
        ScanRun scanRun = scanRunRepository.findOneForOwner(scanId, owner.getId())
                .orElseThrow(() -> new ScanNotFoundException(scanId));
        return ScanRunResponse.fromEntity(scanRun);
    }

    private User findUser(String authenticatedEmail) {
        return userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"));
    }

    private void verifyOwnedProject(UUID projectId, UUID ownerId) {
        projectRepository.findByIdAndOwnerIdForUpdate(projectId, ownerId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ProjectUpload findOwnedUpload(UUID projectId, UUID uploadId, UUID ownerId) {
        return uploadRepository.findOneForOwnedProject(uploadId, projectId, ownerId)
                .orElseThrow(() -> new UploadNotFoundException(uploadId));
    }
}
