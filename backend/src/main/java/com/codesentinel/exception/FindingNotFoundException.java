package com.codesentinel.exception;

import java.util.UUID;

public class FindingNotFoundException extends RuntimeException {

    public FindingNotFoundException(UUID findingId) {
        super("Finding not found with id: " + findingId);
    }
}
