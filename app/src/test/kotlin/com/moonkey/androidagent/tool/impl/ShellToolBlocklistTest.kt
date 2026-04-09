package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

/**
 * Security regression: shell blocklist must contain exactly am/pm/reboot/su
 * and reject all of them (including full-path variants).
 */
class ShellToolBlocklistTest {

    private val tool = ShellTool()

    // ── Blocklist completeness ──────────────────────────────────────

    @Test
    fun `am command is blocked`() {
        assertBlocked("am start -n com.example/.Activity")
    }

    @Test
    fun `pm command is blocked`() {
        assertBlocked("pm install /data/local/tmp/evil.apk")
    }

    @Test
    fun `reboot command is blocked`() {
        assertBlocked("reboot")
    }

    @Test
    fun `su command is blocked`() {
        assertBlocked("su -c id")
    }

    // ── Full-path bypass prevention ─────────────────────────────────

    @Test
    fun `full path to am is blocked`() {
        assertBlocked("/system/bin/am broadcast -a EVIL")
    }

    @Test
    fun `full path to su is blocked`() {
        assertBlocked("/system/xbin/su")
    }

    // ── Allowed commands pass validation ─────────────────────────────

    @Test
    fun `ls is allowed`() {
        assertAllowed("ls /sdcard/")
    }

    @Test
    fun `cat is allowed`() {
        assertAllowed("cat /sdcard/file.txt")
    }

    @Test
    fun `stat is allowed`() {
        assertAllowed("stat /sdcard/file.txt")
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    fun `empty command is invalid`() {
        val result = tool.validate(JSONObject().put("command", ""))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `missing command parameter is invalid`() {
        val result = tool.validate(JSONObject())
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    private fun assertBlocked(command: String) {
        val result = tool.validate(JSONObject().put("command", command))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        val errors = (result as ValidationResult.Invalid).errors
        assertThat(errors.first()).contains("Blocked")
    }

    private fun assertAllowed(command: String) {
        val result = tool.validate(JSONObject().put("command", command))
        assertThat(result).isEqualTo(ValidationResult.Valid)
    }
}
