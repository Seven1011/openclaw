package ai.openclaw.agents.skills;

import ai.openclaw.common.logging.StructuredLogger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Basic skill loader for scanning and loading modular skill packages.
 * Maps TypeScript: src/agents/skills/refresh.ts and workspace.ts
 */
public class SkillLoader {

    private static final StructuredLogger logger = StructuredLogger.create("agents/skills");

    private static final String SKILL_FILE_EXTENSION = ".md";
    private static final String SKILL_FRONTMATTER_DELIMITER = "---";
    private static final long MAX_SKILL_FILE_BYTES = 256_000;

    public record SkillDefinition(
        String name,
        String description,
        String content,
        SkillMetadata metadata,
        Path filePath,
        long loadedAt
    ) {}

    public record SkillMetadata(
        String skillKey,
        String primaryEnv,
        String emoji,
        String homepage,
        List<String> os,
        List<String> requiresBins,
        List<String> requiresAnyBins,
        List<String> requiresEnv,
        List<String> requiresConfig,
        boolean alwaysLoad
    ) {
        public SkillMetadata {
            os = os != null ? List.copyOf(os) : List.of();
            requiresBins = requiresBins != null ? List.copyOf(requiresBins) : List.of();
            requiresAnyBins = requiresAnyBins != null ? List.copyOf(requiresAnyBins) : List.of();
            requiresEnv = requiresEnv != null ? List.copyOf(requiresEnv) : List.of();
            requiresConfig = requiresConfig != null ? List.copyOf(requiresConfig) : List.of();
        }
    }

    public record SkillLoadResult(
        boolean success,
        List<SkillDefinition> skills,
        List<SkillLoadError> errors
    ) {
        public static SkillLoadResult success(List<SkillDefinition> skills) {
            return new SkillLoadResult(true, skills, List.of());
        }

        public static SkillLoadResult partial(List<SkillDefinition> skills, List<SkillLoadError> errors) {
            return new SkillLoadResult(!errors.isEmpty(), skills, errors);
        }

        public static SkillLoadResult error(List<SkillLoadError> errors) {
            return new SkillLoadResult(false, List.of(), errors);
        }
    }

    public record SkillLoadError(
        Path path,
        String reason,
        String detail
    ) {}

    private final Set<Path> loadedPaths;
    private final Map<String, SkillDefinition> skillCache;

    public SkillLoader() {
        this.loadedPaths = new HashSet<>();
        this.skillCache = new HashMap<>();
    }

    /**
     * Load skills from a directory.
     */
    public SkillLoadResult loadFromDirectory(String directory) {
        return loadFromDirectory(directory, null);
    }

    /**
     * Load skills from a directory with optional filter.
     */
    public SkillLoadResult loadFromDirectory(String directory, SkillFilter filter) {
        Path dirPath = Path.of(directory);

        if (!Files.exists(dirPath)) {
            return SkillLoadResult.error(List.of(
                new SkillLoadError(dirPath, "not_found", "Directory does not exist")
            ));
        }

        if (!Files.isDirectory(dirPath)) {
            return SkillLoadResult.error(List.of(
                new SkillLoadError(dirPath, "not_directory", "Path is not a directory")
            ));
        }

        List<SkillDefinition> skills = new ArrayList<>();
        List<SkillLoadError> errors = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(dirPath)) {
            List<Path> skillFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().toLowerCase().endsWith(SKILL_FILE_EXTENSION))
                .filter(p -> !loadedPaths.contains(p.toAbsolutePath()))
                .collect(Collectors.toList());

            for (Path skillFile : skillFiles) {
                try {
                    SkillDefinition skill = loadSkillFile(skillFile);
                    if (skill != null) {
                        if (filter == null || filter.matches(skill)) {
                            skills.add(skill);
                            loadedPaths.add(skillFile.toAbsolutePath());
                            skillCache.put(skill.name(), skill);
                        }
                    }
                } catch (IOException e) {
                    errors.add(new SkillLoadError(skillFile, "io_error", e.getMessage()));
                } catch (Exception e) {
                    errors.add(new SkillLoadError(skillFile, "parse_error", e.getMessage()));
                }
            }

            logger.info("Loaded skills from directory",
                Map.of("directory", directory, "count", skills.size(), "errors", errors.size()));

