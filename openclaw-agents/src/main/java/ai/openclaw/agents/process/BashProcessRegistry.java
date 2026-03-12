package ai.openclaw.agents.process;

import ai.openclaw.common.logging.StructuredLogger;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * Registry for tracking and managing active shell processes.
 * Maps TypeScript: src/agents/bash-process-registry.ts
 */
public class BashProcessRegistry {

    private static final StructuredLogger logger = StructuredLogger.create("agents/bash-process-registry");

    private static final long DEFAULT_JOB_TTL_MS = 30 * 60 * 1000; // 30 minutes
    private static final long MIN_JOB_TTL_MS = 60 * 1000; // 1 minute
    private static final long MAX_JOB_TTL_MS = 3 * 60 * 60 * 1000; // 3 hours
    private static final long DEFAULT_PENDING_OUTPUT_CHARS = 30_000;

    private final Map<String, ProcessSession> runningSessions = new ConcurrentHashMap<>();
    private final Map<String, FinishedSession> finishedSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeperExecutor;

    private volatile long jobTtlMs = DEFAULT_JOB_TTL_MS;

    public enum ProcessStatus {
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        KILLED("killed");

        private final String value;

        ProcessStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record ProcessSession(
        String id,
        String command,
        String scopeKey,
        String sessionKey,
        boolean notifyOnExit,
        boolean notifyOnExitEmptySuccess,
        boolean exitNotified,
        Process child,
        OutputStream stdin,
        Long pid,
        long startedAt,
        String cwd,
        long maxOutputChars,
        Long pendingMaxOutputChars,
        long totalOutputChars,
        List<String> pendingStdout,
        List<String> pendingStderr,
        long pendingStdoutChars,
        long pendingStderrChars,
        StringBuilder aggregated,
        StringBuilder tail,
        Integer exitCode,
        String exitSignal,
        boolean exited,
        boolean truncated,
        boolean backgrounded
    ) {
        public ProcessSession {
            pendingStdout = pendingStdout != null ? pendingStdout : new CopyOnWriteArrayList<>();
            pendingStderr = pendingStderr != null ? pendingStderr : new CopyOnWriteArrayList<>();
            aggregated = aggregated != null ? aggregated : new StringBuilder();
            tail = tail != null ? tail : new StringBuilder();
        }

        public String aggregated() {
            return aggregated.toString();
        }

        public String tail() {
            return tail.toString();
        }
    }

    public record FinishedSession(
        String id,
        String command,
        String scopeKey,
        long startedAt,
        long endedAt,
        String cwd,
        ProcessStatus status,
        Integer exitCode,
        String exitSignal,
        String aggregated,
        String tail,
        boolean truncated,
        long totalOutputChars
    ) {}

    private static final String[] SLUG_ADJECTIVES = {
        "amber", "briny", "brisk", "calm", "clear", "cool", "crisp", "dawn", "delta",
        "ember", "faint", "fast", "fresh", "gentle", "glow", "good", "grand", "keen",
        "kind", "lucky", "marine", "mellow", "mild", "neat", "nimble", "nova", "oceanic",
        "plaid", "quick", "quiet", "rapid", "salty", "sharp", "swift", "tender", "tidal",
        "tidy", "tide", "vivid", "warm", "wild", "young"
    };

    private static final String[] SLUG_NOUNS = {
        "atlas", "basil", "bison", "bloom", "breeze", "canyon", "cedar", "claw", "cloud",
        "comet", "coral", "cove", "crest", "crustacean", "daisy", "dune", "ember", "falcon",
        "fjord", "forest", "glade", "gulf", "harbor", "haven", "kelp", "lagoon", "lobster",
        "meadow", "mist", "nudibranch", "nexus", "ocean", "orbit", "otter", "pine", "prairie",
        "reef", "ridge", "river", "rook", "sable", "sage", "seaslug", "shell", "shoal",
        "shore", "slug", "summit", "tidepool", "trail", "valley", "wharf", "willow", "zephyr"
    };

