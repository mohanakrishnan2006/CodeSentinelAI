package com.codesentinel.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ProcessDockerCommandRunner implements DockerCommandRunner {

    private static final long PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 5;

    @Override
    public ProcessResult run(List<String> command, Duration timeout, int maximumOutputBytes) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new ScanExecutionException("Docker command could not be started", ex);
        }

        BoundedCapture stdout = new BoundedCapture(maximumOutputBytes);
        BoundedCapture stderr = new BoundedCapture(maximumOutputBytes);
        Thread stdoutReader = Thread.ofVirtual().start(() -> stdout.read(process.getInputStream()));
        Thread stderrReader = Thread.ofVirtual().start(() -> stderr.read(process.getErrorStream()));
        boolean timedOut = false;
        int exitCode;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                if (!process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new ScanExecutionException("Docker command could not be stopped after timeout");
                }
            }
            exitCode = timedOut ? -1 : process.exitValue();
            stdoutReader.join(PROCESS_SHUTDOWN_TIMEOUT_SECONDS * 1000);
            stderrReader.join(PROCESS_SHUTDOWN_TIMEOUT_SECONDS * 1000);
            if (stdoutReader.isAlive() || stderrReader.isAlive()) {
                throw new ScanExecutionException("Docker command output could not be closed");
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ScanExecutionException("Docker command was interrupted", ex);
        }

        return new ProcessResult(
                exitCode,
                stdout.text(),
                stderr.text(),
                timedOut,
                stdout.truncated() || stderr.truncated());
    }

    private static final class BoundedCapture {

        private final int maximumBytes;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();

        private BoundedCapture(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private void read(InputStream input) {
            byte[] buffer = new byte[8192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int remaining = maximumBytes - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(remaining, count));
                    }
                    if (count > remaining) {
                        truncated.set(true);
                    }
                }
            } catch (IOException ex) {
                truncated.set(true);
            }
        }

        private String text() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated.get();
        }
    }
}
