package ai.closepaw.browser.script

import android.webkit.JavascriptInterface

internal class BrowserScriptJsInterface(private val bridge: BrowserScriptBridge) {

    @JavascriptInterface
    fun send(message: String) {
        bridge.handleSend(message)
    }

    @JavascriptInterface
    fun done(message: String) {
        bridge.handleDone(message)
    }
}
