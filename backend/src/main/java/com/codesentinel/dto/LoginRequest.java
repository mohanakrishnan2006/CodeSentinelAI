package com.codesentinel.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for the POST /api/v1/auth/login endpoint.
 *
 * <p>Only email and password are accepted. Any extra fields sent by the client
 * (e.g. a "role" field) are silently ignored by Jackson's default deserialization,
 * so a malicious caller cannot escalate privileges through this payload.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Email address is required")
    @Email(message = "Email address must be well-formed")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;
}
