package com.codesentinel.service;

import com.codesentinel.dto.ProjectRequest;
import com.codesentinel.dto.ProjectResponse;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.model.Project;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.ProjectUploadRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final String OWNER_EMAIL = "owner@example.com";

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectUploadRepository uploadRepository;

    @Mock
    private UploadStorageService uploadStorageService;

    @InjectMocks
    private ProjectService projectService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.builder()
                .id(UUID.randomUUID())
                .fullName("Project Owner")
                .email(OWNER_EMAIL)
                .passwordHash("encoded")
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
    }

    @Test
    @DisplayName("Create associates the project with the authenticated user")
    void createUsesAuthenticatedOwner() {
        ProjectRequest request = ProjectRequest.builder().name(" Sentinel ").description(" Source audit ").build();
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(UUID.randomUUID());
            return project;
        });

        ProjectResponse response = projectService.create(request, OWNER_EMAIL);

        assertThat(response.getName()).isEqualTo("Sentinel");
        assertThat(response.getDescription()).isEqualTo("Source audit");
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    @DisplayName("List queries only projects belonging to the authenticated user")
    void listIsOwnerScoped() {
        when(projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId())).thenReturn(List.of());

        assertThat(projectService.list(OWNER_EMAIL)).isEmpty();
        verify(projectRepository).findAllByOwnerIdOrderByCreatedAtDesc(owner.getId());
    }

    @Test
    @DisplayName("Get, update, and delete use an owner-scoped project query")
    void mutationsAreOwnerScoped() {
        UUID projectId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).name("Old name").owner(owner).build();
        when(projectRepository.findByIdAndOwnerId(projectId, owner.getId())).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);
        when(uploadRepository.findAllForOwnedProject(projectId, owner.getId())).thenReturn(List.of());

        assertThat(projectService.get(projectId, OWNER_EMAIL).getName()).isEqualTo("Old name");
        assertThat(projectService.update(projectId,
                ProjectRequest.builder().name("New name").build(), OWNER_EMAIL).getName()).isEqualTo("New name");
        projectService.delete(projectId, OWNER_EMAIL);

        verify(projectRepository, org.mockito.Mockito.times(3))
                .findByIdAndOwnerId(projectId, owner.getId());
        verify(projectRepository).delete(project);
    }

    @Test
    @DisplayName("A project not owned by the authenticated user is not found")
    void rejectsProjectOutsideOwnerScope() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findByIdAndOwnerId(projectId, owner.getId())).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> projectService.get(projectId, OWNER_EMAIL));
    }
}
