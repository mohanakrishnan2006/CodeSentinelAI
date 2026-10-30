package com.codesentinel.controller;

import com.codesentinel.model.Project;
import com.codesentinel.model.ProjectUpload;
import com.codesentinel.model.Role;
import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScanStatus;
import com.codesentinel.model.ScannerType;
import com.codesentinel.model.UploadStatus;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
import com.codesentinel.repository.ScanFindingRepository;
import com.codesentinel.repository.ScanOutcomeRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private UUID ownedScanId;
    private UUID otherScanId;
    private UUID ownedFindingId;

    @BeforeEach
    void setUp() {
        scanFindingRepository.deleteAll();
        scanOutcomeRepository.deleteAll();
        scanRunRepository.deleteAll();
        uploadRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User owner = saveUser(OWNER_EMAIL);
        User other = saveUser(OTHER_EMAIL);
        ScanRun ownedScan = saveScan(saveUpload(saveProject(owner, "Owned project")));
        ScanRun otherScan = saveScan(saveUpload(saveProject(other, "Other project")));
        ownedScanId = ownedScan.getId();
        otherScanId = otherScan.getId();

        ownedFindingId = saveFinding(ownedScan, ScannerType.TRIVY, ScanFindingSeverity.HIGH,
                "CVE-2025-1000", "pom.xml", "Dependency vulnerability detected.").getId();
        saveFinding(ownedScan, ScannerType.SEMGREP, ScanFindingSeverity.MEDIUM,
                "java.lang.security", "src/Main.java", "Static analysis finding detected.");
        saveFinding(otherScan, ScannerType.GITLEAKS, ScanFindingSeverity.HIGH,
                "generic-api-key", "config.txt", "Potential exposed secret detected.");
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
}
