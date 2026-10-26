package com.codesentinel.controller;

import com.codesentinel.dto.AuthResponse;
import com.codesentinel.dto.LoginRequest;
import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.dto.UserResponse;
import com.codesentinel.model.User;
import com.codesentinel.repository.UserRepository;
import com.codesentinel.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller managing authentication endpoints for CodeSentinel AI.
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/register} — public, creates a new account</li>
 *   <li>{@code POST /api/v1/auth/login}    — public, returns a JWT on success</li>
 *   <li>{@code GET  /api/v1/auth/me}       — protected, returns the caller's profile</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    /**
     * Endpoint for public self-registration of software developers.
     *
     * @param request validated registration payload
     * @return 201 Created with the new user's public profile
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Endpoint for user login.
     *
     * <p>Validates credentials, and on success returns a signed JWT token
     * together with a safe profile snapshot. The caller should store the
     * {@code accessToken} and include it as {@code Authorization: Bearer <token>}
     * on all subsequent requests to protected endpoints.</p>
     *
     * @param request validated login payload (email + password)
     * @return 200 OK with an {@link AuthResponse} containing the JWT
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(response);
    }

    /**
     * Protected endpoint returning the authenticated caller's profile.
     *
     * <h3>How {@code @AuthenticationPrincipal} works</h3>
     * <p>After {@link com.codesentinel.security.JwtAuthFilter} validates the JWT and
     * populates the {@link org.springframework.security.core.context.SecurityContextHolder},
     * Spring injects the current {@link UserDetails} object here automatically.
     * No manual token parsing is needed in the controller.</p>
     *
     * @param currentUser injected by Spring Security from the validated JWT
     * @return 200 OK with the caller's {@link UserResponse}
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @AuthenticationPrincipal UserDetails currentUser) {

        User user = userRepository
                .findByEmail(currentUser.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Authenticated user not found in database"));

        return ResponseEntity.ok(UserResponse.fromEntity(user));
    }
}
