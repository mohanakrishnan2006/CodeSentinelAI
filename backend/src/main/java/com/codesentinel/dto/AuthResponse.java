package com.codesentinel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object returned after a successful login.
 *
 * <p>Contains the signed JWT access token and a safe view of the authenticated
 * user's profile. The password hash is never included in this response.</p>
 *
 * <p>Token type is always "Bearer" — clients must send it in the
 * {@code Authorization: Bearer <accessToken>} request header.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** The signed JWT. Clients must include this in subsequent requests. */
    private String accessToken;

    /** Always "Bearer" — tells the client how to use the token. */
    @Builder.Default
    private String tokenType = "Bearer";

    // ── Safe user profile (no password or hash) ──────────────────────────────

    private UUID userId;
    private String fullName;
    private String email;
    private String role;
    private Instant createdAt;
}
