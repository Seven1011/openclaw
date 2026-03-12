# OpenClaw Java 17 Migration Plan

## Executive Summary

This document provides a detailed plan for creating a 1:1 replica of the OpenClaw TypeScript/Node.js codebase in Java 17 using Maven. The migration preserves the exact architecture, module boundaries, and functionality while leveraging Java's ecosystem.

---

## 1. Project Structure (Multi-Module Maven)

```
openclaw-java/
├── pom.xml (root aggregator)
├── openclaw-common/          (shared utilities, types, exceptions)
├── openclacp-runtime/        (ACP - Agent Client Protocol)
├── openclaw-gateway/         (Gateway server, WebSocket, HTTP)
├── openclaw-channels/        (messaging channel abstractions)
├── openclaw-agents/          (AI agent runtime)
├── openclaw-cli/             (command-line interface)
├── openclaw-plugin-sdk/      (plugin development kit)
├── openclaw-extensions/      (channel extensions - parent POM)
│   ├── telegram/
│   ├── whatsapp/
│   ├── discord/
│   ├── slack/
│   ├── signal/
│   └── ...
└── openclaw-skills/          (skill packages - parent POM)
    ├── github/
    ├── notion/
    └── ...
```

---

## 2. Module Mapping

### 2.1 Root POM Configuration

```xml
<groupId>ai.openclaw</groupId>
<artifactId>openclaw-parent</artifactId>
<version>2026.3.11-SNAPSHOT</version>
<packaging>pom</packaging>

<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    
    <!-- Dependency versions -->
    <spring-boot.version>3.2.0</spring-boot.version>
    <jackson.version>2.16.0</jackson.version>
    <netty.version>4.1.101.Final</netty.version>
    <picocli.version>4.7.5</picocli.version>
</properties>
```

### 2.2 Module: openclaw-common

**Source Mapping**: `src/config/`, `src/infra/`, `src/utils/`, `src/logging/`

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| Config types | Jackson-annotated records/classes |
| Path resolution | `java.nio.file.Path`, `System.getProperty("user.home")` |
| Environment | `System.getenv()`, custom env wrapper |
| Logging | SLF4J + Logback (structured JSON) |
| File locking | `java.nio.channels.FileLock` |
| JSON5 parsing | Custom Jackson module or `json-five` library |

**Directory Structure**:
```
openclaw-common/src/main/java/ai/openclaw/common/
├── config/
│   ├── OpenClawConfig.java          (types.base.ts, types.gateway.ts)
│   ├── ConfigLoader.java            (config/io.ts)
│   ├── ConfigValidation.java        (config/validation.ts)
│   └── ConfigPaths.java             (config/paths.ts)
├── infra/
│   ├── EnvNormalizer.java           (infra/env.ts)
│   ├── MachineNameResolver.java     (infra/machine-name.ts)
│   ├── PortChecker.java             (infra/ports.ts)
│   └── BinaryManager.java           (infra/binaries.ts)
├── logging/
│   ├── StructuredLogger.java        (logging/subsystem.ts)
│   └── DiagnosticEvents.java        (infra/diagnostic-events.ts)
└── utils/
    ├── MessageChannelUtils.java     (utils/message-channel.ts)
    └── DeliveryContext.java         (utils/delivery-context.ts)
```

### 2.3 Module: openclaw-acp

**Source Mapping**: `src/acp/`

**Purpose**: Agent Client Protocol implementation for IDE integration

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| ACP server | Netty TCP server or Spring WebFlux |
| NDJSON streaming | Jackson `JsonParser` streaming API |
| Session mapping | `ConcurrentHashMap<String, AcpSession>` |
| Gateway client | WebSocket client (Spring WebFlux or Tyrus) |

