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

        val sourceFile = File(workspaceRoot(), BRIDGE_SOURCE_PATH)
        assertWithMessage("canonical bridge source exists at $sourceFile")
            .that(sourceFile.isFile)
            .isTrue()

        val sourceBytes = sourceFile.readBytes()
        val packagedBytes = readPackagedRawResource()
        assertWithMessage("packaged bridge resource must match canonical source bytes")
            .that(packagedBytes.asList())
            .containsExactlyElementsIn(sourceBytes.asList())
            .inOrder()

        val sourceContent = sourceBytes.toString(Charsets.UTF_8)
        assertWithMessage("canonical bridge source declares expected BRIDGE_VERSION")
            .that(sourceContent.lineSequence().any { it == EXPECTED_BRIDGE_VERSION_LINE })
            .isTrue()
    }

    private fun workspaceRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) {
            "JVM user.dir is required to locate the workspace root"
        }
        val start = File(userDir).absoluteFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { root -> File(root, BRIDGE_SOURCE_PATH).isFile }
            ?: error("Could not find workspace root from $start")
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
        const val BRIDGE_SOURCE_PATH = "tools/termux-bridge/closepaw_bridge.py"
        const val EXPECTED_BRIDGE_VERSION_LINE = "BRIDGE_VERSION = \"1\""
        const val TEST_CONFIG_PATH = "com/android/tools/test_config.properties"
        const val RAW_RESOURCE_PATH = "res/raw/closepaw_bridge_py"
    }
}
