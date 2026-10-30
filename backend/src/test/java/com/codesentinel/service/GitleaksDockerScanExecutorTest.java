package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitleaksDockerScanExecutorTest {

    @TempDir
    Path temporaryDirectory;

    private FakeDockerCommandRunner runner;
    private ScanWorkerProperties properties;
    private GitleaksDockerScanExecutor executor;
    private Path sourceDirectory;

    @BeforeEach
    void setUp() throws Exception {
        runner = new FakeDockerCommandRunner();
        properties = new ScanWorkerProperties();
        properties.setTemporaryDirectory(temporaryDirectory.resolve("worker-temp").toString());
        properties.setExecutionTimeoutMs(120_000);
        properties.setDockerCommandTimeoutMs(1_000);
        sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source"));
        executor = new GitleaksDockerScanExecutor(
                runner, properties, new GitleaksJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    @DisplayName("Builds a restricted container command with fixed Gitleaks options")
    void appliesContainerSecurityRestrictions() {
        List<String> command = executor.createCommand("codesentinel-gitleaks-test", sourceDirectory);

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
                "/tmp:rw,noexec,nosuid,nodev,size=67108864,mode=1777",
                "ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f",
                "--report-format=json",
                "--report-path=-",
                "--redact=100",
                "--exit-code=0",
                "--gitleaks-ignore-path=/dev/null",
                "/src");
        assertThat(command).anyMatch(value -> value.contains(
                "dst=/src,readonly"));
        assertThat(command).noneMatch(value -> value.contains("docker.sock"));
    }

    @Test
    @DisplayName("Runs Gitleaks, discards secret-bearing fields, and cleans temporary data and container")
    void executesAndCleansUp() throws Exception {
        Files.writeString(sourceDirectory.resolve("sample.txt"), "harmless placeholder");
        Files.writeString(sourceDirectory.resolve(".gitleaks.toml"), "attacker-controlled config");
        runner.add(success("", 0));
        runner.add(success("""
                [{"RuleID":"generic-api-key","File":"sample.txt","StartLine":1,
                  "Secret":"dummy_test_secret_123","Match":"secret=dummy_test_secret_123"}]
                """, 0));
        runner.add(success("", 0));

        ScanExecutionResult result = executor.execute(workItem());

        assertThat(result.totalFindings()).isEqualTo(1);
        assertThat(result.findings().get(0).toString()).doesNotContain("dummy_test_secret_123");
        assertThat(runner.commands).hasSize(3);
        List<String> createCommand = runner.commands.get(0);
        assertThat(createCommand).anyMatch(value -> value.contains("gitleaks-")
                && value.endsWith(",dst=/src,readonly"));
        assertThat(createCommand).noneMatch(value -> value.contains("gitleaks.toml"));
        assertThat(runner.commands.get(2)).contains("rm", "--force");
        assertThat(runner.stagedConfigExcluded).isTrue();
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("A valid zero-finding report succeeds")
    void acceptsNoFindings() {
        runner.add(success("", 0));
        runner.add(success("[]", 0));
        runner.add(success("", 0));

        assertThat(executor.execute(workItem()).totalFindings()).isZero();
    }

    @Test
    @DisplayName("Nonzero exit, timeout, and truncated output fail and remove the container")
    void handlesScannerFailuresAndCleansUp() {
        assertFailure(new DockerCommandRunner.ProcessResult(2, "", "", false, false));
        assertFailure(new DockerCommandRunner.ProcessResult(-1, "", "", true, false));
        assertFailure(new DockerCommandRunner.ProcessResult(0, "[]", "", false, true));
    }

    @Test
    @DisplayName("Malformed output and cleanup failures are not reported as scan success")
    void rejectsMalformedOutputAndCleanupFailure() {
        runner.add(success("", 0));
        runner.add(success("{", 0));
        runner.add(success("", 0));
        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));

        runner.add(success("", 0));
        runner.add(success("[]", 0));
        runner.add(success("", 1));
        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
    }

    private void assertFailure(DockerCommandRunner.ProcessResult execution) {
        runner.add(success("", 0));
        runner.add(execution);
        runner.add(success("", 0));
        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands.get(runner.commands.size() - 1)).contains("rm", "--force");
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
        private boolean stagedConfigExcluded;

        private void add(ProcessResult result) {
            results.add(result);
        }

        @Override
        public ProcessResult run(List<String> command, Duration timeout, int maximumOutputBytes) {
            commands.add(List.copyOf(command));
            if (command.contains("create")) {
                String mount = command.get(command.indexOf("--mount") + 1);
                String source = mount.substring("type=bind,src=".length(), mount.indexOf(",dst="));
                Path stagedDirectory = Path.of(source);
                stagedConfigExcluded = Files.exists(stagedDirectory.resolve(".gitleaks.toml"));
                stagedConfigExcluded = !stagedConfigExcluded
                        && Files.exists(stagedDirectory.resolve("sample.txt"));
            }
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected Docker command");
            }
            return results.removeFirst();
        }
    }
}
