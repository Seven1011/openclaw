package ai.openclaw.agents.runtime;

import ai.openclaw.common.config.OpenClawConfig;
import ai.openclaw.common.infra.HomeDirUtils;
import ai.openclaw.common.config.ConfigPaths;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle and context of an agent run.
 * Maps TypeScript: src/agents/agent-scope.ts
 */
public class AgentScope {

    public static final String DEFAULT_AGENT_ID = "main";
    private static final Pattern VALID_ID_RE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Pattern INVALID_CHARS_RE = Pattern.compile("[^a-z0-9_-]+");
    private static final Pattern LEADING_DASH_RE = Pattern.compile("^-+");
    private static final Pattern TRAILING_DASH_RE = Pattern.compile("-+$");

    private static boolean defaultAgentWarned = false;

    public record AgentEntry(
        String id,
        String name,
        String workspace,
        String agentDir,
        Object model,
        List<String> skills,
        Object memorySearch,
        Object humanDelay,
        Object heartbeat,
        Object identity,
        Object groupChat,
        Object subagents,
        Object sandbox,
        Object tools,
        boolean isDefault
    ) {}

    public record ResolvedAgentConfig(
        String name,
        String workspace,
        String agentDir,
        Object model,
        List<String> skills,
        Object memorySearch,
        Object humanDelay,
        Object heartbeat,
        Object identity,
        Object groupChat,
        Object subagents,
        Object sandbox,
        Object tools
    ) {}

    public record SessionAgentIds(
        String defaultAgentId,
        String sessionAgentId
    ) {}

