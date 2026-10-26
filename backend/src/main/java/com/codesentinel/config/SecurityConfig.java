package com.codesentinel.config;

import com.codesentinel.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for CodeSentinel AI.
 *
 * <h3>How the security chain works</h3>
 * <ol>
 *   <li>Every incoming HTTP request passes through the security filter chain.</li>
 *   <li>{@link JwtAuthFilter} runs first and populates the {@link org.springframework.security.core.context.SecurityContextHolder}
 *       when a valid Bearer token is present.</li>
 *   <li>Authorization rules then decide whether to allow or reject the request
 *       based on whether the context is authenticated and what role it has.</li>
 * </ol>
 *
 * <h3>Why stateless sessions?</h3>
 * <p>REST APIs don't use browser sessions or cookies for authentication.
 * Instead, every request carries its own JWT, so we tell Spring Security
 * never to create or consult an HTTP session ({@link SessionCreationPolicy#STATELESS}).</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;

    /**
     * BCrypt password encoder with default strength (10 rounds).
     * Provides one-way salted hashing to protect stored credentials.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * {@link DaoAuthenticationProvider} wires together our {@link UserDetailsService}
     * and {@link PasswordEncoder}. Spring Security's {@link AuthenticationManager}
     * delegates to this provider when verifying login credentials.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Exposes the {@link AuthenticationManager} as a bean so {@link com.codesentinel.service.AuthService}
     * can call it directly to authenticate login credentials.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Defines the HTTP security filter chain:
     * <ul>
     *   <li>CSRF disabled — not needed for stateless REST APIs.</li>
     *   <li>Sessions set to STATELESS — no server-side sessions are created.</li>
     *   <li>Registration and login endpoints are public.</li>
     *   <li>Every other endpoint requires an authenticated JWT.</li>
     *   <li>{@link JwtAuthFilter} is inserted before Spring's default username/password filter.</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no JWT required
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                // Everything else (including /api/v1/auth/me) requires authentication
                .anyRequest().authenticated()
            )
            // Return 401 Unauthorized (not 403) when an unauthenticated request reaches
            // a protected endpoint. 403 is reserved for authenticated users who lack authority.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
            )
            .authenticationProvider(authenticationProvider())
            // Insert our JWT filter before Spring's built-in form-login filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
