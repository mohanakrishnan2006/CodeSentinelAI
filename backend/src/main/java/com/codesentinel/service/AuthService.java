package com.codesentinel.service;

import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.dto.UserResponse;
import com.codesentinel.exception.EmailAlreadyExistsException;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
}