**Directory Structure**:
```
openclaw-acp/src/main/java/ai/openclaw/acp/
├── server/
│   ├── AcpServer.java               (acp/server.ts)
│   ├── AcpSessionManager.java       (acp/control-plane/manager.ts)
│   └── AcpTranslator.java           (acp/translator.ts)
├── client/
│   ├── GatewayClient.java           (gateway/client.ts)
│   └── ConnectionAuth.java          (gateway/connection-auth.ts)
├── runtime/
│   ├── AcpRuntime.java              (acp/runtime/types.ts)
│   └── AcpRuntimeRegistry.java      (acp/runtime/registry.ts)
├── protocol/
│   ├── AcpEvent.java                (event types)
│   ├── AcpRequest.java              (request types)
│   └── NdJsonStream.java            (NDJSON serialization)
└── bindings/
    ├── PersistentBindings.java      (acp/persistent-bindings.ts)
    └── SessionMapper.java           (acp/session-mapper.ts)
```

**Key Dependencies**:
```xml
<dependencies>
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-websocket</artifactId>
    </dependency>
</dependencies>
```

### 2.4 Module: openclaw-gateway

**Source Mapping**: `src/gateway/`, `src/routing/`

**Purpose**: Central control plane - WebSocket/HTTP server, session management, auth

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| Hono web framework | Spring Boot WebFlux (reactive) or MVC |
| WebSocket server | Spring WebSocket (STOMP) or raw Netty |
| Rate limiting | Bucket4j or Resilience4j |
| JWT/auth | Spring Security OAuth2 / Nimbus JOSE |
| Health checks | Spring Boot Actuator |
| Cron jobs | Spring Scheduler or Quartz |

**Directory Structure**:
```
openclaw-gateway/src/main/java/ai/openclaw/gateway/
├── server/
│   ├── GatewayServer.java           (gateway/server.impl.ts)
│   ├── GatewayWebSocketHandler.java (gateway/server-ws-runtime.ts)
│   ├── GatewayHttpController.java   (HTTP endpoints)
│   └── GatewayAuth.java             (gateway/auth.ts)
├── config/
│   ├── ConfigReloader.java          (gateway/config-reload.ts)
│   └── RuntimeConfig.java           (gateway/server-runtime-config.ts)
├── health/
│   ├── HealthMonitor.java           (gateway/channel-health-monitor.ts)
│   └── HealthState.java             (gateway/server/health-state.ts)
├── methods/
│   ├── GatewayMethods.java          (gateway/server-methods.ts)
│   ├── ExecApprovalHandlers.java    (gateway/server-methods/exec-approval.ts)
│   └── SecretsHandlers.java         (gateway/server-methods/secrets.ts)
├── sessions/
│   ├── SessionStore.java            (config/sessions.ts)
│   ├── SessionManager.java          (gateway/client.ts session handling)
│   └── SessionKeyResolver.java      (gateway/server-session-key.ts)
├── plugins/
│   ├── PluginRegistry.java          (plugins/registry.ts)
│   ├── PluginRuntime.java           (plugins/runtime/index.ts)
│   └── HookRunner.java              (plugins/hook-runner-global.ts)
├── nodes/
│   ├── NodeRegistry.java            (gateway/node-registry.ts)
│   └── MobileNodeManager.java       (gateway/server-mobile-nodes.ts)
├── discovery/
│   └── GatewayDiscovery.java        (gateway/server-discovery-runtime.ts)
├── cron/
│   └── GatewayCronService.java      (gateway/server-cron.ts)
├── chat/
│   ├── AgentEventHandler.java       (gateway/server-chat.ts)
│   ├── ChatAttachments.java         (gateway/chat-attachments.ts)
│   └── ChatSanitizer.java           (gateway/chat-sanitize.ts)
└── auth/
    ├── AuthRateLimiter.java         (gateway/auth-rate-limit.ts)
    ├── ConnectionAuth.java          (gateway/connection-auth.ts)
    └── StartupAuth.java             (gateway/startup-auth.ts)
```

**Main Gateway Server (Spring Boot)**:
```java
@SpringBootApplication
@EnableWebSocket
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {
    // Maps to gateway/server-ws-runtime.ts
}
```

