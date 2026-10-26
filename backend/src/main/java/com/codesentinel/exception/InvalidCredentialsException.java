package com.codesentinel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link com.codesentinel.service.AuthService} when a login attempt
 * fails due to an unrecognised email or an incorrect password.
 *
 * <p>We intentionally return the same generic message for both cases to avoid
 * revealing whether a given email address is registered in the system
 * (a common information-disclosure vulnerability).</p>
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
