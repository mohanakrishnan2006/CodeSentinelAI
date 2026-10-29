package com.codesentinel.service;

import com.codesentinel.dto.ScanRunResponse;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.exception.ScanNotFoundException;
import com.codesentinel.exception.UploadNotFoundException;
import com.codesentinel.exception.UploadNotReadyException;
import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanServiceTest {

    private static final String OWNER_EMAIL = "scan.owner@example.com";

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectUploadRepository uploadRepository;
    @Mock
    private ScanRunRepository scanRunRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ScanService scanService;

    private User owner;
    private Project project;
    private ProjectUpload upload;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).email(OWNER_EMAIL)
                .fullName("Scan Owner").passwordHash("unused").role(Role.USER).build();
        project = Project.builder().id(UUID.randomUUID()).name("Project").owner(owner).build();
        upload = ProjectUpload.builder().id(UUID.randomUUID()).project(project)
                .status(UploadStatus.COMPLETED).storageReference(UUID.randomUUID().toString())
                .originalFilename("source.zip").fileCount(1).extractedSizeBytes(1).build();

        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        lenient().when(projectRepository.findByIdAndOwnerIdForUpdate(project.getId(), owner.getId()))
                .thenReturn(Optional.of(project));
        lenient().when(uploadRepository.findOneForOwnedProject(upload.getId(), project.getId(), owner.getId()))
                .thenReturn(Optional.of(upload));
    }

    @Test
    @DisplayName("Creates a queued scan for the authenticated owner's completed upload")
    void createsQueuedScan() {
        when(scanRunRepository.saveAndFlush(any(ScanRun.class))).thenAnswer(invocation -> {
            ScanRun scan = invocation.getArgument(0);
            scan.setId(UUID.randomUUID());
            scan.setRequestedAt(java.time.Instant.now());
            scan.setCreatedAt(scan.getRequestedAt());
            scan.setUpdatedAt(scan.getRequestedAt());
            return scan;
        });

        ScanRunResponse response = scanService.create(project.getId(), upload.getId(), OWNER_EMAIL);

        assertThat(response.getStatus()).isEqualTo(ScanStatus.QUEUED);
        assertThat(response.getProjectId()).isEqualTo(project.getId());
        assertThat(response.getUploadId()).isEqualTo(upload.getId());
        assertThat(response.getTotalFindings()).isZero();
        verify(scanRunRepository).saveAndFlush(any(ScanRun.class));
    }

    @Test
    @DisplayName("Does not create a scan when the upload belongs to a different project")
    void rejectsUploadProjectMismatch() {
        assertThrows(UploadNotFoundException.class,
                () -> scanService.create(project.getId(), UUID.randomUUID(), OWNER_EMAIL));
    }

    @Test
    @DisplayName("Does not create a scan for an upload that is not completed")
    void rejectsIncompleteUpload() {
        upload.setStatus(null);

        assertThrows(UploadNotReadyException.class,
                () -> scanService.create(project.getId(), upload.getId(), OWNER_EMAIL));
    }

    @Test
    @DisplayName("A project outside the authenticated user's ownership is not found")
    void rejectsAnotherUsersProject() {
        when(projectRepository.findByIdAndOwnerIdForUpdate(project.getId(), owner.getId()))
                .thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class,
                () -> scanService.create(project.getId(), upload.getId(), OWNER_EMAIL));
    }

    @Test
    @DisplayName("Lists scans for an owned upload")
    void listsOwnedUploadScans() {
        ScanRun scan = ScanRun.builder().id(UUID.randomUUID()).projectUpload(upload)
                .status(ScanStatus.QUEUED).build();
        when(scanRunRepository.findAllForOwnedUpload(upload.getId(), project.getId(), owner.getId()))
                .thenReturn(List.of(scan));

        assertThat(scanService.list(project.getId(), upload.getId(), OWNER_EMAIL))
                .singleElement()
                .extracting(ScanRunResponse::getUploadId)
                .isEqualTo(upload.getId());
    }

    @Test
    @DisplayName("Scan details are only returned through an owner-scoped query")
    void retrievesOwnedScan() {
        UUID scanId = UUID.randomUUID();
        ScanRun scan = ScanRun.builder().id(scanId).projectUpload(upload)
                .status(ScanStatus.QUEUED).build();
        when(scanRunRepository.findOneForOwner(scanId, owner.getId())).thenReturn(Optional.of(scan));

        assertThat(scanService.get(scanId, OWNER_EMAIL).getId()).isEqualTo(scanId);
        verify(scanRunRepository).findOneForOwner(scanId, owner.getId());
    }

    @Test
    @DisplayName("A scan outside the authenticated user's ownership is not found")
    void hidesAnotherUsersScan() {
        UUID scanId = UUID.randomUUID();
        when(scanRunRepository.findOneForOwner(scanId, owner.getId())).thenReturn(Optional.empty());

        assertThrows(ScanNotFoundException.class, () -> scanService.get(scanId, OWNER_EMAIL));
    }
}
