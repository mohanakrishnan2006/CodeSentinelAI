package com.codesentinel.service;

import com.codesentinel.dto.ProjectRequest;
import com.codesentinel.dto.ProjectResponse;
import com.codesentinel.exception.ProjectNotFoundException;
import com.codesentinel.model.Project;
import com.codesentinel.model.User;
import com.codesentinel.repository.ProjectRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    @Transactional
    public ProjectResponse create(ProjectRequest request, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        Project project = Project.builder()
                .name(request.getName().trim())
                .description(normalizeDescription(request.getDescription()))
                .owner(owner)
                .build();
        return ProjectResponse.fromEntity(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        return projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId())
                .stream()
                .map(ProjectResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        return ProjectResponse.fromEntity(findOwnedProject(projectId, owner.getId()));
    }

    @Transactional
    public ProjectResponse update(UUID projectId, ProjectRequest request, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        Project project = findOwnedProject(projectId, owner.getId());
        project.setName(request.getName().trim());
        project.setDescription(normalizeDescription(request.getDescription()));
        return ProjectResponse.fromEntity(projectRepository.saveAndFlush(project));
    }

    @Transactional
    public void delete(UUID projectId, String authenticatedEmail) {
        User owner = findUserByEmail(authenticatedEmail);
        projectRepository.delete(findOwnedProject(projectId, owner.getId()));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"));
    }

    private Project findOwnedProject(UUID projectId, UUID ownerId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
