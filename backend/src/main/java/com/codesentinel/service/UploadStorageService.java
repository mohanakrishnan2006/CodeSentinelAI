package com.codesentinel.service;

import com.codesentinel.config.UploadProperties;
import com.codesentinel.exception.InvalidZipException;
import com.codesentinel.exception.MissingUploadFileException;
import com.codesentinel.exception.UnsafeArchiveEntryException;
import com.codesentinel.exception.UploadLimitExceededException;
import com.codesentinel.exception.UploadStorageException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.text.Normalizer;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadStorageService {

    private static final Logger log = LoggerFactory.getLogger(UploadStorageService.class);
    private static final int BUFFER_SIZE = 8192;
    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "dll", "so", "dylib", "com", "scr", "msi", "jar", "class", "bat", "cmd", "ps1"
    );

    private final UploadProperties properties;

    public StoredUpload store(MultipartFile multipartFile, UUID uploadId) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new MissingUploadFileException();
        }

        Path root = properties.getStorageDirectory().toAbsolutePath().normalize();
        Path workDirectory = root.resolve(".work-" + uploadId).normalize();
        Path uploadDirectory = root.resolve(uploadId.toString()).normalize();

        if (!workDirectory.startsWith(root) || !uploadDirectory.startsWith(root)) {
            throw new UploadStorageException("Upload storage is unavailable", null);
        }

        try {
            createStorageRoot(root);
            workDirectory = Files.createTempDirectory(root, ".work-" + uploadId + "-");
            Path archivePath = workDirectory.resolve("archive.zip");
            Path extractedDirectory = workDirectory.resolve("content");
            createPrivateDirectory(extractedDirectory);

            copyArchive(multipartFile, archivePath);
            ExtractedContent extracted = extractArchive(archivePath, extractedDirectory);
            if (extracted.fileCount() == 0) {
                throw new InvalidZipException("The ZIP archive contains no files");
            }

            restrictDirectoryAccess(extractedDirectory);
            try {
                Files.move(extractedDirectory, uploadDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(extractedDirectory, uploadDirectory);
            }

            deleteTree(workDirectory);
            return new StoredUpload(extracted.fileCount(), extracted.totalSizeBytes(), uploadId.toString());
        } catch (InvalidZipException | UnsafeArchiveEntryException | UploadLimitExceededException
                 | MissingUploadFileException ex) {
            cleanupAfterFailure(workDirectory, uploadDirectory, ex);
            throw ex;
        } catch (IOException ex) {
            UploadStorageException storageException =
                    new UploadStorageException("Unable to store the uploaded archive", ex);
            cleanupAfterFailure(workDirectory, uploadDirectory, storageException);
            throw storageException;
        } catch (RuntimeException ex) {
            cleanupAfterFailure(workDirectory, uploadDirectory, ex);
            throw ex;
        }
    }

    public void deleteUploadDirectory(UUID uploadId) {
        Path root = properties.getStorageDirectory().toAbsolutePath().normalize();
        Path uploadDirectory = root.resolve(uploadId.toString()).normalize();
        if (!uploadDirectory.startsWith(root) || uploadDirectory.equals(root)) {
            throw new UploadStorageException("Upload storage is unavailable", null);
        }
        try {
            if (Files.exists(root) && (Files.isSymbolicLink(root) || !Files.isDirectory(root))) {
                throw new IOException("Configured storage directory is not a safe directory");
            }
            deleteTree(uploadDirectory);
        } catch (IOException ex) {
            throw new UploadStorageException("Unable to clean up upload storage", ex);
        }
    }

    public Path resolveUploadDirectory(UUID uploadId, String storageReference) {
        if (uploadId == null || storageReference == null
                || !uploadId.toString().equals(storageReference)) {
            throw new UploadStorageException("Upload storage is unavailable", null);
        }

        Path configuredRoot = properties.getStorageDirectory().toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(configuredRoot) || !Files.isDirectory(configuredRoot)) {
                throw new IOException("Configured storage directory is not a safe directory");
            }
            Path storageRoot = configuredRoot.toRealPath();
            Path uploadDirectory = storageRoot.resolve(storageReference).normalize();
            if (!uploadDirectory.startsWith(storageRoot) || uploadDirectory.equals(storageRoot)
                    || Files.isSymbolicLink(uploadDirectory)
                    || !Files.isDirectory(uploadDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Upload directory is not a safe directory");
            }
            Path realUploadDirectory = uploadDirectory.toRealPath();
            if (!realUploadDirectory.startsWith(storageRoot)) {
                throw new IOException("Upload directory is outside configured storage");
            }
            return realUploadDirectory;
        } catch (IOException ex) {
            throw new UploadStorageException("Unable to resolve uploaded source storage", ex);
        }
    }

    private void createStorageRoot(Path root) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Configured storage directory is not a safe directory");
        }
    }

    private void createPrivateDirectory(Path path) throws IOException {
        Files.createDirectory(path);
        restrictDirectoryAccess(path);
    }

    private void restrictDirectoryAccess(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // The configured filesystem does not expose POSIX permissions (for example Windows).
        }
    }

    private void copyArchive(MultipartFile multipartFile, Path archivePath) throws IOException {
        long copied = 0;
        try (InputStream input = multipartFile.getInputStream();
             OutputStream output = Files.newOutputStream(
                     archivePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > properties.getMaxArchiveSizeBytes()) {
                    throw new UploadLimitExceededException("The ZIP archive exceeds the upload size limit");
                }
                output.write(buffer, 0, read);
            }
        }
        if (copied == 0) {
            throw new MissingUploadFileException();
        }
    }

    private ExtractedContent extractArchive(Path archivePath, Path destination) {
        Set<String> seenPaths = new HashSet<>();
        Set<String> files = new HashSet<>();
        long totalSize = 0;
        int fileCount = 0;
        int entryCount = 0;

        ZipFile openedArchive;
        try {
            openedArchive = ZipFile.builder().setPath(archivePath).get();
        } catch (IOException ex) {
            throw new InvalidZipException("The uploaded file is not a valid supported ZIP archive");
        }

        try (ZipFile archive = openedArchive) {
            Enumeration<ZipArchiveEntry> entries = archive.getEntries();
            if (!entries.hasMoreElements()) {
                throw new InvalidZipException("The ZIP archive contains no entries");
            }
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > properties.getMaxFiles() + 1000) {
                    throw new UploadLimitExceededException("The ZIP archive contains too many entries");
                }
                if (!archive.canReadEntryData(entry)) {
                    throw new InvalidZipException("The ZIP archive uses an unsupported or encrypted entry");
                }
                int unixMode = entry.getUnixMode();
                int unixFileType = unixMode & 0170000;
                if (entry.isUnixSymlink()
                        || (!entry.isDirectory() && (unixMode & 0111) != 0)
                        || (unixFileType != 0
                        && unixFileType != (entry.isDirectory() ? 0040000 : 0100000))) {
                    throw new UnsafeArchiveEntryException();
                }

                String entryName = validateEntryName(entry);
                Path relativePath;
                try {
                    relativePath = Path.of(entryName);
                } catch (InvalidPathException ex) {
                    throw new UnsafeArchiveEntryException();
                }
                Path target = destination.resolve(relativePath).normalize();
                if (!target.startsWith(destination)) {
                    throw new UnsafeArchiveEntryException();
                }

                String key = entryName.toLowerCase(Locale.ROOT);
                if (!seenPaths.add(key)) {
                    throw new UnsafeArchiveEntryException();
                }
                validatePathRelationships(key, entry.isDirectory(), files, seenPaths);

                if (entry.isDirectory()) {
                    try {
                        Files.createDirectories(target);
                    } catch (IOException ex) {
                        throw new UploadStorageException("Unable to store an archive entry", ex);
                    }
                    continue;
                }

                rejectExecutableExtension(entryName);
                fileCount++;
                if (fileCount > properties.getMaxFiles()) {
                    throw new UploadLimitExceededException("The ZIP archive contains too many files");
                }
                if (entry.getSize() > properties.getMaxFileSizeBytes()) {
                    throw new UploadLimitExceededException("An archive file exceeds the per-file size limit");
                }

                Path parent = target.getParent();
                if (parent != null) {
                    try {
                        Files.createDirectories(parent);
                    } catch (IOException ex) {
                        throw new UploadStorageException("Unable to store an archive entry", ex);
                    }
                }
                long extractedFileSize = copyEntry(
                        archive, entry, target, properties.getMaxTotalSizeBytes() - totalSize);
                totalSize += extractedFileSize;
                files.add(key);
            }
        } catch (InvalidZipException | UnsafeArchiveEntryException | UploadLimitExceededException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new InvalidZipException("The uploaded file is not a valid supported ZIP archive");
        }
        return new ExtractedContent(fileCount, totalSize);
    }

    private long copyEntry(
            ZipFile archive,
            ZipArchiveEntry entry,
            Path target,
            long remainingTotalBytes) {
        long copied = 0;
        InputStream archiveInput;
        try {
            archiveInput = archive.getInputStream(entry);
        } catch (IOException ex) {
            throw new InvalidZipException("The uploaded file is not a valid supported ZIP archive");
        }

        try (InputStream input = archiveInput) {
            OutputStream fileOutput;
            try {
                fileOutput = Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException ex) {
                throw new UploadStorageException("Unable to store an archive entry", ex);
            }
            try (OutputStream output = fileOutput) {
                copied = copyEntryBytes(input, output, copied, remainingTotalBytes);
            } catch (IOException ex) {
                throw new UploadStorageException("Unable to store an archive entry", ex);
            }
        } catch (InvalidZipException | UploadLimitExceededException | UploadStorageException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new InvalidZipException("The uploaded file is not a valid supported ZIP archive");
        }
        return copied;
    }

    private long copyEntryBytes(
            InputStream input,
            OutputStream output,
            long copied,
            long remainingTotalBytes) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while (true) {
            try {
                read = input.read(buffer);
            } catch (IOException ex) {
                throw new InvalidZipException("The uploaded file is not a valid supported ZIP archive");
            }
            if (read == -1) {
                break;
            }
            copied += read;
            if (copied > properties.getMaxFileSizeBytes()) {
                throw new UploadLimitExceededException("An archive file exceeds the per-file size limit");
            }
            if (copied > remainingTotalBytes) {
                throw new UploadLimitExceededException("The extracted archive exceeds the total size limit");
            }
            try {
                output.write(buffer, 0, read);
            } catch (IOException ex) {
                throw new UploadStorageException("Unable to store an archive entry", ex);
            }
        }
        return copied;
    }

    private String validateEntryName(ZipArchiveEntry entry) {
        String name = Normalizer.normalize(entry.getName(), Normalizer.Form.NFC);
        if (name.isBlank() || name.length() > 180 || name.startsWith("/")
                || name.startsWith("\\") || name.contains("\\") || name.contains(":")) {
            throw new UnsafeArchiveEntryException();
        }

        if (entry.isDirectory() && name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        } else if (name.endsWith("/")) {
            throw new UnsafeArchiveEntryException();
        }

        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.length() > 180 || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new UnsafeArchiveEntryException();
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (Character.isISOControl(character)
                        || character == '*' || character == '?' || character == '"'
                        || character == '<' || character == '>' || character == '|') {
                    throw new UnsafeArchiveEntryException();
                }
            }
            String deviceName = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (deviceName.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw new UnsafeArchiveEntryException();
            }
        }
        return name;
    }

    private void validatePathRelationships(
            String key,
            boolean directory,
            Set<String> files,
            Set<String> seenPaths) {
        int separator = key.indexOf('/');
        while (separator >= 0) {
            if (files.contains(key.substring(0, separator))) {
                throw new UnsafeArchiveEntryException();
            }
            separator = key.indexOf('/', separator + 1);
        }
        if (!directory && seenPaths.stream().anyMatch(path -> path.startsWith(key + "/"))) {
            throw new UnsafeArchiveEntryException();
        }
    }

    private void rejectExecutableExtension(String entryName) {
        int separator = entryName.lastIndexOf('/');
        String filename = entryName.substring(separator + 1);
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator >= 0
                && EXECUTABLE_EXTENSIONS.contains(filename.substring(extensionSeparator + 1)
                .toLowerCase(Locale.ROOT))) {
            throw new UnsafeArchiveEntryException();
        }
    }

    private void cleanupAfterFailure(Path workDirectory, Path uploadDirectory, RuntimeException cause) {
        IOException cleanupFailure = null;
        for (Path path : new Path[]{workDirectory, uploadDirectory}) {
            try {
                deleteTree(path);
            } catch (IOException ex) {
                if (cleanupFailure == null) {
                    cleanupFailure = ex;
                } else {
                    cleanupFailure.addSuppressed(ex);
                }
            }
        }
        if (cleanupFailure != null) {
            cause.addSuppressed(cleanupFailure);
            log.error("Failed to clean partial upload {}", uploadDirectory.getFileName(), cleanupFailure);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record StoredUpload(int fileCount, long totalSizeBytes, String storageReference) {
    }

    private record ExtractedContent(int fileCount, long totalSizeBytes) {
    }
}
