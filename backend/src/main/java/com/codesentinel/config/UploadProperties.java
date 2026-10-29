package com.codesentinel.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "codesentinel.upload")
public class UploadProperties {

    @NotNull
    private Path storageDirectory = Path.of(System.getProperty("user.home"), ".codesentinel", "uploads");

    @Positive
    private long maxArchiveSizeBytes = 50L * 1024 * 1024;

    @Positive
    private int maxFiles = 1000;

    @Positive
    private long maxTotalSizeBytes = 250L * 1024 * 1024;

    @Positive
    private long maxFileSizeBytes = 50L * 1024 * 1024;
}