    public BashProcessRegistry() {
        this.sweeperExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("process-registry-sweeper");
            return t;
        });
        startSweeper();
    }

    /**
     * Create a new process session.
     */
    public ProcessSession createSession(
        String id,
        String command,
        String scopeKey,
        String sessionKey,
        boolean notifyOnExit,
        boolean notifyOnExitEmptySuccess,
        Process child,
        long maxOutputChars
    ) {
        return new ProcessSession(
            id,
            command,
            scopeKey,
            sessionKey,
            notifyOnExit,
            notifyOnExitEmptySuccess,
            false,
            child,
            child.getOutputStream(),
            child.pid(),
            System.currentTimeMillis(),
            System.getProperty("user.dir"),
            maxOutputChars,
            DEFAULT_PENDING_OUTPUT_CHARS,
            0,
            null,
            null,
            0,
            0,
            null,
            null,
            null,
            null,
            false,
            false,
            false
        );
    }

    /**
     * Generate a unique session slug.
     */
    public String createSessionSlug() {
        Random random = new Random();

        for (int attempt = 0; attempt < 12; attempt++) {
            String base = createSlugBase(random);
            if (!isSessionIdTaken(base)) {
                return base;
            }
            for (int i = 2; i <= 12; i++) {
                String candidate = base + "-" + i;
                if (!isSessionIdTaken(candidate)) {
                    return candidate;
                }
            }
        }

        // Fallback with timestamp
        String fallback = createSlugBase(random) + "-" +
            Long.toString(System.currentTimeMillis(), 36).substring(0, 5);
        return isSessionIdTaken(fallback) ? fallback + "-" + System.currentTimeMillis() : fallback;
    }

    private String createSlugBase(Random random) {
        String adjective = SLUG_ADJECTIVES[random.nextInt(SLUG_ADJECTIVES.length)];
        String noun = SLUG_NOUNS[random.nextInt(SLUG_NOUNS.length)];
        return adjective + "-" + noun;
    }

    private boolean isSessionIdTaken(String id) {
        return runningSessions.containsKey(id) || finishedSessions.containsKey(id);
    }

    /**
     * Add a session to the registry.
     */
    public void addSession(ProcessSession session) {
        runningSessions.put(session.id(), session);
    }

    /**
     * Get a running session by ID.
     */
    public ProcessSession getSession(String id) {
        return runningSessions.get(id);
    }

    /**
     * Get a finished session by ID.
     */
    public FinishedSession getFinishedSession(String id) {
        return finishedSessions.get(id);
    }

    /**
     * Delete a session from the registry.
     */
    public void deleteSession(String id) {
        ProcessSession session = runningSessions.remove(id);
        if (session != null) {
            cleanupSession(session);
        }
        finishedSessions.remove(id);
    }

    /**
     * Append output to a session.
     */
    public void appendOutput(ProcessSession session, String stream, String chunk) {
        session.pendingStdout().add(chunk);

        long pendingCap = Math.min(
            session.pendingMaxOutputChars() != null ? session.pendingMaxOutputChars() : DEFAULT_PENDING_OUTPUT_CHARS,
            session.maxOutputChars()
        );

        long pendingChars = sumPendingChars(session.pendingStdout());

        if (pendingChars > pendingCap) {
            session.truncated = true;
            capPendingBuffer(session.pendingStdout(), pendingCap);
        }

        // Update total and aggregated
        session.totalOutputChars += chunk.length();

        String currentAggregated = session.aggregated().toString();
        String newAggregated = trimWithCap(currentAggregated + chunk, (int) session.maxOutputChars());

        if (newAggregated.length() < currentAggregated.length() + chunk.length()) {
            session.truncated = true;
        }

        session.aggregated.setLength(0);
        session.aggregated.append(newAggregated);

        // Update tail (last 2000 chars)
        String tailText = tail(newAggregated, 2000);
        session.tail.setLength(0);
        session.tail.append(tailText);
    }

    /**
     * Mark a session as exited.
     */
    public void markExited(ProcessSession session, Integer exitCode, String exitSignal, ProcessStatus status) {
        session.exited = true;
        session.exitCode = exitCode;
        session.exitSignal = exitSignal;

        // Update tail one final time
        String tailText = tail(session.aggregated(), 2000);
        session.tail.setLength(0);
        session.tail.append(tailText);

        moveToFinished(session, status);
    }

    /**
     * Mark a session as backgrounded.
     */
    public void markBackgrounded(ProcessSession session) {
        session.backgrounded = true;
    }

    private void moveToFinished(ProcessSession session, ProcessStatus status) {
        runningSessions.remove(session.id());

        // Clean up process resources
        cleanupSession(session);

        if (!session.backgrounded()) {
            return;
        }

        finishedSessions.put(session.id(), new FinishedSession(
            session.id(),
            session.command(),
            session.scopeKey(),
            session.startedAt(),
            System.currentTimeMillis(),
            session.cwd(),
            status,
            session.exitCode(),
            session.exitSignal(),
            session.aggregated(),
            session.tail(),
            session.truncated(),
            session.totalOutputChars()
        ));
    }

    private void cleanupSession(ProcessSession session) {
        if (session.child() != null) {
            session.child().destroyForcibly();
        }
        if (session.stdin() != null) {
            try {
                session.stdin().close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Drain pending output from a session.
     */
    public DrainResult drainSession(ProcessSession session) {
        String stdout = String.join("", session.pendingStdout());
        String stderr = String.join("", session.pendingStderr());
        session.pendingStdout().clear();
        session.pendingStderr().clear();
        return new DrainResult(stdout, stderr);
    }

    public record DrainResult(String stdout, String stderr) {}

    /**
     * List all running background sessions.
     */
    public List<ProcessSession> listRunningSessions() {
        return runningSessions.values().stream()
            .filter(ProcessSession::backgrounded)
            .toList();
    }

    /**
     * List all finished sessions.
     */
    public List<FinishedSession> listFinishedSessions() {
        return List.copyOf(finishedSessions.values());
    }

    /**
     * Clear all finished sessions.
     */
    public void clearFinished() {
        finishedSessions.clear();
    }

    /**
     * Set the job TTL (time-to-live) for finished sessions.
     */
    public void setJobTtlMs(long value) {
        this.jobTtlMs = clampTtl(value);
    }

    private long clampTtl(long value) {
        if (value <= 0) {
            return DEFAULT_JOB_TTL_MS;
        }
        return Math.min(Math.max(value, MIN_JOB_TTL_MS), MAX_JOB_TTL_MS);
    }

    private void pruneFinishedSessions() {
        long cutoff = System.currentTimeMillis() - jobTtlMs;
        finishedSessions.entrySet().removeIf(entry -> entry.getValue().endedAt() < cutoff);
    }

    private void startSweeper() {
        long interval = Math.max(30_000, jobTtlMs / 6);
        sweeperExecutor.scheduleAtFixedRate(this::pruneFinishedSessions, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Shutdown the registry and cleanup resources.
     */
    public void shutdown() {
        sweeperExecutor.shutdown();
        try {
            if (!sweeperExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                sweeperExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sweeperExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Clean up all running sessions
        runningSessions.values().forEach(this::cleanupSession);
        runningSessions.clear();
        finishedSessions.clear();
    }

    private static long sumPendingChars(List<String> buffer) {
        long total = 0;
        for (String chunk : buffer) {
            total += chunk.length();
        }
        return total;
    }

    private static void capPendingBuffer(List<String> buffer, long cap) {
        long pendingChars = sumPendingChars(buffer);
        while (pendingChars > cap && !buffer.isEmpty()) {
            pendingChars -= buffer.remove(0).length();
        }
    }

    private static String trimWithCap(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(text.length() - max);
    }

    private static String tail(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(text.length() - max);
    }
}
