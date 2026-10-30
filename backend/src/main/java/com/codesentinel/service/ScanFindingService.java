package com.codesentinel.service;

import com.codesentinel.dto.ScanFindingPageResponse;
import com.codesentinel.dto.ScanFindingResponse;
import com.codesentinel.exception.FindingNotFoundException;
import com.codesentinel.exception.ScanNotFoundException;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScannerType;
import com.codesentinel.repository.ScanFindingRepository;
import com.codesentinel.repository.ScanRunRepository;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanFindingService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE_INDEX = 10_000;

    private final ScanFindingRepository scanFindingRepository;
    private final ScanRunRepository scanRunRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public ScanFindingPageResponse list(
            UUID scanId,
            String authenticatedEmail,
            ScanFindingSeverity severity,
            ScannerType scanner,
            String filePath,
            int page,
            int size) {
        if (page < 0 || page > MAX_PAGE_INDEX || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid finding page or page size");
        }
        String normalizedPath = normalizeFilterPath(filePath);
        UUID ownerId = findOwnerId(authenticatedEmail);
        scanRunRepository.findOneForOwner(scanId, ownerId)
                .orElseThrow(() -> new ScanNotFoundException(scanId));

        Page<ScanFindingResponse> findings = scanFindingRepository.findAllForOwner(
                        scanId,
                        ownerId,
                        severity,
                        scanner,
                        normalizedPath,
                        PageRequest.of(page, size, Sort.by(
                                Sort.Order.asc("filePath"),
                                Sort.Order.asc("scanner"),
                                Sort.Order.asc("ruleId"),
                                Sort.Order.asc("id"))))
                .map(ScanFindingResponse::fromEntity);
        return ScanFindingPageResponse.fromPage(findings);
    }

    @Transactional(readOnly = true)
    public ScanFindingResponse get(UUID findingId, String authenticatedEmail) {
        UUID ownerId = findOwnerId(authenticatedEmail);
        return scanFindingRepository.findOneForOwner(findingId, ownerId)
                .map(ScanFindingResponse::fromEntity)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
    }

    private UUID findOwnerId(String authenticatedEmail) {
        return userRepository.findByEmail(authenticatedEmail.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"))
                .getId();
    }

    private String normalizeFilterPath(String filePath) {
        if (filePath == null) {
            return null;
        }
        if (filePath.isBlank() || filePath.length() > 1024
                || filePath.startsWith("/") || filePath.startsWith("\\")
                || filePath.contains("\\") || filePath.contains(":")) {
            throw new IllegalArgumentException("Invalid file path filter");
        }
        for (String segment : filePath.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid file path filter");
            }
        }
        return filePath;
    }
}
