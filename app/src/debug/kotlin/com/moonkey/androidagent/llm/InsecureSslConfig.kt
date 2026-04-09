package com.moonkey.androidagent.llm

import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Debug-only SSL configuration that skips certificate validation.
 *
 * Used in debug/eval builds when the Android emulator's system clock is frozen
 * to a past date (e.g., Oct 2023 for AndroidWorld), which causes normal SSL
 * certificate validation to fail because certs appear "not yet valid."
 *
 * This file exists only in the debug source set. The release source set
 * provides a no-op stub that returns null for all properties.
 */
object InsecureSslConfig {

    private const val TAG = "InsecureSslConfig"

    val trustManager: X509TrustManager? = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslSocketFactory: SSLSocketFactory? by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        sslContext.socketFactory.also {
            Log.w(TAG, "Using insecure SSL config (certificate validation disabled)")
        }
    }

    fun validateBaseUrl(url: String?) {
        // Debug builds allow any base URL (including HTTP for local testing)
    }
}
