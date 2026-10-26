package com.codesentinel.controller;

import com.codesentinel.dto.ProjectRequest;
import com.codesentinel.dto.ProjectResponse;
import com.codesentinel.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @Valid @RequestBody ProjectRequest request,
            @AuthenticationPrincipal UserDetails currentUser) {
        return projectService.create(request, currentUser.getUsername());
    }

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal UserDetails currentUser) {
        return projectService.list(currentUser.getUsername());
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return projectService.get(projectId, currentUser.getUsername());
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectRequest request,
            @AuthenticationPrincipal UserDetails currentUser) {
        return projectService.update(projectId, request, currentUser.getUsername());
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UserDetails currentUser) {
        projectService.delete(projectId, currentUser.getUsername());
    }
}
