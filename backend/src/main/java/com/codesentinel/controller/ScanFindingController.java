package com.codesentinel.controller;

import com.codesentinel.dto.ScanFindingPageResponse;
import com.codesentinel.dto.ScanFindingResponse;
import com.codesentinel.dto.FindingAnalysisResponse;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScannerType;
import com.codesentinel.service.ScanFindingAnalysisService;
import com.codesentinel.service.ScanFindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScanFindingController {

    private final ScanFindingService scanFindingService;
    private final ScanFindingAnalysisService analysisService;

    @GetMapping("/scans/{scanId}/findings")
    @ResponseStatus(HttpStatus.OK)
    public ScanFindingPageResponse list(
            @PathVariable UUID scanId,
            @AuthenticationPrincipal UserDetails currentUser,
            @RequestParam(required = false) ScanFindingSeverity severity,
            @RequestParam(required = false) ScannerType scanner,
            @RequestParam(required = false) String filePath,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return scanFindingService.list(
                scanId, currentUser.getUsername(), severity, scanner, filePath, page, size);
    }

    @GetMapping("/findings/{findingId}")
    @ResponseStatus(HttpStatus.OK)
    public ScanFindingResponse get(
            @PathVariable UUID findingId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return scanFindingService.get(findingId, currentUser.getUsername());
    }

    @org.springframework.web.bind.annotation.PostMapping("/findings/{findingId}/analyze")
    @ResponseStatus(HttpStatus.OK)
    public FindingAnalysisResponse analyze(
            @PathVariable UUID findingId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return analysisService.analyze(findingId, currentUser.getUsername());
    }

    @GetMapping("/findings/{findingId}/analysis")
    @ResponseStatus(HttpStatus.OK)
    public FindingAnalysisResponse getAnalysis(
            @PathVariable UUID findingId,
            @AuthenticationPrincipal UserDetails currentUser) {
        return analysisService.get(findingId, currentUser.getUsername());
    }
}
