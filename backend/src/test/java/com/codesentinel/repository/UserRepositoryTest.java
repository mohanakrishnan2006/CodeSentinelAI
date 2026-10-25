package com.codesentinel.repository;

import com.codesentinel.model.Role;
import com.codesentinel.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Persist a user, verify UUID generation, timestamps, and retrieval by ID")
    void shouldPersistUserAndGenerateUuidAndTimestamps() {
        User user = User.builder()
                .fullName("Alice Developer")
                .email("alice@example.com")
                .passwordHash("$2a$12$e8Y...mockHashedPassword")
                .role(Role.USER)
                .build();

        User savedUser = userRepository.saveAndFlush(user);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getId()).isInstanceOf(UUID.class);
        assertThat(savedUser.getFullName()).isEqualTo("Alice Developer");
        assertThat(savedUser.getEmail()).isEqualTo("alice@example.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("$2a$12$e8Y...mockHashedPassword");
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(savedUser.getUpdatedAt()).isNotNull();

        Optional<User> retrieved = userRepository.findById(savedUser.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("Verify findByEmail returns the user when email exists")
    void shouldFindUserByEmail() {
        User user = User.builder()
                .fullName("Bob Reviewer")
                .email("bob.reviewer@example.com")
                .passwordHash("$2a$12$anotherHashedSecret")
                .role(Role.REVIEWER)
                .build();

        userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByEmail("bob.reviewer@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Bob Reviewer");
        assertThat(found.get().getRole()).isEqualTo(Role.REVIEWER);
    }

    @Test
    @DisplayName("Verify findByEmail returns empty Optional for non-existent email")
    void shouldReturnEmptyWhenEmailNotFound() {
        Optional<User> found = userRepository.findByEmail("nonexistent@example.com");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Verify existsByEmail returns true for existing email and false otherwise")
    void shouldCheckIfEmailExists() {
        User user = User.builder()
                .fullName("Charlie Admin")
                .email("charlie.admin@example.com")
                .passwordHash("$2a$12$secureAdminHash")
                .role(Role.ADMIN)
                .build();

        userRepository.saveAndFlush(user);

        assertThat(userRepository.existsByEmail("charlie.admin@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("unknown@example.com")).isFalse();
    }

    @Test
    @DisplayName("Verify duplicate email persistence is rejected with DataIntegrityViolationException")
    void shouldPreventDuplicateEmailPersistence() {
        User user1 = User.builder()
                .fullName("User One")
                .email("duplicate@example.com")
                .passwordHash("$2a$12$hash1")
                .build();

        userRepository.saveAndFlush(user1);

        User user2 = User.builder()
                .fullName("User Two")
                .email("duplicate@example.com")
                .passwordHash("$2a$12$hash2")
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> {
            userRepository.saveAndFlush(user2);
        });
    }

    @Test
    @DisplayName("Verify new User defaults to Role.USER when role is not specified")
    void shouldDefaultToUserRole() {
        User user = User.builder()
                .fullName("Default Role User")
                .email("default.role@example.com")
                .passwordHash("$2a$12$defaultHash")
                .build();

        User savedUser = userRepository.saveAndFlush(user);

        assertThat(savedUser.getRole()).isEqualTo(Role.USER);
    }
}
