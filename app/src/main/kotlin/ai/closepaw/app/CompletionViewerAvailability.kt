package ai.closepaw.app

import ai.closepaw.protocol.SessionState

internal fun canOpenCompletionViewer(
    serviceViewerAvailable: Boolean,
    servicePresent: Boolean,
    localSessionState: SessionState?,
    localViewerAvailable: Boolean,
): Boolean {
    if (serviceViewerAvailable) return true
    return servicePresent &&
        localSessionState != null &&
        localSessionState != SessionState.Shutdown &&
        localViewerAvailable
}
