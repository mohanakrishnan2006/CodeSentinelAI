package com.codesentinel.service;

import com.codesentinel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bridges CodeSentinel's {@link com.codesentinel.model.User} entity with
 * Spring Security's authentication framework.
 *
 * <h3>Why is this needed?</h3>
 * <p>Spring Security's {@code AuthenticationManager} calls
 * {@link #loadUserByUsername(String)} during login to fetch the stored
 * credentials and authorities for the email being authenticated. Without this
 * implementation, Spring would fall back to its auto-generated in-memory user
 * (the annoying "Using generated security password" warning in the logs).</p>
 *
 * <h3>What is UserDetails?</h3>
 * <p>It is Spring Security's portable representation of a user: username (email
 * here), password hash, enabled status, and granted authorities (roles).</p>
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads a user by their email address.
     *
     * @param email the email submitted in the login request
     * @return a {@link UserDetails} object Spring Security can authenticate against
     * @throws UsernameNotFoundException if no account exists for that email
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        com.codesentinel.model.User user = userRepository
                .findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No account registered for email: " + email));

        // Map our Role enum to a Spring Security granted authority.
        // Spring Security expects authority names starting with "ROLE_" by convention.
        return new User(
                user.getEmail(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
