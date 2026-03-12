package ai.openclaw.agents.runtime;

import ai.openclaw.common.config.OpenClawConfig;
import ai.openclaw.common.logging.StructuredLogger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service for agent initialization and process spawning.
 * Maps TypeScript: src/agents/acp-spawn.ts
 */
public class AgentSpawnService {

    private static final StructuredLogger logger = StructuredLogger.create("agents/acp-spawn");

    public static final List<String> ACP_SPAWN_MODES = List.of("run", "session");
    public static final List<String> ACP_SPAWN_SANDBOX_MODES = List.of("inherit", "require");
    public static final List<String> ACP_SPAWN_STREAM_TARGETS = List.of("parent");

    public static final String ACP_SPAWN_ACCEPTED_NOTE =
        "initial ACP task queued in isolated session; follow-ups continue in the bound thread.";
    public static final String ACP_SPAWN_SESSION_ACCEPTED_NOTE =
        "thread-bound ACP session stays active after this task; continue in-thread for follow-ups.";

    private final ExecutorService executor;
    private final Map<String, AgentRuntimeSession> activeSessions;

    public enum SpawnMode {
        RUN("run"),
        SESSION("session");

        private final String value;

        SpawnMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static SpawnMode fromString(String value) {
            return Arrays.stream(values())
                .filter(m -> m.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(RUN);
        }
    }

    public enum SandboxMode {
        INHERIT("inherit"),
        REQUIRE("require");

        private final String value;

        SandboxMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record SpawnParams(
        String task,
        String label,
        String agentId,
        String resumeSessionId,
        String cwd,
        SpawnMode mode,
        boolean thread,
        SandboxMode sandbox,
        String streamTo
    ) {
        public SpawnParams {
            mode = mode != null ? mode : SpawnMode.RUN;
            sandbox = sandbox != null ? sandbox : SandboxMode.INHERIT;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String task;
            private String label;
            private String agentId;
            private String resumeSessionId;
            private String cwd;
            private SpawnMode mode = SpawnMode.RUN;
            private boolean thread = false;
            private SandboxMode sandbox = SandboxMode.INHERIT;
            private String streamTo;

            public Builder task(String task) {
                this.task = task;
                return this;
            }

            public Builder label(String label) {
                this.label = label;
                return this;
            }

            public Builder agentId(String agentId) {
                this.agentId = agentId;
                return this;
            }

            public Builder resumeSessionId(String resumeSessionId) {
                this.resumeSessionId = resumeSessionId;
                return this;
            }

            public Builder cwd(String cwd) {
                this.cwd = cwd;
                return this;
            }

            public Builder mode(SpawnMode mode) {
                this.mode = mode;
                return this;
            }

            public Builder thread(boolean thread) {
                this.thread = thread;
                return this;
            }

            public Builder sandbox(SandboxMode sandbox) {
                this.sandbox = sandbox;
                return this;
            }

            public Builder streamTo(String streamTo) {
                this.streamTo = streamTo;
                return this;
            }

            public SpawnParams build() {
                Objects.requireNonNull(task, "Task is required");
                return new SpawnParams(task, label, agentId, resumeSessionId, cwd, mode, thread, sandbox, streamTo);
            }
        }
    }

    public record SpawnContext(
        String agentSessionKey,
        String agentChannel,
        String agentAccountId,
        String agentTo,
        Object agentThreadId,
        boolean sandboxed
    ) {}

    public record SpawnResult(
        SpawnStatus status,
        String childSessionKey,
        String runId,
        SpawnMode mode,
        String streamLogPath,
        String note,
        String error
    ) {
        public static SpawnResult accepted(String childSessionKey, String runId, SpawnMode mode, String note) {
            return new SpawnResult(SpawnStatus.ACCEPTED, childSessionKey, runId, mode, null, note, null);
        }

        public static SpawnResult accepted(String childSessionKey, String runId, SpawnMode mode, String streamLogPath, String note) {
            return new SpawnResult(SpawnStatus.ACCEPTED, childSessionKey, runId, mode, streamLogPath, note, null);
        }

        public static SpawnResult forbidden(String error) {
            return new SpawnResult(SpawnStatus.FORBIDDEN, null, null, null, null, null, error);
        }

        public static SpawnResult error(String error) {
            return new SpawnResult(SpawnStatus.ERROR, null, null, null, null, null, error);
        }

        public static SpawnResult error(String childSessionKey, String error) {
            return new SpawnResult(SpawnStatus.ERROR, childSessionKey, null, null, null, null, error);
        }
    }

    public enum SpawnStatus {
        ACCEPTED("accepted"),
        FORBIDDEN("forbidden"),
        ERROR("error");

        private final String value;

        SpawnStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record AgentRuntimeSession(
        String sessionKey,
        String agentId,
        SpawnMode mode,
        String task,
        String label,
        long createdAt,
        CompletableFuture<AgentResult> future
    ) {}

    public record AgentResult(
        boolean success,
        String output,
        String error,
        long durationMs
    ) {}

    public AgentSpawnService() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("agent-spawn-" + t.getId());
            return t;
        });
        this.activeSessions = new ConcurrentHashMap<>();
    }

    /**
     * Spawn an agent with the given parameters.
     */
    public SpawnResult spawn(SpawnParams params, SpawnContext ctx) {
        return spawn(params, ctx, null);
    }

