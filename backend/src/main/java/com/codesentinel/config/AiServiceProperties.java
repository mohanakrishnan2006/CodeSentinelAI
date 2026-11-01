package com.codesentinel.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "codesentinel.ai")
public class AiServiceProperties {

    @NotBlank
    private String baseUrl;

    @NotNull
    private Duration connectTimeout;

    @NotNull
    private Duration readTimeout;

    @NotNull
    private Duration pendingStaleTimeout;

    @Positive
    private long ollamaGenerationTimeoutSeconds;

    @Positive
    private int maxAttempts;

    @NotBlank
    private String modelIdentifier;

    @AssertTrue(message = "AI service URL and timeout settings are invalid")
    public boolean isConfigurationValid() {
        try {
            URI uri = URI.create(baseUrl);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && validTimeouts();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean validTimeouts() {
        return connectTimeout != null
                && !connectTimeout.isNegative()
                && !connectTimeout.isZero()
                && connectTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && readTimeout != null
                && readTimeout.compareTo(Duration.ofSeconds(ollamaGenerationTimeoutSeconds)) > 0
                && readTimeout.compareTo(Duration.ofMinutes(10)) <= 0
                && pendingStaleTimeout != null
                && pendingStaleTimeout.compareTo(readTimeout) > 0
                && maxAttempts >= 1
                && maxAttempts <= 5
                && ollamaGenerationTimeoutSeconds <= 300;
    }
}
