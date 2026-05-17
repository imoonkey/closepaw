package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelIdValidatorTest {

    @Test
    fun `accepts simple ids and trims surrounding whitespace`() {
        assertThat(ModelIdValidator.validate(" vendor/model ").getOrNull()).isEqualTo("vendor/model")
        assertThat(ModelIdValidator.validate("gpt-5.2").getOrNull()).isEqualTo("gpt-5.2")
        assertThat(ModelIdValidator.validate("anthropic/claude-opus-4.7").getOrNull())
            .isEqualTo("anthropic/claude-opus-4.7")
    }

    @Test
    fun `rejects blank`() {
        assertThat(ModelIdValidator.validate("").isFailure).isTrue()
        assertThat(ModelIdValidator.validate("   ").isFailure).isTrue()
    }

    @Test
    fun `rejects whitespace inside id`() {
        val result = ModelIdValidator.validate("vendor model")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("whitespace")
    }

    @Test
    fun `rejects leading colon or slash`() {
        assertThat(ModelIdValidator.validate(":foo").isFailure).isTrue()
        assertThat(ModelIdValidator.validate("/foo").isFailure).isTrue()
    }

    @Test
    fun `preserves case`() {
        assertThat(ModelIdValidator.validate("Anthropic/Claude").getOrNull())
            .isEqualTo("Anthropic/Claude")
    }
}
