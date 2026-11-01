package com.codesentinel.service;

import com.codesentinel.dto.ai.AiAnalysisRequest;
import com.codesentinel.dto.ai.AiAnalysisResponse;
import com.codesentinel.exception.AiIntegrationException;
import com.codesentinel.model.ScanFindingAnalysisFailureCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpAiAnalysisClientTest {

    @Test
    @DisplayName("Serializes only normalized finding fields and maps the structured response")
    void serializesRequestAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/api/v1/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "ruleId":"generic-api-key",
                          "severity":"HIGH",
                          "message":"Potential exposed secret detected.",
                          "filePath":"config.txt",
                          "startLine":4,
                          "startColumn":2,
                          "endLine":4,
                          "endColumn":8,
                          "scanner":"GITLEAKS",
                          "metadata":{}
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "explanation":"A potential secret was detected.",
                          "whyItMatters":"It could allow unauthorized access.",
                          "recommendedRemediation":"Rotate the credential and use a secret manager.",
                          "illustrativeFix":null
                        }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        HttpAiAnalysisClient client = new HttpAiAnalysisClient(builder.build());
        AiAnalysisResponse result = client.analyze(new AiAnalysisRequest(
                "generic-api-key", "HIGH", "Potential exposed secret detected.", "config.txt",
                4, 2, 4, 8, "GITLEAKS", Map.of()));

        assertThat(result.explanation()).isEqualTo("A potential secret was detected.");
        assertThat(result.illustrativeFix()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("Maps unsafe input to a controlled 422 without exposing the upstream body")
    void mapsUnsafeInputWithoutLeakingUpstreamResponse() {
        HttpAiAnalysisClient client = clientForStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                "sensitive upstream response that must not be forwarded");

        AiIntegrationException exception = assertThrows(AiIntegrationException.class,
                () -> client.analyze(sampleRequest()));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exception.getFailureCode()).isEqualTo(ScanFindingAnalysisFailureCode.UNSAFE_FINDING);
        assertThat(exception.getMessage()).doesNotContain("sensitive upstream response");
    }

    @Test
    @DisplayName("Maps upstream unavailable, timeout, and invalid-response statuses safely")
    void mapsUpstreamStatuses() {
        assertStatus(HttpStatus.BAD_GATEWAY, HttpStatus.BAD_GATEWAY,
                ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                ScanFindingAnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
        assertStatus(HttpStatus.GATEWAY_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
                ScanFindingAnalysisFailureCode.AI_GENERATION_TIMEOUT);
    }

    @Test
    @DisplayName("Maps malformed model responses to a controlled upstream failure")
    void rejectsMalformedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/api/v1/analyze"))
                .andRespond(withSuccess("""
                        {"explanation":"","whyItMatters":"Risk","recommendedRemediation":"Fix"}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        AiIntegrationException exception = assertThrows(AiIntegrationException.class,
                () -> new HttpAiAnalysisClient(builder.build()).analyze(sampleRequest()));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception.getFailureCode())
                .isEqualTo(ScanFindingAnalysisFailureCode.AI_RESPONSE_FAILURE);
    }

    @Test
    @DisplayName("Maps connection failures and generation timeouts to controlled errors")
    void mapsConnectionFailureAndTimeout() {
        AiIntegrationException unavailable = assertThrows(AiIntegrationException.class,
                () -> clientFailingWith(new IOException("connect failure")).analyze(sampleRequest()));
        assertThat(unavailable.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        AiIntegrationException timedOut = assertThrows(AiIntegrationException.class,
                () -> clientFailingWith(new SocketTimeoutException("timeout")).analyze(sampleRequest()));
        assertThat(timedOut.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(timedOut.getFailureCode())
                .isEqualTo(ScanFindingAnalysisFailureCode.AI_GENERATION_TIMEOUT);
    }

    private HttpAiAnalysisClient clientForStatus(HttpStatus status, String body) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/api/v1/analyze"))
                .andRespond(withStatus(status).body(body));
        return new HttpAiAnalysisClient(builder.build());
    }

    private HttpAiAnalysisClient clientFailingWith(IOException failure) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://ai.test")
                .requestFactory((uri, method) -> {
                    throw failure;
                })
                .build();
        return new HttpAiAnalysisClient(restClient);
    }

    private void assertStatus(
            HttpStatus upstreamStatus,
            HttpStatus expectedStatus,
            ScanFindingAnalysisFailureCode expectedCode) {
        AiIntegrationException exception = assertThrows(AiIntegrationException.class,
                () -> clientForStatus(upstreamStatus, "untrusted upstream body")
                        .analyze(sampleRequest()));
        assertThat(exception.getStatus()).isEqualTo(expectedStatus);
        assertThat(exception.getFailureCode()).isEqualTo(expectedCode);
        assertThat(exception.getMessage()).doesNotContain("untrusted upstream body");
    }

    private AiAnalysisRequest sampleRequest() {
        return new AiAnalysisRequest(
                "rule", "HIGH", "generic description", "src/Main.java",
                1, 1, 1, 2, "SEMGREP", Map.of());
    }
}
