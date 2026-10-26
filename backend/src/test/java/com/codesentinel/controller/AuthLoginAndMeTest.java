package com.codesentinel.controller;

import com.codesentinel.dto.LoginRequest;
import com.codesentinel.dto.RegisterRequest;
import com.codesentinel.repository.UserRepository;
import com.codesentinel.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the JWT login flow and the protected /me endpoint.
 *
 * <p>Uses the full Spring Boot context with an H2 in-memory database (profile "test")
 * so the entire filter chain, authentication, and token handling is exercised.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginAndMeTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL    = "/api/v1/auth/login";
    private static final String ME_URL       = "/api/v1/auth/me";

    private static final String TEST_EMAIL    = "login.test@example.com";
    private static final String TEST_PASSWORD = "Password1234!";
    private static final String TEST_NAME     = "Login Test User";

    @Autowired private MockMvc       mockMvc;
    @Autowired private ObjectMapper  objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService    jwtService;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();

        // Register a test user before each test that requires one.
        RegisterRequest reg = RegisterRequest.builder()
                .fullName(TEST_NAME)
                .email(TEST_EMAIL)
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());
    }

    // ── Login success ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /login - 200 OK with JWT and user profile on valid credentials")
    void shouldLoginSuccessfullyAndReturnJwt() throws Exception {
        LoginRequest login = LoginRequest.builder()
                .email(TEST_EMAIL)
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.email", is(TEST_EMAIL)))
                .andExpect(jsonPath("$.fullName", is(TEST_NAME)))
                .andExpect(jsonPath("$.role", is("USER")))
                .andExpect(jsonPath("$.userId", notNullValue()));
    }

    @Test
    @DisplayName("POST /login - JWT subject equals the user's email")
    void loginTokenSubjectShouldBeUserEmail() throws Exception {
        LoginRequest login = LoginRequest.builder()
                .email(TEST_EMAIL)
                .password(TEST_PASSWORD)
                .build();

        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        String body   = result.getResponse().getContentAsString();
        String token  = objectMapper.readTree(body).get("accessToken").asText();
        String subject = jwtService.extractEmail(token);

        assertThat(subject).isEqualTo(TEST_EMAIL);
    }

    // ── Login failures ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /login - 401 Unauthorized on wrong password")
    void shouldReturn401OnWrongPassword() throws Exception {
        LoginRequest login = LoginRequest.builder()
                .email(TEST_EMAIL)
                .password("WrongPassword999!")
                .build();

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Invalid email address or password")));
    }

    @Test
    @DisplayName("POST /login - 401 Unauthorized on unknown email")
    void shouldReturn401OnUnknownEmail() throws Exception {
        LoginRequest login = LoginRequest.builder()
                .email("nobody@example.com")
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid email address or password")));
    }

    @Test
    @DisplayName("POST /login - 400 Bad Request on missing or blank fields")
    void shouldReturn400OnMissingLoginFields() throws Exception {
        String emptyPayload = "{}";

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.password", notNullValue()));
    }

    // ── GET /me — protected endpoint ─────────────────────────────────────────

    @Test
    @DisplayName("GET /me - 200 OK with user profile when called with a valid JWT")
    void shouldReturnUserProfileForValidToken() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get(ME_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(TEST_EMAIL)))
                .andExpect(jsonPath("$.fullName", is(TEST_NAME)))
                .andExpect(jsonPath("$.role", is("USER")))
                .andExpect(jsonPath("$.id", notNullValue()))
                // Security: no password hash must ever appear in the response
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("GET /me - 401 Unauthorized when no Authorization header is sent")
    void shouldReturn401WhenNoTokenProvided() throws Exception {
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me - 401 Unauthorized when a completely invalid token is sent")
    void shouldReturn401ForInvalidToken() throws Exception {
        mockMvc.perform(get(ME_URL)
                        .header("Authorization", "Bearer this.is.not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me - 401 Unauthorized when token is expired")
    void shouldReturn401ForExpiredToken() throws Exception {
        // Build a 1ms-expiry JwtService directly for this test
        JwtService shortLived = new JwtService(
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
                1L
        );
        var userDetails = new User(
                TEST_EMAIL,
                "$2a$10$placeholder",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        String expiredToken = shortLived.generateToken(userDetails);
        Thread.sleep(10); // wait for expiry

        mockMvc.perform(get(ME_URL)
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Performs a login and extracts the JWT access token from the response.
     */
    private String loginAndGetToken() throws Exception {
        LoginRequest login = LoginRequest.builder()
                .email(TEST_EMAIL)
                .password(TEST_PASSWORD)
                .build();

        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
