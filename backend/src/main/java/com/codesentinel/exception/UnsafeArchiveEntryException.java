package com.codesentinel.exception;

public class UnsafeArchiveEntryException extends RuntimeException {

    public UnsafeArchiveEntryException() {
        super("The archive contains an unsafe or unsupported entry");
    }
}
