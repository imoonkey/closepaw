package ai.closepaw.llm

/**
 * Single validation rule for user-supplied or discovered model identifiers.
 *
 * Applied at every entry point (OTHER synth, intent extras, discovery
 * namespacing, settings auto-flip) so the catalog never holds a model id
 * that could collide with the `provider:` namespacing scheme or break the
 * `selectedModel` storage format. Rules:
 *
 *  - non-blank after trim.
 *  - no whitespace anywhere — model ids are URL-safe atoms in the request
 *    body and whitespace round-tripping is a known footgun.
 *  - must not start with `/` or `:` — `:` would clash with the discovery
 *    namespace separator (`provider:modelId`); `/` would look like an
 *    absolute path prefix and confuse url construction.
 *
 * Returns the trimmed value (no other normalization — case is meaningful
 * for some upstreams).
 */
object ModelIdValidator {
    fun validate(input: String): Result<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Model id must not be blank"))
        }
        if (trimmed.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("Model id must not contain whitespace"))
        }
        if (trimmed.startsWith('/') || trimmed.startsWith(':')) {
            return Result.failure(IllegalArgumentException("Model id must not start with '/' or ':'"))
        }
        return Result.success(trimmed)
    }
}
