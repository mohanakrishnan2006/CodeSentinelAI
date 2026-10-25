package com.codesentinel.repository;

import com.codesentinel.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for managing User entities.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Look up a user by their unique email address.
     *
     * @param email user email to query
     * @return Optional containing the User if found, or empty Optional otherwise
     */
    Optional<User> findByEmail(String email);

    /**
     * Check whether a user already exists with the given email address.
     *
     * @param email email to check for uniqueness
     * @return true if an account exists with this email, false otherwise
     */
    boolean existsByEmail(String email);
}
