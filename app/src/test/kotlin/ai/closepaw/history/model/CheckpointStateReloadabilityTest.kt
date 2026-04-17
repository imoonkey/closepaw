package ai.closepaw.history.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CheckpointStateReloadabilityTest {

    @Test
    fun `IDLE_READY and CLOSED are reloadable`() {
        assertThat(CheckpointState.IDLE_READY.isReloadable()).isTrue()
        assertThat(CheckpointState.CLOSED.isReloadable()).isTrue()
    }

    @Test
    fun `RUNNING_DIRTY is not reloadable`() {
        assertThat(CheckpointState.RUNNING_DIRTY.isReloadable()).isFalse()
    }
}