### 2.5 Module: openclaw-channels

**Source Mapping**: `src/channels/`

**Purpose**: Channel abstractions, registry, and common messaging logic

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| Channel registry | Spring `ApplicationContext` + `@ChannelPlugin` |
| Channel adapters | Interface-based plugin system |
| Allowlist matching | Regex patterns, Bloom filters |
| Message threading | Thread-safe collections |
| Dock/connection mgmt | Connection pool pattern |

**Directory Structure**:
```
openclaw-channels/src/main/java/ai/openclaw/channels/
├── api/
│   ├── ChannelPlugin.java           (channels/plugins/types.plugin.ts)
│   ├── ChannelMessagingAdapter.java (ChannelMessagingAdapter type)
│   ├── ChannelAuthAdapter.java      (ChannelAuthAdapter type)
│   ├── ChannelStatusAdapter.java    (ChannelStatusAdapter type)
│   └── ChannelOutboundAdapter.java  (ChannelOutboundAdapter type)
├── registry/
│   ├── ChannelRegistry.java         (channels/registry.ts)
│   └── PluginAccountHelpers.java    (channels/plugins/account-helpers.ts)
├── lifecycle/
│   ├── ChannelLifecycle.java        (plugin-sdk/channel-lifecycle.ts)
│   └── ChannelDock.java             (channels/dock.ts)
├── allowlist/
│   ├── AllowlistMatcher.java        (channels/allowlist-match.ts)
│   └── AllowFromResolver.java       (channels/allow-from.ts)
├── threading/
│   ├── ThreadBindings.java          (channels/thread-bindings-messages.ts)
│   └── SessionEnvelope.java         (channels/session-envelope.ts)
├── messaging/
│   ├── InboundEnvelope.java         (plugin-sdk/inbound-envelope.ts)
│   ├── InboundReplyDispatch.java    (plugin-sdk/inbound-reply-dispatch.ts)
│   └── OutboundContext.java         (ChannelOutboundContext type)
├── config/
│   └── ChannelConfig.java           (channels/channel-config.ts)
└── model/
    ├── ChatType.java                (channels/chat-type.ts)
    ├── ChannelId.java               (ChannelId type)
    ├── ChannelMeta.java             (ChannelMeta type)
    └── SenderIdentity.java          (channels/sender-identity.ts)
```

### 2.6 Module: openclaw-agents

**Source Mapping**: `src/agents/`

**Purpose**: AI assistant runtime, tool execution, auth profiles

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| Agent spawn | `ExecutorService`, `CompletableFuture` |
| Tool catalog | Registry pattern with annotation scanning |
| Auth profiles | Rotating credentials store |
| Bash execution | `ProcessBuilder`, Apache Commons Exec |
| Process registry | `ConcurrentHashMap<UUID, ProcessHandle>` |
| Skills system | Plugin loader with classpath scanning |

**Directory Structure**:
```
openclaw-agents/src/main/java/ai/openclaw/agents/
├── runtime/
│   ├── AgentRuntime.java            (acp/runtime types integration)
│   ├── AgentSpawnService.java       (agents/acp-spawn.ts)
│   └── AgentScope.java              (agents/agent-scope.ts)
├── tools/
│   ├── ToolCatalog.java             (agents/tool-catalog.ts)
│   ├── ToolRegistry.java            (agents/tools/ registry)
│   ├── BashTools.java               (agents/bash-tools.exec-runtime.ts)
│   ├── FileTools.java               (agents/tools/fs.ts)
│   ├── WebTools.java                (agents/tools/web.ts)
│   └── AgentTools.java              (agents/tools/sessions.ts)
├── auth/
│   ├── AuthProfileManager.java      (agents/auth-profiles.ts)
│   ├── AuthProfileStore.java        (agents/auth-profiles.*.ts)
│   └── ApiKeyRotation.java          (agents/api-key-rotation.ts)
├── process/
│   ├── BashProcessRegistry.java     (agents/bash-process-registry.ts)
│   ├── CommandQueue.java            (process/command-queue.ts)
│   └── ExecApprovalRequest.java     (agents/bash-tools.exec-approval-request.ts)
├── sandbox/
│   └── SandboxRuntime.java          (agents/sandbox/)
├── skills/
│   ├── SkillLoader.java             (agents/skills/refresh.ts)
│   ├── SkillRegistry.java           (agents/skills/registry.ts)
│   └── RemoteSkillCache.java        (infra/skills-remote.ts)
├── subagent/
│   └── SubagentRegistry.java        (agents/subagent-registry.ts)
├── models/
│   ├── ModelCatalog.java            (gateway/server-model-catalog.ts)
│   └── ModelResolver.java           (agents/model-resolution.ts)
└── patch/
    └── PatchApplier.java            (agents/apply-patch.ts)
```

