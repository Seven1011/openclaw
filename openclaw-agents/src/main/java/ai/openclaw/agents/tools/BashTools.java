package ai.openclaw.agents.tools;

import ai.openclaw.agents.process.BashProcessRegistry;
import ai.openclaw.agents.process.BashProcessRegistry.ProcessSession;
import ai.openclaw.agents.process.BashProcessRegistry.FinishedSession;
import ai.openclaw.common.logging.StructuredLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bash tool execution for running shell commands.
 * Maps TypeScript: src/agents/bash-tools.exec-runtime.ts
 */
public class BashTools {

    private static final StructuredLogger logger = StructuredLogger.create("agents/bash-tools");

    private final BashProcessRegistry registry;
    private final ExecutorService executor;

    public record ExecParams(
        String command,
        String cwd,
        Long timeoutSec,
        Boolean background,
        Map<String, String> env,
        Long maxOutputChars,
        Boolean notifyOnExit,
        Boolean notifyOnExitEmptySuccess,
        String sessionKey,
        String scopeKey
    ) {
        public ExecParams {
            timeoutSec = timeoutSec != null ? timeoutSec : 60L;
            background = background != null ? background : false;
            notifyOnExit = notifyOnExit != null ? notifyOnExit : false;
            notifyOnExitEmptySuccess = notifyOnExitEmptySuccess != null ? notifyOnExitEmptySuccess : false;
            maxOutputChars = maxOutputChars != null ? maxOutputChars : 100_000L;
        }

        public static ExecParams simple(String command) {
            return new ExecParams(command, null, null, null, null, null, null, null, null, null);
        }

        public static ExecParams withCwd(String command, String cwd) {
            return new ExecParams(command, cwd, null, null, null, null, null, null, null, null);
        }
    }

    public record ExecResult(
        boolean success,
        String sessionId,
        Integer exitCode,
        String stdout,
        String stderr,
        String aggregated,
        String tail,
        Boolean truncated,
        Long durationMs,
        String status,
        String error
    ) {
        public static ExecResult running(String sessionId, Long pid) {
            return new ExecResult(true, sessionId, null, null, null, null, null, null, null, "running", null);
        }

        public static ExecResult completed(String sessionId, int exitCode, String stdout, String stderr,
                                          String aggregated, String tail, boolean truncated, long durationMs) {
            return new ExecResult(true, sessionId, exitCode, stdout, stderr, aggregated, tail, truncated,
                durationMs, exitCode == 0 ? "completed" : "failed", null);
        }

        public static ExecResult error(String error) {
            return new ExecResult(false, null, null, null, null, null, null, null, null, "error", error);
        }
    }

