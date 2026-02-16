package com.moonkey.androidagent.platform.virtualdisplay

import android.content.Context
import android.content.Intent
import android.util.Log

/** Launches activities onto a target display using hidden ActivityOptions APIs. */
internal class ShizukuActivityLauncher {
        companion object {
                private const val TAG = "ShizukuActivityLaunch"
        }

        fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
                try {
                        val optionsClass = Class.forName("android.app.ActivityOptions")
                        val options = optionsClass.getMethod("makeBasic").invoke(null)
                        optionsClass
                                .getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                                .invoke(options, displayId)
                        val bundle =
                                optionsClass.getMethod("toBundle").invoke(options) as
                                        android.os.Bundle
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
                        Log.d(TAG, "Launched activity on display $displayId")
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch on display $displayId", e)
                }
        }
}
