package com.codesentinel.dto;

import com.codesentinel.model.Project;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class ProjectResponse {

    UUID id;
    String name;
    String description;
    Instant createdAt;
    Instant updatedAt;

    public static ProjectResponse fromEntity(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
