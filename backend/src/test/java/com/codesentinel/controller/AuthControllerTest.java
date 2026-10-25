package com.codesentinel.controller;

import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import com.codesentinel.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - 201 Created on valid registration")
    void shouldRegisterUserSuccessfully() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("Sarah Connor")
                .email("sarah.connor@example.com")
                .password("Cyberdyne1984!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.fullName", is("Sarah Connor")))
                .andExpect(jsonPath("$.email", is("sarah.connor@example.com")))
                .andExpect(jsonPath("$.role", is("USER")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                // Verify no password or hash is returned in response
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Verify database persistence and BCrypt hashing
        Optional<User> savedUserOpt = userRepository.findByEmail("sarah.connor@example.com");
        assertThat(savedUserOpt).isPresent();

        User savedUser = savedUserOpt.get();
        assertThat(savedUser.getFullName()).isEqualTo("Sarah Connor");
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);

        // Verify password is BCrypt hashed, never plain text
        assertThat(savedUser.getPasswordHash()).startsWith("$2a$");
        assertThat(savedUser.getPasswordHash()).doesNotContain("Cyberdyne1984!");
        assertThat(passwordEncoder.matches("Cyberdyne1984!", savedUser.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - 409 Conflict on duplicate email")
    void shouldRejectDuplicateEmail() throws Exception {
        RegisterRequest first = RegisterRequest.builder()
                .fullName("First Registrant")
                .email("duplicate.check@example.com")
                .password("securePassword123")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        RegisterRequest duplicate = RegisterRequest.builder()
                .fullName("Second Registrant")
                .email("duplicate.check@example.com")
                .password("anotherPassword456")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", is("Email address is already registered: duplicate.check@example.com")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - 400 Bad Request on invalid email format")
    void shouldRejectInvalidEmail() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("John Doe")
                .email("invalid-email-format")
                .password("validPassword123")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.validationErrors.email", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - 400 Bad Request on blank name and short password")
    void shouldRejectBlankNameAndShortPassword() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("")
                .email("valid@example.com")
                .password("short") // less than 8 chars
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors.fullName", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.password", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Security: Client cannot assign ADMIN or REVIEWER via payload")
    void shouldIgnoreInjectedRoleAndAssignRoleUser() throws Exception {
        // Construct raw JSON payload containing an unauthorized "role" field
        String maliciousPayload = """
                {
                    "fullName": "Malicious User",
                    "email": "hacker@example.com",
                    "password": "Password12345!",
                    "role": "ADMIN"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role", is("USER")));

        User userInDb = userRepository.findByEmail("hacker@example.com").orElseThrow();
        assertThat(userInDb.getRole()).isEqualTo(Role.USER);
    }
}
