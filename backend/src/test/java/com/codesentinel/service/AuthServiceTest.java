package com.codesentinel.service;

import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.dto.UserResponse;
import com.codesentinel.exception.EmailAlreadyExistsException;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService} using Mockito — no Spring context or database.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository        userRepository;
    @Mock private PasswordEncoder       passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService            jwtService;
    @Mock private UserDetailsService    userDetailsService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtService,
                userDetailsService
        );
    }

    // ── register() tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Successfully register a user, hash password, and assign USER role")
    void shouldRegisterUserSuccessfully() {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("Jane Developer")
                .email("Jane.Dev@Example.COM ")
                .password("superSecurePass123")
                .build();

        when(userRepository.existsByEmail("jane.dev@example.com")).thenReturn(false);
        when(passwordEncoder.encode("superSecurePass123")).thenReturn("$2a$10$hashedBcryptStringExample");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            return user;
        });

        UserResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getFullName()).isEqualTo("Jane Developer");
        assertThat(response.getEmail()).isEqualTo("jane.dev@example.com");
        assertThat(response.getRole()).isEqualTo(Role.USER);

        // Verify password was hashed and raw password was never stored
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getPasswordHash()).isEqualTo("$2a$10$hashedBcryptStringExample");
        assertThat(savedUser.getPasswordHash()).doesNotContain("superSecurePass123");
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("Throw EmailAlreadyExistsException when email is already registered")
    void shouldThrowExceptionWhenEmailAlreadyExists() {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("Duplicate User")
                .email("existing@example.com")
                .password("validPassword123")
                .build();

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        EmailAlreadyExistsException exception = assertThrows(
                EmailAlreadyExistsException.class,
                () -> authService.register(request)
        );

        assertThat(exception.getMessage()).contains("Email address is already registered: existing@example.com");
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }
}
