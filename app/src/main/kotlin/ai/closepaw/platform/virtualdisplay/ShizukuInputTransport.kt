package ai.closepaw.platform.virtualdisplay

import android.util.Log
import android.view.InputEvent

/** Transport layer for input injection through IInputManager. */
internal class ShizukuInputTransport(
        private val proxyProvider: ShizukuServiceProxyProvider
) {
        companion object {
                private const val TAG = "ShizukuInputTrans"
        }

        fun injectInputEvent(event: InputEvent, mode: Int): Boolean {
                return try {
                        val proxy = proxyProvider.inputManagerProxy()
                        val method =
                                proxy.javaClass.getMethod(
                                        "injectInputEvent",
                                        InputEvent::class.java,
                                        Int::class.javaPrimitiveType
                                )
                        method.invoke(proxy, event, mode) as Boolean
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to inject input event", e)
                        false
                }
        }
}
