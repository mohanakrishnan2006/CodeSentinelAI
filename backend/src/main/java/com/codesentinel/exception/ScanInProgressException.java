package com.codesentinel.exception;

import java.util.UUID;

public class ScanInProgressException extends RuntimeException {

    public ScanInProgressException(UUID projectId) {
        super("A scan is running for project: " + projectId);
    }
}
