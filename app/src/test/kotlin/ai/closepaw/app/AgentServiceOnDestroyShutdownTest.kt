package ai.closepaw.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Regression coverage for er-ondestroy-anr: AgentService.onDestroy() must NOT
 * block the main thread. The fix detaches shutdown onto a Dispatchers.Default
 * scope; any reintroduction of runBlocking in this file risks an ANR.
 *
 * A source-level grep is used because the real ServiceLifecycleOwner, overlay
 * controller, and AgentSession wiring is too heavy to mock meaningfully for a
 * unit test. This assertion is weaker than a behavioural test but still
 * catches the specific regression we care about.
 */
class AgentServiceOnDestroyShutdownTest {

    @Test
    fun `AgentService source does not use runBlocking`() {
        val source = locateAgentServiceSource()
        val matches = source.readLines()
            .withIndex()
            .filter { (_, line) ->
                val trimmed = line.trimStart()
                !trimmed.startsWith("//") &&
                        !trimmed.startsWith("*") &&
                        Regex("\\brunBlocking\\b").containsMatchIn(line)
            }
            .map { (idx, line) -> "${idx + 1}: ${line.trim()}" }

        assertThat(matches).isEmpty()
    }

    private fun locateAgentServiceSource(): File {
        val candidates = listOf(
            "app/src/main/kotlin/ai/closepaw/app/AgentService.kt",
            "src/main/kotlin/ai/closepaw/app/AgentService.kt"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        error(
            "AgentService.kt not found. Working dir=${File(".").absolutePath}, tried=$candidates"
        )
    }
}
