package com.codesentinel.service;

import java.nio.file.Path;
import java.util.UUID;

public record ScanWorkItem(UUID scanId, UUID uploadId, Path sourceDirectory) {
}
