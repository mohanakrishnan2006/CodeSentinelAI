package com.codesentinel.exception;

public class MissingUploadFileException extends RuntimeException {

    public MissingUploadFileException() {
        super("A non-empty ZIP file is required");
    }
}
