package ai.closepaw.llm

import ai.closepaw.BuildConfig
import java.net.URI
import java.net.URISyntaxException

/**
 * Single validation rule for the user-configured OTHER provider base URL.
 *
 * Encodes one policy across release and debug:
 *  - scheme must be `http` or `https`; host must be non-empty.
 *  - release: scheme must be `https`. No exceptions.
 *  - debug (`allowDebugHttp=true`): `https` always allowed; `http` allowed only
 *    when host is `localhost`, `127.0.0.1`, or `10.0.2.2` (emulator loopback).
 *
 * `allowDebugHttp` is injected (default reads `BuildConfig.DEBUG`) so JVM unit
 * tests can verify both policy branches without flipping build types.
 *
 * Returns the normalized URL (whitespace trimmed, single trailing `/` removed)
 * via [Result.success]. Returns a descriptive failure via [Result.failure]
 * suitable for surfacing in the settings UI verbatim.
 */
object OtherBaseUrlValidator {

    private val DEBUG_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun validate(input: String, allowDebugHttp: Boolean = BuildConfig.DEBUG): Result<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Base URL must not be blank"))
        }

        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return Result.failure(IllegalArgumentException("Base URL is not a valid URI: ${e.message}"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(
                IllegalArgumentException("Base URL must use http or https (got '${uri.scheme ?: ""}')")
            )
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Base URL must include a host"))
        }

        if (scheme == "http") {
            if (!allowDebugHttp) {
                return Result.failure(IllegalArgumentException("Base URL must use https"))
            }
            if (host.lowercase() !in DEBUG_HTTP_HOSTS) {
                return Result.failure(
                    IllegalArgumentException(
                        "http is allowed only for localhost / 127.0.0.1 / 10.0.2.2 in debug builds"
                    )
                )
            }
        }

        val normalized = if (trimmed.endsWith('/')) trimmed.trimEnd('/') else trimmed
        return Result.success(normalized)
    }
}
