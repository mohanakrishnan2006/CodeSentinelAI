package com.codesentinel.dto;

import com.codesentinel.model.ProjectUpload;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class ProjectUploadResponse {

    UUID id;
    UUID projectId;
    String originalFilename;
    String status;
    int fileCount;
    long extractedSizeBytes;
    Instant createdAt;
    Instant updatedAt;

    public static ProjectUploadResponse fromEntity(ProjectUpload upload) {
        return ProjectUploadResponse.builder()
                .id(upload.getId())
                .projectId(upload.getProject().getId())
                .originalFilename(upload.getOriginalFilename())
                .status(upload.getStatus().name())
                .fileCount(upload.getFileCount())
                .extractedSizeBytes(upload.getExtractedSizeBytes())
                .createdAt(upload.getCreatedAt())
                .updatedAt(upload.getUpdatedAt())
                .build();
    }
}
