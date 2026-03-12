# OpenClaw Agents Module

Java implementation of the AI assistant runtime and tool execution system.

## Overview

This module replicates the TypeScript `src/agents/` directory functionality in Java 17, providing:

- Agent runtime and lifecycle management
- Tool discovery, registration, and execution
- Bash process management and registry
- Auth profile management with credential rotation and failover
- Skill loading and management

## Architecture

### Package Structure

```
ai.openclaw.agents/
├── runtime/
│   ├── AgentScope.java          # Agent lifecycle and context management
│   └── AgentSpawnService.java   # Agent initialization and process spawning
├── tools/
│   ├── ToolCatalog.java         # Core tool definitions and profiles
│   ├── ToolRegistry.java        # Tool registration and discovery
│   ├── BashTools.java           # Shell command execution
│   ├── FileTools.java           # File system operations
│   └── WebTools.java            # HTTP/web operations
├── auth/
│   └── AuthProfileManager.java  # Credential management and rotation
├── process/
│   └── BashProcessRegistry.java # Process tracking and management
└── skills/
    └── SkillLoader.java         # Skill package loading
```

## Key Components

### AgentScope
Manages agent lifecycle including:
- Agent ID normalization and resolution
- Agent configuration resolution
- Workspace directory management
- Session key parsing

### AgentSpawnService
Handles agent spawning with:
- Multiple spawn modes (run, session)
- Sandbox policy enforcement
- Thread binding support
- Async execution

### ToolCatalog
Defines core tools and profiles:
- Minimal, Coding, Messaging, Full profiles
- Tool grouping by category
- Profile-based filtering

### ToolRegistry
Dynamic tool management:
- Tool registration/unregistration
- Group management
- Profile-based filtering
- Execution handling

### BashTools
Shell command execution:
- Synchronous and background execution
- Process management
- Output streaming
- Timeout handling

### FileTools
File system operations:
- Read/write/edit files
- Directory listing
- File metadata
- Path security (workspace containment)

### WebTools
Web operations:
- HTTP GET/POST requests
- Response handling
- HTML text extraction
- URL validation

### BashProcessRegistry
Process lifecycle management:
- Session tracking
- Output buffering
- Automatic cleanup
- TTL-based pruning

### AuthProfileManager
Credential management:
- Profile storage (JSON)
- Multiple credential types (API key, Token, OAuth)
- Round-robin rotation
- Cooldown and failure tracking
- Configurable backoff

### SkillLoader
Skill package handling:
- Directory scanning
- Markdown skill file parsing
- Frontmatter metadata
- Platform filtering
- Binary requirement checking

## Usage

### Basic Agent Spawning
```java
AgentSpawnService spawnService = new AgentSpawnService();

SpawnParams params = SpawnParams.builder()
    .task("Analyze the codebase")
    .agentId("analyzer")
    .mode(SpawnMode.RUN)
    .thread(true)
    .build();

SpawnContext ctx = new SpawnContext(
    "agent:main:session:123", null, null, null, null, false
);

SpawnResult result = spawnService.spawn(params, ctx);
```

### Tool Execution
```java
ToolRegistry registry = new ToolRegistry();
BashProcessRegistry processRegistry = new BashProcessRegistry();
BashTools bashTools = new BashTools(processRegistry);

// Execute a command
BashTools.ExecResult result = bashTools.exec(
    BashTools.ExecParams.simple("ls -la")
);
```

### File Operations
```java
FileTools fileTools = new FileTools("/workspace");

// Read a file
FileTools.ReadResult result = fileTools.read("src/Main.java");

// Write a file
FileTools.WriteResult write = fileTools.write(
    "output.txt", "Hello World"
);
```

### Auth Profile Management
```java
AuthProfileManager authManager = new AuthProfileManager();
AuthProfileStore store = authManager.loadAuthProfileStore();

// Add a credential
authManager.upsertAuthProfile("anthropic:default",
    new AuthProfileManager.ApiKeyCredential(
        "api_key", "anthropic", "sk-...", null, null, null
    )
);

// Get ordered profiles for provider
List<String> profiles = authManager.resolveAuthProfileOrder(
    config, store, "anthropic"
);
```

## Dependencies

- Java 17+
- openclaw-common (shared utilities)
- Jackson (JSON handling)
- SLF4J (logging)

## Mapping to TypeScript

| Java Class | TypeScript Source |
|------------|-------------------|
| AgentScope | src/agents/agent-scope.ts |
| AgentSpawnService | src/agents/acp-spawn.ts |
| ToolCatalog | src/agents/tool-catalog.ts |
| ToolRegistry | src/agents/tools/* registry |
| BashTools | src/agents/bash-tools.exec-runtime.ts |
| FileTools | src/agents/pi-tools.read.ts |
| WebTools | src/agents/tools/web.ts |
| BashProcessRegistry | src/agents/bash-process-registry.ts |
| AuthProfileManager | src/agents/auth-profiles/*.ts |
| SkillLoader | src/agents/skills/refresh.ts |

## Notes

- Uses Java 17 features: records, sealed classes, pattern matching
- Thread-safe implementations using ConcurrentHashMap and locks
- Immutable data structures where possible
- 1:1 logic parity with TypeScript implementations
