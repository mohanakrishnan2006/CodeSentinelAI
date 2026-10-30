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

class DockerSemgrepScanExecutorTest {

    @TempDir
    Path temporaryDirectory;

    private FakeDockerCommandRunner runner;
    private ScanWorkerProperties properties;
    private DockerSemgrepScanExecutor executor;
    private Path sourceDirectory;

    @BeforeEach
    void setUp() throws Exception {
        runner = new FakeDockerCommandRunner();
        properties = new ScanWorkerProperties();
        properties.setTemporaryDirectory(temporaryDirectory.resolve("worker-temp").toString());
        properties.setExecutionTimeoutMs(1000);
        properties.setDockerCommandTimeoutMs(1000);
        properties.setLeaseTimeoutMs(60_000);
        sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source"));
        executor = new DockerSemgrepScanExecutor(
                runner, properties, new SemgrepJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    @DisplayName("Builds a restricted container command with only read-only source and trusted ruleset mounts")
    void appliesContainerSecurityRestrictions() {
        List<String> command = executor.createCommand(
                "codesentinel-semgrep-test", sourceDirectory, temporaryDirectory.resolve("rules.yml"));

        assertThat(command).contains(
                "--pull=never",
                "--network", "none",
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true",
                "--pids-limit", "128",
                "--memory", "536870912b",
                "--cpus", "1.0",
                "/tmp:rw,noexec,nosuid,nodev,size=67108864,mode=1777",
                "semgrep/semgrep:1.182.1-nonroot@sha256:0ed16bbbb23e5bf7c442df9144704d74b54ad2d003094e09905130d642f22473",
                "--workdir", "/src", "--config", "/tmp/codesentinel.yml", ".");
        assertThat(command).anyMatch(value -> value.contains(
                "src=" + sourceDirectory + ",dst=/src,readonly"));
        assertThat(command).anyMatch(value -> value.contains(
                "dst=/tmp/codesentinel.yml,readonly"));
        assertThat(command).noneMatch(value -> value.contains("docker.sock"));
        assertThat(command).doesNotContain("--user");
    }

    @Test
    @DisplayName("Parses scanner output and removes its temporary files and container")
    void executesAndCleansUp() throws Exception {
        Files.writeString(sourceDirectory.resolve("sample.java"), "class Sample {}");
        runner.add(success("container-id", "", 0));
        runner.add(success(semgrepJson(), "", 0));
        runner.add(success("container-id", "", 0));

        ScanExecutionResult result = executor.execute(workItem());

        assertThat(result.totalFindings()).isEqualTo(1);
        assertThat(result.count(com.codesentinel.model.ScanFindingSeverity.HIGH)).isEqualTo(1);
        assertThat(runner.commands).hasSize(3);
        assertThat(runner.commands.get(0)).anyMatch(value ->
                value.contains("worker-temp") && value.endsWith(",dst=/src,readonly"));
        assertThat(runner.commands.get(0)).noneMatch(value -> value.contains(
                "src=" + sourceDirectory + ",dst=/src,readonly"));
        assertThat(runner.commands.get(2)).contains("rm", "--force");
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("Valid empty scanner output is a successful zero-finding result")
    void allowsZeroFindings() {
        runner.add(success("container-id", "", 0));
        runner.add(success("{\"results\":[]}", "", 0));
        runner.add(success("container-id", "", 0));

        assertThat(executor.execute(workItem()).totalFindings()).isZero();
    }

    @Test
    @DisplayName("Fails on scanner non-zero exit and still removes its container")
    void removesContainerWhenScannerExitsNonZero() throws Exception {
        runner.add(success("container-id", "", 0));
        runner.add(success("invalid result", "scanner stderr", 2));
        runner.add(success("container-id", "", 0));

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands).hasSize(3);
        assertThat(runner.commands.get(2)).contains("rm", "--force");
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("Fails on execution timeout and forcibly removes the container")
    void cleansUpAfterExecutionTimeout() throws Exception {
        runner.add(success("container-id", "", 0));
        runner.add(new DockerCommandRunner.ProcessResult(-1, "", "", true, false));
        runner.add(success("container-id", "", 0));

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands.get(2)).contains("rm", "--force");
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("Attempts forced removal when container creation times out")
    void removesContainerWhenCreationTimesOut() throws Exception {
        runner.add(new DockerCommandRunner.ProcessResult(-1, "", "", true, false));
        runner.add(success("container-id", "", 0));

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands).hasSize(2);
        assertThat(runner.commands.get(1)).contains("rm", "--force");
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("Rejects truncated scanner output and removes the container")
    void rejectsTruncatedOutput() throws Exception {
        runner.add(success("container-id", "", 0));
        runner.add(new DockerCommandRunner.ProcessResult(0, "{\"results\":[]}", "", false, true));
        runner.add(success("container-id", "", 0));

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands.get(2)).contains("rm", "--force");
    }

    @Test
    @DisplayName("Rejects a source snapshot that exceeds the configured staging limit")
    void rejectsOversizedSourceSnapshot() throws Exception {
        properties.setMaxSourceBytes(1);
        Files.writeString(sourceDirectory.resolve("sample.java"), "too large");

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
        assertThat(runner.commands).isEmpty();
        assertThat(tempDirectories()).isEmpty();
    }

    @Test
    @DisplayName("Fails when container cleanup fails instead of reporting success")
    void failsIfCleanupFails() {
        runner.add(success("container-id", "", 0));
        runner.add(success("{\"results\":[]}", "", 0));
        runner.add(success("", "", 1));

        assertThrows(ScanExecutionException.class, () -> executor.execute(workItem()));
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

    private DockerCommandRunner.ProcessResult success(String stdout, String stderr, int exitCode) {
        return new DockerCommandRunner.ProcessResult(exitCode, stdout, stderr, false, false);
    }

    private String semgrepJson() {
        return """
                {"results":[{"check_id":"test.rule","path":"src/Test.java",
                  "start":{"line":3,"col":1},"end":{"line":3,"col":8},
                  "extra":{"severity":"ERROR","message":"A finding"}}]}
                """;
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
