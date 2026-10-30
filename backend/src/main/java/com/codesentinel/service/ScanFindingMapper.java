package com.codesentinel.service;

import com.codesentinel.model.ScanFinding;
import com.codesentinel.model.ScanFindingSeverity;
import com.codesentinel.model.ScanRun;
import com.codesentinel.model.ScannerType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ScanFindingMapper {

    private static final int MAX_RULE_ID_LENGTH = 256;
    private static final int MAX_PATH_LENGTH = 1024;
    private static final int MAX_COORDINATE = 10_000_000;

    public List<ScanFinding> toEntities(ScanRun scanRun, List<NormalizedScanFinding> findings) {
        List<ScanFinding> entities = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        for (NormalizedScanFinding finding : findings) {
            ValidatedFinding validated = validate(finding);
            String fingerprint = fingerprint(validated);
            if (fingerprints.add(fingerprint)) {
                entities.add(ScanFinding.builder()
                        .scanRun(scanRun)
                        .scanner(validated.scanner())
                        .ruleId(validated.ruleId())
                        .severity(validated.severity())
                        .filePath(validated.filePath())
                        .startLine(validated.startLine())
                        .startColumn(validated.startColumn())
                        .endLine(validated.endLine())
                        .endColumn(validated.endColumn())
                        .description(description(validated.scanner()))
                        .fingerprint(fingerprint)
                        .build());
            }
        }
        return entities;
    }

    private ValidatedFinding validate(NormalizedScanFinding finding) {
        if (finding == null || finding.severity() == null) {
            throw new IllegalArgumentException("Scanner returned an invalid finding");
        }
        ScannerType scanner;
        try {
            scanner = ScannerType.valueOf(finding.scanner());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Scanner returned an invalid finding");
        }
        String ruleId = finding.ruleId();
        String filePath = finding.filePath();
        if (!isSafeText(ruleId, MAX_RULE_ID_LENGTH)
                || !isSafePath(filePath)
                || !validCoordinates(finding)) {
            throw new IllegalArgumentException("Scanner returned an invalid finding");
        }
        return new ValidatedFinding(scanner, ruleId, finding.severity(), filePath,
                finding.startLine(), finding.startColumn(), finding.endLine(), finding.endColumn());
    }

    private boolean isSafeText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean isSafePath(String path) {
        if (!isSafeText(path, MAX_PATH_LENGTH) || path.startsWith("/")
                || path.startsWith("\\") || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private boolean validCoordinates(NormalizedScanFinding finding) {
        Integer startLine = finding.startLine();
        Integer endLine = finding.endLine();
        Integer startColumn = finding.startColumn();
        Integer endColumn = finding.endColumn();
        if (startLine == null && endLine == null) {
            return startColumn == null && endColumn == null;
        }
        if (!validCoordinate(startLine) || !validCoordinate(endLine)
                || (startColumn != null && !validCoordinate(startColumn))
                || (endColumn != null && !validCoordinate(endColumn))
                || (startColumn == null) != (endColumn == null)
                || endLine < startLine) {
            return false;
        }
        return !startLine.equals(endLine) || startColumn == null || startColumn <= endColumn;
    }

    private boolean validCoordinate(Integer value) {
        return value != null && value >= 1 && value <= MAX_COORDINATE;
    }

    private String description(ScannerType scanner) {
        return switch (scanner) {
            case SEMGREP -> "Static analysis finding detected.";
            case GITLEAKS -> "Potential exposed secret detected.";
            case TRIVY -> "Dependency vulnerability detected.";
        };
    }

    private String fingerprint(ValidatedFinding finding) {
        String value = String.join("\u0000",
                finding.scanner().name(),
                finding.ruleId(),
                finding.severity().name(),
                finding.filePath(),
                nullable(finding.startLine()),
                nullable(finding.startColumn()),
                nullable(finding.endLine()),
                nullable(finding.endColumn()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String nullable(Integer value) {
        return value == null ? "" : value.toString();
    }

    private record ValidatedFinding(
            ScannerType scanner,
            String ruleId,
            ScanFindingSeverity severity,
            String filePath,
            Integer startLine,
            Integer startColumn,
            Integer endLine,
            Integer endColumn) {
    }
}
