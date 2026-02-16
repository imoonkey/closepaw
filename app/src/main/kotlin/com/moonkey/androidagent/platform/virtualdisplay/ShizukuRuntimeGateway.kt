package com.moonkey.androidagent.platform.virtualdisplay

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/** Runtime-level Shizuku operations: availability, permissions, binder lifecycle, hidden API. */
internal class ShizukuRuntimeGateway {
        companion object {
                private const val TAG = "ShizukuRuntime"
        }

        fun isAvailable(): Boolean {
                return try {
                        Shizuku.pingBinder()
                } catch (e: Exception) {
                        Log.w(TAG, "Shizuku ping failed: ${e.message}")
                        false
                }
        }

        fun hasPermission(): Boolean {
                return try {
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                        Log.w(TAG, "Shizuku permission check failed: ${e.message}")
                        false
                }
        }

        fun requestPermission(requestCode: Int) {
                Shizuku.requestPermission(requestCode)
        }

        fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
                Shizuku.addBinderDeadListener(listener)
        }

        fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
                Shizuku.removeBinderDeadListener(listener)
        }

        fun bypassHiddenApis() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        HiddenApiBypass.addHiddenApiExemptions("")
                        Log.d(TAG, "Hidden API restrictions bypassed")
                }
        }
}
