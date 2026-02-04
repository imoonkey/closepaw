package com.moonkey.androidagent.session

data class AgentSessionState(
    val todos: TodoState = TodoState(),
    val scratchpad: ScratchpadState = ScratchpadState()
)
