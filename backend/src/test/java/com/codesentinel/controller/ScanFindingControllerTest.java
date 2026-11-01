package com.codesentinel.controller;

import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingAnalysis;
import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import com.codesentinel.model.ScanFindingAnalysisStatus;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.ScannerType;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanFindingRepository;
import com.codesentinel.repository.ScanFindingAnalysisRepository;
import com.codesentinel.repository.ScanOutcomeRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import com.codesentinel.service.AiAnalysisClient;
import com.codesentinel.service.ProjectService;
import com.codesentinel.dto.ai.AiAnalysisRequest;
import com.codesentinel.dto.ai.AiAnalysisResponse;
import com.codesentinel.dto.ai.AiIllustrativeFix;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScanFindingControllerTest {

    private static final String OWNER_EMAIL = "finding.owner@example.com";
    private static final String OTHER_EMAIL = "finding.other@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectUploadRepository uploadRepository;
    @Autowired
    private ScanRunRepository scanRunRepository;
    @Autowired
    private ScanOutcomeRepository scanOutcomeRepository;
    @Autowired
    private ScanFindingRepository scanFindingRepository;
    @Autowired
    private ScanFindingAnalysisRepository analysisRepository;
    @Autowired
    private ProjectService projectService;
    @MockBean
    private AiAnalysisClient aiAnalysisClient;

    private UUID ownedScanId;
    private UUID otherScanId;
    private UUID ownedFindingId;
    private UUID ownedProjectId;

    @BeforeEach
    void setUp() {
        analysisRepository.deleteAll();
        scanFindingRepository.deleteAll();
        scanOutcomeRepository.deleteAll();
        scanRunRepository.deleteAll();
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User owner = saveUser(OWNER_EMAIL);
        User other = saveUser(OTHER_EMAIL);
        var ownedProject = saveProject(owner, "Owned project");
        ownedProjectId = ownedProject.getId();
        ScanRun ownedScan = saveScan(saveUpload(ownedProject));
        ScanRun otherScan = saveScan(saveUpload(saveProject(other, "Other project")));
        ownedScanId = ownedScan.getId();
        otherScanId = otherScan.getId();

        ownedFindingId = saveFinding(ownedScan, ScannerType.TRIVY, ScanFindingSeverity.HIGH,
                "CVE-2025-1000", "pom.xml", "Dependency vulnerability detected.").getId();
        saveFinding(ownedScan, ScannerType.SEMGREP, ScanFindingSeverity.MEDIUM,
                "java.lang.security", "src/Main.java", "Static analysis finding detected.");
        saveFinding(otherScan, ScannerType.GITLEAKS, ScanFindingSeverity.HIGH,
                "generic-api-key", "config.txt", "Potential exposed secret detected.");
        clearInvocations(aiAnalysisClient);
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Lists owner-scoped findings with filters and bounded stable pagination")
    void listsAndFiltersOwnedFindings() throws Exception {
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", ownedScanId)
                        .param("severity", "HIGH")
                        .param("scanner", "TRIVY")
                        .param("filePath", "pom.xml")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()", is(1)))
                .andExpect(jsonPath("$.content[0].scanner", is("TRIVY")))
                .andExpect(jsonPath("$.content[0].ruleId", is("CVE-2025-1000")))
                .andExpect(jsonPath("$.content[0].description", is("Dependency vulnerability detected.")))
                .andExpect(jsonPath("$.content[0].rawOutput").doesNotExist())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(1)));
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", otherScanId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Retrieves only findings owned by the authenticated user")
    void retrievesFindingAndConcealsForeignFinding() throws Exception {
        mockMvc.perform(get("/api/v1/findings/{findingId}", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.scanId", is(ownedScanId.toString())))
                .andExpect(jsonPath("$.description", is("Dependency vulnerability detected.")))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.match").doesNotExist());

        UUID foreignFindingId = scanFindingRepository.findAll().stream()
                .filter(finding -> finding.getScanRun().getId().equals(otherScanId))
                .findFirst().orElseThrow().getId();
        mockMvc.perform(get("/api/v1/findings/{findingId}", foreignFindingId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Rejects unsafe path filters and pagination outside configured limits")
    void validatesFiltersAndPagination() throws Exception {
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", ownedScanId)
                        .param("filePath", "../outside"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", ownedScanId)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", ownedScanId)
                        .param("scanner", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Finding endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/scans/{scanId}/findings", ownedScanId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/findings/{findingId}", ownedFindingId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/findings/{findingId}/analysis", ownedFindingId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Analyzes and persists an owned finding using only normalized safe fields")
    void analyzesAndPersistsOwnedFinding() throws Exception {
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class))).thenReturn(successfulAnalysis());

        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingId", is(ownedFindingId.toString())))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.explanation", is("A dependency has a known vulnerability.")))
                .andExpect(jsonPath("$.illustrativeFix.language", is("xml")))
                .andExpect(jsonPath("$.failureCode").doesNotExist());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(AiAnalysisRequest.class);
        verify(aiAnalysisClient).analyze(requestCaptor.capture());
        AiAnalysisRequest request = requestCaptor.getValue();
        assertThat(request.ruleId()).isEqualTo("CVE-2025-1000");
        assertThat(request.message()).isEqualTo("Dependency vulnerability detected.");
        assertThat(request.filePath()).isEqualTo("pom.xml");
        assertThat(request.scanner()).isEqualTo("TRIVY");
        assertThat(request.metadata()).isEmpty();
        assertThat(objectMapper.writeValueAsString(request)).doesNotContain("snippet");
        assertThat(analysisRepository.findByFindingId(ownedFindingId)).isPresent();
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Gitleaks analysis sends the generic persisted description, never matched secret data")
    void gitleaksAnalysisUsesOnlySafePersistedFields() throws Exception {
        ScanFinding gitleaksFinding = saveFinding(
                scanRunRepository.findById(ownedScanId).orElseThrow(),
                ScannerType.GITLEAKS,
                ScanFindingSeverity.HIGH,
                "generic-api-key",
                "config.txt",
                "Potential exposed secret detected.");
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class))).thenReturn(successfulAnalysis());

        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", gitleaksFinding.getId()))
                .andExpect(status().isOk());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(AiAnalysisRequest.class);
        verify(aiAnalysisClient).analyze(requestCaptor.capture());
        AiAnalysisRequest request = requestCaptor.getValue();
        assertThat(request.message()).isEqualTo("Potential exposed secret detected.");
        assertThat(request.metadata()).isEmpty();
        assertThat(objectMapper.writeValueAsString(request))
                .doesNotContain("secret-value", "match", "snippet");
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Reuses a completed analysis without another Python request")
    void reusesCompletedAnalysis() throws Exception {
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class))).thenReturn(successfulAnalysis());

        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        verify(aiAnalysisClient, times(1)).analyze(any(AiAnalysisRequest.class));
        mockMvc.perform(get("/api/v1/findings/{findingId}/analysis", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedRemediation", is("Upgrade to a fixed version.")));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Foreign and missing findings are rejected before calling Python")
    void doesNotAnalyzeForeignOrMissingFindings() throws Exception {
        UUID foreignFindingId = scanFindingRepository.findAll().stream()
                .filter(finding -> finding.getScanRun().getId().equals(otherScanId))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", foreignFindingId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/findings/{findingId}/analysis", foreignFindingId))
                .andExpect(status().isNotFound());
        verify(aiAnalysisClient, never()).analyze(any(AiAnalysisRequest.class));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("A failed analysis can be retried within the configured attempt limit")
    void retriesFailedAnalysis() throws Exception {
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class)))
                .thenThrow(new com.codesentinel.exception.AiIntegrationException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        ScanFindingAnalysisFailureCode.AI_SERVICE_UNAVAILABLE,
                        "The AI analysis service is unavailable"))
                .thenReturn(successfulAnalysis());

        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isServiceUnavailable());
        assertThat(analysisRepository.findByFindingId(ownedFindingId).orElseThrow().getAttemptCount())
                .isEqualTo(1);
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptCount", is(2)));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("A recent pending analysis conflicts and a stale one can be safely reclaimed")
    void handlesPendingAndStaleAnalysis() throws Exception {
        ScanFinding finding = scanFindingRepository.findById(ownedFindingId).orElseThrow();
        analysisRepository.saveAndFlush(pendingAnalysis(finding, Instant.now(), 1));
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isConflict());
        verify(aiAnalysisClient, never()).analyze(any(AiAnalysisRequest.class));

        analysisRepository.deleteAll();
        analysisRepository.saveAndFlush(pendingAnalysis(
                finding, Instant.now().minusSeconds(600), 1));
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class))).thenReturn(successfulAnalysis());
        mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptCount", is(2)));
    }

    @Test
    @DisplayName("Concurrent analysis requests allow only one outbound AI call")
    void preventsConcurrentDuplicateRequests() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        when(aiAnalysisClient.analyze(any(AiAnalysisRequest.class))).thenAnswer(invocation -> {
            requestStarted.countDown();
            if (!allowCompletion.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the mocked AI request");
            }
            return successfulAnalysis();
        });

        try {
            var firstRequest = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/findings/{findingId}/analyze", ownedFindingId)
                                    .with(user(OWNER_EMAIL)))
                    .andReturn());
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(post("/api/v1/findings/{findingId}/analyze", ownedFindingId)
                            .with(user(OWNER_EMAIL)))
                    .andExpect(status().isConflict());
            allowCompletion.countDown();
            assertThat(firstRequest.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            verify(aiAnalysisClient, times(1)).analyze(any(AiAnalysisRequest.class));
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Database uniqueness prevents multiple analysis rows for a finding")
    void enforcesUniqueAnalysisPerFinding() {
        ScanFinding finding = scanFindingRepository.findById(ownedFindingId).orElseThrow();
        analysisRepository.saveAndFlush(pendingAnalysis(finding, Instant.now(), 1));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> analysisRepository.saveAndFlush(pendingAnalysis(finding, Instant.now(), 1)));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Project deletion removes analysis records before bulk-deleting findings")
    void projectDeletionCleansUpAnalyses() {
        ScanFinding finding = scanFindingRepository.findById(ownedFindingId).orElseThrow();
        analysisRepository.saveAndFlush(pendingAnalysis(finding, Instant.now(), 1));
        projectService.delete(ownedProjectId, OWNER_EMAIL);
        assertThat(analysisRepository.findByFindingId(ownedFindingId)).isEmpty();
        assertThat(scanFindingRepository.findById(ownedFindingId)).isEmpty();
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .fullName("Finding Test User")
                .email(email)
                .passwordHash("unused")
                .role(Role.USER)
                .build());
    }

    private Project saveProject(User owner, String name) {
        return projectRepository.save(Project.builder().name(name).owner(owner).build());
    }

    private ProjectUpload saveUpload(Project project) {
        return uploadRepository.save(ProjectUpload.builder()
                .project(project)
                .originalFilename("source.zip")
                .status(UploadStatus.COMPLETED)
                .fileCount(1)
                .extractedSizeBytes(1)
                .storageReference(UUID.randomUUID().toString())
                .build());
    }

    private ScanRun saveScan(ProjectUpload upload) {
        return scanRunRepository.save(ScanRun.builder()
                .projectUpload(upload)
                .status(ScanStatus.SUCCEEDED)
                .build());
    }

    private ScanFinding saveFinding(
            ScanRun scan, ScannerType scanner, ScanFindingSeverity severity,
            String ruleId, String filePath, String description) {
        return scanFindingRepository.save(ScanFinding.builder()
                .scanRun(scan)
                .scanner(scanner)
                .severity(severity)
                .ruleId(ruleId)
                .filePath(filePath)
                .description(description)
                .fingerprint(UUID.randomUUID().toString().replace("-", "").repeat(2))
                .build());
    }

    private AiAnalysisResponse successfulAnalysis() {
        return new AiAnalysisResponse(
                "A dependency has a known vulnerability.",
                "An attacker may exploit the vulnerable dependency.",
                "Upgrade to a fixed version.",
                new AiIllustrativeFix("xml", "<dependency>updated-version</dependency>"));
    }

    private ScanFindingAnalysis pendingAnalysis(ScanFinding finding, Instant updatedAt, int attempts) {
        Instant createdAt = Instant.now().minusSeconds(600);
        return ScanFindingAnalysis.builder()
                .finding(finding)
                .status(ScanFindingAnalysisStatus.PENDING)
                .attemptCount(attempts)
                .claimToken(UUID.randomUUID())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
