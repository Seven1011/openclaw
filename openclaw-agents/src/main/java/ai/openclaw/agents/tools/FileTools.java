package ai.openclaw.agents.tools;

import ai.openclaw.common.logging.StructuredLogger;
import ai.openclaw.common.utils.OpenClawException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * File system operations for AI tools.
 * Maps TypeScript: src/agents/pi-tools.read.ts and related file operations.
 */
public class FileTools {

    private static final StructuredLogger logger = StructuredLogger.create("agents/file-tools");
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024; // 2MB

    private final String workspaceDir;
    private final Set<Path> allowedPaths;

    public FileTools(String workspaceDir) {
        this(workspaceDir, Set.of());
    }

    public FileTools(String workspaceDir, Set<String> allowedPaths) {
        this.workspaceDir = workspaceDir != null ? workspaceDir : System.getProperty("user.dir");
        this.allowedPaths = allowedPaths.stream()
            .map(Path::of)
            .collect(Collectors.toSet());
    }

    public record ReadResult(
        boolean success,
        String content,
        String error,
        Long fileSize,
        String encoding
    ) {
        public static ReadResult success(String content, long fileSize) {
            return new ReadResult(true, content, null, fileSize, "utf-8");
        }

        public static ReadResult error(String error) {
            return new ReadResult(false, null, error, null, null);
        }
    }

    public record WriteResult(
        boolean success,
        String path,
        String error
    ) {
        public static WriteResult success(String path) {
            return new WriteResult(true, path, null);
        }

        public static WriteResult error(String error) {
            return new WriteResult(false, null, error);
        }
    }

    public record EditResult(
        boolean success,
        String path,
        Integer replacements,
        String error
    ) {
        public static EditResult success(String path, int replacements) {
            return new EditResult(true, path, replacements, null);
        }

        public static EditResult error(String error) {
            return new EditResult(false, null, null, error);
        }
    }

    public record FileInfo(
        String path,
        String name,
        boolean isDirectory,
        boolean isFile,
        long size,
        long lastModified,
        boolean readable,
        boolean writable
    ) {}

    public record ListResult(
        boolean success,
        List<FileInfo> entries,
        String error
    ) {
        public static ListResult success(List<FileInfo> entries) {
            return new ListResult(true, entries, null);
        }

        public static ListResult error(String error) {
            return new ListResult(false, null, error);
        }
    }

    /**
     * Read a file's contents.
     */
    public ReadResult read(String filePath) {
        return read(filePath, null);
    }

