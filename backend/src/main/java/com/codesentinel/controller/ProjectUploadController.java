package com.codesentinel.controller;

import com.codesentinel.dto.ProjectUploadResponse;
import com.codesentinel.service.ProjectUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/uploads")
@RequiredArgsConstructor
public class ProjectUploadController {

    private final ProjectUploadService uploadService;

    @PostMapping
    public ResponseEntity<ProjectUploadResponse> upload(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails currentUser) {
        ProjectUploadResponse response =
                uploadService.upload(projectId, file, currentUser.getUsername());
        URI location = URI.create("/api/v1/projects/" + projectId + "/uploads/" + response.getId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public List<ProjectUploadResponse> list(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return uploadService.list(projectId, currentUser.getUsername());
    }

    @GetMapping("/{uploadId}")
    public ProjectUploadResponse get(
            @PathVariable UUID projectId,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return uploadService.get(projectId, uploadId, currentUser.getUsername());
    }
}
