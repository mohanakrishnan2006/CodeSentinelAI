package com.codesentinel.exception;

/**
 * Exception thrown when an attempt is made to register an account with an email
 * address that is already registered in the system.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
