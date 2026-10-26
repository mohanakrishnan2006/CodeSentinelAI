package com.codesentinel.controller;

import com.codesentinel.model.Project;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectControllerTest {

    private static final String PROJECTS_URL = "/api/v1/projects";
    private static final String OWNER_EMAIL = "project.owner@example.com";
    private static final String OTHER_EMAIL = "project.other@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private UUID ownedProjectId;

    @BeforeEach
    void setUp() {
        projectRepository.deleteAll();
        userRepository.deleteAll();
        User owner = saveUser(OWNER_EMAIL);
        User other = saveUser(OTHER_EMAIL);
        Project project = projectRepository.save(Project.builder()
                .name("Existing Project")
                .description("Owned by test user")
                .owner(owner)
                .build());
        ownedProjectId = project.getId();
        projectRepository.save(Project.builder()
                .name("Other User Project")
                .owner(other)
                .build());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Create returns 201 and derives the owner from the authenticated user")
    void createsProjectForAuthenticatedUser() throws Exception {
        mockMvc.perform(post(PROJECTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New Project","description":"Test project","ownerId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("New Project")))
                .andExpect(jsonPath("$.description", is("Test project")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));

        org.assertj.core.api.Assertions.assertThat(projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(
                userRepository.findByEmail(OWNER_EMAIL).orElseThrow().getId())).hasSize(2);
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("List only returns the authenticated user's projects")
    void listsOnlyOwnedProjects() throws Exception {
        mockMvc.perform(get(PROJECTS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Existing Project")));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Get returns an owned project")
    void getsOwnedProject() throws Exception {
        mockMvc.perform(get(PROJECTS_URL + "/" + ownedProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ownedProjectId.toString())))
                .andExpect(jsonPath("$.name", is("Existing Project")));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Update changes only an owned project")
    void updatesOwnedProject() throws Exception {
        mockMvc.perform(put(PROJECTS_URL + "/" + ownedProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProjectPayload("Updated Project", "Updated description"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Project")))
                .andExpect(jsonPath("$.description", is("Updated description")));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Delete returns 204 for an owned project")
    void deletesOwnedProject() throws Exception {
        mockMvc.perform(delete(PROJECTS_URL + "/" + ownedProjectId))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(projectRepository.findById(ownedProjectId)).isEmpty();
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Another user's project is concealed as not found for get, update, and delete")
    void preventsAccessToAnotherUsersProject() throws Exception {
        UUID otherOwnerId = userRepository.findByEmail(OTHER_EMAIL).orElseThrow().getId();
        UUID otherProjectId = projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(otherOwnerId)
                .get(0).getId();

        mockMvc.perform(get(PROJECTS_URL + "/" + otherProjectId))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(PROJECTS_URL + "/" + otherProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProjectPayload("Tampered", null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(PROJECTS_URL + "/" + otherProjectId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Project endpoints reject unauthenticated requests")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PROJECTS_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Invalid project payload returns 400")
    void validatesProjectPayload() throws Exception {
        mockMvc.perform(post(PROJECTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name", notNullValue()));
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Unknown project ID returns 404")
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get(PROJECTS_URL + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER_EMAIL)
    @DisplayName("Malformed project ID returns 400")
    void malformedProjectIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get(PROJECTS_URL + "/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .fullName("Project User")
                .email(email)
                .passwordHash("not-used-by-this-test")
                .role(Role.USER)
                .build());
    }

    private record ProjectPayload(String name, String description) {
    }
}