            if (errors.isEmpty()) {
                return SkillLoadResult.success(skills);
            } else {
                return SkillLoadResult.partial(skills, errors);
            }

        } catch (IOException e) {
            logger.error("Failed to walk directory", Map.of("directory", directory, "error", e.getMessage()));
            return SkillLoadResult.error(List.of(new SkillLoadError(dirPath, "walk_error", e.getMessage())));
        }
    }

    /**
     * Load a single skill from a file.
     */
    public SkillDefinition loadSkillFile(Path skillFile) throws IOException {
        // Check file size
        long size = Files.size(skillFile);
        if (size > MAX_SKILL_FILE_BYTES) {
            throw new IOException("Skill file too large: " + size + " bytes (max " + MAX_SKILL_FILE_BYTES + ")");
        }

        String content = Files.readString(skillFile);

        // Parse frontmatter
        FrontmatterParseResult frontmatter = parseFrontmatter(content);

        // Extract skill name from filename
        String fileName = skillFile.getFileName().toString();
        String skillName = fileName.substring(0, fileName.length() - SKILL_FILE_EXTENSION.length());

        // Parse metadata from frontmatter
        SkillMetadata metadata = parseMetadata(frontmatter.frontmatter());

        // Use skill key from frontmatter or filename
        String finalSkillName = metadata.skillKey() != null ? metadata.skillKey() : skillName;

        return new SkillDefinition(
            finalSkillName,
            extractDescription(frontmatter.content()),
            frontmatter.content(),
            metadata,
            skillFile,
            System.currentTimeMillis()
        );
    }

    /**
     * Get a loaded skill by name.
     */
    public Optional<SkillDefinition> getSkill(String name) {
        return Optional.ofNullable(skillCache.get(name));
    }

    /**
     * List all loaded skills.
     */
    public List<SkillDefinition> listLoadedSkills() {
        return List.copyOf(skillCache.values());
    }

    /**
     * List loaded skills filtered by metadata criteria.
     */
    public List<SkillDefinition> listSkillsByOs(String os) {
        return skillCache.values().stream()
            .filter(s -> s.metadata().os().isEmpty() || s.metadata().os().contains(os))
            .toList();
    }

    /**
     * Clear the skill cache.
     */
    public void clearCache() {
        skillCache.clear();
        loadedPaths.clear();
    }

    /**
     * Filter skills that have all required binaries available.
     */
    public List<SkillDefinition> filterAvailableSkills(List<SkillDefinition> skills) {
        return skills.stream()
            .filter(this::hasRequiredBins)
            .toList();
    }

    private boolean hasRequiredBins(SkillDefinition skill) {
        // Check required binaries
        for (String bin : skill.metadata().requiresBins()) {
            if (!isBinaryAvailable(bin)) {
                return false;
            }
        }

        // Check any-of-required binaries
        if (!skill.metadata().requiresAnyBins().isEmpty()) {
            boolean hasAny = skill.metadata().requiresAnyBins().stream()
                .anyMatch(this::isBinaryAvailable);
            if (!hasAny) {
                return false;
            }
        }

        // Check required env vars
        for (String env : skill.metadata().requiresEnv()) {
            if (System.getenv(env) == null) {
                return false;
            }
        }

        return true;
    }

    private boolean isBinaryAvailable(String bin) {
        // Simple check - in real implementation, this would check PATH
        return true; // Placeholder
    }

    private FrontmatterParseResult parseFrontmatter(String content) {
        if (!content.startsWith(SKILL_FRONTMATTER_DELIMITER)) {
            return new FrontmatterParseResult(null, content);
        }

        int endIndex = content.indexOf("\n" + SKILL_FRONTMATTER_DELIMITER, 3);
        if (endIndex == -1) {
            return new FrontmatterParseResult(null, content);
        }

        String frontmatter = content.substring(3, endIndex).trim();
        String body = content.substring(endIndex + 4).trim();

        return new FrontmatterParseResult(frontmatter, body);
    }

    private record FrontmatterParseResult(String frontmatter, String content) {}

    private SkillMetadata parseMetadata(String frontmatter) {
        if (frontmatter == null || frontmatter.isBlank()) {
            return new SkillMetadata(null, null, null, null, null, null, null, null, null, false);
        }

        Map<String, String> fields = new HashMap<>();

        // Simple YAML-like parsing for frontmatter
        String[] lines = frontmatter.split("\n");
        for (String line : lines) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                fields.put(key, value);
            }
        }

        return new SkillMetadata(
            fields.get("skillKey"),
            fields.get("primaryEnv"),
            fields.get("emoji"),
            fields.get("homepage"),
            parseList(fields.get("os")),
            parseList(fields.get("requires.bins")),
            parseList(fields.get("requires.anyBins")),
            parseList(fields.get("requires.env")),
            parseList(fields.get("requires.config")),
            "true".equalsIgnoreCase(fields.get("always"))
        );
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private String extractDescription(String content) {
        // Extract first paragraph or first line as description
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            }
        }
        return "";
    }

    /**
     * Filter interface for skill loading.
     */
    @FunctionalInterface
    public interface SkillFilter {
        boolean matches(SkillDefinition skill);
    }
}
