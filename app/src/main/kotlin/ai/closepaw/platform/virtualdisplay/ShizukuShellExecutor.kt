package ai.closepaw.platform.virtualdisplay

import android.util.Log
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Executes shell commands through Shizuku with timeout handling. */
internal class ShizukuShellExecutor {
        companion object {
                private const val TAG = "ShizukuShellExec"
        }

        fun execute(command: Array<String>): Int {
                return try {
                        val process = newProcessViaShizuku(command)
                        if (!waitForProcess(process, 30, TimeUnit.SECONDS)) {
                                process.destroy()
                                Log.e(TAG, "Shell command timed out: ${command.joinToString(" ")}")
                                return -1
                        }
                        val exitCode = process.exitValue()
                        if (exitCode != 0) {
                                val error = process.errorStream.bufferedReader().use { it.readText() }
                                Log.w(
                                        TAG,
                                        "Shell command non-zero exit ($exitCode): ${command.joinToString(" ")}\n$error"
                                )
                        } else {
                                Log.d(TAG, "Shell command success: ${command.joinToString(" ")}")
                        }
                        exitCode
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to execute shell command: ${command.joinToString(" ")}", e)
                        -1
                }
        }

        /**
         * Custom waitFor implementation to handle Shizuku's incorrect exception usage.
         * ShizukuRemoteProcess throws IllegalArgumentException instead of IllegalThreadStateException
         * when process hasn't exited.
         */
        private fun waitForProcess(process: Process, timeout: Long, unit: TimeUnit): Boolean {
                val startTime = System.nanoTime()
                val remNanos = unit.toNanos(timeout)
                var rem = remNanos
                var sleepMs = 10L

                do {
                        try {
                                process.exitValue()
                                return true
                        } catch (e: IllegalThreadStateException) {
                                // Expected, keep waiting.
                        } catch (e: IllegalArgumentException) {
                                if (e.message?.contains("process hasn't exited") != true) {
                                        throw e
                                }
                        }

                        if (rem > 0) {
                                try {
                                        Thread.sleep(
                                                minOf(TimeUnit.NANOSECONDS.toMillis(rem) + 1, sleepMs)
                                        )
                                        sleepMs = minOf(sleepMs * 2, 100L)
                                } catch (e: InterruptedException) {
                                        return false
                                }
                        }
                        rem = remNanos - (System.nanoTime() - startTime)
                } while (rem > 0)

                return false
        }

        /**
         * Obtain a Process via Shizuku.
         *
         * Some Shizuku versions expose newProcess as a public method, while others keep it private.
         */
        private fun newProcessViaShizuku(command: Array<String>): Process {
                val shizukuClass = Shizuku::class.java
                val method =
                        runCatching {
                                        shizukuClass.getMethod(
                                                "newProcess",
                                                Array<String>::class.java,
                                                Array<String>::class.java,
                                                String::class.java
                                        )
                                }
                                .getOrNull()
                                ?: shizukuClass.getDeclaredMethod(
                                        "newProcess",
                                        Array<String>::class.java,
                                        Array<String>::class.java,
                                        String::class.java
                                )
                method.isAccessible = true
                return method.invoke(null, command, null, null) as Process
        }
}
