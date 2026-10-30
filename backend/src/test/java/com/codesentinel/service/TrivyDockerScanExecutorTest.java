package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.model.ScannerFailureCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrivyDockerScanExecutorTest {

    @TempDir
    Path temporaryDirectory;

    private FakeDockerCommandRunner runner;
    private ScanWorkerProperties properties;
    private TrivyDockerScanExecutor executor;
    private Path sourceDirectory;
    private Path cacheDirectory;

    @BeforeEach
    void setUp() throws Exception {
        runner = new FakeDockerCommandRunner();
        properties = new ScanWorkerProperties();
        properties.setTemporaryDirectory(temporaryDirectory.resolve("worker-temp").toString());
        properties.setTrivyDatabaseDirectory(temporaryDirectory.resolve("trusted-trivy-cache").toString());
        properties.setExecutionTimeoutMs(120_000);
        properties.setDockerCommandTimeoutMs(1_000);
        sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.writeString(sourceDirectory.resolve("pom.xml"), "<project/>");
        cacheDirectory = Path.of(properties.getTrivyDatabaseDirectory());
        createFreshDatabase(cacheDirectory);
        ObjectMapper objectMapper = new ObjectMapper();
        executor = new TrivyDockerScanExecutor(
                runner, properties, new TrivyJsonParser(objectMapper),
                new TrivyDatabaseValidator(properties, objectMapper));
    }

    @Test
    @DisplayName("Builds a restricted offline non-root container with read-only mounts")
    void appliesContainerSecurityRestrictions() {
        List<String> command = executor.createCommand(
                "codesentinel-trivy-test", sourceDirectory, cacheDirectory);

        assertThat(command).contains(
                "--pull=never",
                "--network", "none",
                "--read-only",
                "--user", "10001:10001",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true",
                "--pids-limit", "128",
                "--memory", "536870912b",
                "--cpus", "1.0",
                "--skip-db-update",
                "--skip-java-db-update",
                "--offline-scan",
                "--scanners", "vuln",
                "--format", "json",
                "--exit-code", "0",
                "aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c");
        assertThat(command).anyMatch(value -> value.contains("dst=/src,readonly"));
        assertThat(command).anyMatch(value -> value.contains("dst=/trivy-cache,readonly"));
        assertThat(command).noneMatch(value -> value.contains("docker.sock"));
    }

    @Test
    @DisplayName("Runs Trivy, parses normalized findings, and removes container and temporary files")
    void executesAndCleansUp() throws Exception {
        runner.add(success("", 0));
        runner.add(success("""
                {"SchemaVersion":2,"Results":[{"Target":"/src/pom.xml","Vulnerabilities":[
                  {"VulnerabilityID":"CVE-2025-1000","Severity":"HIGH",
                   "Title":"dummy-only-not-real-secret"}]}]}
                """, 0));
        runner.add(success("", 0));

        ScanExecutionResult result = executor.execute(workItem());

        assertThat(result.totalFindings()).isEqualTo(1);
        assertThat(result.findings().get(0).filePath()).isEqualTo("pom.xml");
        assertThat(result.findings().get(0).toString()).doesNotContain("dummy-only-not-real-secret");
        assertThat(runner.commands).hasSize(3);
        assertThat(runner.commands.get(0)).anyMatch(value ->
                value.contains("dst=/src,readonly"));
        assertThat(runner.commands.get(0)).anyMatch(value ->
                value.contains("dst=/trivy-cache,readonly"));
        assertThat(runner.commands.get(2)).contains("rm", "--force");
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("A missing or stale database fails before creating a container")
    void failsForUnavailableDatabase() throws Exception {
        Files.delete(cacheDirectory.resolve("db/metadata.json"));

        ScanExecutionException missing = assertThrows(
                ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(missing.failureCode()).isEqualTo(ScannerFailureCode.DATABASE_UNAVAILABLE);
        assertThat(runner.commands).isEmpty();

        createFreshDatabase(cacheDirectory);
        Files.writeString(cacheDirectory.resolve("db/metadata.json"), """
                {"UpdatedAt":"2020-01-01T00:00:00Z","NextUpdate":"2020-01-02T00:00:00Z"}
                """);
        ScanExecutionException stale = assertThrows(
                ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(stale.failureCode()).isEqualTo(ScannerFailureCode.DATABASE_UNAVAILABLE);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    @DisplayName("Timeouts, nonzero exits, truncated output, and invalid JSON fail and clean up")
    void handlesScannerFailuresAndCleansUp() {
        assertFailure(new DockerCommandRunner.ProcessResult(-1, "", "", true, false));
        assertFailure(new DockerCommandRunner.ProcessResult(2, "", "", false, false));
        assertFailure(new DockerCommandRunner.ProcessResult(0, "{}", "", false, true));

        runner.add(success("", 0));
        runner.add(success("{", 0));
        runner.add(success("", 0));
        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands.get(runner.commands.size() - 1)).contains("rm", "--force");
    }

    private void assertFailure(DockerCommandRunner.ProcessResult execution) {
        runner.add(success("", 0));
        runner.add(execution);
        runner.add(success("", 0));
        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands.get(runner.commands.size() - 1)).contains("rm", "--force");
    }

    private void createFreshDatabase(Path root) throws Exception {
        Path db = Files.createDirectories(root.resolve("db"));
        Files.write(db.resolve("trivy.db"), new byte[]{1, 2, 3});
        Files.writeString(db.resolve("metadata.json"), """
                {"UpdatedAt":"%s","NextUpdate":"%s"}
                """.formatted(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)));
    }

    private ScanWorkItem workItem() {
        return new ScanWorkItem(UUID.randomUUID(), UUID.randomUUID(), sourceDirectory);
    }

    private List<Path> tempDirectories() throws Exception {
        Path root = temporaryDirectory.resolve("worker-temp");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.list(root)) {
            return paths.toList();
        }
    }

    private DockerCommandRunner.ProcessResult success(String stdout, int exitCode) {
        return new DockerCommandRunner.ProcessResult(exitCode, stdout, "", false, false);
    }

    private static final class FakeDockerCommandRunner implements DockerCommandRunner {

        private final Deque<ProcessResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();

        private void add(ProcessResult result) {
            results.add(result);
        }

        @Override
        public ProcessResult run(List<String> command, Duration timeout, int maximumOutputBytes) {
            commands.add(List.copyOf(command));
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected Docker command");
            }
            return results.removeFirst();
        }
    }
}