    public BashTools(BashProcessRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Registry cannot be null");
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("bash-tool-" + t.getId());
            return t;
        });
    }

    /**
     * Execute a bash command.
     */
    public ExecResult exec(ExecParams params) {
        if (params.command() == null || params.command().isBlank()) {
            return ExecResult.error("Command cannot be empty");
        }

        String sessionId = registry.createSessionSlug();
        Path cwdPath = resolveCwd(params.cwd());

        try {
            ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "/bin/sh",
                System.getProperty("os.name").toLowerCase().contains("win") ? "/c" : "-c",
                params.command()
            );

            pb.directory(cwdPath.toFile());
            pb.redirectErrorStream(false);

            // Set environment variables
            if (params.env() != null) {
                pb.environment().putAll(params.env());
            }

            Process process = pb.start();

            ProcessSession session = registry.createSession(
                sessionId,
                params.command(),
                params.scopeKey(),
                params.sessionKey(),
                params.notifyOnExit(),
                params.notifyOnExitEmptySuccess(),
                process,
                params.maxOutputChars()
            );

            registry.addSession(session);

            if (params.background()) {
                runInBackground(session, process, params);
                return ExecResult.running(sessionId, process.pid());
            } else {
                return runSynchronously(session, process, params);
            }

        } catch (IOException e) {
            logger.error("Failed to start process", Map.of("error", e.getMessage()));
            return ExecResult.error("Failed to start process: " + e.getMessage());
        }
    }

    private ExecResult runSynchronously(ProcessSession session, Process process, ExecParams params) {
        long startTime = System.currentTimeMillis();
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        try (InputStream stdoutStream = process.getInputStream();
             InputStream stderrStream = process.getErrorStream();
             OutputStream stdinStream = process.getOutputStream()) {

            // Start readers for stdout and stderr
            Future<String> stdoutFuture = executor.submit(() ->
                readStream(stdoutStream, session, "stdout", stdoutBuilder));
            Future<String> stderrFuture = executor.submit(() ->
                readStream(stderrStream, session, "stderr", stderrBuilder));

            boolean finished = process.waitFor(params.timeoutSec(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                registry.markExited(session, null, "SIGTERM", BashProcessRegistry.ProcessStatus.KILLED);
                return ExecResult.error("Command timed out after " + params.timeoutSec() + " seconds");
            }

            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            int exitCode = process.exitValue();
            long durationMs = System.currentTimeMillis() - startTime;

            BashProcessRegistry.ProcessStatus status = exitCode == 0
                ? BashProcessRegistry.ProcessStatus.COMPLETED
                : BashProcessRegistry.ProcessStatus.FAILED;

            registry.markExited(session, exitCode, null, status);

            FinishedSession finishedSession = registry.getFinishedSession(session.id());
            String aggregated = finishedSession != null ? finishedSession.aggregated() : session.aggregated();
            String tail = finishedSession != null ? finishedSession.tail() : session.tail();
            boolean truncated = finishedSession != null ? finishedSession.truncated() : session.truncated();

            return ExecResult.completed(
                session.id(),
                exitCode,
                stdout,
                stderr,
                aggregated,
                tail,
                truncated,
                durationMs
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ExecResult.error("Command interrupted");
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return ExecResult.error("Failed to read command output: " + e.getMessage());
        } catch (IOException e) {
            return ExecResult.error("IO error: " + e.getMessage());
        }
    }

    private void runInBackground(ProcessSession session, Process process, ExecParams params) {
        executor.submit(() -> {
            try (InputStream stdoutStream = process.getInputStream();
                 InputStream stderrStream = process.getErrorStream()) {

                Future<?> stdoutFuture = executor.submit(() ->
                    drainStream(stdoutStream, session, "stdout"));
                Future<?> stderrFuture = executor.submit(() ->
                    drainStream(stderrStream, session, "stderr"));

                int exitCode = process.waitFor();

                stdoutFuture.get(5, TimeUnit.SECONDS);
                stderrFuture.get(5, TimeUnit.SECONDS);

                BashProcessRegistry.ProcessStatus status = exitCode == 0
                    ? BashProcessRegistry.ProcessStatus.COMPLETED
                    : BashProcessRegistry.ProcessStatus.FAILED;

                registry.markExited(session, exitCode, null, status);

                logger.info("Background process completed",
                    Map.of("sessionId", session.id(), "exitCode", exitCode));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                registry.markExited(session, null, "SIGTERM", BashProcessRegistry.ProcessStatus.KILLED);
            } catch (Exception e) {
                logger.error("Background process error",
                    Map.of("sessionId", session.id(), "error", e.getMessage()));
                registry.markExited(session, null, null, BashProcessRegistry.ProcessStatus.FAILED);
            }
        });
    }

    private String readStream(InputStream stream, ProcessSession session, String streamName,
                              StringBuilder collector) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
            collector.append(chunk);
            registry.appendOutput(session, streamName, chunk);
        }
        return collector.toString();
    }

    private void drainStream(InputStream stream, ProcessSession session, String streamName) {
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = stream.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                registry.appendOutput(session, streamName, chunk);
            }
        } catch (IOException e) {
            logger.warn("Error reading " + streamName,
                Map.of("sessionId", session.id(), "error", e.getMessage()));
        }
    }

    private Path resolveCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return Path.of(System.getProperty("user.dir"));
        }
        Path path = Path.of(cwd);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize();
    }

    /**
     * Write to a running session's stdin.
     */
    public boolean writeToSession(String sessionId, String data) {
        ProcessSession session = registry.getSession(sessionId);
        if (session == null || session.stdin() == null) {
            return false;
        }

        try {
            session.stdin().write(data.getBytes(StandardCharsets.UTF_8));
            session.stdin().flush();
            return true;
        } catch (IOException e) {
            logger.error("Failed to write to session stdin",
                Map.of("sessionId", sessionId, "error", e.getMessage()));
            return false;
        }
    }

    /**
     * Send EOF to a session's stdin.
     */
    public boolean endSessionInput(String sessionId) {
        ProcessSession session = registry.getSession(sessionId);
        if (session == null || session.stdin() == null) {
            return false;
        }

        try {
            session.stdin().close();
            return true;
        } catch (IOException e) {
            logger.error("Failed to close session stdin",
                Map.of("sessionId", sessionId, "error", e.getMessage()));
            return false;
        }
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
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
