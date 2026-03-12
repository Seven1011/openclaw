package ai.openclaw.agents.tools;

import java.util.*;

/**
 * Tool catalog for discovery and registration of AI tools.
 * Maps TypeScript: src/agents/tool-catalog.ts
 */
public class ToolCatalog {

    public enum ToolProfileId {
        MINIMAL("minimal"),
        CODING("coding"),
        MESSAGING("messaging"),
        FULL("full");

        private final String id;

        ToolProfileId(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static ToolProfileId fromString(String value) {
            return Arrays.stream(values())
                .filter(p -> p.id.equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
        }
    }

    public record ToolProfilePolicy(
        List<String> allow,
        List<String> deny
    ) {
        public ToolProfilePolicy {
            allow = allow != null ? List.copyOf(allow) : null;
            deny = deny != null ? List.copyOf(deny) : null;
        }
    }

    public record CoreToolSection(
        String id,
        String label,
        List<CoreToolInfo> tools
    ) {}

    public record CoreToolInfo(
        String id,
        String label,
        String description
    ) {}

    public record CoreToolDefinition(
        String id,
        String label,
        String description,
        String sectionId,
        List<ToolProfileId> profiles,
        boolean includeInOpenClawGroup
    ) {}

    public record ProfileOption(
        String id,
        String label
    ) {}

    private static final List<CoreToolSectionDef> CORE_TOOL_SECTION_ORDER = List.of(
        new CoreToolSectionDef("fs", "Files"),
        new CoreToolSectionDef("runtime", "Runtime"),
        new CoreToolSectionDef("web", "Web"),
        new CoreToolSectionDef("memory", "Memory"),
        new CoreToolSectionDef("sessions", "Sessions"),
        new CoreToolSectionDef("ui", "UI"),
        new CoreToolSectionDef("messaging", "Messaging"),
        new CoreToolSectionDef("automation", "Automation"),
        new CoreToolSectionDef("nodes", "Nodes"),
        new CoreToolSectionDef("agents", "Agents"),
        new CoreToolSectionDef("media", "Media")
    );

    private record CoreToolSectionDef(String id, String label) {}

    private static final List<CoreToolDefinition> CORE_TOOL_DEFINITIONS = List.of(
        new CoreToolDefinition("read", "read", "Read file contents", "fs",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("write", "write", "Create or overwrite files", "fs",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("edit", "edit", "Make precise edits", "fs",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("apply_patch", "apply_patch", "Patch files (OpenAI)", "fs",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("exec", "exec", "Run shell commands", "runtime",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("process", "process", "Manage background processes", "runtime",
            List.of(ToolProfileId.CODING), false),
        new CoreToolDefinition("web_search", "web_search", "Search the web", "web",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("web_fetch", "web_fetch", "Fetch web content", "web",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("memory_search", "memory_search", "Semantic search", "memory",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("memory_get", "memory_get", "Read memory files", "memory",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("sessions_list", "sessions_list", "List sessions", "sessions",
            List.of(ToolProfileId.CODING, ToolProfileId.MESSAGING), true),
        new CoreToolDefinition("sessions_history", "sessions_history", "Session history", "sessions",
            List.of(ToolProfileId.CODING, ToolProfileId.MESSAGING), true),
        new CoreToolDefinition("sessions_send", "sessions_send", "Send to session", "sessions",
            List.of(ToolProfileId.CODING, ToolProfileId.MESSAGING), true),
        new CoreToolDefinition("sessions_spawn", "sessions_spawn", "Spawn sub-agent", "sessions",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("subagents", "subagents", "Manage sub-agents", "sessions",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("session_status", "session_status", "Session status", "sessions",
            List.of(ToolProfileId.MINIMAL, ToolProfileId.CODING, ToolProfileId.MESSAGING), true),
        new CoreToolDefinition("browser", "browser", "Control web browser", "ui",
            List.of(), true),
        new CoreToolDefinition("canvas", "canvas", "Control canvases", "ui",
            List.of(), true),
        new CoreToolDefinition("message", "message", "Send messages", "messaging",
            List.of(ToolProfileId.MESSAGING), true),
        new CoreToolDefinition("cron", "cron", "Schedule tasks", "automation",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("gateway", "gateway", "Gateway control", "automation",
            List.of(), true),
        new CoreToolDefinition("nodes", "nodes", "Nodes + devices", "nodes",
            List.of(), true),
        new CoreToolDefinition("agents_list", "agents_list", "List agents", "agents",
            List.of(), true),
        new CoreToolDefinition("image", "image", "Image understanding", "media",
            List.of(ToolProfileId.CODING), true),
        new CoreToolDefinition("tts", "tts", "Text-to-speech conversion", "media",
            List.of(), true)
    );

    private static final Map<String, CoreToolDefinition> CORE_TOOL_BY_ID = CORE_TOOL_DEFINITIONS.stream()
        .collect(java.util.stream.Collectors.toMap(
            CoreToolDefinition::id,
            d -> d,
            (a, b) -> a,
            java.util.LinkedHashMap::new
        ));

    public static final List<ProfileOption> PROFILE_OPTIONS = List.of(
        new ProfileOption("minimal", "Minimal"),
        new ProfileOption("coding", "Coding"),
        new ProfileOption("messaging", "Messaging"),
        new ProfileOption("full", "Full")
    );

    public static final Map<String, List<String>> CORE_TOOL_GROUPS = buildCoreToolGroupMap();

    private static Map<String, List<String>> buildCoreToolGroupMap() {
        Map<String, List<String>> sectionToolMap = new HashMap<>();

        for (CoreToolDefinition tool : CORE_TOOL_DEFINITIONS) {
            String groupId = "group:" + tool.sectionId();
            sectionToolMap.computeIfAbsent(groupId, k -> new ArrayList<>()).add(tool.id());
        }

        List<String> openclawTools = CORE_TOOL_DEFINITIONS.stream()
            .filter(CoreToolDefinition::includeInOpenClawGroup)
            .map(CoreToolDefinition::id)
            .toList();

        Map<String, List<String>> result = new HashMap<>();
        result.put("group:openclaw", openclawTools);
        result.putAll(sectionToolMap);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Resolve core tool profile policy.
     */
    public static ToolProfilePolicy resolveCoreToolProfilePolicy(String profile) {
        if (profile == null || profile.isBlank()) {
            return null;
        }

        ToolProfileId resolved = ToolProfileId.fromString(profile);
        if (resolved == null) {
            return null;
        }

        List<String> allowed = listCoreToolIdsForProfile(resolved);

        if (allowed.isEmpty() && resolved != ToolProfileId.FULL) {
            return null;
        }

        return new ToolProfilePolicy(
            resolved == ToolProfileId.FULL ? null : allowed,
            null
        );
    }

    /**
     * List core tool sections with their tools.
     */
    public static List<CoreToolSection> listCoreToolSections() {
        return CORE_TOOL_SECTION_ORDER.stream()
            .map(section -> {
                List<CoreToolInfo> tools = CORE_TOOL_DEFINITIONS.stream()
                    .filter(t -> t.sectionId().equals(section.id()))
                    .map(t -> new CoreToolInfo(t.id(), t.label(), t.description()))
                    .toList();
                return new CoreToolSection(section.id(), section.label(), tools);
            })
            .filter(s -> !s.tools().isEmpty())
            .toList();
    }

    /**
     * Resolve core tool profiles for a given tool ID.
     */
    public static List<ToolProfileId> resolveCoreToolProfiles(String toolId) {
        CoreToolDefinition tool = CORE_TOOL_BY_ID.get(toolId);
        if (tool == null) {
            return List.of();
        }
        return List.copyOf(tool.profiles());
    }

    /**
     * Check if a tool ID is a known core tool.
     */
    public static boolean isKnownCoreToolId(String toolId) {
        return CORE_TOOL_BY_ID.containsKey(toolId);
    }

    /**
     * List all core tool IDs.
     */
    public static List<String> listAllCoreToolIds() {
        return List.copyOf(CORE_TOOL_BY_ID.keySet());
    }

    /**
     * Get core tool definition by ID.
     */
    public static CoreToolDefinition getCoreToolDefinition(String toolId) {
        return CORE_TOOL_BY_ID.get(toolId);
    }

    private static List<String> listCoreToolIdsForProfile(ToolProfileId profile) {
        return CORE_TOOL_DEFINITIONS.stream()
            .filter(t -> t.profiles().contains(profile))
            .map(CoreToolDefinition::id)
            .toList();
    }
}
