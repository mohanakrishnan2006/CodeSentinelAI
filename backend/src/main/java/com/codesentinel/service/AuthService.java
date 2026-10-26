package com.codesentinel.service;

import com.codesentinel.dto.AuthResponse;
import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.dto.UserResponse;
import com.codesentinel.exception.EmailAlreadyExistsException;
import com.codesentinel.exception.InvalidCredentialsException;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating user authentication and registration workflows.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Registers a new standard developer user account.
     *
     * @param request the registration request containing full name, email, and raw password
     * @return UserResponse containing user details (without password or password hash)
     * @throws EmailAlreadyExistsException if the normalized email is already in use
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException("Email address is already registered: " + normalizedEmail);
        }

        // Salt and hash the raw password using BCrypt. The raw password is never persisted.
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(normalizedEmail)
                .passwordHash(hashedPassword)
                .role(Role.USER) // Enforce Role.USER for all public registrations
                .build();

        User savedUser = userRepository.saveAndFlush(user);

        log.info("Successfully registered user account with ID: {} and email: {}", savedUser.getId(), normalizedEmail);

        return UserResponse.fromEntity(savedUser);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates a user by email and password, returning a signed JWT on success.
     *
     * <h3>How this works</h3>
     * <ol>
     *   <li>The {@link AuthenticationManager} calls {@link UserDetailsServiceImpl#loadUserByUsername}
     *       to fetch the stored BCrypt hash, then compares it to the supplied password.</li>
     *   <li>If authentication passes, we generate a JWT and build the {@link AuthResponse}.</li>
     *   <li>If the email is unknown or the password is wrong, Spring throws
     *       {@link BadCredentialsException} which we convert to {@link InvalidCredentialsException}
     *       so the client receives a 401 with a generic, non-revealing message.</li>
     * </ol>
     *
     * @param email    the user's email address
     * @param password the raw (plain-text) password submitted by the user
     * @return an {@link AuthResponse} containing the JWT and safe user profile
     * @throws InvalidCredentialsException if credentials are wrong (same message for both cases)
     */
    public AuthResponse login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase();

        try {
            // Let Spring Security's AuthenticationManager verify credentials.
            // It calls UserDetailsServiceImpl → BCrypt comparison internally.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, password)
            );
        } catch (BadCredentialsException e) {
            // We re-throw as our own exception to keep the error message generic.
            // We deliberately do NOT log the password or the specific failure reason.
            log.warn("Failed login attempt for email: {}", normalizedEmail);
            throw new InvalidCredentialsException("Invalid email address or password");
        }

        // Authentication succeeded — load full UserDetails to generate the token.
        UserDetails userDetails = userDetailsService.loadUserByUsername(normalizedEmail);
        String token = jwtService.generateToken(userDetails);

        // Load the entity again to build the rich AuthResponse (includes role, timestamps).
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email address or password"));

        log.info("Successful login for user ID: {}", user.getId());

        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
