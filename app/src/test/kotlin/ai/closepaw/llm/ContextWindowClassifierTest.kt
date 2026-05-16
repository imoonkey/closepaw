package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextWindowClassifierTest {

    @Test
    fun `recognizes prompt_too_long`() {
        val e = classifyContextWindowExceeded("openai.BadRequestError: prompt_too_long: tokens=1234")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes request_too_long`() {
        val e = classifyContextWindowExceeded("request_too_long: 800k > 200k")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes request_too_large`() {
        val e = classifyContextWindowExceeded("Anthropic: request_too_large")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes context_length_exceeded`() {
        val e = classifyContextWindowExceeded("error code context_length_exceeded")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes 'exceeded max context length'`() {
        val e = classifyContextWindowExceeded("prompt too long; exceeded max context length")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes 'maximum context length'`() {
        val e = classifyContextWindowExceeded("This model's maximum context length is 200000 tokens.")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes 'payload too large'`() {
        val e = classifyContextWindowExceeded("HTTP/1.1 413 Payload Too Large")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes HTTP 413 in message`() {
        val e = classifyContextWindowExceeded("status=413 url=https://api")
        assertThat(e).isNotNull()
    }

    @Test
    fun `recognizes explicit httpCode 413`() {
        val e = classifyContextWindowExceeded(message = "something", httpCode = 413)
        assertThat(e).isNotNull()
    }

    @Test
    fun `does not match unrelated 413 substrings`() {
        // The standalone-number regex should not match digits embedded in other tokens.
        val e = classifyContextWindowExceeded("model id v413p2")
        assertThat(e).isNull()
    }

    @Test
    fun `does not match unrelated errors`() {
        val e = classifyContextWindowExceeded("RateLimitError: tokens per minute exceeded")
        assertThat(e).isNull()
    }

    @Test
    fun `pass-through returns existing ContextWindowExceededException`() {
        val original = ContextWindowExceededException("prompt_too_long")
        val result = classifyContextWindowExceeded(original)
        assertThat(result).isSameInstanceAs(original)
    }

    @Test
    fun `unwraps cause chain`() {
        val cause = RuntimeException("prompt_too_long: 800k")
        val wrapper = RuntimeException("LLM call failed", cause)
        val result = classifyContextWindowExceeded(wrapper)
        assertThat(result).isNotNull()
    }

    @Test
    fun `null message returns null`() {
        val e = classifyContextWindowExceeded(message = null)
        assertThat(e).isNull()
    }
}
