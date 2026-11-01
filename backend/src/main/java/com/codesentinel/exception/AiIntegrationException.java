package com.codesentinel.exception;

import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import org.springframework.http.HttpStatus;

public class AiIntegrationException extends RuntimeException {

    private final HttpStatus status;
    private final ScanFindingAnalysisFailureCode failureCode;
    private final String safeMessage;

    public AiIntegrationException(
            HttpStatus status,
            ScanFindingAnalysisFailureCode failureCode,
            String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.failureCode = failureCode;
        this.safeMessage = safeMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ScanFindingAnalysisFailureCode getFailureCode() {
        return failureCode;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
