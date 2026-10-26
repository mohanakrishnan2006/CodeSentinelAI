package com.codesentinel.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * Service that handles all JWT (JSON Web Token) operations for CodeSentinel AI.
 *
 * <h3>What is a JWT?</h3>
 * <p>A JWT is a compact, URL-safe token composed of three Base64-encoded parts
 * separated by dots: {@code header.payload.signature}. The signature is created
 * with a secret key only the server knows, so no one can tamper with the payload
 * without invalidating the signature.</p>
 *
 * <h3>JJWT 0.12.x API note</h3>
 * <p>JJWT 0.12 introduced a new fluent builder API. We use
 * {@link Jwts#builder()} and {@link Jwts#parser()} (no deprecated methods).</p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HMAC-SHA256 signing key derived from the configured secret string. */
    private final SecretKey signingKey;

    /** How long a token is valid for, in milliseconds (default: 24 h). */
    private final long expirationMs;

    /**
     * Spring injects the values from application-dev.yml / application-test.yml.
     * In production these are supplied via environment variables (JWT_SECRET,
     * JWT_EXPIRATION_MS), so no real credentials ever appear in source code.
     */
    public JwtService(
            @Value("${codesentinel.jwt.secret}") String secret,
            @Value("${codesentinel.jwt.expiration-ms}") long expirationMs) {

        // Keys.hmacShaKeyFor requires at least 256 bits (32 bytes) for HS256.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // ── Token generation ──────────────────────────────────────────────────────

    /**
     * Creates a signed JWT whose subject is the user's email address.
     *
     * @param userDetails the authenticated user provided by Spring Security
     * @return a compact JWT string (never null)
     */
    public String generateToken(UserDetails userDetails) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userDetails.getUsername())   // "sub" claim = email
                .issuedAt(new Date(now))              // "iat" claim
                .expiration(new Date(now + expirationMs)) // "exp" claim
                .signWith(signingKey)                 // HMAC-SHA256 by default
                .compact();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    /**
     * Returns true when the token is well-formed, correctly signed, not expired,
     * and its subject matches the given {@link UserDetails}.
     *
     * @param token       the raw JWT string from the Authorization header
     * @param userDetails the user to validate against
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String subject = extractEmail(token);
            return subject.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            // Covers malformed, unsupported, or tampered tokens
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Claim extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the email address (subject claim) from the token.
     *
     * @throws JwtException if the token is malformed, expired, or has an invalid signature
     */
    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Checks whether the token's expiration timestamp is in the past.
     *
     * <p>JJWT throws {@link ExpiredJwtException} inside {@link #extractClaim} when the token
     * is already expired — the parser validates the {@code exp} claim before returning.
     * We catch that here and return {@code true} because an expired token is, by definition,
     * expired.</p>
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            return expiration.before(new Date());
        } catch (ExpiredJwtException e) {
            return true; // token is definitively expired
        }
    }

    /**
     * Generic helper that parses the token and applies a {@link Function} to its claims.
     *
     * @param token          the raw JWT string
     * @param claimsResolver a function that extracts the desired value from {@link Claims}
     * @param <T>            the return type of the extraction function
     * @throws ExpiredJwtException      if the token has expired
     * @throws JwtException             if the token is invalid for any other reason
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }
}
