#!/bin/bash
#
# logs.sh - View filtered agent logs
#
# Usage:
#   ./scripts/logs.sh              # All agent logs
#   ./scripts/logs.sh orch         # Orchestration logs
#   ./scripts/logs.sh llm          # LLM/API call logs
#   ./scripts/logs.sh session      # Session lifecycle logs
#   ./scripts/logs.sh action       # Action execution logs
#   ./scripts/logs.sh all          # Unfiltered logcat
#

set -e

BLUE='\033[0;34m'
NC='\033[0m'
log() { echo -e "${BLUE}> $1${NC}"; }

if ! adb get-state >/dev/null 2>&1; then
    echo "No device detected" >&2
    exit 1
fi

FILTER="${1:-default}"

case "$FILTER" in
    orch|orchestration)
        log "Orchestration logs (Ctrl+C to stop)..."
        adb logcat -s MobileV3Orchestration:*
        ;;
    llm|api)
        log "LLM/API logs (Ctrl+C to stop)..."
        adb logcat -s OpenAILLMClient:* LFMLLMClient:* LLMClient:*
        ;;
    session)
        log "Session logs (Ctrl+C to stop)..."
        adb logcat -s AgentSession:* AgentService:*
        ;;
    action)
        log "Action logs (Ctrl+C to stop)..."
        adb logcat -s Agent:* ToolRouter:* AccessibilityPlatform:* AgentSession:* | grep -E "(Executing tool|ActionExecuted|Captured screen|Updated snapshot|Tool call|Policy decision)" || true
        ;;
    all)
        log "All logs (Ctrl+C to stop)..."
        adb logcat
        ;;
    *)
        log "Agent logs (Ctrl+C to stop)..."
        adb logcat -s Agent:* AgentService:* AgentSession:* Turn:* LLMClient:* ToolRouter:* AccessibilityPlatform:*
        ;;
esac
