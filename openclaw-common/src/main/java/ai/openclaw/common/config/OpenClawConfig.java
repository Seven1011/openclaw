package ai.openclaw.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenClawConfig(
    Meta meta,
    LoggingConfig logging,
    DiagnosticsConfig diagnostics,
    SessionConfig session,
    WebConfig web,
    GatewayConfig gateway,
    AgentsConfig agents
) {
    public OpenClawConfig {
    }

    public OpenClawConfig(Meta meta, LoggingConfig logging, DiagnosticsConfig diagnostics,
                          SessionConfig session, WebConfig web, GatewayConfig gateway) {
        this(meta, logging, diagnostics, session, web, gateway, null);
    }

    public record Meta(
        String lastTouchedVersion,
        String lastTouchedAt
    ) {}

    public record LoggingConfig(
        String level,
        String file,
        Long maxFileBytes,
        String consoleLevel,
        String consoleStyle,
        String redactSensitive,
        List<String> redactPatterns
    ) {}

    public record DiagnosticsConfig(
        Boolean enabled,
        List<String> flags,
        Long stuckSessionWarnMs,
        OtelConfig otel,
        CacheTraceConfig cacheTrace
    ) {
        public record OtelConfig(
            Boolean enabled,
            String endpoint,
            String protocol,
            Map<String, String> headers,
            String serviceName,
            Boolean traces,
            Boolean metrics,
            Boolean logs,
            Double sampleRate,
            Long flushIntervalMs
        ) {}

        public record CacheTraceConfig(
            Boolean enabled,
            String filePath,
            Boolean includeMessages,
            Boolean includePrompt,
            Boolean includeSystem
        ) {}
    }

    public record SessionConfig(
        String scope,
        String dmScope,
        Map<String, List<String>> identityLinks,
        List<String> resetTriggers,
        Integer idleMinutes,
        SessionResetConfig reset,
        SessionResetByTypeConfig resetByType,
        Map<String, SessionResetConfig> resetByChannel,
        String store,
        Integer typingIntervalSeconds,
        String typingMode,
        Integer parentForkMaxTokens,
        String mainKey,
        SessionSendPolicyConfig sendPolicy,
        MaintenanceConfig maintenance
    ) {
        public record SessionResetConfig(
            String mode,
            Integer atHour,
            Integer idleMinutes
        ) {}

        public record SessionResetByTypeConfig(
            SessionResetConfig direct,
            SessionResetConfig dm,
            SessionResetConfig group,
            SessionResetConfig thread
        ) {}

        public record SessionSendPolicyConfig(
            @JsonProperty("default") String defaultAction,
            List<SessionSendPolicyRule> rules
        ) {
            public record SessionSendPolicyRule(
                String action,
                SessionSendPolicyMatch match
            ) {}

            public record SessionSendPolicyMatch(
                String channel,
                String chatType,
                String keyPrefix,
                String rawKeyPrefix
            ) {}
        }

        public record MaintenanceConfig(
            String mode,
            Object pruneAfter,
            Integer pruneDays,
            Integer maxEntries,
            Object rotateBytes,
            Object resetArchiveRetention,
            Object maxDiskBytes,
            Object highWaterBytes
        ) {}
    }

    public record WebConfig(
        Boolean enabled,
        Integer heartbeatSeconds,
        ReconnectConfig reconnect
    ) {
        public record ReconnectConfig(
            Integer initialMs,
            Integer maxMs,
            Double factor,
            Double jitter,
            Integer maxAttempts
        ) {}
    }

    public record GatewayConfig(
        Integer port,
        String bind,
        AuthConfig auth
    ) {
        public record AuthConfig(
            String mode,
            String token
        ) {}
    }

    public record AgentsConfig(
        List<AgentConfig> list,
        AgentDefaults defaults
    ) {
        public record AgentDefaults(
            String workspace,
            Object model,
            Object heartbeat
        ) {}
    }

    public record AgentConfig(
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
        @JsonProperty("default") boolean isDefault
    ) {}
}
