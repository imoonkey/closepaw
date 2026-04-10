package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

/**
 * Security regression: shell blocklist and metacharacter rejection.
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

    @Test
    fun `env command is blocked`() {
        assertBlocked("env am start -n com.example/.Activity")
    }

    @Test
    fun `xargs command is blocked`() {
        assertBlocked("xargs rm")
    }

    @Test
    fun `find command is blocked`() {
        assertBlocked("find /sdcard -name test")
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

    // ── Metacharacter rejection ─────────────────────────────────────

    @Test
    fun `semicolon rejected`() {
        assertMetacharRejected("ls /sdcard; rm -rf /")
    }

    @Test
    fun `pipe rejected`() {
        assertMetacharRejected("cat /etc/passwd | grep root")
    }

    @Test
    fun `ampersand rejected`() {
        assertMetacharRejected("sleep 100 &")
    }

    @Test
    fun `backtick rejected`() {
        assertMetacharRejected("echo `whoami`")
    }

    @Test
    fun `dollar sign rejected`() {
        assertMetacharRejected("echo \$HOME")
    }

    @Test
    fun `dollar-paren rejected`() {
        assertMetacharRejected("echo \$(whoami)")
    }

    @Test
    fun `dollar-brace rejected`() {
        assertMetacharRejected("echo \${HOME}")
    }

    @Test
    fun `output redirect rejected`() {
        assertMetacharRejected("echo pwned > /sdcard/file.txt")
    }

    @Test
    fun `input redirect rejected`() {
        assertMetacharRejected("cat < /sdcard/file.txt")
    }

    @Test
    fun `newline rejected`() {
        assertMetacharRejected("cat /sdcard/file.txt\nrm -rf /")
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
        assertThat(errors.first()).containsMatch("Blocked|metacharacters")
    }

    private fun assertMetacharRejected(command: String) {
        val result = tool.validate(JSONObject().put("command", command))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        val errors = (result as ValidationResult.Invalid).errors
        assertThat(errors.first()).contains("metacharacters")
    }

    private fun assertAllowed(command: String) {
        val result = tool.validate(JSONObject().put("command", command))
        assertThat(result).isEqualTo(ValidationResult.Valid)
    }
}
