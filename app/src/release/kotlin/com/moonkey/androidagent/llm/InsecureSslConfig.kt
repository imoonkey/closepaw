package com.moonkey.androidagent.llm

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Release no-op stub for InsecureSslConfig.
 *
 * Returns null for all SSL properties — no insecure TLS is possible in
 * release builds. Enforces HTTPS for base URL overrides.
 */
object InsecureSslConfig {

    val trustManager: X509TrustManager? = null

    val sslSocketFactory: SSLSocketFactory? = null

    fun validateBaseUrl(url: String?) {
        require(url == null || url.startsWith("https://")) {
            "Non-HTTPS base URL is not allowed in release builds: $url"
        }
    }
}
