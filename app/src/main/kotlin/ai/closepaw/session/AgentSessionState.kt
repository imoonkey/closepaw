package ai.closepaw.session

data class AgentSessionState(
    val todos: TodoState = TodoState(),
    val scratchpad: ScratchpadState = ScratchpadState()
)
