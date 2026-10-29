package com.codesentinel.controller;

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
import com.codesentinel.service.ScanRunCoordinator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanControllerTest {

    private static final String OWNER_EMAIL = "scan.controller.owner@example.com";
    private static final String OTHER_EMAIL = "scan.controller.other@example.com";
    private static final Path STORAGE_DIRECTORY = createTemporaryStorageDirectory();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectUploadRepository uploadRepository;
    @SpyBean
    private ScanRunRepository scanRunRepository;
    @Autowired
    private ScanRunCoordinator scanRunCoordinator;

    private UUID ownerProjectId;
    private UUID ownerUploadId;
    private UUID ownerOtherProjectUploadId;
    private UUID otherProjectId;
    private UUID otherUploadId;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("codesentinel.upload.storage-directory", () -> STORAGE_DIRECTORY.toString());
    }

    @BeforeEach
    void setUp() throws IOException {
        scanRunRepository.deleteAll();
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        clearStorageDirectory();

        User owner = saveUser(OWNER_EMAIL);
        User other = saveUser(OTHER_EMAIL);
        Project ownerProject = saveProject(owner, "Owner project");
        Project otherProject = saveProject(other, "Other project");
        Project secondOwnerProject = saveProject(owner, "Second owner project");
        ownerProjectId = ownerProject.getId();
        otherProjectId = otherProject.getId();
        ownerUploadId = saveUpload(ownerProject).getId();
        otherUploadId = saveUpload(otherProject).getId();
        ownerOtherProjectUploadId = saveUpload(secondOwnerProject).getId();
    }

    @AfterAll
    static void cleanTestStorage() throws IOException {
        deleteTree(STORAGE_DIRECTORY);
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Creates a queued scan and returns only safe scan metadata")
    void createsScan() throws Exception {
        mockMvc.perform(post(scanCollectionUrl(ownerProjectId, ownerUploadId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.projectId", is(ownerProjectId.toString())))
                .andExpect(jsonPath("$.uploadId", is(ownerUploadId.toString())))
                .andExpect(jsonPath("$.status", is("QUEUED")))
                .andExpect(jsonPath("$.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.totalFindings", is(0)))
                .andExpect(jsonPath("$.criticalCount", is(0)))
                .andExpect(jsonPath("$.storageReference").doesNotExist())
                .andExpect(jsonPath("$.rawOutput").doesNotExist());
        assertThat(scanRunRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Scan history and detail are available to the owning user")
    void retrievesScanHistoryAndDetail() throws Exception {
        ScanRun saved = saveScan(saveOwnedUpload());

        mockMvc.perform(get(scanCollectionUrl(ownerProjectId, ownerUploadId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(saved.getId().toString())));
        mockMvc.perform(get("/api/v1/scans/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId", is(ownerUploadId.toString())));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Upload/project mismatch is concealed as not found")
    void rejectsUploadFromDifferentProject() throws Exception {
        mockMvc.perform(post(scanCollectionUrl(ownerProjectId, otherUploadId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(scanCollectionUrl(ownerProjectId, ownerOtherProjectUploadId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Another user's projects and scans are concealed as not found")
    void enforcesOwnership() throws Exception {
        ScanRun otherScan = saveScan(uploadRepository.findById(otherUploadId).orElseThrow());

        mockMvc.perform(post(scanCollectionUrl(otherProjectId, otherUploadId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(scanCollectionUrl(otherProjectId, otherUploadId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/scans/" + otherScan.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Scan endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post(scanCollectionUrl(ownerProjectId, ownerUploadId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(scanCollectionUrl(ownerProjectId, ownerUploadId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/scans/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Project deletion removes scan metadata and still cleans external upload files")
    void deletingProjectRemovesScansAndStoredFiles() throws Exception {
        ProjectUpload upload = saveOwnedUpload();
        ScanRun scan = saveScan(upload);
        Path uploadDirectory = STORAGE_DIRECTORY.resolve(upload.getStorageReference());
        Files.createDirectories(uploadDirectory);
        Path sourceFile = uploadDirectory.resolve("source.txt");
        Files.writeString(sourceFile, "source");

        mockMvc.perform(delete("/api/v1/projects/" + ownerProjectId))
                .andExpect(status().isNoContent());

        assertThat(scanRunRepository.findById(scan.getId())).isEmpty();
        assertThat(uploadRepository.findById(upload.getId())).isEmpty();
        assertThat(Files.exists(uploadDirectory)).isFalse();
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Deleting a project cancels queued scans before removing scan and upload metadata")
    void deletingProjectWithQueuedScanCleansUp() throws Exception {
        ProjectUpload upload = saveOwnedUpload();
        ScanRun scan = saveScan(upload);

        mockMvc.perform(delete("/api/v1/projects/" + ownerProjectId))
                .andExpect(status().isNoContent());

        assertThat(scanRunRepository.findById(scan.getId())).isEmpty();
        assertThat(uploadRepository.findById(upload.getId())).isEmpty();
        assertThat(projectRepository.findById(ownerProjectId)).isEmpty();
        assertThat(scanRunCoordinator.claimNext(60_000)).isEmpty();
        org.mockito.InOrder deletionOrder = org.mockito.Mockito.inOrder(scanRunRepository);
        deletionOrder.verify(scanRunRepository).cancelQueuedForProject(
                org.mockito.ArgumentMatchers.eq(ownerProjectId),
                org.mockito.ArgumentMatchers.eq(ScanStatus.QUEUED),
                org.mockito.ArgumentMatchers.eq(ScanStatus.CANCELLED),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        deletionOrder.verify(scanRunRepository).deleteAllForUploads(
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Deleting a project with a running scan returns 409 and preserves its data")
    void rejectsDeletingProjectWithRunningScan() throws Exception {
        ProjectUpload upload = saveOwnedUpload();
        ScanRun scan = scanRunRepository.save(ScanRun.builder()
                .projectUpload(upload)
                .status(ScanStatus.RUNNING)
                .startedAt(java.time.Instant.now())
                .leaseExpiresAt(java.time.Instant.now().plusSeconds(60))
                .build());

        mockMvc.perform(delete("/api/v1/projects/" + ownerProjectId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",
                        is("The project cannot be deleted while a scan is running")));

        assertThat(projectRepository.findById(ownerProjectId)).isPresent();
        assertThat(uploadRepository.findById(upload.getId())).isPresent();
        assertThat(scanRunRepository.findById(scan.getId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.RUNNING);
    }

    private String scanCollectionUrl(UUID projectId, UUID uploadId) {
        return "/api/v1/projects/" + projectId + "/uploads/" + uploadId + "/scans";
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder().fullName("Scan User").email(email)
                .passwordHash("not-used-by-this-test").role(Role.USER).build());
    }

    private Project saveProject(User owner, String name) {
        return projectRepository.save(Project.builder().name(name).owner(owner).build());
    }

    private ProjectUpload saveUpload(Project project) {
        UUID id = UUID.randomUUID();
        return uploadRepository.save(ProjectUpload.builder()
                .id(id).project(project).originalFilename("source.zip")
                .status(UploadStatus.COMPLETED).fileCount(1).extractedSizeBytes(6)
                .storageReference(id.toString()).build());
    }

    private ProjectUpload saveOwnedUpload() {
        return uploadRepository.findById(ownerUploadId).orElseThrow();
    }

    private ScanRun saveScan(ProjectUpload upload) {
        return scanRunRepository.save(ScanRun.builder().projectUpload(upload)
                .status(ScanStatus.QUEUED).build());
    }

    private static Path createTemporaryStorageDirectory() {
        try {
            return Files.createTempDirectory("codesentinel-scan-tests-");
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void clearStorageDirectory() throws IOException {
        try (var children = Files.list(STORAGE_DIRECTORY)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
