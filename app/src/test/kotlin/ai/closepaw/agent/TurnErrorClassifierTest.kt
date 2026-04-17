package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class TurnErrorClassifierTest {

        @Test
        fun `dns failure is non recoverable`() {
                val classification =
                        TurnErrorClassifier.classify(
                                RuntimeException(
                                        "request failed",
                                        UnknownHostException("Unable to resolve host")
                                )
                        )

                assertThat(classification.recoverable).isFalse()
        }

        @Test
        fun `timeout failure is recoverable`() {
                val classification =
                        TurnErrorClassifier.classify(
                                RuntimeException("request failed", SocketTimeoutException("timeout"))
                        )

                assertThat(classification.recoverable).isTrue()
        }

        @Test
        fun `context limit failure is non recoverable`() {
                val classification =
                        TurnErrorClassifier.classify(
                                RuntimeException("maximum context length exceeded")
                        )

                assertThat(classification.recoverable).isFalse()
        }

        @Test
        fun `context limit message is user-friendly`() {
                val classification =
                        TurnErrorClassifier.classify(
                                RuntimeException("This model's maximum context length is 128000 tokens")
                        )

                assertThat(classification.message).doesNotContain("128000")
                assertThat(classification.message).contains("Conversation too long")
                assertThat(classification.message).contains("new task")
        }

        @Test
        fun `blank message falls back to unknown error`() {
                val classification =
                        TurnErrorClassifier.classify(RuntimeException("", SocketTimeoutException()))

                assertThat(classification.message).isEqualTo("Unknown error")
        }
}
