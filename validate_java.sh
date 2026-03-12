#!/bin/bash
# Simple validation script to check Java file syntax
# Since javac is not available, we do basic structure validation

echo "Validating Java source files..."

# Check for required files
REQUIRED_FILES=(
    "openclaw-agents/src/main/java/ai/openclaw/agents/runtime/AgentScope.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/runtime/AgentSpawnService.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/tools/ToolCatalog.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/tools/ToolRegistry.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/tools/BashTools.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/tools/FileTools.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/tools/WebTools.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/process/BashProcessRegistry.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/auth/AuthProfileManager.java"
    "openclaw-agents/src/main/java/ai/openclaw/agents/skills/SkillLoader.java"
)

ALL_FOUND=true
for file in "${REQUIRED_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo "✓ Found: $file"
    else
        echo "✗ Missing: $file"
        ALL_FOUND=false
    fi
done

if [ "$ALL_FOUND" = true ]; then
    echo ""
    echo "All required files are present!"

    # Basic syntax checks
    echo ""
    echo "Running basic syntax validation..."

    SYNTAX_OK=true

    # Check for balanced braces in each file
    for file in "${REQUIRED_FILES[@]}"; do
        OPEN=$(grep -o '{' "$file" | wc -l)
        CLOSE=$(grep -o '}' "$file" | wc -l)
        if [ "$OPEN" -eq "$CLOSE" ]; then
            echo "✓ $file: braces balanced ($OPEN open, $CLOSE close)"
        else
            echo "✗ $file: braces unbalanced ($OPEN open, $CLOSE close)"
            SYNTAX_OK=false
        fi
    done

    # Check for balanced parentheses
    for file in "${REQUIRED_FILES[@]}"; do
        OPEN=$(grep -o '(' "$file" | wc -l)
        CLOSE=$(grep -o ')' "$file" | wc -l)
        if [ "$OPEN" -eq "$CLOSE" ]; then
            echo "✓ $file: parentheses balanced ($OPEN open, $CLOSE close)"
        else
            echo "⚠ $file: parentheses count differs ($OPEN open, $CLOSE close) - may be ok (text content)"
        fi
    done

    echo ""
    if [ "$SYNTAX_OK" = true ]; then
        echo "✓ Basic syntax validation passed!"
        exit 0
    else
        echo "✗ Some syntax issues detected"
        exit 1
    fi
else
    echo ""
    echo "✗ Some required files are missing"
    exit 1
fi
