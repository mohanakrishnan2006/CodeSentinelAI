package com.codesentinel.exception;

import java.util.UUID;

public class UploadNotReadyException extends RuntimeException {

    public UploadNotReadyException(UUID uploadId) {
        super("Upload is not ready for scanning: " + uploadId);
    }
}
