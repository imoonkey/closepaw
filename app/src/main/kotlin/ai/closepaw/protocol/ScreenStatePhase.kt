package ai.closepaw.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ScreenStatePhase {
    PRE_TURN,
    POST_ACTION
}
