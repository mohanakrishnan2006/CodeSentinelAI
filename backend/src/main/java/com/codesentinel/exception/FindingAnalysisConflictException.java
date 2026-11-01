package com.codesentinel.exception;

public class FindingAnalysisConflictException extends RuntimeException {

    public FindingAnalysisConflictException() {
        super("Analysis is already processing or has reached its retry limit");
    }
}
