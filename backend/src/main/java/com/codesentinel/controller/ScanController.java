package com.codesentinel.controller;

import com.codesentinel.dto.ScanRunResponse;
import com.codesentinel.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;

    @PostMapping("/projects/{projectId}/uploads/{uploadId}/scans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanRunResponse create(
            @PathVariable UUID projectId,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return scanService.create(projectId, uploadId, currentUser.getUsername());
    }

    @GetMapping("/projects/{projectId}/uploads/{uploadId}/scans")
    public List<ScanRunResponse> list(
            @PathVariable UUID projectId,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return scanService.list(projectId, uploadId, currentUser.getUsername());
    }

    @GetMapping("/scans/{scanId}")
    public ScanRunResponse get(
            @PathVariable UUID scanId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return scanService.get(scanId, currentUser.getUsername());
    }
}
