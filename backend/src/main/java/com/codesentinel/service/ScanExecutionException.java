package com.codesentinel.service;

import com.codesentinel.model.ScannerFailureCode;

public class ScanExecutionException extends RuntimeException {

    private final ScannerFailureCode failureCode;

    public ScanExecutionException(String message) {
        this(message, ScannerFailureCode.EXECUTION_ERROR, null);
    }

    public ScanExecutionException(String message, Throwable cause) {
        this(message, ScannerFailureCode.EXECUTION_ERROR, cause);
    }

    public ScanExecutionException(String message, ScannerFailureCode failureCode) {
        this(message, failureCode, null);
    }

    public ScanExecutionException(
            String message,
            ScannerFailureCode failureCode,
            Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public ScannerFailureCode failureCode() {
        return failureCode;
    }
}