    /**
     * Spawn an agent with the given parameters and config.
     */
    public SpawnResult spawn(SpawnParams params, SpawnContext ctx, OpenClawConfig cfg) {
        // Validate parameters
        if (params.task() == null || params.task().isBlank()) {
            return SpawnResult.error("Task cannot be empty");
        }

        // Resolve spawn mode
        SpawnMode spawnMode = resolveSpawnMode(params.mode(), params.thread());

        // Validate mode/thread combination
        if (spawnMode == SpawnMode.SESSION && !params.thread()) {
            return SpawnResult.error("mode='session' requires thread=true so the ACP session can stay bound to a thread");
        }

        // Check runtime policy
        String policyError = resolveAcpSpawnRuntimePolicyError(cfg, ctx, params.sandbox());
        if (policyError != null) {
            return SpawnResult.forbidden(policyError);
        }

        // Resolve target agent
        String targetAgentId = resolveTargetAgentId(params.agentId(), cfg);
        if (targetAgentId == null) {
            return SpawnResult.error("ACP target agent is not configured. Pass `agentId` in `sessions_spawn` or set `acp.defaultAgent` in config.");
        }

        // Generate session key
        String sessionKey = generateSessionKey(targetAgentId);
        String runId = generateRunId();

        try {
            // Create session
            AgentRuntimeSession session = createSession(sessionKey, targetAgentId, spawnMode, params.task(), params.label());
            activeSessions.put(sessionKey, session);

            // Start agent execution
            CompletableFuture<AgentResult> future = executeAgentAsync(session, params, ctx);

            // Build result
            String note = spawnMode == SpawnMode.SESSION ? ACP_SPAWN_SESSION_ACCEPTED_NOTE : ACP_SPAWN_ACCEPTED_NOTE;

            logger.info("Agent spawned",
                Map.of("sessionKey", sessionKey, "runId", runId, "agentId", targetAgentId, "mode", spawnMode.getValue()));

            return SpawnResult.accepted(sessionKey, runId, spawnMode, note);

        } catch (Exception e) {
            logger.error("Failed to spawn agent", Map.of("error", e.getMessage()));
            cleanupFailedSpawn(sessionKey);
            return SpawnResult.error(sessionKey, "Failed to spawn agent: " + e.getMessage());
        }
    }

    /**
     * Get an active session by key.
     */
    public Optional<AgentRuntimeSession> getSession(String sessionKey) {
        return Optional.ofNullable(activeSessions.get(sessionKey));
    }

    /**
     * Cancel an active session.
     */
    public boolean cancelSession(String sessionKey) {
        AgentRuntimeSession session = activeSessions.get(sessionKey);
        if (session == null) {
            return false;
        }

        if (session.future() != null && !session.future().isDone()) {
            session.future().cancel(true);
        }

        activeSessions.remove(sessionKey);
        return true;
    }

    /**
     * List all active sessions.
     */
    public List<AgentRuntimeSession> listActiveSessions() {
        return List.copyOf(activeSessions.values());
    }

    private SpawnMode resolveSpawnMode(SpawnMode requestedMode, boolean threadRequested) {
        if (requestedMode != null) {
            return requestedMode;
        }
        return threadRequested ? SpawnMode.SESSION : SpawnMode.RUN;
    }

    private String resolveAcpSpawnRuntimePolicyError(OpenClawConfig cfg, SpawnContext ctx, SandboxMode sandbox) {
        if (ctx != null && ctx.sandboxed()) {
            return "Sandboxed sessions cannot spawn ACP sessions because runtime='acp' runs on the host. Use runtime='subagent' from sandboxed sessions.";
        }

        if (sandbox == SandboxMode.REQUIRE) {
            return "sessions_spawn sandbox='require' is unsupported for runtime='acp' because ACP sessions run outside the sandbox. Use runtime='subagent' or sandbox='inherit'.";
        }

        return null;
    }

    private String resolveTargetAgentId(String requestedAgentId, OpenClawConfig cfg) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return AgentScope.normalizeAgentId(requestedAgentId);
        }

        if (cfg != null && cfg.agents() != null && !cfg.agents().list().isEmpty()) {
            return AgentScope.resolveDefaultAgentId(cfg);
        }

        return null;
    }

    private String generateSessionKey(String agentId) {
        return "agent:" + agentId + ":acp:" + UUID.randomUUID();
    }

    private String generateRunId() {
        return UUID.randomUUID().toString();
    }

    private AgentRuntimeSession createSession(String sessionKey, String agentId, SpawnMode mode,
                                               String task, String label) {
        return new AgentRuntimeSession(
            sessionKey,
            agentId,
            mode,
            task,
            label,
            System.currentTimeMillis(),
            null
        );
    }

    private CompletableFuture<AgentResult> executeAgentAsync(AgentRuntimeSession session, SpawnParams params,
                                                              SpawnContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                // Simulate agent execution (this would integrate with actual ACP runtime)
                logger.info("Executing agent task",
                    Map.of("sessionKey", session.sessionKey(), "task", params.task()));

                // Placeholder for actual agent execution
                // In real implementation, this would:
                // 1. Initialize ACP runtime
                // 2. Send task to agent
                // 3. Stream results
                // 4. Return final result

                // For now, simulate a simple execution
                Thread.sleep(100); // Simulate work

                long durationMs = System.currentTimeMillis() - startTime;
                return new AgentResult(true, "Task completed", null, durationMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AgentResult(false, null, "Execution interrupted", System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                return new AgentResult(false, null, e.getMessage(), System.currentTimeMillis() - startTime);
            } finally {
                activeSessions.remove(session.sessionKey());
            }
        }, executor);
    }

    private void cleanupFailedSpawn(String sessionKey) {
        activeSessions.remove(sessionKey);
    }

    /**
     * Shutdown the spawn service.
     */
    public void shutdown() {
        // Cancel all active sessions
        for (AgentRuntimeSession session : activeSessions.values()) {
            if (session.future() != null && !session.future().isDone()) {
                session.future().cancel(true);
            }
        }
        activeSessions.clear();

        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
