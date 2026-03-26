package com.moonkey.androidagent.llm

import android.util.Log
import com.moonkey.androidagent.BuildConfig
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Provides SSL configuration that skips certificate date validation.
 *
 * Used in debug/eval builds when the Android emulator's system clock is frozen
 * to a past date (e.g., Oct 2023 for AndroidWorld), which causes normal SSL
 * certificate validation to fail because certs appear "not yet valid."
 *
 * Guarded by BuildConfig.DEBUG — throws in release builds.
 */
object InsecureSslConfig {

    private const val TAG = "InsecureSslConfig"

    val trustManager: X509TrustManager
        get() {
            check(BuildConfig.DEBUG) { "InsecureSslConfig must not be used in release builds" }
            return insecureTrustManager
        }

    val sslSocketFactory: SSLSocketFactory
        get() {
            check(BuildConfig.DEBUG) { "InsecureSslConfig must not be used in release builds" }
            return insecureSslSocketFactory
        }

    private val insecureTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val insecureSslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(insecureTrustManager), SecureRandom())
        sslContext.socketFactory.also {
            Log.w(TAG, "Using insecure SSL config (certificate validation disabled)")
        }
    }
}
