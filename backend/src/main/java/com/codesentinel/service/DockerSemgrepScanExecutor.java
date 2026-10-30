package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "codesentinel.scan-worker.executor",
        havingValue = "semgrep",
        matchIfMissing = true)
public class DockerSemgrepScanExecutor implements ScanExecutor {

    private static final String RULESET_RESOURCE = "/semgrep/codesentinel.yml";
    private static final int CLEANUP_OUTPUT_LIMIT = 1024;
    private static final String DOCKER_EXECUTABLE = "docker";

    private final DockerCommandRunner commandRunner;
    private final ScanWorkerProperties properties;
    private final SemgrepJsonParser parser;

    @Override
    public ScanExecutionResult execute(ScanWorkItem workItem) {
        Path temporaryDirectory = createTemporaryDirectory();
        String containerName = "codesentinel-semgrep-" + workItem.scanId() + "-" + UUID.randomUUID();
        boolean cleanupRequired = false;
        try {
            Path ruleset = writeTrustedRuleset(temporaryDirectory);
            Path stagedSource = stageSourceDirectory(workItem.sourceDirectory(), temporaryDirectory);
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanExecutionException("Scan was interrupted before Semgrep started");
            }
            cleanupRequired = true;
            DockerCommandRunner.ProcessResult created = commandRunner.run(
                    createCommand(containerName, stagedSource, ruleset),
                    Duration.ofMillis(properties.getDockerCommandTimeoutMs()),
                    CLEANUP_OUTPUT_LIMIT);
            requireSuccessful(created, "Semgrep container could not be created");

            DockerCommandRunner.ProcessResult result = commandRunner.run(
                    List.of(DOCKER_EXECUTABLE, "start", "--attach", containerName),
                    Duration.ofMillis(properties.getExecutionTimeoutMs()),
                    properties.getMaxOutputBytes());
            requireSuccessful(result, "Semgrep execution failed");
            return parser.parse(result.stdout());
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (cleanupRequired) {
                    removeContainer(containerName);
                }
            } finally {
                try {
                    deleteTemporaryDirectory(temporaryDirectory);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private Path stageSourceDirectory(Path sourceDirectory, Path temporaryDirectory) {
        Path stagedSource = temporaryDirectory.resolve("source");
        long[] copiedBytes = {0};
        try {
            Files.createDirectory(stagedSource);
            Files.walkFileTree(sourceDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw new IOException("Upload contains an unsafe directory");
                    }
                    Path target = stagedSource.resolve(sourceDirectory.relativize(directory)).normalize();
                    if (!target.startsWith(stagedSource)) {
                        throw new IOException("Upload contains an unsafe path");
                    }
                    if (!target.equals(stagedSource)) {
                        Files.createDirectory(target);
                    }
                    makeReadableByScanner(target, true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw new IOException("Upload contains an unsafe file");
                    }
                    Path target = stagedSource.resolve(sourceDirectory.relativize(file)).normalize();
                    if (!target.startsWith(stagedSource)) {
                        throw new IOException("Upload contains an unsafe path");
                    }
                    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                         OutputStream output = Files.newOutputStream(
                                 target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            copiedBytes[0] = Math.addExact(copiedBytes[0], count);
                            if (copiedBytes[0] > properties.getMaxSourceBytes()) {
                                throw new IOException("Upload exceeds the configured scanner source limit");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                    makeReadableByScanner(target, false);
                    return FileVisitResult.CONTINUE;
                }
            });
            return stagedSource;
        } catch (IOException | ArithmeticException ex) {
            throw new ScanExecutionException("Uploaded source could not be safely staged", ex);
        }
    }

    private void makeReadableByScanner(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use the worker temporary directory's inherited access controls.
        }
    }

    List<String> createCommand(String name, Path sourceDirectory, Path ruleset) {
        List<String> command = new ArrayList<>(List.of(
                DOCKER_EXECUTABLE,
                "create",
                "--pull=never",
                "--name", name,
                "--network", "none",
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true",
                "--pids-limit", Integer.toString(properties.getProcessLimit()),
                "--memory", properties.getMemoryLimitBytes() + "b",
                "--cpus", Double.toString(properties.getCpuLimit()),
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size="
                        + properties.getTemporaryMemoryBytes() + ",mode=1777",
                "--env", "HOME=/tmp",
                "--workdir", "/src",
                "--mount", bindMount(sourceDirectory, "/src"),
                "--mount", bindMount(ruleset, "/tmp/codesentinel.yml"),
                "--entrypoint", "semgrep",
                properties.getSemgrepImage(),
                "scan",
                "--json",
                "--metrics=off",
                "--disable-version-check",
                "--config", "/tmp/codesentinel.yml",
                "."));
        return List.copyOf(command);
    }

    private String bindMount(Path source, String destination) {
        String path = source.toAbsolutePath().normalize().toString();
        if (path.contains(",") || path.contains("\n") || path.contains("\r")) {
            throw new ScanExecutionException("Scanner storage path contains unsupported characters");
        }
        return "type=bind,src=" + path + ",dst=" + destination + ",readonly";
    }

    private Path createTemporaryDirectory() {
        Path root = Path.of(properties.getTemporaryDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Temporary output root is not a safe directory");
            }
            return Files.createTempDirectory(root, "semgrep-");
        } catch (IOException ex) {
            throw new ScanExecutionException("Semgrep temporary storage is unavailable", ex);
        }
    }

    private Path writeTrustedRuleset(Path temporaryDirectory) {
        Path ruleset = temporaryDirectory.resolve("codesentinel.yml");
        try (InputStream input = DockerSemgrepScanExecutor.class.getResourceAsStream(RULESET_RESOURCE)) {
            if (input == null) {
                throw new IOException("Trusted Semgrep ruleset is missing");
            }
            Files.copy(input, ruleset);
            makeReadableByScanner(ruleset, false);
            return ruleset;
        } catch (IOException ex) {
            throw new ScanExecutionException("Trusted Semgrep ruleset is unavailable", ex);
        }
    }

    private void requireSuccessful(DockerCommandRunner.ProcessResult result, String failureMessage) {
        if (result.timedOut()) {
            throw new ScanExecutionException("Semgrep execution exceeded its configured deadline");
        }
        if (result.outputTruncated()) {
            throw new ScanExecutionException("Semgrep output exceeded its configured limit");
        }
        if (result.exitCode() != 0) {
            throw new ScanExecutionException(failureMessage);
        }
    }

    private void removeContainer(String containerName) {
        DockerCommandRunner.ProcessResult cleanup = commandRunner.run(
                List.of(DOCKER_EXECUTABLE, "rm", "--force", containerName),
                Duration.ofMillis(properties.getDockerCommandTimeoutMs()),
                CLEANUP_OUTPUT_LIMIT);
        if (cleanup.timedOut() || cleanup.outputTruncated() || cleanup.exitCode() != 0) {
            throw new ScanExecutionException("Semgrep container cleanup failed");
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException ex) {
            throw new ScanExecutionException("Semgrep temporary output cleanup failed", ex);
        }
    }
}
