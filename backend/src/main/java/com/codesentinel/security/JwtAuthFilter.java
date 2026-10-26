package com.codesentinel.security;

import com.codesentinel.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT authentication filter that runs once per HTTP request.
 *
 * <h3>How JWT authentication works in a stateless REST API</h3>
 * <ol>
 *   <li>The client logs in via {@code POST /api/v1/auth/login} and receives a JWT.</li>
 *   <li>For every subsequent request, the client includes
 *       {@code Authorization: Bearer <token>} in the request header.</li>
 *   <li>This filter intercepts the request, extracts the token, validates it,
 *       and — if valid — populates Spring Security's {@link SecurityContextHolder}
 *       so downstream code can call {@code SecurityContextHolder.getContext().getAuthentication()}.</li>
 *   <li>If the token is missing or invalid the filter does nothing; Spring Security's
 *       access control rules will then reject the request with 401 Unauthorized.</li>
 * </ol>
 *
 * <p>Extending {@link OncePerRequestFilter} guarantees this filter runs exactly
 * once per request, even in servlet forward/include chains.</p>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // 1. No Authorization header or not a Bearer token → skip JWT processing.
        //    Spring Security will treat the request as unauthenticated.
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            // 2. Extract the email (subject claim) from the token.
            String email = jwtService.extractEmail(token);

            // 3. Only proceed if we got an email and the SecurityContext has no
            //    existing authentication (avoids unnecessary DB calls on re-entrant filters).
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 4. Load the user's details from the database to verify they still exist.
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // 5. Validate the token: signature, expiration, and subject match.
                if (jwtService.isTokenValid(token, userDetails)) {

                    // 6. Build a Spring Security authentication object and populate the context.
                    //    This marks the request as authenticated for all downstream filters and controllers.
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,                        // credentials null — password not needed here
                                    userDetails.getAuthorities()
                            );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (ExpiredJwtException e) {
            // Log at debug level — this is a routine client-side condition, not a server error.
            log.debug("JWT token has expired for request to {}", request.getRequestURI());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token for request to {}: {}", request.getRequestURI(), e.getMessage());
        }

        // 7. Always continue the filter chain regardless of outcome.
        //    Spring Security's authorization rules decide whether to allow or reject the request.
        filterChain.doFilter(request, response);
    }
}
