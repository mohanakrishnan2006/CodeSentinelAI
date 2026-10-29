package com.codesentinel.exception;

import java.util.UUID;

public class UploadNotFoundException extends RuntimeException {

    public UploadNotFoundException(UUID uploadId) {
        super("Upload not found: " + uploadId);
    }
}