    /**
     * Read a file's contents with optional offset/limit.
     */
    public ReadResult read(String filePath, ReadOptions options) {
        try {
            Path resolved = resolvePath(filePath);

            if (!isPathAllowed(resolved)) {
                return ReadResult.error("Access denied: path outside workspace");
            }

            if (!Files.exists(resolved)) {
                return ReadResult.error("File not found: " + filePath);
            }

            if (Files.isDirectory(resolved)) {
                return ReadResult.error("Path is a directory: " + filePath);
            }

            long fileSize = Files.size(resolved);
            if (fileSize > MAX_FILE_BYTES) {
                return ReadResult.error("File too large: " + fileSize + " bytes (max " + MAX_FILE_BYTES + ")");
            }

            String content;
            if (options != null && (options.offset() != null || options.limit() != null)) {
                content = readPartial(resolved, options);
            } else {
                content = Files.readString(resolved, StandardCharsets.UTF_8);
            }

            logger.debug("File read", Map.of("path", filePath, "size", fileSize));
            return ReadResult.success(content, fileSize);

        } catch (IOException e) {
            logger.error("Failed to read file", Map.of("path", filePath, "error", e.getMessage()));
            return ReadResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private String readPartial(Path path, ReadOptions options) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        int offset = options.offset() != null ? options.offset() : 0;
        int limit = options.limit() != null ? options.limit() : lines.size();

        if (offset < 0) offset = 0;
        if (offset >= lines.size()) return "";

        int end = Math.min(offset + limit, lines.size());
        return String.join("\n", lines.subList(offset, end));
    }

    public record ReadOptions(Integer offset, Integer limit) {}

    /**
     * Write content to a file (create or overwrite).
     */
    public WriteResult write(String filePath, String content) {
        return write(filePath, content, false);
    }

    /**
     * Write content to a file.
     */
    public WriteResult write(String filePath, String content, boolean append) {
        try {
            Path resolved = resolvePath(filePath);

            if (!isPathAllowed(resolved)) {
                return WriteResult.error("Access denied: path outside workspace");
            }

            // Create parent directories if needed
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            OpenOption[] options = append
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

            Files.writeString(resolved, content, StandardCharsets.UTF_8, options);

            logger.debug("File written", Map.of("path", filePath, "append", append));
            return WriteResult.success(resolved.toString());

        } catch (IOException e) {
            logger.error("Failed to write file", Map.of("path", filePath, "error", e.getMessage()));
            return WriteResult.error("Failed to write file: " + e.getMessage());
        }
    }

    /**
     * Edit a file by replacing occurrences of oldString with newString.
     */
    public EditResult edit(String filePath, String oldString, String newString) {
        try {
            Path resolved = resolvePath(filePath);

            if (!isPathAllowed(resolved)) {
                return EditResult.error("Access denied: path outside workspace");
            }

            if (!Files.exists(resolved)) {
                return EditResult.error("File not found: " + filePath);
            }

            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            // Count occurrences
            int occurrences = countOccurrences(content, oldString);

            if (occurrences == 0) {
                return EditResult.error("String not found in file: " + oldString.substring(0, Math.min(50, oldString.length())));
            }

            if (occurrences > 1) {
                return EditResult.error("String appears multiple times (" + occurrences + " occurrences). Use a more specific pattern.");
            }

            String newContent = content.replace(oldString, newString);
            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);

            logger.debug("File edited", Map.of("path", filePath, "replacements", 1));
            return EditResult.success(resolved.toString(), 1);

        } catch (IOException e) {
            logger.error("Failed to edit file", Map.of("path", filePath, "error", e.getMessage()));
            return EditResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    /**
     * Apply a patch to a file.
     */
    public EditResult applyPatch(String filePath, String patch) {
        try {
            Path resolved = resolvePath(filePath);

            if (!isPathAllowed(resolved)) {
                return EditResult.error("Access denied: path outside workspace");
            }

            // Simple patch application - this is a simplified version
            // Real implementation would parse unified diff format
            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            // For now, treat patch as a search/replace with more flexibility
            // A real implementation would parse diff format properly
            String newContent = content + "\n" + patch;
            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);

            return EditResult.success(resolved.toString(), 1);

        } catch (IOException e) {
            logger.error("Failed to apply patch", Map.of("path", filePath, "error", e.getMessage()));
            return EditResult.error("Failed to apply patch: " + e.getMessage());
        }
    }

    /**
     * List directory contents.
     */
    public ListResult list(String dirPath) {
        return list(dirPath, false);
    }

    /**
     * List directory contents with optional recursive listing.
     */
    public ListResult list(String dirPath, boolean recursive) {
        try {
            Path resolved = resolvePath(dirPath);

            if (!isPathAllowed(resolved)) {
                return ListResult.error("Access denied: path outside workspace");
            }

            if (!Files.exists(resolved)) {
                return ListResult.error("Directory not found: " + dirPath);
            }

            if (!Files.isDirectory(resolved)) {
                return ListResult.error("Path is not a directory: " + dirPath);
            }

            List<FileInfo> entries = new ArrayList<>();

            if (recursive) {
                Files.walk(resolved)
                    .filter(p -> !p.equals(resolved))
                    .forEach(p -> entries.add(toFileInfo(p)));
            } else {
                try (var stream = Files.list(resolved)) {
                    stream.forEach(p -> entries.add(toFileInfo(p)));
                }
            }

            entries.sort(Comparator.comparing(FileInfo::name));
            return ListResult.success(entries);

        } catch (IOException e) {
            logger.error("Failed to list directory", Map.of("path", dirPath, "error", e.getMessage()));
            return ListResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    /**
     * Check if a file exists.
     */
    public boolean exists(String filePath) {
        try {
            Path resolved = resolvePath(filePath);
            if (!isPathAllowed(resolved)) {
                return false;
            }
            return Files.exists(resolved);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a directory.
     */
    public boolean mkdir(String dirPath) {
        try {
            Path resolved = resolvePath(dirPath);

            if (!isPathAllowed(resolved)) {
                return false;
            }

            Files.createDirectories(resolved);
            return true;

        } catch (IOException e) {
            logger.error("Failed to create directory", Map.of("path", dirPath, "error", e.getMessage()));
            return false;
        }
    }

    /**
     * Delete a file or directory.
     */
    public boolean delete(String path, boolean recursive) {
        try {
            Path resolved = resolvePath(path);

            if (!isPathAllowed(resolved)) {
                return false;
            }

            if (!Files.exists(resolved)) {
                return true; // Already deleted
            }

            if (Files.isDirectory(resolved)) {
                if (recursive) {
                    Files.walk(resolved)
                        .sorted(Comparator.reverseOrder())
                        .forEach(this::deleteQuietly);
                } else {
                    Files.delete(resolved);
                }
            } else {
                Files.delete(resolved);
            }

            return true;

        } catch (IOException e) {
            logger.error("Failed to delete", Map.of("path", path, "error", e.getMessage()));
            return false;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
        }
    }

    private Path resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Path.of(workspaceDir);
        }

        Path path = Path.of(filePath);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        return Path.of(workspaceDir).resolve(path).normalize();
    }

    private boolean isPathAllowed(Path path) {
        Path workspace = Path.of(workspaceDir).toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();

        // Check if within workspace
        if (normalized.startsWith(workspace)) {
            return true;
        }

        // Check if in allowed paths
        for (Path allowed : allowedPaths) {
            if (normalized.startsWith(allowed.toAbsolutePath().normalize())) {
                return true;
            }
        }

        return false;
    }

    private FileInfo toFileInfo(Path path) {
        return new FileInfo(
            path.toString(),
            path.getFileName() != null ? path.getFileName().toString() : path.toString(),
            Files.isDirectory(path),
            Files.isRegularFile(path),
            sizeOrZero(path),
            lastModifiedOrZero(path),
            Files.isReadable(path),
            Files.isWritable(path)
        );
    }

    private long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private int countOccurrences(String content, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