    /**
     * Normalize an agent ID to lowercase, trimming whitespace.
     */
    public static String normalizeAgentId(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT_AGENT_ID;
        }
        String normalized = id.trim().toLowerCase();
        normalized = INVALID_CHARS_RE.matcher(normalized).replaceAll("-");
        normalized = LEADING_DASH_RE.matcher(normalized).replaceAll("");
        normalized = TRAILING_DASH_RE.matcher(normalized).replaceAll("");
        if (normalized.isEmpty()) {
            return DEFAULT_AGENT_ID;
        }
        return normalized;
    }

    /**
     * List all agent entries from config.
     */
    public static List<OpenClawConfig.AgentConfig> listAgentEntries(OpenClawConfig cfg) {
        if (cfg.agents() == null || cfg.agents().list() == null) {
            return List.of();
        }
        return cfg.agents().list().stream()
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * List all agent IDs from config.
     */
    public static List<String> listAgentIds(OpenClawConfig cfg) {
        List<OpenClawConfig.AgentConfig> agents = listAgentEntries(cfg);
        if (agents.isEmpty()) {
            return List.of(DEFAULT_AGENT_ID);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>();

        for (OpenClawConfig.AgentConfig entry : agents) {
            String id = normalizeAgentId(entry.id());
            if (seen.contains(id)) {
                continue;
            }
            seen.add(id);
            ids.add(id);
        }

        return ids.isEmpty() ? List.of(DEFAULT_AGENT_ID) : ids;
    }

    /**
     * Resolve the default agent ID from config.
     */
    public static String resolveDefaultAgentId(OpenClawConfig cfg) {
        List<OpenClawConfig.AgentConfig> agents = listAgentEntries(cfg);
        if (agents.isEmpty()) {
            return DEFAULT_AGENT_ID;
        }

        List<OpenClawConfig.AgentConfig> defaults = agents.stream()
            .filter(a -> a != null && a.isDefault())
            .toList();

        if (defaults.size() > 1 && !defaultAgentWarned) {
            defaultAgentWarned = true;
            System.err.println("Multiple agents marked default=true; using the first entry as default.");
        }

        OpenClawConfig.AgentConfig chosen = defaults.isEmpty() ? agents.get(0) : defaults.get(0);
        String chosenId = chosen != null && chosen.id() != null ? chosen.id().trim() : "";
        return normalizeAgentId(chosenId);
    }

    /**
     * Resolve session agent IDs based on session key and explicit agent ID.
     */
    public static SessionAgentIds resolveSessionAgentIds(String sessionKey, OpenClawConfig config, String agentId) {
        String defaultAgentId = resolveDefaultAgentId(config != null ? config : new OpenClawConfig(null, null, null, null, null, null));

        String explicitAgentIdRaw = agentId != null ? agentId.trim().toLowerCase() : "";
        String explicitAgentId = !explicitAgentIdRaw.isEmpty() ? normalizeAgentId(explicitAgentIdRaw) : null;

        String normalizedSessionKey = sessionKey != null ? sessionKey.trim().toLowerCase() : null;
        ParsedAgentSessionKey parsed = normalizedSessionKey != null ? parseAgentSessionKey(normalizedSessionKey) : null;

        String sessionAgentId = explicitAgentId != null
            ? explicitAgentId
            : (parsed != null && parsed.agentId() != null ? normalizeAgentId(parsed.agentId()) : defaultAgentId);

        return new SessionAgentIds(defaultAgentId, sessionAgentId);
    }

    /**
     * Resolve the session agent ID from session key.
     */
    public static String resolveSessionAgentId(String sessionKey, OpenClawConfig config) {
        return resolveSessionAgentIds(sessionKey, config, null).sessionAgentId();
    }

    /**
     * Parse an agent session key into its components.
     */
    public static ParsedAgentSessionKey parseAgentSessionKey(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }

        String normalized = sessionKey.trim().toLowerCase();
        if (!normalized.startsWith("agent:")) {
            return null;
        }

        String[] parts = normalized.split(":", 3);
        if (parts.length < 2) {
            return null;
        }

        String agentId = parts[1];
        String rest = parts.length > 2 ? parts[2] : "";

        return new ParsedAgentSessionKey(agentId, rest, normalized);
    }

    public record ParsedAgentSessionKey(String agentId, String rest, String fullKey) {}

    /**
     * Resolve agent ID from session key.
     */
    public static String resolveAgentIdFromSessionKey(String sessionKey) {
        ParsedAgentSessionKey parsed = parseAgentSessionKey(sessionKey);
        return normalizeAgentId(parsed != null ? parsed.agentId() : DEFAULT_AGENT_ID);
    }

    /**
     * Resolve agent configuration for a given agent ID.
     */
    public static ResolvedAgentConfig resolveAgentConfig(OpenClawConfig cfg, String agentId) {
        String id = normalizeAgentId(agentId);
        OpenClawConfig.AgentConfig entry = listAgentEntries(cfg).stream()
            .filter(e -> normalizeAgentId(e.id()).equals(id))
            .findFirst()
            .orElse(null);

        if (entry == null) {
            return null;
        }

        return new ResolvedAgentConfig(
            entry.name(),
            entry.workspace(),
            entry.agentDir(),
            entry.model(),
            entry.skills(),
            entry.memorySearch(),
            entry.humanDelay(),
            entry.heartbeat(),
            entry.identity(),
            entry.groupChat(),
            entry.subagents(),
            entry.sandbox(),
            entry.tools()
        );
    }

    /**
     * Resolve agent skills filter.
     */
    public static List<String> resolveAgentSkillsFilter(OpenClawConfig cfg, String agentId) {
        return normalizeSkillFilter(resolveAgentConfig(cfg, agentId).skills());
    }

    /**
     * Normalize skill filter entries.
     */
    public static List<String> normalizeSkillFilter(List<String> skills) {
        if (skills == null) {
            return null;
        }
        return skills.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * Resolve the primary model for an agent.
     */
    public static String resolveAgentExplicitModelPrimary(OpenClawConfig cfg, String agentId) {
        Object raw = resolveAgentConfig(cfg, agentId).model();
        return resolveModelPrimary(raw);
    }

    /**
     * Resolve effective model primary (agent or default).
     */
    public static String resolveAgentEffectiveModelPrimary(OpenClawConfig cfg, String agentId) {
        String explicit = resolveAgentExplicitModelPrimary(cfg, agentId);
        if (explicit != null) {
            return explicit;
        }
        return cfg.agents() != null && cfg.agents().defaults() != null
            ? resolveModelPrimary(cfg.agents().defaults().model())
            : null;
    }

    private static String resolveModelPrimary(Object raw) {
        if (raw instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object primary = map.get("primary");
        if (!(primary instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolve fallback agent ID from parameters.
     */
    public static String resolveFallbackAgentId(String agentId, String sessionKey) {
        String explicit = agentId != null ? agentId.trim() : "";
        if (!explicit.isEmpty()) {
            return normalizeAgentId(explicit);
        }
        return resolveAgentIdFromSessionKey(sessionKey);
    }

    /**
     * Resolve agent workspace directory.
     */
    public static String resolveAgentWorkspaceDir(OpenClawConfig cfg, String agentId) {
        String id = normalizeAgentId(agentId);
        ResolvedAgentConfig config = resolveAgentConfig(cfg, id);
        String configured = config != null && config.workspace() != null ? config.workspace().trim() : null;

        if (configured != null && !configured.isEmpty()) {
            return stripNullBytes(resolveUserPath(configured));
        }

        String defaultAgentId = resolveDefaultAgentId(cfg);
        if (id.equals(defaultAgentId)) {
            String fallback = cfg.agents() != null && cfg.agents().defaults() != null
                ? cfg.agents().defaults().workspace()
                : null;
            if (fallback != null && !fallback.trim().isEmpty()) {
                return stripNullBytes(resolveUserPath(fallback.trim()));
            }
            return stripNullBytes(resolveDefaultAgentWorkspaceDir());
        }

        String stateDir = ConfigPaths.resolveStateDir();
        return Paths.get(stateDir, "workspace-" + id).toString();
    }

    /**
     * Resolve agent directory.
     */
    public static String resolveAgentDir(OpenClawConfig cfg, String agentId) {
        String id = normalizeAgentId(agentId);
        ResolvedAgentConfig config = resolveAgentConfig(cfg, id);
        String configured = config != null && config.agentDir() != null ? config.agentDir().trim() : null;

        if (configured != null && !configured.isEmpty()) {
            return resolveUserPath(configured);
        }

        String root = ConfigPaths.resolveStateDir();
        return Paths.get(root, "agents", id, "agent").toString();
    }

    /**
     * Find agent IDs by workspace path (returns agents whose workspace contains the path).
     */
    public static List<String> resolveAgentIdsByWorkspacePath(OpenClawConfig cfg, String workspacePath) {
        String normalizedWorkspacePath = normalizePathForComparison(workspacePath);
        List<String> ids = listAgentIds(cfg);

        List<AgentWorkspaceMatch> matches = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            String workspaceDir = normalizePathForComparison(resolveAgentWorkspaceDir(cfg, id));
            if (!isPathWithinRoot(normalizedWorkspacePath, workspaceDir)) {
                continue;
            }
            matches.add(new AgentWorkspaceMatch(id, workspaceDir, index));
        }

        matches.sort((left, right) -> {
            int workspaceLengthDelta = right.workspaceDir.length() - left.workspaceDir.length();
            if (workspaceLengthDelta != 0) {
                return workspaceLengthDelta;
            }
            return left.order - right.order;
        });

        return matches.stream().map(m -> m.id).toList();
    }

    /**
     * Find the most specific agent ID by workspace path.
     */
    public static String resolveAgentIdByWorkspacePath(OpenClawConfig cfg, String workspacePath) {
        List<String> ids = resolveAgentIdsByWorkspacePath(cfg, workspacePath);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private record AgentWorkspaceMatch(String id, String workspaceDir, int order) {}

    private static boolean isPathWithinRoot(String candidatePath, String rootPath) {
        Path relative = Path.of(rootPath).relativize(Path.of(candidatePath));
        return !relative.toString().startsWith("..") && !relative.isAbsolute();
    }

    private static String normalizePathForComparison(String input) {
        if (input == null) {
            return "";
        }
        Path resolved = Path.of(stripNullBytes(resolveUserPath(input))).toAbsolutePath().normalize();
        String normalized = resolved.toString();

        // On Windows, lowercase for comparison
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return normalized.toLowerCase();
        }
        return normalized;
    }

    private static String stripNullBytes(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\0", "");
    }

    private static String resolveUserPath(String path) {
        return HomeDirUtils.expandHomePrefix(path);
    }

    private static String resolveDefaultAgentWorkspaceDir() {
        String home = HomeDirUtils.resolveRequiredHomeDir();
        String profile = System.getenv("OPENCLAW_PROFILE");
        if (profile != null && !profile.trim().isEmpty() && !"default".equalsIgnoreCase(profile.trim())) {
            return Paths.get(home, ".openclaw", "workspace-" + profile.trim()).toString();
        }
        return Paths.get(home, ".openclaw", "workspace").toString();
    }
}
