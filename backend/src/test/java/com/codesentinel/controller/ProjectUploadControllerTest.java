package com.codesentinel.controller;

import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.exception.UploadStorageException;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectUploadControllerTest {

    private static final String UPLOADS_URL = "/api/v1/projects/%s/uploads";
    private static final String OWNER_EMAIL = "upload.owner@example.com";
    private static final String OTHER_EMAIL = "upload.other@example.com";
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
    private com.codesentinel.service.UploadStorageService storageService;

    private UUID ownerProjectId;
    private UUID otherProjectId;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("codesentinel.upload.storage-directory", () -> STORAGE_DIRECTORY.toString());
        registry.add("codesentinel.upload.max-files", () -> 2);
    }

    @BeforeEach
    void setUp() throws IOException {
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        clearStorageDirectory();

        User owner = saveUser(OWNER_EMAIL);
        User other = saveUser(OTHER_EMAIL);
        ownerProjectId = saveProject(owner, "Owner project").getId();
        otherProjectId = saveProject(other, "Other project").getId();
    }

    @AfterAll
    static void cleanTestStorage() throws IOException {
        deleteTree(STORAGE_DIRECTORY);
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Upload extracts a ZIP and persists metadata without exposing storage paths")
    void uploadsArchiveAndPersistsMetadata() throws Exception {
        MockMultipartFile file = zipFile("../source.zip",
                new String[][]{{"src/Main.java", "class Main {}"}, {"README.md", "test"}});

        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId)).file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/api/v1/projects/" + ownerProjectId + "/uploads/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.projectId", is(ownerProjectId.toString())))
                .andExpect(jsonPath("$.originalFilename", is("source.zip")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.fileCount", is(2)))
                .andExpect(jsonPath("$.extractedSizeBytes", is(17)))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.storageReference").doesNotExist());

        ProjectUpload saved = uploadRepository.findAll().get(0);
        assertThat(saved.getProject().getId()).isEqualTo(ownerProjectId);
        assertThat(saved.getStorageReference()).isEqualTo(saved.getId().toString());
        assertThat(Files.readString(STORAGE_DIRECTORY.resolve(saved.getId().toString())
                .resolve("src/Main.java"))).isEqualTo("class Main {}");
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Upload history and upload details return only metadata for the owned project")
    void retrievesUploadHistoryAndDetails() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId)
                        ).file(zipFile("history.zip", new String[][]{{"file.txt", "history"}})))
                .andExpect(status().isCreated());
        ProjectUpload saved = uploadRepository.findAll().get(0);

        mockMvc.perform(get(String.format(UPLOADS_URL, ownerProjectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(saved.getId().toString())));
        mockMvc.perform(get(String.format(UPLOADS_URL, ownerProjectId) + "/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename", is("history.zip")));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Rejects empty and invalid ZIP archives")
    void rejectsEmptyAndInvalidArchives() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(zipFile("empty.zip", new String[0][])))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(new MockMultipartFile("file", "invalid.zip", "application/zip",
                                "not zip data".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Requires a ZIP filename and a supplied multipart file")
    void rejectsMissingFileAndNonZipFilename() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(new MockMultipartFile("file", "source.txt", "application/zip",
                                zipBytes(new String[][]{{"source.java", "class C {}"}}))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Owner cannot read, upload to, or enumerate another user's project uploads")
    void enforcesProjectOwnership() throws Exception {
        mockMvc.perform(get(String.format(UPLOADS_URL, otherProjectId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(String.format(UPLOADS_URL, otherProjectId) + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart(String.format(UPLOADS_URL, otherProjectId))
                        .file(zipFile("source.zip", new String[][]{{"source.txt", "data"}})))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Upload endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(String.format(UPLOADS_URL, ownerProjectId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(zipFile("source.zip", new String[][]{{"source.txt", "data"}})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Unknown projects and uploads return 404")
    void returnsNotFoundForUnknownProjectAndUpload() throws Exception {
        mockMvc.perform(get(String.format(UPLOADS_URL, UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(String.format(UPLOADS_URL, ownerProjectId) + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Rejects traversal entries through the API")
    void rejectsTraversalArchiveThroughApi() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(zipFile("unsafe.zip", new String[][]{{"../../outside.txt", "unsafe"}})))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Too many extracted files returns 413")
    void rejectsTooManyFilesThroughApi() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(zipFile("many.zip", new String[][]{
                                {"one.txt", "1"}, {"two.txt", "2"}, {"three.txt", "3"}
                        })))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Storage failure returns a safe 500 response")
    void handlesStorageFailureWithoutLeakingFilesystemDetails() throws Exception {
        doThrow(new UploadStorageException("internal C:\\private\\uploads path", new IOException("disk error")))
                .when(storageService).store(any(MultipartFile.class), any(UUID.class));
        try {
            String response = mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                            .file(zipFile("source.zip", new String[][]{{"source.txt", "data"}})))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message", is("The upload could not be stored")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("private", "uploads path", "disk error");
        } finally {
            reset(storageService);
        }
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Deleting a project removes its upload metadata and stored files")
    void deletingProjectCleansUpItsUploads() throws Exception {
        mockMvc.perform(multipart(String.format(UPLOADS_URL, ownerProjectId))
                        .file(zipFile("source.zip", new String[][]{{"source.txt", "data"}})))
                .andExpect(status().isCreated());
        ProjectUpload saved = uploadRepository.findAll().get(0);
        Path uploadDirectory = STORAGE_DIRECTORY.resolve(saved.getStorageReference());

        mockMvc.perform(delete("/api/v1/projects/" + ownerProjectId))
                .andExpect(status().isNoContent());

        assertThat(projectRepository.findById(ownerProjectId)).isEmpty();
        assertThat(uploadRepository.findById(saved.getId())).isEmpty();
        assertThat(Files.exists(uploadDirectory)).isFalse();
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .fullName("Upload User")
                .email(email)
                .passwordHash("not-used-by-this-test")
                .role(Role.USER)
                .build());
    }

    private Project saveProject(User owner, String name) {
        return projectRepository.save(Project.builder().name(name).owner(owner).build());
    }

    private MockMultipartFile zipFile(String filename, String[][] files) throws IOException {
        return new MockMultipartFile("file", filename, "application/zip", zipBytes(files));
    }

    private byte[] zipBytes(String[][] files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Path createTemporaryStorageDirectory() {
        try {
            return Files.createTempDirectory("codesentinel-upload-tests-");
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