### 2.7 Module: openclaw-cli

**Source Mapping**: `src/cli/`, `src/commands/`, `src/wizard/`

**Purpose**: Command-line interface using picocli

**Key Components**:
| TypeScript | Java Equivalent |
|------------|-----------------|
| Commander.js | Picocli (https://picocli.info/) |
| Interactive prompts | JLine3 or Lanterna |
| Progress spinners | Rich console with ANSI codes |
| Table formatting | Picocli Table or ASCII Table |
| Config wizard | Interactive shell with JLine3 |

**Directory Structure**:
```
openclaw-cli/src/main/java/ai/openclaw/cli/
├── OpenClawCommand.java             (cli/program/build-program.ts)
├── commands/
│   ├── GatewayCommand.java          (commands/gateway.ts)
│   ├── AgentCommand.java            (commands/agent.ts)
│   ├── ConfigCommand.java           (commands/config.ts)
│   ├── ChannelsCommand.java         (commands/channels.ts)
│   ├── SendCommand.java             (commands/send.ts)
│   ├── AcpCommand.java              (commands/acp.ts)
│   ├── DoctorCommand.java           (commands/doctor.ts)
│   └── OnboardCommand.java          (wizard/onboarding.ts)
├── wizard/
│   ├── OnboardingWizard.java        (wizard/onboarding.ts)
│   ├── Prompts.java                 (wizard/prompts.ts)
│   └── GatewayConfigStep.java       (wizard/onboarding.gateway-config.ts)
├── deps/
│   └── DefaultDeps.java             (cli/deps.ts)
├── terminal/
│   ├── TableFormatter.java          (terminal/table.ts)
│   └── Palette.java                 (terminal/palette.ts)
└── progress/
    └── ProgressIndicator.java       (cli/progress.ts)
```

**Example CLI Structure**:
```java
@Command(name = "openclaw",
         mixinStandardHelpOptions = true,
         version = "2026.3.11",
         subcommands = {
             GatewayCommand.class,
             AgentCommand.class,
             ConfigCommand.class,
             // ...
         })
public class OpenClawCommand implements Runnable {
    @Override
    public void run() {
        // Default action - show help
    }
}
```

### 2.8 Module: openclaw-plugin-sdk

**Source Mapping**: `src/plugin-sdk/`

**Purpose**: Public API for third-party plugin development

**Directory Structure**:
```
openclaw-plugin-sdk/src/main/java/ai/openclaw/plugin/
├── api/
│   ├── OpenClawPlugin.java          (plugins/types.ts)
│   ├── ChannelPlugin.java           (re-export from channels)
│   └── SkillPlugin.java             (skills integration)
├── http/
│   ├── HttpPathNormalizer.java      (plugins/http-path.ts)
│   └── HttpRegistry.java            (plugins/http-registry.ts)
├── webhook/
│   ├── WebhookTargetRegistry.java   (plugin-sdk/webhook-targets.ts)
│   └── WebhookRequestGuards.java    (plugin-sdk/webhook-request-guards.ts)
├── lifecycle/
│   ├── ChannelLifecycle.java        (plugin-sdk/channel-lifecycle.ts)
│   └── StatusHelpers.java           (plugin-sdk/status-helpers.ts)
├── auth/
│   └── ProviderAuthResult.java      (plugin-sdk/provider-auth-result.ts)
└── util/
    ├── FileLock.java                (plugin-sdk/file-lock.ts)
    ├── KeyedAsyncQueue.java         (plugin-sdk/keyed-async-queue.ts)
    └── JsonStore.java               (plugin-sdk/json-store.ts)
```

---

## 3. Extension Channels (Maven Sub-modules)

Each extension is a separate Maven module under `openclaw-extensions/`.

### 3.1 Telegram Extension

```xml
<artifactId>openclaw-telegram</artifactId>
<dependencies>
    <!-- https://github.com/rubenlagus/TelegramBots -->
    <dependency>
        <groupId>org.telegram</groupId>
        <artifactId>telegrambots-longpolling</artifactId>
        <version>7.0.0</version>
    </dependency>
</dependencies>
```

**Mapping**:
| TypeScript | Java |
|------------|------|
| grammy library | TelegramBots |
| Long polling | `TelegramBotsLongPollingApplication` |
| Bot API types | Auto-generated from Bot API schema |

### 3.2 Discord Extension

```xml
<dependency>
    <groupId>com.discord4j</groupId>
    <artifactId>discord4j-core</artifactId>
    <version>3.2.6</version>
</dependency>
```

**Mapping**:
| TypeScript | Java |
|------------|------|
| discord.js | Discord4J |
| Gateway intents | Discord4J Gateway |
| Voice | Discord4J Voice |

### 3.3 Slack Extension

```xml
<dependency>
    <groupId>com.slack.api</groupId>
    <artifactId>slack-api-client</artifactId>
    <version>1.38.0</version>
</dependency>
<dependency>
    <groupId>com.slack.api</groupId>
    <artifactId>bolt-socket-mode</artifactId>
    <version>1.38.0</version>
</dependency>
```

### 3.4 WhatsApp Extension

Use WhatsApp Web multi-device API via:
- [whatsapp-web-java](https://github.com/Auties00/whatsweb) (unofficial)
- Or implement Baileys protocol in Java

### 3.5 Signal Extension

Interface with signal-cli via:
- REST API (signal-cli daemon mode)
- DBus (Unix sockets)

---

## 4. Database & Storage Mapping

| TypeScript | Java |
|------------|------|
| JSON file stores | Jackson JSON serialization |
| Session store | SQLite with JDBI or JDBC |
| Memory/Vector | LanceDB Java bindings or Pinecone client |
| File attachments | `java.nio.file.Files` with mime-type detection |
| Config files | HOCON (Typesafe Config) or YAML |

**Recommended Libraries**:
```xml
<!-- JSON -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>

<!-- SQLite -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.44.0.0</version>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
    <version>3.42.0</version>
</dependency>

<!-- Vector DB -->
<dependency>
    <groupId>com.github.lancedb</groupId>
    <artifactId>lancedb</artifactId>
    <version>0.7.0</version>
</dependency>
```

---

## 5. Async & Concurrency Mapping

| TypeScript Pattern | Java Equivalent |
|-------------------|-----------------|
| `async/await` | `CompletableFuture` |
| `Promise.all()` | `CompletableFuture.allOf()` |
| Event emitters | `ApplicationEventPublisher` or Reactive Streams |
| WebSocket streams | Project Reactor `Flux`/`Mono` |
| Streams (Node.js) | `java.io.InputStream`/`OutputStream` |
| Worker threads | `ExecutorService`, `ForkJoinPool` |

**Reactive Stack**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
</dependency>
```

---

## 6. Testing Strategy

| TypeScript | Java |
|------------|------|
| Vitest | JUnit 5 + AssertJ |
| Test coverage | JaCoCo |
| Mocking | Mockito |
| Integration tests | TestContainers |
| E2E tests | Cucumber + Selenium/Playwright |

```xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>3.24.2</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 7. Build & Packaging

### 7.1 Maven Wrapper
```bash
mvn wrapper:wrapper -Dmaven=3.9.6
```

### 7.2 Spring Boot Fat JAR
```bash
./mvnw spring-boot:build-image  # Docker image
./mvnw package                  # Fat JAR
```

### 7.3 Native Image (Optional)
```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
</plugin>
```

---

## 8. Migration Priority Order

### Phase 1: Foundation (Weeks 1-3)
1. `openclaw-common` - Config types, utilities, logging
2. `openclaw-plugin-sdk` - Public API contracts
3. `openclaw-channels` - Channel abstractions

### Phase 2: Core Runtime (Weeks 4-6)
4. `openclaw-agents` - Agent runtime, tools
5. `openclaw-gateway` - Gateway server (without plugins)

### Phase 3: Protocol (Weeks 7-8)
6. `openclaw-acp` - ACP bridge

### Phase 4: Channels (Weeks 9-12)
7. Core channel extensions (telegram, discord, slack)

### Phase 5: CLI (Weeks 13-14)
8. `openclaw-cli` - Command-line interface

### Phase 6: Skills (Ongoing)
9. Port skills packages as needed

---

## 9. Configuration Files

### 9.1 application.yml (Spring Boot)
```yaml
openclaw:
  gateway:
    port: 18789
    bind: loopback
    auth:
      mode: token
      token: ${GATEWAY_TOKEN:}
  channels:
    telegram:
      enabled: true
      bot-token: ${TELEGRAM_BOT_TOKEN:}
    discord:
      enabled: false
  sessions:
    store: sqlite
    sqlite:
      path: ~/.openclaw/sessions.db
```

### 9.2 Logback Configuration
```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

---

## 10. Notable Implementation Details

### 10.1 Session Key Format
Preserve the exact session key format from TypeScript:
```java
public record SessionKey(String value) {
    public static SessionKey forAgent(String agentId, String threadId) {
        return new SessionKey("agent:" + agentId + ":" + threadId);
    }
    
    public static SessionKey forAcp(String uuid) {
        return new SessionKey("acp:" + uuid);
    }
}
```

### 10.2 Event System
Use Spring's `ApplicationEvent` for internal events:
```java
public class GatewayEvent extends ApplicationEvent {
    private final String eventType;
    private final JsonNode payload;
    // ...
}

@Component
public class AgentEventListener {
    @EventListener
    public void handleGatewayEvent(GatewayEvent event) {
        // Handle agent events
    }
}
```

### 10.3 Plugin Loading
Use ServiceLoader for plugin discovery:
```java
// In openclaw-plugin-sdk
public interface ChannelPlugin {
    String getChannelId();
    void initialize(PluginContext context);
}

// In each extension: META-INF/services/ai.openclaw.plugin.ChannelPlugin
// com.example.TelegramChannelPlugin
```

---

## 11. Docker Support

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/openclaw-gateway-*.jar app.jar
EXPOSE 18789
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  openclaw:
    build: .
    ports:
      - "18789:18789"
    volumes:
      - ~/.openclaw:/root/.openclaw
    environment:
      - GATEWAY_TOKEN=${GATEWAY_TOKEN}
```

---

## 12. Summary

This migration plan provides:

1. **Clear module boundaries** matching the TypeScript source structure
2. **Technology mapping** from Node.js/TS libraries to Java equivalents
3. **Incremental migration path** starting with core abstractions
4. **Preservation of protocols** - session keys, config format, plugin API
5. **Modern Java practices** - records, pattern matching, virtual threads (Java 21+ ready)

The Java implementation will maintain API compatibility with existing clients while benefiting from Java's mature ecosystem, strong typing, and excellent tooling support.
