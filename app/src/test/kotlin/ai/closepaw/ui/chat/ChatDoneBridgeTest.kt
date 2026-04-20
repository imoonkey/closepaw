package ai.closepaw.ui.chat

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.CapsuleBinding
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Pin: ChatScreen's onUserResponse must hit CapsuleBinding.onUserResponseSent
 * before the ViewModel send so the supplement capsule clears its waiting
 * state (regression for commit d23537e8).
 */
class ChatDoneBridgeTest {

    private fun bindingFor(
        accept: (String) -> Boolean,
    ): CapsuleBinding = CapsuleBinding(
        mode = MutableStateFlow(CapsuleMode.Hidden),
        platformMode = MutableStateFlow(PlatformMode.ACCESSIBILITY),
        isStopPending = MutableStateFlow(false),
        previousMode = { null },
        onStopRequested = { true },
        onApprovalResolved = { true },
        onUserResponseSent = accept,
    )

    @Test
    fun `routes through capsule then forwards to viewModel send`() {
        val seen = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        val binding = bindingFor(accept = { id -> seen += id; true })

        forwardUserResponse(binding, { c, r -> sent += c to r }, "call-1", "yes")

        assertThat(seen).containsExactly("call-1")
        assertThat(sent).containsExactly("call-1" to "yes")
    }

    @Test
    fun `skips viewModel send when capsule rejects callId`() {
        val sent = mutableListOf<Pair<String, String>>()
        val binding = bindingFor(accept = { false })

        forwardUserResponse(binding, { c, r -> sent += c to r }, "stale", "ok")

        assertThat(sent).isEmpty()
    }
}
