package com.codesentinel.service;

import java.time.Duration;
import java.util.List;

public interface DockerCommandRunner {

    ProcessResult run(List<String> command, Duration timeout, int maximumOutputBytes);

    record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean outputTruncated) {
    }
}
