package com.codesentinel.service;

import com.codesentinel.dto.ai.AiAnalysisRequest;
import com.codesentinel.dto.ai.AiAnalysisResponse;
import com.codesentinel.exception.AiIntegrationException;
import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class HttpAiAnalysisClient implements AiAnalysisClient {

    private final RestClient restClient;

    public HttpAiAnalysisClient(RestClient aiServiceRestClient) {
        this.restClient = aiServiceRestClient;
    }

    @Override
    public AiAnalysisResponse analyze(AiAnalysisRequest request) {
        try {
            AiAnalysisResponse response = restClient.post()
                    .uri("/api/v1/analyze")
                    .body(request)
                    .retrieve()
                    .body(AiAnalysisResponse.class);
            validateResponse(response);
            return response;
        } catch (RestClientResponseException ex) {
            throw mapStatus(ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw failure(HttpStatus.GATEWAY_TIMEOUT,
                        ScanFindingAnalysisFailureCode.AI_GENERATION_TIMEOUT,
                        "The AI analysis request timed out");
            }
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    ScanFindingAnalysisFailureCode.AI_SERVICE_UNAVAILABLE,
                    "The AI analysis service is unavailable");
        } catch (RestClientException ex) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE,
                    "The AI analysis service returned an invalid response");
        }
    }

    private void validateResponse(AiAnalysisResponse response) {
        if (response == null
                || !validText(response.explanation(), 3000)
                || !validText(response.whyItMatters(), 2000)
                || !validText(response.recommendedRemediation(), 3000)) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE,
                    "The AI analysis service returned an invalid response");
        }
        if (response.illustrativeFix() != null
                && (!validText(response.illustrativeFix().language(), 64)
                || !validText(response.illustrativeFix().code(), 4096))) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE,
                    "The AI analysis service returned an invalid response");
        }
    }

    private boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private AiIntegrationException mapStatus(int statusCode) {
        return switch (statusCode) {
            case 422 -> failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    ScanFindingAnalysisFailureCode.UNSAFE_FINDING,
                    "The finding could not be analyzed safely");
            case 502 -> failure(HttpStatus.BAD_GATEWAY,
                    ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE,
                    "The AI analysis service returned an invalid response");
            case 503 -> failure(HttpStatus.SERVICE_UNAVAILABLE,
                    ScanFindingAnalysisFailureCode.AI_SERVICE_UNAVAILABLE,
                    "The AI analysis service is unavailable");
            case 504 -> failure(HttpStatus.GATEWAY_TIMEOUT,
                    ScanFindingAnalysisFailureCode.AI_GENERATION_TIMEOUT,
                    "The AI analysis request timed out");
            default -> failure(HttpStatus.BAD_GATEWAY,
                    ScanFindingAnalysisFailureCode.AI_UPSTREAM_FAILURE,
                    "The AI analysis service could not complete the request");
        };
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AiIntegrationException failure(
            HttpStatus status,
            ScanFindingAnalysisFailureCode code,
            String message) {
        return new AiIntegrationException(status, code, message);
    }
}
