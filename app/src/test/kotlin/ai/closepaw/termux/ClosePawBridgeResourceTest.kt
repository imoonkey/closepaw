package ai.closepaw.termux

import ai.closepaw.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import org.junit.Test

class ClosePawBridgeResourceTest {

    @Test
    fun `packaged bridge resource exists and declares version`() {
        assertThat(R.raw.closepaw_bridge_py).isNotEqualTo(0)

        val content = readPackagedRawResource().toString(Charsets.UTF_8)

        assertWithMessage("raw bridge resource declares BRIDGE_VERSION")
            .that(content.lineSequence().any { it.trim().startsWith("BRIDGE_VERSION =") })
            .isTrue()
    }

    private fun readPackagedRawResource(): ByteArray {
        val configStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(TEST_CONFIG_PATH),
        ) { "Android unit-test config is available" }

        val properties = Properties().apply {
            configStream.use { load(it) }
        }
        val resourceApkPath = requireNotNull(properties.getProperty("android_resource_apk")) {
            "Android unit-test resource APK path is configured"
        }

        val resourceApk = File(resourceApkPath)
        assertWithMessage("Android unit-test resource APK exists at $resourceApk")
            .that(resourceApk.isFile)
            .isTrue()

        ZipFile(resourceApk).use { apk ->
            val entry = requireNotNull(apk.getEntry(RAW_RESOURCE_PATH)) {
                "$RAW_RESOURCE_PATH exists in $resourceApk"
            }
            return apk.getInputStream(entry).use { it.readBytes() }
        }
    }

    private companion object {
        const val TEST_CONFIG_PATH = "com/android/tools/test_config.properties"
        const val RAW_RESOURCE_PATH = "res/raw/closepaw_bridge_py"
    }
}
