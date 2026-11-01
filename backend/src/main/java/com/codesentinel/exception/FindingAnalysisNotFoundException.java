package com.codesentinel.exception;

import java.util.UUID;

public class FindingAnalysisNotFoundException extends RuntimeException {

    public FindingAnalysisNotFoundException(UUID findingId) {
        super("Analysis not found for finding " + findingId);
    }
}
