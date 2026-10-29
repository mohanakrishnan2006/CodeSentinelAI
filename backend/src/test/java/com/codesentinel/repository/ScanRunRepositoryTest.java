package com.codesentinel.repository;

import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ScanRunRepositoryTest {

    @Autowired
    private ScanRunRepository scanRunRepository;
    @Autowired
    private ProjectUploadRepository uploadRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private UUID ownerId;
    private UUID ownerProjectId;
    private UUID ownerUploadId;

    @BeforeEach
    void setUp() {
        scanRunRepository.deleteAll();
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User owner = saveUser("repository.owner@example.com");
        User other = saveUser("repository.other@example.com");
        Project ownedProject = saveProject(owner);
        Project otherProject = saveProject(other);
        ownerId = owner.getId();
        ownerProjectId = ownedProject.getId();
        ownerUploadId = saveUpload(ownedProject).getId();
        ProjectUpload otherUpload = saveUpload(otherProject);
        scanRunRepository.save(ScanRun.builder().projectUpload(otherUpload)
                .status(ScanStatus.QUEUED).build());
        scanRunRepository.save(ScanRun.builder()
                .projectUpload(uploadRepository.findById(ownerUploadId).orElseThrow())
                .status(ScanStatus.QUEUED).build());
    }

    @Test
    @DisplayName("History and detail queries restrict scans through the project owner")
    void scopesQueriesToOwner() {
        assertThat(scanRunRepository.findAllForOwnedUpload(ownerUploadId, ownerProjectId, ownerId))
                .hasSize(1);

        ScanRun ownedScan = scanRunRepository.findAllForOwnedUpload(ownerUploadId, ownerProjectId, ownerId)
                .get(0);
        assertThat(scanRunRepository.findOneForOwner(ownedScan.getId(), ownerId)).isPresent();
        assertThat(scanRunRepository.findOneForOwner(ownedScan.getId(), UUID.randomUUID())).isEmpty();
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder().fullName("Repository User").email(email)
                .passwordHash("unused").role(Role.USER).build());
    }

    private Project saveProject(User owner) {
        return projectRepository.save(Project.builder().name("Repository project").owner(owner).build());
    }

    private ProjectUpload saveUpload(Project project) {
        UUID id = UUID.randomUUID();
        return uploadRepository.save(ProjectUpload.builder().id(id).project(project)
                .originalFilename("source.zip").status(UploadStatus.COMPLETED)
                .fileCount(1).extractedSizeBytes(1).storageReference(id.toString()).build());
    }
}
