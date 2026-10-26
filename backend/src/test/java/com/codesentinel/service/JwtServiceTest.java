package com.codesentinel.service;

import com.codesentinel.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}.
 *
 * <p>These tests are pure unit tests — no Spring context is loaded, no database
 * is needed. We construct the service directly with test values.</p>
 */
class JwtServiceTest {

    // 64-character hex string → 256-bit key, the minimum for HMAC-SHA256.
    private static final String TEST_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long ONE_HOUR_MS = 3_600_000L;

    private JwtService jwtService;
    private UserDetails testUserDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, ONE_HOUR_MS);
        testUserDetails = new User(
                "test@example.com",
                "$2a$10$hashedPassword",
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.USER.name()))
        );
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("generateToken produces a non-null, non-blank, three-part JWT")
    void shouldGenerateValidJwt() {
        String token = jwtService.generateToken(testUserDetails);

        assertThat(token).isNotNull().isNotBlank();
        // JWTs are always three Base64url segments separated by dots
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("generateToken embeds the user's email as the subject claim")
    void shouldEmbedEmailAsSubject() {
        String token = jwtService.generateToken(testUserDetails);
        String extractedEmail = jwtService.extractEmail(token);

        assertThat(extractedEmail).isEqualTo("test@example.com");
    }

    // ── Token validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("isTokenValid returns true for a fresh, correctly-signed token")
    void shouldReturnTrueForValidToken() {
        String token = jwtService.generateToken(testUserDetails);
        assertThat(jwtService.isTokenValid(token, testUserDetails)).isTrue();
    }

    @Test
    @DisplayName("isTokenValid returns false when the subject does not match the UserDetails")
    void shouldReturnFalseWhenSubjectMismatch() {
        String token = jwtService.generateToken(testUserDetails);

        UserDetails differentUser = new User(
                "different@example.com",
                "$2a$10$anotherHash",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        assertThat(jwtService.isTokenValid(token, differentUser)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for a tampered token")
    void shouldReturnFalseForTamperedToken() {
        String token = jwtService.generateToken(testUserDetails);
        // Flip a character in the signature segment (last part after the final dot)
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(jwtService.isTokenValid(tampered, testUserDetails)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for an expired token (expiry = 1 ms)")
    void shouldReturnFalseForExpiredToken() throws InterruptedException {
        JwtService shortLivedService = new JwtService(TEST_SECRET, 1L); // expires in 1 ms
        String token = shortLivedService.generateToken(testUserDetails);

        Thread.sleep(10); // wait for expiry

        assertThat(shortLivedService.isTokenValid(token, testUserDetails)).isFalse();
    }

    @Test
    @DisplayName("isTokenExpired returns false for a fresh token")
    void shouldNotBeExpiredForFreshToken() {
        String token = jwtService.generateToken(testUserDetails);
        assertThat(jwtService.isTokenExpired(token)).isFalse();
    }

    @Test
    @DisplayName("isTokenExpired returns true for an expired token")
    void shouldBeExpiredAfterExpiryTime() throws InterruptedException {
        JwtService shortLivedService = new JwtService(TEST_SECRET, 1L);
        String token = shortLivedService.generateToken(testUserDetails);
        Thread.sleep(10);

        assertThat(shortLivedService.isTokenExpired(token)).isTrue();
    }

    @Test
    @DisplayName("extractEmail throws JwtException for a completely invalid token string")
    void shouldThrowForMalformedToken() {
        assertThatThrownBy(() -> jwtService.extractEmail("this.is.not.a.jwt"))
                .isInstanceOf(Exception.class); // JwtException or its subclass
    }
}
