package ai.openclaw.agents.tools;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry for AI tools with dynamic registration and discovery.
 * Works in conjunction with ToolCatalog for core tools.
 */
public class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolGroup> groups = new ConcurrentHashMap<>();

    public record RegisteredTool(
        String id,
        String name,
        String description,
        ToolCategory category,
        Function<ToolInput, ToolResult> handler,
        Set<String> profiles,
        Map<String, Object> metadata
    ) {}

    public record ToolInput(
        Map<String, Object> params,
        ToolContext context
    ) {}

    public record ToolResult(
        boolean success,
        Object data,
        String error,
        Map<String, Object> metadata
    ) {
        public static ToolResult success(Object data) {
            return new ToolResult(true, data, null, null);
        }

        public static ToolResult success(Object data, Map<String, Object> metadata) {
            return new ToolResult(true, data, null, metadata);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, null, error, null);
        }

        public static ToolResult error(String error, Map<String, Object> metadata) {
            return new ToolResult(false, null, error, metadata);
        }
    }

    public record ToolContext(
        String sessionKey,
        String agentId,
        String workspaceDir,
        Map<String, Object> sessionState
    ) {}

    public enum ToolCategory {
        FILE_SYSTEM("fs"),
        RUNTIME("runtime"),
        WEB("web"),
        MEMORY("memory"),
        SESSIONS("sessions"),
        UI("ui"),
        MESSAGING("messaging"),
        AUTOMATION("automation"),
        NODES("nodes"),
        AGENTS("agents"),
        MEDIA("media");

        private final String sectionId;

        ToolCategory(String sectionId) {
            this.sectionId = sectionId;
        }

        public String getSectionId() {
            return sectionId;
        }

        public static ToolCategory fromSectionId(String sectionId) {
            return Arrays.stream(values())
                .filter(c -> c.sectionId.equals(sectionId))
                .findFirst()
                .orElse(null);
        }
    }

    public record ToolGroup(
        String id,
        String label,
        List<String> toolIds
    ) {}

    public ToolRegistry() {
        registerCoreToolGroups();
    }

    private void registerCoreToolGroups() {
        // Register built-in groups from ToolCatalog
        for (Map.Entry<String, List<String>> entry : ToolCatalog.CORE_TOOL_GROUPS.entrySet()) {
            String groupId = entry.getKey();
            String label = groupId.replace("group:", "");
            groups.put(groupId, new ToolGroup(groupId, label, entry.getValue()));
        }
    }

    /**
     * Register a new tool.
     */
    public void register(RegisteredTool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(tool.id(), "Tool ID cannot be null");
        tools.put(tool.id(), tool);
    }

    /**
     * Unregister a tool by ID.
     */
    public boolean unregister(String toolId) {
        return tools.remove(toolId) != null;
    }

    /**
     * Get a tool by ID.
     */
    public Optional<RegisteredTool> get(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    /**
     * Check if a tool is registered.
     */
    public boolean has(String toolId) {
        return tools.containsKey(toolId) || ToolCatalog.isKnownCoreToolId(toolId);
    }

    /**
     * List all registered tool IDs.
     */
    public Set<String> listToolIds() {
        Set<String> allIds = new HashSet<>(tools.keySet());
        allIds.addAll(ToolCatalog.listAllCoreToolIds());
        return Collections.unmodifiableSet(allIds);
    }

    /**
     * List all registered tools.
     */
    public Collection<RegisteredTool> listTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * List tools by category.
     */
    public List<RegisteredTool> listByCategory(ToolCategory category) {
        return tools.values().stream()
            .filter(t -> t.category() == category)
            .toList();
    }

    /**
     * List tools by profile.
     */
    public List<String> listByProfile(String profileId) {
        ToolCatalog.ToolProfilePolicy policy = ToolCatalog.resolveCoreToolProfilePolicy(profileId);
        if (policy == null || policy.allow() == null) {
            return List.of();
        }
        return List.copyOf(policy.allow());
    }

    /**
     * Execute a tool by ID.
     */
    public ToolResult execute(String toolId, ToolInput input) {
        RegisteredTool tool = tools.get(toolId);
        if (tool == null) {
            // Check if it's a known core tool that hasn't been implemented yet
            if (ToolCatalog.isKnownCoreToolId(toolId)) {
                return ToolResult.error("Tool '" + toolId + "' is defined but not yet implemented");
            }
            return ToolResult.error("Tool not found: " + toolId);
        }
        try {
            return tool.handler().apply(input);
        } catch (Exception e) {
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * Register a tool group.
     */
    public void registerGroup(ToolGroup group) {
        Objects.requireNonNull(group, "Group cannot be null");
        groups.put(group.id(), group);
    }

    /**
     * Get a tool group by ID.
     */
    public Optional<ToolGroup> getGroup(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    /**
     * List all tool group IDs.
     */
    public Set<String> listGroupIds() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    /**
     * Clear all registered tools (for testing).
     */
    public void clear() {
        tools.clear();
    }

    /**
     * Get tools filtered by profile policy.
     */
    public List<String> filterByProfile(List<String> toolIds, String profileId) {
        ToolCatalog.ToolProfilePolicy policy = ToolCatalog.resolveCoreToolProfilePolicy(profileId);
        if (policy == null || profileId == null || "full".equalsIgnoreCase(profileId)) {
            return List.copyOf(toolIds);
        }

        Set<String> allowed = policy.allow() != null ? new HashSet<>(policy.allow()) : null;
        Set<String> denied = policy.deny() != null ? new HashSet<>(policy.deny()) : Set.of();

        return toolIds.stream()
            .filter(id -> !denied.contains(id))
            .filter(id -> allowed == null || allowed.contains(id))
            .toList();
    }
}
