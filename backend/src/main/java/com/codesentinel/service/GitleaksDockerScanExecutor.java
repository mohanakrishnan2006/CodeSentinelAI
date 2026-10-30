package com.codesentinel.service;

import com.codesentinel.config.ScanWorkerProperties;
import com.codesentinel.model.ScannerFailureCode;
import com.codesentinel.model.ScannerType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "codesentinel.scan-worker.executor", havingValue = "composite")
public class GitleaksDockerScanExecutor implements ScannerAdapter {

    private static final String DOCKER_EXECUTABLE = "docker";
    private static final String CONFIG_FILENAME = ".gitleaks.toml";
    private static final int CLEANUP_OUTPUT_LIMIT = 1024;
    private static final int SAFE_OUTPUT_MARGIN_SECONDS = 5;

    private final DockerCommandRunner commandRunner;
    private final ScanWorkerProperties properties;
    private final GitleaksJsonParser parser;

    @Override
    public ScannerType scannerType() {
        return ScannerType.GITLEAKS;
    }

    @Override
    public ScanExecutionResult execute(ScanWorkItem workItem) {
        Path temporaryDirectory = createTemporaryDirectory();
        String containerName = "codesentinel-gitleaks-" + workItem.scanId() + "-" + UUID.randomUUID();
        boolean cleanupRequired = false;
        try {
            Path stagedSource = stageSourceDirectory(workItem.sourceDirectory(), temporaryDirectory);
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanExecutionException("Scan was interrupted before Gitleaks started",
                        ScannerFailureCode.TIMEOUT);
            }
            cleanupRequired = true;
            DockerCommandRunner.ProcessResult created = commandRunner.run(
                    createCommand(containerName, stagedSource),
                    Duration.ofMillis(properties.getDockerCommandTimeoutMs()),
                    CLEANUP_OUTPUT_LIMIT);
            requireSuccessful(created, "Gitleaks container could not be created");

            DockerCommandRunner.ProcessResult result = commandRunner.run(
                    List.of(DOCKER_EXECUTABLE, "start", "--attach", containerName),
                    Duration.ofMillis(properties.getExecutionTimeoutMs()),
                    properties.getMaxOutputBytes());
            requireSuccessful(result, "Gitleaks execution failed");
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

    List<String> createCommand(String name, Path sourceDirectory) {
        long timeoutSeconds = Math.max(1,
                properties.getExecutionTimeoutMs() / 1000 - SAFE_OUTPUT_MARGIN_SECONDS);
        return List.of(
                DOCKER_EXECUTABLE,
                "create",
                "--pull=never",
                "--name", name,
                "--network", "none",
                "--read-only",
                "--user", "10001:10001",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges:true",
                "--pids-limit", Integer.toString(properties.getProcessLimit()),
                "--memory", properties.getMemoryLimitBytes() + "b",
                "--cpus", Double.toString(properties.getCpuLimit()),
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size="
                        + properties.getTemporaryMemoryBytes() + ",mode=1777",
                "--env", "HOME=/tmp",
                "--workdir", "/tmp",
                "--mount", bindMount(sourceDirectory),
                "--entrypoint", "gitleaks",
                properties.getGitleaksImage(),
                "dir",
                "--report-format=json",
                "--report-path=-",
                "--redact=100",
                "--exit-code=0",
                "--no-banner",
                "--log-level=error",
                "--gitleaks-ignore-path=/dev/null",
                "--max-archive-depth=0",
                "--max-decode-depth=0",
                "--timeout=" + timeoutSeconds,
                "/src");
    }

    private String bindMount(Path sourceDirectory) {
        String path = sourceDirectory.toAbsolutePath().normalize().toString();
        if (path.contains(",") || path.contains("\n") || path.contains("\r")) {
            throw new ScanExecutionException(
                    "Scanner storage path contains unsupported characters", ScannerFailureCode.EXECUTION_ERROR);
        }
        return "type=bind,src=" + path + ",dst=/src,readonly";
    }

    private Path createTemporaryDirectory() {
        Path root = Path.of(properties.getTemporaryDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Temporary output root is not a safe directory");
            }
            return Files.createTempDirectory(root, "gitleaks-");
        } catch (IOException ex) {
            throw new ScanExecutionException(
                    "Gitleaks temporary storage is unavailable", ScannerFailureCode.EXECUTION_ERROR, ex);
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
                    Path relative = sourceDirectory.relativize(directory);
                    Path target = stagedSource.resolve(relative).normalize();
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
                    Path relative = sourceDirectory.relativize(file);
                    if (relative.getNameCount() == 1 && CONFIG_FILENAME.equals(relative.toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path target = stagedSource.resolve(relative).normalize();
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
            throw new ScanExecutionException(
                    "Uploaded source could not be safely staged", ScannerFailureCode.EXECUTION_ERROR, ex);
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

    private void requireSuccessful(DockerCommandRunner.ProcessResult result, String failureMessage) {
        if (result.timedOut()) {
            throw new ScanExecutionException(
                    "Gitleaks execution exceeded its configured deadline", ScannerFailureCode.TIMEOUT);
        }
        if (result.outputTruncated()) {
            throw new ScanExecutionException(
                    "Gitleaks output exceeded its configured limit", ScannerFailureCode.OUTPUT_LIMIT);
        }
        if (result.exitCode() != 0) {
            throw new ScanExecutionException(failureMessage, ScannerFailureCode.NON_ZERO_EXIT);
        }
    }

    private void removeContainer(String containerName) {
        DockerCommandRunner.ProcessResult cleanup = commandRunner.run(
                List.of(DOCKER_EXECUTABLE, "rm", "--force", containerName),
                Duration.ofMillis(properties.getDockerCommandTimeoutMs()),
                CLEANUP_OUTPUT_LIMIT);
        if (cleanup.timedOut() || cleanup.outputTruncated() || cleanup.exitCode() != 0) {
            throw new ScanExecutionException(
                    "Gitleaks container cleanup failed", ScannerFailureCode.CLEANUP_ERROR);
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException ex) {
            throw new ScanExecutionException(
                    "Gitleaks temporary output cleanup failed", ScannerFailureCode.CLEANUP_ERROR, ex);
        }
    }
}
